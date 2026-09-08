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
import com.zhiyun.harness.LeaseService;
import com.zhiyun.harness.ReviewSlot;
import com.zhiyun.harness.ToolPolicy;
import com.zhiyun.llm.AgentModelRouter;
import com.zhiyun.llm.LlmGateway;
import com.zhiyun.llm.UsageMeter;
import com.zhiyun.llm.UserLlmOverride;
import com.zhiyun.rag.DocumentParser;
import com.zhiyun.rag.RagService;
import com.zhiyun.rag.VenueQuery;
import com.zhiyun.tool.AcademicSearchTool;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentRuntime {
    private static final Pattern DOI = Pattern.compile("10\\.\\d{4,9}/[-._;()/:A-Z0-9]+", Pattern.CASE_INSENSITIVE);
    private final ArtifactStore artifactStore;
    private final AcademicSearchTool academicSearchTool;
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
    private final ConcurrentHashMap<String, Integer> attempts = new ConcurrentHashMap<>();

    public AgentRuntime(ArtifactStore artifactStore, AcademicSearchTool academicSearchTool, RagService ragService,
                        DocumentParser documentParser, LlmGateway llmGateway, ObjectMapper objectMapper,
                        ZhiyunProperties properties, SkillRegistry skillRegistry, AgentModelRouter modelRouter,
                        ParallelFanout parallelFanout, AgentTraceService agentTraceService, LeaseService leaseService) {
        this.artifactStore = artifactStore;
        this.academicSearchTool = academicSearchTool;
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
    }

    public void run(ReviewSlot slot, String agentId) {
        if (artifactStore.completed(slot.getTask().getId(), agentId)) {
            return;
        }
        leaseService.assertWritable(slot.getTask().getId(), slot.getFencingToken());
        int tokensBefore = UsageMeter.snapshot();
        agentTraceService.begin(slot.getTask(), agentId, slot.getFencingToken());
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
            }
        }
        String raw = "structured output failed after retry: " + (last == null ? "" : last.getMessage());
        String message = PublicError.message(raw);
        agentTraceService.fail(slot.getTask(), agentId, message, slot.getFencingToken());
        throw new IllegalStateException(message, last);
    }

    private JsonNode produce(ReviewSlot slot, String agentId, int attempt) throws Exception {
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
            return dryRun(slot, agentId);
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
        return objectMapper.readTree(extractJson(live));
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

    private JsonNode dryRun(ReviewSlot slot, String agentId) {
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
        List<String> dois = new ArrayList<>();
        Matcher matcher = DOI.matcher(text);
        while (matcher.find()) {
            dois.add(matcher.group());
        }
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
                ev.put("supportsClaim", true);
                ev.put("confidence", 0.86);
                ev.put("status", "VERIFIED");
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
        if (text.toLowerCase(Locale.ROOT).contains("outperforms") && !text.toLowerCase(Locale.ROOT).contains("ablation")) {
            ObjectNode issue = issues.addObject();
            issue.put("issueId", "iss-rev-1");
            issue.put("severity", "MEDIUM");
            issue.put("category", "REVIEW");
            issue.put("section", "experiments");
            issue.putObject("location").put("anchor", "claim-outperforms");
            issue.put("summary", "Claim of superiority lacks ablation / evidence gap");
            issue.put("detail", "Reviewer is strict but does not assert experimental error without evidence. Retrieved chunks: "
                    + ctx.size());
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
            issue.put("detail", "Mechanical connectives or empty summary. Numbers, citations and results must stay untouched.");
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
        addPlan(tasks, reviewIssues, "HYBRID", "Rewrite the claim using existing results; do not invent new experiments.");
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
                + "\nManuscript excerpt:\n" + cap(slot.getSource().getContentText(), 4000)
                + "\nRetrieved PRIVATE:\n" + cap(privateCtx, 2000)
                + "\nRetrieved PUBLIC:\n" + cap(publicCtx, 1500)
                + "\nUpstream artifacts:\n" + cap(upstream(slot, agentId), 3000);
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

    private String cap(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
