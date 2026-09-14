package com.zhiyun.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyun.common.PublicError;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.harness.AgentIds;
import com.zhiyun.harness.AgentTraceService;
import com.zhiyun.harness.ArtifactStore;
import com.zhiyun.harness.HarnessMeters;
import com.zhiyun.harness.LeaseService;
import com.zhiyun.harness.ReviewSlot;
import com.zhiyun.harness.TokenBudget;
import com.zhiyun.harness.ToolCallRecorder;
import com.zhiyun.harness.ToolPolicy;
import com.zhiyun.llm.AgentModelRouter;
import com.zhiyun.llm.LlmGateway;
import com.zhiyun.llm.UsageMeter;
import com.zhiyun.llm.UserLlmOverride;
import com.zhiyun.rag.DocumentParser;
import com.zhiyun.rag.RagService;
import com.zhiyun.rag.VenueQuery;
import com.zhiyun.tool.AcademicSearchTool;
import com.zhiyun.tool.DocxTool;
import com.zhiyun.tool.WebSearchTool;
import com.zhiyun.workflow.PatchApplier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentRuntime {
    private static final Pattern DOI = Pattern.compile("10\\.\\d{4,9}/[-._;()/:A-Z0-9]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP_URL = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private final ArtifactStore artifactStore;
    private final AcademicSearchTool academicSearchTool;
    private final WebSearchTool webSearchTool;
    private final RagService ragService;
    private final DocumentParser documentParser;
    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final ZhiyunProperties properties;
    private final SkillRegistry skillRegistry;
    private final AgentModelRouter modelRouter;
    private final ParallelFanout parallelFanout;
    private final AgentTraceService agentTraceService;
    private final LeaseService leaseService;
    private final DocxTool docxTool;
    private final HarnessMeters harnessMeters;
    private final ConcurrentHashMap<String, Integer> attempts = new ConcurrentHashMap<>();

    public AgentRuntime(ArtifactStore artifactStore, AcademicSearchTool academicSearchTool, WebSearchTool webSearchTool,
                        RagService ragService, DocumentParser documentParser, LlmGateway llmGateway,
                        ObjectMapper objectMapper, ZhiyunProperties properties, SkillRegistry skillRegistry,
                        AgentModelRouter modelRouter, ParallelFanout parallelFanout,
                        AgentTraceService agentTraceService, LeaseService leaseService, DocxTool docxTool,
                        HarnessMeters harnessMeters) {
        this.artifactStore = artifactStore;
        this.academicSearchTool = academicSearchTool;
        this.webSearchTool = webSearchTool;
        this.ragService = ragService;
        this.documentParser = documentParser;
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.skillRegistry = skillRegistry;
        this.modelRouter = modelRouter;
        this.parallelFanout = parallelFanout;
        this.agentTraceService = agentTraceService;
        this.leaseService = leaseService;
        this.docxTool = docxTool;
        this.harnessMeters = harnessMeters;
    }

    public void run(ReviewSlot slot, String agentId) {
        if (artifactStore.completed(slot.getTask().getId(), agentId)) {
            agentTraceService.skip(slot.getTask(), agentId, slot.getFencingToken());
            return;
        }
        try {
            leaseService.assertWritable(slot.getTask().getId(), slot.getFencingToken());
        } catch (RuntimeException e) {
            if (isFencing(e)) {
                agentTraceService.fail(slot.getTask(), agentId, e.getMessage(), slot.getFencingToken());
            }
            throw e;
        }
        int tokensBefore = UsageMeter.snapshot();
        agentTraceService.begin(slot.getTask(), agentId, slot.getFencingToken());
        ToolCallRecorder.open();
        try {
            if (agentId.equals(properties.getFault().getTimeoutAgent())) {
                agentTraceService.fail(slot.getTask(), agentId, "injected timeout on " + agentId, slot.getFencingToken());
                throw new IllegalStateException("injected timeout on " + agentId);
            }
            Exception last = null;
            for (int i = 0; i < 2; i++) {
                try {
                    JsonNode produced = produce(slot, agentId, i);
                    validate(agentId, produced);
                    persist(slot, agentId, produced);
                    if (agentId.equals(properties.getFault().getKillAfterAgent())) {
                        throw new IllegalStateException("injected kill after " + agentId);
                    }
                    agentTraceService.complete(slot.getTask(), agentId, UsageMeter.snapshot() - tokensBefore, slot.getFencingToken());
                    return;
                } catch (Exception e) {
                    last = e;
                    if (isFencing(e)) {
                        break;
                    }
                }
            }
            if (isFencing(last)) {
                String raw = last.getMessage() == null ? "stale fencing token rejected" : last.getMessage();
                agentTraceService.fail(slot.getTask(), agentId, raw, slot.getFencingToken());
                if (last instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException(raw, last);
            }
            String raw = "structured output failed after retry: " + (last == null ? "" : last.getMessage());
            String message = PublicError.message(raw);
            harnessMeters.recordStructuredFail();
            agentTraceService.fail(slot.getTask(), agentId, message, slot.getFencingToken());
            throw new IllegalStateException(message, last);
        } finally {
            ToolCallRecorder.close();
        }
    }

    JsonNode produce(ReviewSlot slot, String agentId, int attempt) throws Exception {
        String fault = properties.getFault().getIllegalOutputAgent();
        String key = slot.getTask().getId() + ":" + agentId;
        if (agentId.equals(fault) && attempt == 0) {
            attempts.merge(key, 1, Integer::sum);
            return objectMapper.readTree("{\"invalid\":true}");
        }
        UserLlmOverride override = modelRouter.resolve(agentId);
        if (override != null) {
            UsageMeter.markByok();
        }
        if (properties.dryRun()) {
            UsageMeter.add(2000);
            harnessMeters.recordLlmCall();
            harnessMeters.recordLlmTokens(2000);
            return dryRun(slot, agentId);
        }
        if (AgentIds.CITATION.equals(agentId)) {
            return produceLiveCitation(slot, agentId, override);
        }
        String model = modelRouter.chatModel(agentId);
        String user = userPrompt(slot, agentId);
        if (AgentIds.FIGURE.equals(agentId)) {
            DocumentParser.PdfInspection inspection = documentParser.inspect(
                    slot.getSource().getStoragePath(), slot.getSource().getContentText());
            user += "\nProgrammatic PDF/Figure report (do not guess DPI or page size):\n" + inspection.summary();
            if (inspection.needsVision()) {
                ToolPolicy.assertAllowed(agentId, ToolPolicy.VISION);
                StringBuilder visionNotes = new StringBuilder();
                List<DocumentParser.VisionTarget> targets = documentParser.visionTargets(
                        slot.getSource().getStoragePath(), inspection);
                List<String> notes = parallelFanout.mapFigure(targets, target -> {
                    String vision = llmGateway.vision(modelRouter.visionModel(),
                            "Judge blur, unreadability, stretching for page " + target.page()
                                    + " figure " + target.name() + " reason=" + target.reason()
                                    + ". Do not guess DPI or page size.",
                            target.png(), override);
                    if (vision == null || vision.isBlank()) {
                        return "";
                    }
                    return "page=" + target.page()
                            + " name=" + target.name()
                            + " reason=" + target.reason()
                            + "\n" + vision + "\n";
                });
                for (String note : notes) {
                    visionNotes.append(note);
                }
                if (visionNotes.length() > 0) {
                    user += "\nVision (only flagged pages/figures, " + modelRouter.visionModel() + "):\n" + visionNotes;
                }
            }
        }
        String live = llmGateway.complete(
                model,
                temperature(agentId),
                skillRegistry.systemMessage(agentId) + "\nChat model=" + model,
                user,
                override
        );
        if (live == null || live.isBlank()) {
            throw new IllegalStateException("live LLM empty for " + agentId + " model=" + model);
        }
        JsonNode produced = objectMapper.readTree(extractJson(live));
        if (AgentIds.EXECUTION.equals(agentId)) {
            attachDocxCandidate(slot, produced);
        }
        return produced;
    }

    /**
     * live Citation：Java 先跑 AcademicSearchTool.lookupDoi（Crossref），把候选 Evidence 注入 Prompt；
     * 模型只判断 Claim 是否被 Evidence 支持，禁止编 DOI。无 DOI / paper=null → NOT_VERIFIED。
     */
    private JsonNode produceLiveCitation(ReviewSlot slot, String agentId, UserLlmOverride override) throws Exception {
        ToolPolicy.assertAllowed(agentId, ToolPolicy.ACADEMIC_SEARCH);
        String text = slot.getSource() == null ? "" : slot.getSource().getContentText();
        ObjectNode javaBase = objectMapper.createObjectNode();
        citation(javaBase, text == null ? "" : text);
        attachWebEvidence(javaBase, text == null ? "" : text);
        List<String> dois = parseDois(text);
        if (dois.isEmpty()) {
            return javaBase;
        }
        String model = modelRouter.chatModel(agentId);
        String user = userPrompt(slot, agentId)
                + "\nJava AcademicSearchTool.lookupDoi already ran (Crossref). "
                + "Evidence doi/title/authors/year/venue below is Java metadata. "
                + "Judge only supportsClaim (does Evidence support the manuscript Claim). "
                + "Do not invent or rewrite DOIs. If paper is null, status MUST be NOT_VERIFIED.\n"
                + javaBase.path("evidence");
        String live = llmGateway.complete(
                model,
                temperature(agentId),
                skillRegistry.systemMessage(agentId) + "\nChat model=" + model
                        + "\nJava already executed AcademicSearchTool.lookupDoi. Do not invent DOIs.",
                user,
                override
        );
        if (live == null || live.isBlank()) {
            throw new IllegalStateException("live LLM empty for " + agentId + " model=" + model);
        }
        JsonNode llm = objectMapper.readTree(extractJson(live));
        return mergeCitationLive(javaBase, llm, dois);
    }

    private void persist(ReviewSlot slot, String agentId, JsonNode produced) {
        leaseService.assertWritable(slot.getTask().getId(), slot.getFencingToken());
        if (produced.has("issues")) {
            artifactStore.save(slot.getTask(), slot.getFencingToken(), agentId, "ReviewIssue", produced.get("issues"));
        }
        if (produced.has("evidence")) {
            artifactStore.save(slot.getTask(), slot.getFencingToken(), agentId, "Evidence", produced.get("evidence"));
        }
        if (produced.has("verification")) {
            artifactStore.save(slot.getTask(), slot.getFencingToken(), agentId, "VerificationResult", produced.get("verification"));
        }
        if (produced.has("revisionTasks")) {
            artifactStore.save(slot.getTask(), slot.getFencingToken(), agentId, "RevisionTask", produced.get("revisionTasks"));
        }
        if (produced.has("patches")) {
            artifactStore.save(slot.getTask(), slot.getFencingToken(), agentId, "RevisionPatch", produced.get("patches"));
        }
        artifactStore.save(slot.getTask(), slot.getFencingToken(), agentId, "Bundle", produced);
    }

    private void validate(String agentId, JsonNode node) {
        if (node == null || node.isMissingNode() || node.path("invalid").asBoolean(false)) {
            throw new IllegalArgumentException("schema validation failed");
        }
        String blob = node.toString();
        if (AgentIds.STYLE.equals(agentId) || AgentIds.PLANNING.equals(agentId)) {
            if (blob.contains("AcademicSearch") || blob.contains("AcademicSearchTool")) {
                throw new IllegalArgumentException(agentId + " must not call AcademicSearch");
            }
        }
        if (AgentIds.STYLE.equals(agentId)) {
            ToolPolicy.assertAllowed(agentId, ToolPolicy.DOCUMENT_READ);
        }
        if (AgentIds.VERIFICATION.equals(agentId)) {
            JsonNode ver = node.path("verification");
            if (ver.isArray()) {
                for (JsonNode item : ver) {
                    if (item.path("basedOnExecutionSelfReport").asBoolean(false)) {
                        throw new IllegalArgumentException("verification cannot trust execution self-report");
                    }
                }
            }
        }
    }

    JsonNode dryRun(ReviewSlot slot, String agentId) {
        String text = slot.getSource().getContentText();
        ObjectNode root = objectMapper.createObjectNode();
        switch (agentId) {
            case AgentIds.CITATION -> citation(root, text);
            case AgentIds.FIGURE -> figure(root, text, slot);
            case AgentIds.REVIEWER -> reviewer(root, text, slot);
            case AgentIds.STYLE -> style(root, text);
            case AgentIds.PLANNING -> planning(root, slot);
            case AgentIds.EXECUTION -> execution(root, slot);
            case AgentIds.VERIFICATION -> verification(root, slot);
            default -> throw new IllegalArgumentException(agentId);
        }
        return root;
    }

    private void citation(ObjectNode root, String text) {
        ArrayNode evidence = root.putArray("evidence");
        ArrayNode issues = root.putArray("issues");
        ArrayNode verification = root.putArray("verification");
        List<String> dois = parseDois(text);
        List<com.fasterxml.jackson.databind.node.ObjectNode> papers = parallelFanout.mapCitation(dois, academicSearchTool::lookupDoi);
        boolean any = !dois.isEmpty();
        for (int i = 0; i < dois.size(); i++) {
            String doi = dois.get(i);
            com.fasterxml.jackson.databind.node.ObjectNode paper = papers.get(i);
            ObjectNode ev = evidence.addObject();
            String id = "ev-" + UUID.randomUUID();
            ev.put("evidenceId", id);
            ev.put("claim", "Citation " + doi);
            ev.put("source", "CROSSREF");
            ev.set("paper", paper);
            ev.put("excerpt", doi);
            if (paper == null) {
                ev.put("supportsClaim", false);
                ev.put("confidence", 0.2);
                ev.put("status", "NOT_VERIFIED");
                ObjectNode issue = issues.addObject();
                issue.put("issueId", "iss-" + UUID.randomUUID());
                issue.put("severity", "HIGH");
                issue.put("category", "CITATION");
                issue.put("section", "references");
                issue.putObject("location").put("anchor", doi);
                issue.put("summary", "DOI not found in academic source");
                issue.put("detail", "Java metadata verification failed; model is forbidden to invent a replacement DOI.");
                issue.putArray("evidenceIds").add(id);
                issue.put("sourceAgent", AgentIds.CITATION);
                ObjectNode vr = verification.addObject();
                vr.put("resultId", "vr-" + UUID.randomUUID());
                vr.put("issueId", issue.get("issueId").asText());
                vr.put("resolved", false);
                vr.put("stillHumanRequired", true);
                vr.put("notes", "NOT_VERIFIED");
                vr.put("basedOnExecutionSelfReport", false);
            } else {
                // Crossref 命中只表示文献存在，不等于 Claim 已被支持。
                ev.put("supportsClaim", false);
                ev.put("confidence", 0.5);
                ev.put("status", "NOT_VERIFIED");
            }
        }
        if (!any) {
            ObjectNode ev = evidence.addObject();
            ev.put("evidenceId", "ev-none");
            ev.put("claim", "No DOI parsed");
            ev.put("source", "MANUSCRIPT");
            ev.putNull("paper");
            ev.put("excerpt", "");
            ev.put("supportsClaim", false);
            ev.put("confidence", 0.1);
            ev.put("status", "NOT_VERIFIED");
        }
        root.set("verification", verification);
    }

    private JsonNode mergeCitationLive(ObjectNode javaBase, JsonNode llm, List<String> dois) {
        Set<String> allowed = new java.util.LinkedHashSet<>();
        for (String doi : dois) {
            allowed.add(doi.toLowerCase(Locale.ROOT));
        }
        Map<String, JsonNode> judged = new java.util.HashMap<>();
        int invented = 0;
        if (llm != null && llm.path("evidence").isArray()) {
            for (JsonNode ev : llm.path("evidence")) {
                String doi = doiOf(ev);
                if (doi != null && allowed.contains(doi.toLowerCase(Locale.ROOT))) {
                    judged.put(doi.toLowerCase(Locale.ROOT), ev);
                } else if (doi != null && !doi.isBlank()) {
                    invented++;
                }
            }
        }
        if (invented > 0) {
            ToolCallRecorder.extra(ToolPolicy.ACADEMIC_SEARCH, "inventedDropped", invented);
            harnessMeters.recordInventedDoi(invented);
        }
        ArrayNode evidence = javaBase.withArray("evidence");
        for (JsonNode node : evidence) {
            if (!(node instanceof ObjectNode ev)) {
                continue;
            }
            boolean missingPaper = ev.path("paper").isMissingNode() || ev.path("paper").isNull();
            if (missingPaper) {
                ev.put("supportsClaim", false);
                ev.put("status", "NOT_VERIFIED");
                continue;
            }
            String doi = doiOf(ev);
            JsonNode llmEv = doi == null ? null : judged.get(doi.toLowerCase(Locale.ROOT));
            if (llmEv != null && llmEv.has("supportsClaim")) {
                boolean support = llmEv.path("supportsClaim").asBoolean(false);
                ev.put("supportsClaim", support);
                ev.put("status", support ? "VERIFIED" : "CONFLICT");
                if (llmEv.has("confidence")) {
                    ev.put("confidence", llmEv.path("confidence").asDouble());
                }
            }
        }
        int notVerified = 0;
        for (JsonNode node : evidence) {
            if ("NOT_VERIFIED".equals(node.path("status").asText())) {
                notVerified++;
            }
        }
        if (notVerified > 0) {
            ToolCallRecorder.extra(ToolPolicy.ACADEMIC_SEARCH, "notVerified", notVerified);
        }
        return javaBase;
    }

    private List<String> parseDois(String text) {
        List<String> dois = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return dois;
        }
        Matcher matcher = DOI.matcher(text);
        while (matcher.find()) {
            dois.add(matcher.group());
        }
        return dois;
    }

    private List<String> parseHttpUrls(String text) {
        List<String> urls = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return urls;
        }
        Matcher matcher = HTTP_URL.matcher(text);
        while (matcher.find()) {
            String url = matcher.group();
            if (url.endsWith(".") || url.endsWith(",") || url.endsWith(")") || url.endsWith("。")) {
                url = url.substring(0, url.length() - 1);
            }
            urls.add(url);
        }
        return urls;
    }

    /**
     * live 正文里出现网页 URL 时调用已有 WebSearchTool，不新开检索服务。
     * WEB Evidence 同样不默认 supportsClaim。
     */
    private void attachWebEvidence(ObjectNode root, String text) {
        List<String> urls = parseHttpUrls(text);
        if (urls.isEmpty() || webSearchTool == null) {
            return;
        }
        ArrayNode evidence = root.withArray("evidence");
        for (String url : urls) {
            ArrayNode hits = webSearchTool.search(AgentIds.CITATION, url);
            if (hits == null || hits.isEmpty()) {
                continue;
            }
            for (JsonNode hit : hits) {
                ObjectNode ev = evidence.addObject();
                ev.put("evidenceId", "ev-web-" + UUID.randomUUID());
                ev.put("claim", "Web page " + url);
                ev.put("source", "WEB");
                ev.putNull("paper");
                ev.put("excerpt", hit.path("excerpt").asText(url));
                ev.put("supportsClaim", false);
                ev.put("confidence", 0.3);
                ev.put("status", "NOT_VERIFIED");
                ev.set("web", hit);
            }
        }
    }

    /**
     * live / dry-run 在源文件是 DOCX 时调用已有 DocxTool.writeCandidate，只写候选稿语义。
     */
    private void attachDocxCandidate(ReviewSlot slot, JsonNode produced) {
        if (slot.getSource() == null || !docxTool.supports(slot.getSource().getStoragePath())) {
            return;
        }
        ToolPolicy.assertAllowed(AgentIds.EXECUTION, ToolPolicy.DOCX);
        String text = slot.getSource().getContentText();
        JsonNode patches = produced.path("patches");
        if (patches.isArray() && patches.size() > 0) {
            List<PatchApplier.Spec> specs = new ArrayList<>();
            for (JsonNode patch : patches) {
                specs.add(PatchApplier.fromJson(patch));
            }
            text = PatchApplier.applyAll(text, specs).text();
        }
        long t0 = System.nanoTime();
        try {
            byte[] bytes = docxTool.writeCandidate(AgentIds.EXECUTION, text);
            ToolCallRecorder.record(ToolPolicy.DOCX, true, (System.nanoTime() - t0) / 1_000_000L);
            harnessMeters.recordToolCall(true);
            if (produced instanceof ObjectNode root) {
                root.put("docxTool", "candidate-only");
                root.put("docxCandidateBytes", bytes.length);
            }
        } catch (Exception e) {
            ToolCallRecorder.record(ToolPolicy.DOCX, false, (System.nanoTime() - t0) / 1_000_000L);
            harnessMeters.recordToolCall(false);
            throw new IllegalStateException("DocxTool.writeCandidate failed", e);
        }
    }

    private String doiOf(JsonNode ev) {
        if (ev == null) {
            return null;
        }
        String paperDoi = ev.path("paper").path("doi").asText("");
        if (!paperDoi.isBlank()) {
            return paperDoi;
        }
        Matcher excerpt = DOI.matcher(ev.path("excerpt").asText(""));
        if (excerpt.find()) {
            return excerpt.group();
        }
        Matcher claim = DOI.matcher(ev.path("claim").asText(""));
        if (claim.find()) {
            return claim.group();
        }
        return null;
    }

    /** 正文提到 ablation 但写的是没做 / 缺消融，不算有消融实验。 */
    private static boolean hasAblationStudy(String lower) {
        if (lower == null || !lower.contains("ablation")) {
            return false;
        }
        return !lower.contains("without ablation")
                && !lower.contains("did not run an ablation")
                && !lower.contains("no ablation");
    }

    private static boolean sectionHasLimitations(String text) {
        if (text == null) {
            return false;
        }
        return text.contains("# Limitations") || text.contains("## Limitations");
    }

    private void figure(ObjectNode root, String text, ReviewSlot slot) {
        ArrayNode issues = root.putArray("issues");
        DocumentParser.PdfInspection inspection = documentParser.inspect(
                slot.getSource().getStoragePath(), text);
        int i = 0;
        java.util.Set<String> figCodes = java.util.Set.of("LOW_DPI", "TINY", "STRETCH", "BLUR_CANDIDATE");
        for (DocumentParser.ProgramIssue item : inspection.issues()) {
            if (!inspection.figures().isEmpty() && figCodes.contains(item.code())) {
                continue;
            }
            ObjectNode issue = issues.addObject();
            issue.put("issueId", "iss-fig-" + (++i));
            issue.put("severity", item.severity());
            issue.put("category", "FIGURE_PDF");
            issue.put("section", "figures");
            issue.putObject("location").put("anchor", item.anchor());
            issue.put("summary", item.summary());
            issue.put("detail", item.detail());
            issue.putArray("evidenceIds");
            issue.put("sourceAgent", AgentIds.FIGURE);
        }
        if (!inspection.figures().isEmpty()) {
            List<List<DocumentParser.ProgramIssue>> perFig = parallelFanout.mapFigure(
                    inspection.figures(), documentParser::issuesOfFigure);
            for (List<DocumentParser.ProgramIssue> batch : perFig) {
                for (DocumentParser.ProgramIssue item : batch) {
                    ObjectNode issue = issues.addObject();
                    issue.put("issueId", "iss-fig-" + (++i));
                    issue.put("severity", item.severity());
                    issue.put("category", "FIGURE_PDF");
                    issue.put("section", "figures");
                    issue.putObject("location").put("anchor", item.anchor());
                    issue.put("summary", item.summary());
                    issue.put("detail", item.detail());
                    issue.putArray("evidenceIds");
                    issue.put("sourceAgent", AgentIds.FIGURE);
                }
            }
        }
        if (issues.isEmpty() && inspection.needsVision()) {
            ObjectNode issue = issues.addObject();
            issue.put("issueId", "iss-fig-vision");
            issue.put("severity", "MEDIUM");
            issue.put("category", "FIGURE_PDF");
            issue.put("section", "figures");
            issue.putObject("location").put("anchor", "vision");
            issue.put("summary", "Program flagged pages/figures for Vision (blur or stretch)");
            issue.put("detail", "Vision is limited to program-flagged pages/figures; firstRaster of page 1 is not used.");
            issue.putArray("evidenceIds");
            issue.put("sourceAgent", AgentIds.FIGURE);
        }
    }

    private void reviewer(ObjectNode root, String text, ReviewSlot slot) {
        ArrayNode issues = root.putArray("issues");
        var ctx = ragService.retrievePrivate(slot.getTask().getTenantId(), slot.getManuscript().getId(),
                slot.getSource().getVersionNo(), "method claim experiment consistency", "method");
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("outperforms") && !hasAblationStudy(lower)) {
            ObjectNode issue = issues.addObject();
            issue.put("issueId", "iss-rev-1");
            issue.put("severity", "MEDIUM");
            issue.put("category", "REVIEW");
            issue.put("section", "experiments");
            issue.putObject("location").put("anchor", "claim-outperforms");
            issue.put("summary", "EVIDENCE_GAP: Claim of superiority lacks ablation");
            issue.put("detail", "Reviewer is strict but does not assert experimental error without evidence. Retrieved chunks: "
                    + ctx.size()
                    + (lower.contains("80-example") || lower.contains("80 example") ? " private_split_80" : ""));
            issue.putArray("evidenceIds");
            issue.put("sourceAgent", AgentIds.REVIEWER);
        }
        if (lower.contains("no limitations")
                || (lower.contains("acl") && lower.contains("usually need") && !sectionHasLimitations(text))) {
            ObjectNode issue = issues.addObject();
            issue.put("issueId", "iss-rev-lim");
            issue.put("severity", "LOW");
            issue.put("category", "REVIEW");
            issue.put("section", "conclusion");
            issue.putObject("location").put("anchor", "limitations");
            issue.put("summary", "ACL Limitations section missing");
            issue.put("detail", "Venue checklist usually needs Limitations before references.");
            issue.putArray("evidenceIds");
            issue.put("sourceAgent", AgentIds.REVIEWER);
        }
        if (text.contains("10.0000/ghost.doi") && (lower.contains("gain") || lower.contains("12-point") || lower.contains("12 point"))) {
            ObjectNode issue = issues.addObject();
            issue.put("issueId", "iss-rev-ghost-gain");
            issue.put("severity", "HIGH");
            issue.put("category", "REVIEW");
            issue.put("section", "experiments");
            issue.putObject("location").put("anchor", "ghost-gain");
            issue.put("summary", "Ghost DOI gain is unsupported");
            issue.put("detail", "A fabricated 10.0000/ghost.doi cannot support a reported gain. No fraud claim.");
            issue.putArray("evidenceIds");
            issue.put("sourceAgent", AgentIds.REVIEWER);
        }
    }

    private void style(ObjectNode root, String text) {
        ArrayNode issues = root.putArray("issues");
        if (text.contains("Firstly") || text.contains("In conclusion, this paper") || text.contains("Firstly,")) {
            ObjectNode issue = issues.addObject();
            issue.put("issueId", "iss-sty-1");
            issue.put("severity", "LOW");
            issue.put("category", "STYLE");
            issue.put("section", "introduction");
            issue.putObject("location").put("anchor", "ai-pattern");
            issue.put("summary", "AI-like writing pattern");
            issue.put("detail", "Mechanical connectives or empty summary (Firstly / In conclusion). Numbers, citations and results must stay untouched.");
            issue.putArray("evidenceIds");
            issue.put("sourceAgent", AgentIds.STYLE);
        }
    }

    private void planning(ObjectNode root, ReviewSlot slot) {
        ArrayNode tasks = root.putArray("revisionTasks");
        JsonNode citationIssues = artifactStore.body(slot.getTask().getId(), AgentIds.CITATION, "ReviewIssue");
        JsonNode styleIssues = artifactStore.body(slot.getTask().getId(), AgentIds.STYLE, "ReviewIssue");
        JsonNode reviewIssues = artifactStore.body(slot.getTask().getId(), AgentIds.REVIEWER, "ReviewIssue");
        addPlan(tasks, citationIssues, "HUMAN_REQUIRED", "Verify or replace unverifiable citations; author must choose the final reference.");
        addPlan(tasks, reviewIssues, "HUMAN_REQUIRED", "Missing ablation / new experiments stay HUMAN_REQUIRED; do not invent results.");
        addPlan(tasks, styleIssues, "AI_AUTOMATABLE", "Polish AI-like sentences while protecting facts.");
        JsonNode figureIssues = artifactStore.body(slot.getTask().getId(), AgentIds.FIGURE, "ReviewIssue");
        addPlan(tasks, figureIssues, "HYBRID", "Fix caption/layout from PDF metadata; author confirms camera-ready page size.");
    }

    private void addPlan(ArrayNode tasks, JsonNode issues, String kind, String instruction) {
        if (issues == null || !issues.isArray()) {
            return;
        }
        for (JsonNode issue : issues) {
            ObjectNode t = tasks.addObject();
            t.put("taskId", "rt-" + UUID.randomUUID());
            t.put("issueId", issue.path("issueId").asText());
            t.put("kind", kind);
            t.put("instruction", instruction);
            t.putArray("protectedFacts").add("numbers").add("formulas").add("citations");
        }
    }

    private void execution(ObjectNode root, ReviewSlot slot) {
        ArrayNode patches = root.putArray("patches");
        JsonNode tasks = artifactStore.body(slot.getTask().getId(), AgentIds.PLANNING, "RevisionTask");
        String text = slot.getSource().getContentText();
        if (tasks.isArray()) {
            for (JsonNode task : tasks) {
                if ("HUMAN_REQUIRED".equals(task.path("kind").asText())) {
                    continue;
                }
                ObjectNode p = patches.addObject();
                p.put("patchId", "rp-" + UUID.randomUUID());
                p.put("revisionTaskId", task.path("taskId").asText());
                p.put("issueId", task.path("issueId").asText());
                String original = text.contains("Firstly") ? "Firstly, we propose a novel method."
                        : "In conclusion, this paper has demonstrated the effectiveness of our approach.";
                int start = text.indexOf(original);
                var loc = p.putObject("location");
                loc.put("anchor", "intro");
                if (start >= 0) {
                    loc.put("startOffset", start);
                    loc.put("endOffset", start + original.length());
                }
                p.put("originalText", original);
                p.put("proposedText", "We describe a retrieval-augmented reviewer that isolates citation verification from language edits.");
                p.put("reason", "Style / claim rewrite candidate. Formal manuscript is not overwritten.");
                p.putArray("evidenceIds");
            }
        }
        if (docxTool.supports(slot.getSource().getStoragePath())) {
            attachDocxCandidate(slot, root);
        }
    }

    private void verification(ObjectNode root, ReviewSlot slot) {
        ArrayNode verification = root.putArray("verification");
        JsonNode patches = artifactStore.body(slot.getTask().getId(), AgentIds.EXECUTION, "RevisionPatch");
        JsonNode issues = artifactStore.body(slot.getTask().getId(), AgentIds.STYLE, "ReviewIssue");
        if (issues.isArray()) {
            for (JsonNode issue : issues) {
                ObjectNode vr = verification.addObject();
                vr.put("resultId", "vr-" + UUID.randomUUID());
                vr.put("issueId", issue.path("issueId").asText());
                vr.put("resolved", patches.isArray() && patches.size() > 0);
                vr.put("stillHumanRequired", false);
                vr.putArray("newProblems");
                vr.put("notes", "Checked against source text and patches, not execution self-report.");
                vr.put("basedOnExecutionSelfReport", false);
            }
        }
        JsonNode citationIssues = artifactStore.body(slot.getTask().getId(), AgentIds.CITATION, "ReviewIssue");
        if (citationIssues.isArray()) {
            for (JsonNode issue : citationIssues) {
                ObjectNode vr = verification.addObject();
                vr.put("resultId", "vr-" + UUID.randomUUID());
                vr.put("issueId", issue.path("issueId").asText());
                vr.put("resolved", false);
                vr.put("stillHumanRequired", true);
                vr.putArray("newProblems");
                vr.put("notes", "Unverified citations remain HUMAN_REQUIRED.");
                vr.put("basedOnExecutionSelfReport", false);
            }
        }
        JsonNode figureIssues = artifactStore.body(slot.getTask().getId(), AgentIds.FIGURE, "ReviewIssue");
        if (figureIssues.isArray()) {
            for (JsonNode issue : figureIssues) {
                ObjectNode vr = verification.addObject();
                vr.put("resultId", "vr-" + UUID.randomUUID());
                vr.put("issueId", issue.path("issueId").asText());
                vr.put("resolved", false);
                vr.put("stillHumanRequired", true);
                vr.putArray("newProblems");
                vr.put("notes", "Figure/PDF items re-checked against metadata, not execution self-report.");
                vr.put("basedOnExecutionSelfReport", false);
            }
        }
    }

    private String userPrompt(ReviewSlot slot, String agentId) {
        String fallbackQuery = queryFor(agentId);
        String privateCtx = joinRetrieved(ragService.retrievePrivate(
                slot.getTask().getTenantId(), slot.getManuscript().getId(),
                slot.getSource().getVersionNo(), fallbackQuery, null));
        // 刊规范走公共 RAG：用户选的投稿期刊优先，否则正文抽 venue；抽不到回退固定词。
        String publicQuery = needsPublic(agentId)
                ? VenueQuery.publicQuery(slot.getTask().getTargetVenue(),
                manuscriptTitle(slot), manuscriptText(slot), fallbackQuery)
                : fallbackQuery;
        String publicCtx = needsPublic(agentId)
                ? joinRetrieved(ragService.retrievePublic(publicQuery))
                : "";
        return "Agent=" + agentId
                + "\npromptVersion=" + skillRegistry.promptVersion()
                + " skillVersion=" + skillRegistry.skillVersion()
                + "\nToolPolicy=" + String.join(",", ToolPolicy.allowed(agentId))
                + "\nTargetVenue=" + (slot.getTask().getTargetVenue() == null ? "" : slot.getTask().getTargetVenue())
                + "\nManuscript excerpt:\n" + TokenBudget.cap(slot.getSource().getContentText(), TokenBudget.MANUSCRIPT)
                + "\nRetrieved PRIVATE:\n" + TokenBudget.cap(privateCtx, TokenBudget.PRIVATE_RAG)
                + "\nRetrieved PUBLIC:\n" + TokenBudget.cap(publicCtx, TokenBudget.PUBLIC_RAG)
                + "\nUpstream artifacts:\n" + TokenBudget.cap(upstream(slot, agentId), TokenBudget.UPSTREAM);
    }

    private String manuscriptTitle(ReviewSlot slot) {
        if (slot.getManuscript() == null || slot.getManuscript().getTitle() == null) {
            return "";
        }
        return slot.getManuscript().getTitle();
    }

    private String manuscriptText(ReviewSlot slot) {
        if (slot.getSource() == null || slot.getSource().getContentText() == null) {
            return "";
        }
        return slot.getSource().getContentText();
    }

    private String queryFor(String agentId) {
        return switch (agentId) {
            case AgentIds.CITATION -> "DOI citation Crossref claim support 参考文献 bst";
            case AgentIds.FIGURE -> "PDF page size figure DPI caption Times Roman 公式字体 booktabs";
            case AgentIds.REVIEWER -> "method claim experiment consistency evidence gap 仿真 实验 ablation Limitations";
            case AgentIds.STYLE -> "academic writing humanizer 英文润色 中文润色 中译英 LaTeX 转义 Firstly In conclusion";
            case AgentIds.PLANNING -> "revision task classification";
            case AgentIds.EXECUTION -> "document patch candidate version";
            case AgentIds.VERIFICATION -> "independent verification not self-report";
            default -> agentId;
        };
    }

    private boolean needsPublic(String agentId) {
        return AgentIds.CITATION.equals(agentId)
                || AgentIds.FIGURE.equals(agentId)
                || AgentIds.STYLE.equals(agentId)
                || AgentIds.REVIEWER.equals(agentId);
    }

    private String upstream(ReviewSlot slot, String agentId) {
        long taskId = slot.getTask().getId();
        if (AgentIds.PLANNING.equals(agentId) || AgentIds.EXECUTION.equals(agentId) || AgentIds.VERIFICATION.equals(agentId)) {
            return "CitationIssues=" + artifactStore.body(taskId, AgentIds.CITATION, "ReviewIssue")
                    + "\nFigureIssues=" + artifactStore.body(taskId, AgentIds.FIGURE, "ReviewIssue")
                    + "\nReviewerIssues=" + artifactStore.body(taskId, AgentIds.REVIEWER, "ReviewIssue")
                    + "\nStyleIssues=" + artifactStore.body(taskId, AgentIds.STYLE, "ReviewIssue")
                    + "\nRevisionTasks=" + artifactStore.body(taskId, AgentIds.PLANNING, "RevisionTask")
                    + "\nPatches=" + artifactStore.body(taskId, AgentIds.EXECUTION, "RevisionPatch");
        }
        if (AgentIds.REVIEWER.equals(agentId)) {
            return "Evidence=" + artifactStore.body(taskId, AgentIds.CITATION, "Evidence");
        }
        return "";
    }

    private String joinRetrieved(java.util.List<RagService.Retrieved> hits) {
        return String.join("\n---\n", hits.stream().map(RagService.Retrieved::content).toList());
    }

    private double temperature(String agentId) {
        return switch (agentId) {
            case AgentIds.CITATION, AgentIds.REVIEWER, AgentIds.VERIFICATION, AgentIds.FIGURE -> 0.1;
            default -> 0.4;
        };
    }

    private String extractJson(String live) {
        int start = live.indexOf('{');
        int end = live.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return live.substring(start, end + 1);
        }
        return live;
    }

    private static boolean isFencing(Throwable e) {
        if (e == null) {
            return false;
        }
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("fencing") || "fencing".equals(PublicError.code(e.getMessage()));
    }
}
