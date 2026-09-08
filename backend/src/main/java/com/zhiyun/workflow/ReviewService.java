package com.zhiyun.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyun.billing.BillingService;
import com.zhiyun.common.ApiException;
import com.zhiyun.common.BusinessNos;
import com.zhiyun.common.PublicError;
import com.zhiyun.common.Pages;
import com.zhiyun.common.SearchLabels;
import com.zhiyun.config.RabbitConfig;
import com.zhiyun.domain.Artifact;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.DocumentVersion;
import com.zhiyun.domain.Manuscript;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.harness.AgentTraceService;
import com.zhiyun.harness.ArtifactStore;
import com.zhiyun.harness.LeaseService;
import com.zhiyun.manuscript.ManuscriptService;
import com.zhiyun.notify.InboxService;
import com.zhiyun.rag.RagService;
import com.zhiyun.rag.SemanticChunker;
import com.zhiyun.rag.VenueCatalog;
import com.zhiyun.repo.DocumentVersionRepo;
import com.zhiyun.repo.ManuscriptRepo;
import com.zhiyun.repo.ReviewTaskRepo;
import com.zhiyun.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ReviewService {
    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);
    private static final Set<String> WORKFLOWS = Set.of(Codes.FULL_REVIEW, Codes.QUICK_REVIEW, Codes.CITATION_ONLY);

    private final ReviewTaskRepo reviewTaskRepo;
    private final ManuscriptService manuscriptService;
    private final ManuscriptRepo manuscriptRepo;
    private final DocumentVersionRepo documentVersionRepo;
    private final BillingService billingService;
    private final ArtifactStore artifactStore;
    private final LeaseService leaseService;
    private final RabbitTemplate rabbitTemplate;
    private final ReviewOrchestrator orchestrator;
    private final WorkflowCatalog workflowCatalog;
    private final ObjectMapper objectMapper;
    private final RagService ragService;
    private final SemanticChunker chunker;
    private final InboxService inboxService;
    private final AgentTraceService agentTraceService;

    public ReviewService(ReviewTaskRepo reviewTaskRepo, ManuscriptService manuscriptService,
                         ManuscriptRepo manuscriptRepo, DocumentVersionRepo documentVersionRepo,
                         BillingService billingService, ArtifactStore artifactStore,
                         LeaseService leaseService,
                         ObjectProvider<RabbitTemplate> rabbitTemplate, ReviewOrchestrator orchestrator,
                         WorkflowCatalog workflowCatalog, ObjectMapper objectMapper,
                         RagService ragService, SemanticChunker chunker,
                         InboxService inboxService, AgentTraceService agentTraceService) {
        this.reviewTaskRepo = reviewTaskRepo;
        this.manuscriptService = manuscriptService;
        this.manuscriptRepo = manuscriptRepo;
        this.documentVersionRepo = documentVersionRepo;
        this.billingService = billingService;
        this.artifactStore = artifactStore;
        this.leaseService = leaseService;
        this.rabbitTemplate = rabbitTemplate.getIfAvailable();
        this.orchestrator = orchestrator;
        this.workflowCatalog = workflowCatalog;
        this.objectMapper = objectMapper;
        this.ragService = ragService;
        this.chunker = chunker;
        this.inboxService = inboxService;
        this.agentTraceService = agentTraceService;
    }

    public ReviewTask start(long manuscriptId, String workflow) {
        return start(manuscriptId, workflow, null);
    }

    public ReviewTask start(long manuscriptId, String workflow, String targetVenue) {
        if (!WORKFLOWS.contains(workflow)) {
            throw ApiException.bad("workflow must be FULL_REVIEW, QUICK_REVIEW or CITATION_ONLY");
        }
        String venue = normalizeVenue(targetVenue);
        Manuscript ms = manuscriptService.requireManuscript(manuscriptId);
        billingService.requireMinBalance(TenantContext.tenantId(), TenantContext.userId(), 1);
        ReviewTask task = new ReviewTask();
        task.setTenantId(TenantContext.tenantId());
        task.setUserId(TenantContext.userId());
        task.setManuscriptId(ms.getId());
        task.setWorkflow(workflow);
        task.setTargetVenue(venue);
        task.setStatus(Codes.PENDING);
        task.setSourceVersion(ms.getCurrentVersion());
        task.setIdempotencyKey(UUID.randomUUID().toString());
        task = reviewTaskRepo.saveAndFlush(task);
        dispatch(task.getId());
        return reviewTaskRepo.findById(task.getId()).orElse(task);
    }

    /**
     * FAILED 从最近 checkpoint 重投：租户隔离，仅 FAILED 可调。
     * 不新建任务、不重复扣额（同一 taskId 结算一次）；已完成 Agent 由 Artifact 唯一键跳过。
     */
    public ReviewTask retry(long taskId) {
        ReviewTask task = get(taskId);
        if (!Codes.FAILED.equals(task.getStatus())) {
            throw ApiException.conflict("只有失败的任务才能从检查点继续");
        }
        int n = reviewTaskRepo.markRetry(task.getId(), TenantContext.tenantId(), Codes.PENDING, Codes.FAILED);
        if (n == 0) {
            throw ApiException.conflict("只有失败的任务才能从检查点继续");
        }
        leaseService.forceRelease(task.getId());
        long id = task.getId();
        dispatch(id);
        // execute() 会清 TenantContext，这里不能再读租户上下文
        return reviewTaskRepo.findById(id).orElse(task);
    }

    /**
     * 取消 PENDING/RUNNING：落入 FAILED（不发明第四态），释放 lease，不在此结算。
     * 已启动的 Worker 结束时仍按 taskId 幂等结算一次；后续可走现有 retry。
     */
    @Transactional
    public ReviewTask cancel(long taskId) {
        ReviewTask task = get(taskId);
        if (!Codes.PENDING.equals(task.getStatus()) && !Codes.RUNNING.equals(task.getStatus())) {
            throw ApiException.conflict("只有排队中或正在审校的任务才能取消");
        }
        int n = reviewTaskRepo.markCancelled(task.getId(), TenantContext.tenantId(), Codes.FAILED, "已取消");
        if (n == 0) {
            throw ApiException.conflict("只有排队中或正在审校的任务才能取消");
        }
        leaseService.forceRelease(task.getId());
        ReviewTask cancelled = reviewTaskRepo.findByIdAndTenantId(task.getId(), TenantContext.tenantId())
                .orElseThrow(() -> ApiException.notFound("task not found"));
        inboxService.notifyReview(cancelled);
        return cancelled;
    }

    public Map<String, Object> listMine(String q, Integer page, Integer size, String status) {
        int p = Pages.page(page);
        int s = Pages.size(size);
        boolean blank = Pages.blank(q);
        List<String> workflows = SearchLabels.workflows(q);
        List<String> statuses = SearchLabels.reviewStatuses(q);
        String statusFilter = status == null ? "" : status.trim();
        boolean statusBlank = statusFilter.isEmpty() || "ALL".equalsIgnoreCase(statusFilter);
        boolean active = "ACTIVE".equalsIgnoreCase(statusFilter);
        String exactStatus = statusBlank || active ? "" : statusFilter;
        var result = reviewTaskRepo.search(
                TenantContext.tenantId(), TenantContext.userId(),
                Pages.flag(blank), Pages.needle(q),
                Pages.flag(!workflows.isEmpty()), Pages.orDummy(workflows),
                Pages.flag(!statuses.isEmpty()), Pages.orDummy(statuses),
                Pages.flag(statusBlank), exactStatus, Pages.flag(active),
                Pages.of(p, s));
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> unread = inboxService.unreadTaskKeys(TenantContext.tenantId(), TenantContext.userId());
        for (ReviewTask task : result.getContent()) {
            String title = manuscriptRepo.findByIdAndTenantId(task.getManuscriptId(), TenantContext.tenantId())
                    .map(Manuscript::getTitle)
                    .orElse("已删除的论文");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", task.publicId());
            row.put("manuscriptId", task.getManuscriptId());
            row.put("manuscriptTitle", title);
            row.put("workflow", task.getWorkflow());
            row.put("workflowName", workflowCatalog.nameOf(task.getWorkflow()));
            row.put("targetVenue", task.getTargetVenue());
            row.put("status", task.getStatus());
            row.put("sourceVersion", task.getSourceVersion());
            row.put("candidateVersion", task.getCandidateVersion());
            row.put("checkpointAgent", task.getCheckpointAgent());
            row.put("fencingToken", task.getFencingToken() == null ? 0L : task.getFencingToken());
            row.put("createdAt", task.getCreatedAt());
            row.put("updatedAt", task.getUpdatedAt());
            row.put("errorMessage", PublicError.message(task.getErrorMessage()));
            row.put("errorCode", PublicError.code(task.getErrorMessage()));
            row.put("unread", unread.contains(task.publicId())
                    || (task.getId() != null && unread.contains(String.valueOf(task.getId()))));
            out.add(row);
        }
        return Pages.wrap(out, result.getTotalElements(), p, s);
    }

    private static String normalizeVenue(String raw) {
        if (raw == null || raw.isBlank() || "UNSPECIFIED".equalsIgnoreCase(raw.strip()) || "未指定".equals(raw.strip())) {
            return null;
        }
        String canonical = VenueCatalog.canonical(raw);
        if (canonical == null) {
            throw ApiException.bad("投稿期刊不在平台目录中，请从期刊列表选择或留空");
        }
        return canonical;
    }

    private void dispatch(long taskId) {
        try {
            if (rabbitTemplate != null) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("taskId", taskId);
                rabbitTemplate.convertAndSend(RabbitConfig.REVIEW_QUEUE, payload);
                log.info("Review task {} published to {}", taskId, RabbitConfig.REVIEW_QUEUE);
            } else {
                log.warn("RabbitTemplate missing; executing review task {} synchronously", taskId);
                orchestrator.execute(taskId);
            }
        } catch (Exception ex) {
            log.warn("Queue publish failed for task {}; falling back to sync execution", taskId, ex);
            orchestrator.execute(taskId);
        }
    }

    public Map<String, Object> trace(long taskId) {
        return agentTraceService.timeline(get(taskId));
    }

    public Map<String, Object> trace(String key) {
        return trace(requireId(key));
    }

    public ReviewTask get(String key) {
        return findOwned(TenantContext.tenantId(), key)
                .orElseThrow(() -> ApiException.notFound("task not found"));
    }

    public ReviewTask get(long taskId) {
        return reviewTaskRepo.findByIdAndTenantId(taskId, TenantContext.tenantId())
                .orElseThrow(() -> ApiException.notFound("task not found"));
    }

    public java.util.Optional<ReviewTask> findOwned(long tenantId, String key) {
        if (key == null || key.isBlank()) {
            return java.util.Optional.empty();
        }
        String s = key.trim();
        var byNo = reviewTaskRepo.findByTaskNoAndTenantId(s, tenantId);
        if (byNo.isPresent()) {
            return byNo;
        }
        if (BusinessNos.looksNumericPk(s)) {
            return reviewTaskRepo.findByIdAndTenantId(Long.parseLong(s), tenantId);
        }
        return java.util.Optional.empty();
    }

    public long requireId(String key) {
        return get(key).getId();
    }

    public ReviewTask retry(String key) {
        return retry(requireId(key));
    }

    public ReviewTask cancel(String key) {
        return cancel(requireId(key));
    }

    public ReviewTask accept(String key) {
        return accept(requireId(key));
    }

    public Map<String, Object> acceptPartial(String key, List<String> patchIds) {
        return acceptPartial(requireId(key), patchIds);
    }

    public ReviewTask reject(String key) {
        return reject(requireId(key));
    }

    public Map<String, String> diff(String key) {
        return diff(requireId(key));
    }

    public Map<String, Object> mergePreview(String key, List<String> patchIds) {
        return mergePreview(requireId(key), patchIds);
    }

    public List<Object> artifacts(String key) {
        return artifacts(requireId(key));
    }

    public List<Object> artifacts(long taskId) {
        ReviewTask task = get(taskId);
        return artifactStore.list(task.getId(), TenantContext.tenantId()).stream()
                .map(this::toPublicArtifact)
                .map(Object.class::cast)
                .toList();
    }

    /** Map.of 不允许 null，落库 payload / fencingToken 为空时不能把整个列表打成 500。 */
    public Map<String, Object> toPublicArtifact(Artifact a) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", a.getId());
        row.put("agent", a.getAgent() == null ? "" : a.getAgent());
        row.put("artifactType", a.getArtifactType() == null ? "" : a.getArtifactType());
        row.put("fencingToken", a.getFencingToken() == null ? 0L : a.getFencingToken());
        row.put("payload", a.getPayload() == null ? "" : a.getPayload());
        return row;
    }

    @Transactional
    public ReviewTask accept(long taskId) {
        ReviewTask task = requireWaiting(taskId);
        if (task.getCandidateVersion() == null) {
            throw ApiException.bad("no candidate version");
        }
        Manuscript ms = manuscriptRepo.findByIdAndTenantId(task.getManuscriptId(), TenantContext.tenantId()).orElseThrow();
        DocumentVersion candidate = documentVersionRepo
                .findByManuscriptIdAndVersionNoAndTenantId(ms.getId(), task.getCandidateVersion(), TenantContext.tenantId())
                .orElseThrow();
        candidate.setStatus(Codes.OFFICIAL);
        documentVersionRepo.save(candidate);
        DocumentVersion old = documentVersionRepo
                .findByManuscriptIdAndVersionNoAndTenantId(ms.getId(), task.getSourceVersion(), TenantContext.tenantId())
                .orElseThrow();
        if (!old.getId().equals(candidate.getId())) {
            old.setStatus(Codes.REJECTED);
            documentVersionRepo.save(old);
        }
        ms.setCurrentVersion(candidate.getVersionNo());
        manuscriptRepo.save(ms);
        task.setStatus(Codes.DONE);
        ReviewTask saved = reviewTaskRepo.save(task);
        inboxService.notifyReview(saved);
        inboxService.markTaskRead(saved);
        return saved;
    }

    @Transactional
    public Map<String, Object> acceptPartial(long taskId, List<String> patchIds) {
        ReviewTask task = requireWaiting(taskId);
        List<String> selected = normalizeIds(patchIds);
        if (selected.isEmpty()) {
            throw ApiException.bad("请至少选择一处修改");
        }
        List<PatchRef> all = collectPatches(task.getId(), TenantContext.tenantId());
        LinkedHashSet<PatchRef> picked = new LinkedHashSet<>();
        for (String sel : selected) {
            for (PatchRef p : all) {
                if (p.matches(sel)) {
                    picked.add(p);
                }
            }
        }
        if (picked.isEmpty()) {
            throw ApiException.bad("没有匹配到任何建议修改");
        }
        DocumentVersion source = documentVersionRepo
                .findByManuscriptIdAndVersionNoAndTenantId(task.getManuscriptId(), task.getSourceVersion(), TenantContext.tenantId())
                .orElseThrow(() -> ApiException.notFound("source version not found"));
        String text = source.getContentText() == null ? "" : source.getContentText();
        List<PatchApplier.Spec> specs = new ArrayList<>();
        for (PatchRef p : picked) {
            specs.add(p.toSpec());
        }
        PatchApplier.Result appliedResult = PatchApplier.applyAll(text, specs);
        text = appliedResult.text();
        int applied = appliedResult.applied();
        List<String> skipped = new ArrayList<>();
        for (String orig : appliedResult.skipped()) {
            skipped.add("正文里找不到「" + clip(orig, 40) + "」，已跳过");
        }
        Manuscript ms = manuscriptRepo.findByIdAndTenantId(task.getManuscriptId(), TenantContext.tenantId()).orElseThrow();
        int nextNo = documentVersionRepo.findByManuscriptIdAndTenantIdOrderByVersionNoAsc(ms.getId(), TenantContext.tenantId())
                .stream().mapToInt(DocumentVersion::getVersionNo).max().orElse(task.getSourceVersion()) + 1;
        DocumentVersion neu = manuscriptService.saveVersion(ms, nextNo, Codes.CANDIDATE, source.getStoragePath(), text);
        neu.setStatus(Codes.OFFICIAL);
        documentVersionRepo.save(neu);
        for (DocumentVersion v : documentVersionRepo.findByManuscriptIdAndTenantIdOrderByVersionNoAsc(ms.getId(), TenantContext.tenantId())) {
            if (v.getId().equals(neu.getId())) {
                continue;
            }
            if (Codes.OFFICIAL.equals(v.getStatus()) || Codes.CANDIDATE.equals(v.getStatus())) {
                v.setStatus(Codes.REJECTED);
                documentVersionRepo.save(v);
            }
        }
        try {
            ragService.indexManuscript(ms.getTenantId(), ms.getProjectId(), ms.getId(), nextNo,
                    chunker.split(text), "PRIVATE");
        } catch (Exception e) {
            log.warn("index after partial accept failed: {}", e.getMessage());
        }
        ms.setCurrentVersion(neu.getVersionNo());
        manuscriptRepo.save(ms);
        task.setCandidateVersion(neu.getVersionNo());
        task.setStatus(Codes.DONE);
        reviewTaskRepo.save(task);
        inboxService.notifyReview(task);
        inboxService.markTaskRead(task);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", task.publicId());
        out.put("status", task.getStatus());
        out.put("manuscriptId", task.getManuscriptId());
        out.put("sourceVersion", task.getSourceVersion());
        out.put("candidateVersion", task.getCandidateVersion());
        out.put("applied", applied);
        out.put("skipped", skipped);
        return out;
    }

    @Transactional
    public ReviewTask reject(long taskId) {
        ReviewTask task = requireWaiting(taskId);
        if (task.getCandidateVersion() != null) {
            documentVersionRepo.findByManuscriptIdAndVersionNoAndTenantId(
                    task.getManuscriptId(), task.getCandidateVersion(), TenantContext.tenantId()
            ).ifPresent(v -> {
                v.setStatus(Codes.REJECTED);
                documentVersionRepo.save(v);
            });
        }
        task.setStatus(Codes.DONE);
        ReviewTask saved = reviewTaskRepo.save(task);
        inboxService.notifyReview(saved);
        inboxService.markTaskRead(saved);
        return saved;
    }

    public Map<String, String> diff(long taskId) {
        ReviewTask task = get(taskId);
        DocumentVersion source = documentVersionRepo
                .findByManuscriptIdAndVersionNoAndTenantId(task.getManuscriptId(), task.getSourceVersion(), TenantContext.tenantId())
                .orElseThrow();
        String official = manuscriptService.resolveText(source);
        String candidateText = official;
        if (task.getCandidateVersion() != null) {
            candidateText = documentVersionRepo
                    .findByManuscriptIdAndVersionNoAndTenantId(task.getManuscriptId(), task.getCandidateVersion(), TenantContext.tenantId())
                    .map(manuscriptService::resolveText).orElse(official);
        }
        return Map.of("official", official, "candidate", candidateText);
    }

    /**
     * 合并预览：正式稿 vs 将应用的修改。patchIds == null 用整份候选稿；非 null 则只应用勾选的 patch。
     * 不写库、不改正式稿。
     */
    public Map<String, Object> mergePreview(long taskId, List<String> patchIds) {
        ReviewTask task = get(taskId);
        DocumentVersion source = documentVersionRepo
                .findByManuscriptIdAndVersionNoAndTenantId(task.getManuscriptId(), task.getSourceVersion(), TenantContext.tenantId())
                .orElseThrow(() -> ApiException.notFound("source version not found"));
        String official = manuscriptService.resolveText(source);
        String candidate = official;
        if (task.getCandidateVersion() != null) {
            candidate = documentVersionRepo
                    .findByManuscriptIdAndVersionNoAndTenantId(task.getManuscriptId(), task.getCandidateVersion(), TenantContext.tenantId())
                    .map(manuscriptService::resolveText).orElse(official);
        }
        List<PatchRef> all = collectPatches(task.getId(), TenantContext.tenantId());
        boolean partial = patchIds != null;
        String preview = candidate;
        String mode = "candidate";
        List<PatchRef> used = all;
        List<String> skipped = new ArrayList<>();
        int applied = 0;
        if (partial) {
            mode = "partial";
            List<String> selected = normalizeIds(patchIds);
            LinkedHashSet<PatchRef> picked = new LinkedHashSet<>();
            for (String sel : selected) {
                for (PatchRef p : all) {
                    if (p.matches(sel)) {
                        picked.add(p);
                    }
                }
            }
            used = new ArrayList<>(picked);
            preview = official;
            List<PatchApplier.Spec> specs = new ArrayList<>();
            for (PatchRef p : used) {
                specs.add(p.toSpec());
            }
            PatchApplier.Result appliedResult = PatchApplier.applyAll(preview, specs);
            preview = appliedResult.text();
            applied = appliedResult.applied();
            for (String orig : appliedResult.skipped()) {
                skipped.add("正文里找不到「" + clip(orig, 40) + "」，预览已跳过");
            }
        } else {
            applied = all.size();
        }

        List<Map<String, Object>> artifacts = artifactStore.list(task.getId(), TenantContext.tenantId()).stream()
                .map(a -> Map.<String, Object>of(
                        "agent", a.getAgent(),
                        "artifactType", a.getArtifactType(),
                        "payload", a.getPayload() == null ? "" : a.getPayload()
                )).toList();
        ScoreCard score = scoreMerge(official, preview, used, artifacts);
        List<Map<String, Object>> reasons = explainChanges(used, artifacts);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("official", official);
        out.put("preview", preview);
        out.put("candidate", candidate);
        out.put("mode", mode);
        out.put("sourceVersion", task.getSourceVersion());
        out.put("candidateVersion", task.getCandidateVersion());
        out.put("applied", applied);
        out.put("skipped", skipped);
        out.put("score", score.score());
        out.put("grade", score.grade());
        out.put("points", score.points());
        out.put("reasons", reasons);
        out.put("changed", !official.equals(preview));
        return out;
    }

    private ReviewTask requireWaiting(long taskId) {
        ReviewTask task = get(taskId);
        if (!Codes.WAITING_ACCEPT.equals(task.getStatus())) {
            throw ApiException.conflict("task is not waiting for accept/reject");
        }
        return task;
    }

    private List<String> normalizeIds(List<String> patchIds) {
        if (patchIds == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : patchIds) {
            if (raw == null) {
                continue;
            }
            String id = raw.trim();
            if (id.isEmpty() || isJunk(id) || !seen.add(id)) {
                continue;
            }
            out.add(id);
        }
        return out;
    }

    private List<PatchRef> collectPatches(long taskId, long tenantId) {
        List<PatchRef> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Artifact artifact : artifactStore.list(taskId, tenantId)) {
            JsonNode body = unwrapBody(artifact.getPayload());
            for (JsonNode node : patchNodes(artifact.getArtifactType(), body)) {
                String original = firstText(node, "originalText", "original_text", "original", "oldText", "old_text", "before");
                String proposed = firstText(node, "proposedText", "proposed_text", "proposed", "newText", "new_text", "after");
                if (original.isBlank() && proposed.isBlank()) {
                    continue;
                }
                if (original.equals(proposed)) {
                    continue;
                }
                String patchId = firstText(node, "patchId");
                String reason = firstText(node, "reason", "rationale", "explanation", "comment", "instruction");
                PatchApplier.Spec spec = PatchApplier.fromJson(node);
                String composite = original + "→" + proposed;
                String dedupe = !patchId.isBlank() ? patchId : composite;
                if (!seen.add(dedupe)) {
                    continue;
                }
                int index = out.size();
                out.add(new PatchRef(patchId, composite, original, proposed, reason, index,
                        spec.startOffset(), spec.endOffset()));
            }
        }
        return out;
    }

    private JsonNode unwrapBody(String payload) {
        if (payload == null || payload.isBlank()) {
            return objectMapper.missingNode();
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root != null && root.has("body")) {
                return root.get("body");
            }
            return root == null ? objectMapper.missingNode() : root;
        } catch (Exception e) {
            return objectMapper.missingNode();
        }
    }

    private static List<JsonNode> patchNodes(String artifactType, JsonNode body) {
        if (body == null || body.isMissingNode() || body.isNull()) {
            return List.of();
        }
        if ("Bundle".equals(artifactType)) {
            return asArray(firstNode(body, "patches", "revisions", "edits", "hunks"));
        }
        if (!"RevisionPatch".equals(artifactType)) {
            return List.of();
        }
        if (body.isArray()) {
            return asArray(body);
        }
        JsonNode nested = firstNode(body, "patches", "revisions", "edits");
        if (nested != null && !nested.isMissingNode() && !nested.isNull()) {
            return asArray(nested);
        }
        return List.of(body);
    }

    private static JsonNode firstNode(JsonNode body, String... keys) {
        if (body == null || !body.isObject()) {
            return null;
        }
        for (String key : keys) {
            if (body.has(key)) {
                return body.get(key);
            }
        }
        return null;
    }

    private static List<JsonNode> asArray(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<JsonNode> out = new ArrayList<>();
            node.forEach(out::add);
            return out;
        }
        if (node.isObject()) {
            return List.of(node);
        }
        return List.of();
    }

    private static String firstText(JsonNode node, String... keys) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull() || value.isMissingNode() || !value.isTextual()) {
                continue;
            }
            String text = value.asText("").trim();
            if (!text.isEmpty() && !isJunk(text)) {
                return text;
            }
        }
        return "";
    }

    private static boolean isJunk(String s) {
        if (s == null) {
            return true;
        }
        String t = s.trim();
        return t.isEmpty() || t.contains("[native code]") || t.startsWith("function ");
    }

    private static String clip(String s, int n) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        if (t.length() <= n) {
            return t;
        }
        return t.substring(0, n) + "…";
    }

    private ScoreCard scoreMerge(String official, String preview, List<PatchRef> used, List<Map<String, Object>> artifacts) {
        List<Map<String, Object>> points = new ArrayList<>();
        int score = 58;
        int resolved = 0;
        int human = 0;
        int unverifiedLeft = 0;
        int highLeft = 0;
        int newProblems = 0;
        List<JsonNode> verifications = collectTyped(artifacts, "VerificationResult");
        List<JsonNode> issues = collectTyped(artifacts, "ReviewIssue");
        if (issues.isEmpty()) {
            issues = collectBundleField(artifacts, "issues");
        }
        if (verifications.isEmpty()) {
            verifications = collectBundleField(artifacts, "verification");
        }
        for (JsonNode vr : verifications) {
            if (vr.path("resolved").asBoolean(false)) {
                resolved++;
            }
            if (vr.path("stillHumanRequired").asBoolean(false)) {
                human++;
            }
            JsonNode np = vr.get("newProblems");
            if (np != null && np.isArray()) {
                newProblems += np.size();
            } else if (np != null && np.isTextual() && !np.asText().isBlank()) {
                newProblems++;
            }
        }
        if (resolved > 0) {
            int bonus = Math.min(24, resolved * 8);
            score += bonus;
            points.add(point(bonus, "复核确认已解决 " + resolved + " 项"));
        }
        if (human > 0) {
            int penalty = Math.min(24, human * 8);
            score -= penalty;
            points.add(point(-penalty, "仍有 " + human + " 项需要人工处理"));
        }
        if (newProblems > 0) {
            int penalty = Math.min(15, newProblems * 5);
            score -= penalty;
            points.add(point(-penalty, "复核发现 " + newProblems + " 个新问题"));
        }
        int reasoned = 0;
        for (PatchRef p : used) {
            if (p.original() != null && !p.original().isBlank() && p.proposed() != null && !p.original().equals(p.proposed())) {
                reasoned++;
            }
        }
        if (reasoned > 0) {
            int bonus = Math.min(15, reasoned * 3);
            score += bonus;
            points.add(point(bonus, "将写入 " + reasoned + " 处有依据的修改"));
        }
        String previewText = preview == null ? "" : preview;
        for (JsonNode issue : issues) {
            String severity = issue.path("severity").asText("");
            String summary = firstText(issue, "summary", "detail", "message");
            String doi = extractDoi(issue);
            boolean citationUnverified = "NOT_VERIFIED".equals(severity)
                    || summary.toUpperCase().contains("NOT_VERIFIED")
                    || summary.toLowerCase().contains("doi not found");
            if (doi != null && citationUnverified && previewText.contains(doi)) {
                unverifiedLeft++;
            }
            if (("HIGH".equals(severity) || "CRITICAL".equals(severity)) && !"CITATION".equals(issue.path("category").asText())) {
                String original = firstText(issue, "originalText", "original_text", "excerpt");
                if (!original.isBlank() && previewText.contains(original)) {
                    highLeft++;
                }
            }
        }
        if (unverifiedLeft > 0) {
            int penalty = Math.min(24, unverifiedLeft * 12);
            score -= penalty;
            points.add(point(-penalty, "预览稿里仍有 " + unverifiedLeft + " 处未能核实的引用"));
        }
        if (highLeft > 0) {
            int penalty = Math.min(18, highLeft * 6);
            score -= penalty;
            points.add(point(-penalty, "预览稿仍留有 " + highLeft + " 条高优先级问题原文"));
        }
        boolean changed = official != null && !official.equals(preview);
        if (changed) {
            score += 4;
            points.add(point(4, "相对正式稿确有改动"));
        } else {
            score -= 8;
            points.add(point(-8, "预览与正式稿相同，没有可合并的改动"));
        }
        score = Math.max(0, Math.min(96, score));
        if (!(human == 0 && unverifiedLeft == 0 && highLeft == 0 && newProblems == 0 && resolved > 0 && changed)) {
            score = Math.min(92, score);
        }
        String grade = score >= 90 ? "A" : score >= 78 ? "B" : score >= 62 ? "C" : "D";
        return new ScoreCard(score, grade, points);
    }

    private List<Map<String, Object>> explainChanges(List<PatchRef> used, List<Map<String, Object>> artifacts) {
        List<JsonNode> issues = collectTyped(artifacts, "ReviewIssue");
        if (issues.isEmpty()) {
            issues = collectBundleField(artifacts, "issues");
        }
        List<JsonNode> tasks = collectTyped(artifacts, "RevisionTask");
        if (tasks.isEmpty()) {
            tasks = collectBundleField(artifacts, "revisionTasks");
        }
        List<JsonNode> vers = collectTyped(artifacts, "VerificationResult");
        if (vers.isEmpty()) {
            vers = collectBundleField(artifacts, "verification");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int i = 0;
        for (PatchRef p : used) {
            i++;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("patchId", p.patchId());
            row.put("original", clip(p.original(), 80));
            row.put("proposed", clip(p.proposed(), 80));
            row.put("why", reasonFor(p, issues, tasks, vers));
            out.add(row);
        }
        if (out.isEmpty()) {
            for (JsonNode issue : issues) {
                String summary = firstText(issue, "summary", "detail", "message");
                if (summary.isBlank()) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("index", out.size() + 1);
                row.put("patchId", firstText(issue, "issueId"));
                row.put("original", clip(firstText(issue, "originalText", "excerpt"), 80));
                row.put("proposed", "");
                row.put("why", summary);
                out.add(row);
                if (out.size() >= 12) {
                    break;
                }
            }
        }
        return out;
    }

    private static String reasonFor(PatchRef p, List<JsonNode> issues, List<JsonNode> tasks, List<JsonNode> vers) {
        if (p.reason() != null && !p.reason().isBlank() && !isJunk(p.reason())) {
            return p.reason();
        }
        JsonNode issue = findIssue(p, issues);
        if (issue != null) {
            for (JsonNode vr : vers) {
                if (firstText(issue, "issueId").equals(firstText(vr, "issueId"))) {
                    String notes = firstText(vr, "notes", "note");
                    boolean resolved = vr.path("resolved").asBoolean(false);
                    boolean human = vr.path("stillHumanRequired").asBoolean(false);
                    String summary = firstText(issue, "summary", "detail");
                    if (resolved && !human) {
                        return (summary.isBlank() ? "复核认为这一处已经改对。" : summary)
                                + (notes.isBlank() ? "" : " " + notes);
                    }
                    if (human) {
                        return (summary.isBlank() ? "这一处仍需你过目。" : summary)
                                + (notes.isBlank() ? "" : " " + notes);
                    }
                }
            }
            String summary = firstText(issue, "summary", "detail");
            if (!summary.isBlank()) {
                return summary;
            }
        }
        for (JsonNode task : tasks) {
            if (p.patchId() != null && !p.patchId().isBlank() && p.patchId().equals(firstText(task, "patchId"))) {
                String ins = firstText(task, "instruction", "reason", "guidance");
                if (!ins.isBlank()) {
                    return ins;
                }
            }
            if (issue != null && firstText(issue, "issueId").equals(firstText(task, "issueId"))) {
                String ins = firstText(task, "instruction", "reason", "guidance");
                if (!ins.isBlank()) {
                    return ins;
                }
            }
        }
        if (p.original() != null && p.proposed() != null && !p.original().equals(p.proposed())) {
            return "改写这一句，让表述更准确、少套话。";
        }
        return "与审校意见对齐的一处修改。";
    }

    private static JsonNode findIssue(PatchRef p, List<JsonNode> issues) {
        for (JsonNode issue : issues) {
            String id = firstText(issue, "issueId");
            if (!id.isBlank() && (id.equals(p.patchId()) || (p.composite() != null && p.composite().contains(id)))) {
                return issue;
            }
        }
        String doi = doiOf(p.original());
        if (doi == null) {
            doi = doiOf(p.proposed());
        }
        if (doi != null) {
            for (JsonNode issue : issues) {
                if (issue.toString().contains(doi)) {
                    return issue;
                }
            }
        }
        String head = p.original() == null ? "" : p.original().trim();
        if (head.length() >= 12) {
            String needle = head.substring(0, Math.min(40, head.length()));
            for (JsonNode issue : issues) {
                if (issue.toString().contains(needle)) {
                    return issue;
                }
            }
        }
        return null;
    }

    private List<JsonNode> collectTyped(List<Map<String, Object>> artifacts, String type) {
        List<JsonNode> out = new ArrayList<>();
        for (Map<String, Object> a : artifacts) {
            if (!type.equals(String.valueOf(a.get("artifactType")))) {
                continue;
            }
            JsonNode body = unwrapBody(String.valueOf(a.get("payload")));
            out.addAll(asArray(body));
        }
        return out;
    }

    private List<JsonNode> collectBundleField(List<Map<String, Object>> artifacts, String field) {
        List<JsonNode> out = new ArrayList<>();
        for (Map<String, Object> a : artifacts) {
            if (!"Bundle".equals(String.valueOf(a.get("artifactType")))) {
                continue;
            }
            JsonNode body = unwrapBody(String.valueOf(a.get("payload")));
            if (body != null && body.has(field)) {
                out.addAll(asArray(body.get(field)));
            }
        }
        return out;
    }

    private static Map<String, Object> point(int delta, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("delta", delta);
        m.put("label", label);
        return m;
    }

    private static String extractDoi(JsonNode issue) {
        String direct = firstText(issue, "doi");
        if (!direct.isBlank()) {
            return direct;
        }
        return doiOf(firstText(issue, "summary", "detail", "excerpt", "originalText"));
    }

    private static String doiOf(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("10\\.\\d{4,9}/[^\\s，。；;,)]+", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(text);
        if (!m.find()) {
            return null;
        }
        return m.group().replaceAll("[.,;]+$", "");
    }

    private record ScoreCard(int score, String grade, List<Map<String, Object>> points) {
    }

    private record PatchRef(String patchId, String composite, String original, String proposed, String reason, int index,
                            int startOffset, int endOffset) {
        boolean matches(String sel) {
            if (sel == null || sel.isBlank()) {
                return false;
            }
            return sel.equals(patchId) || sel.equals(composite) || sel.equals(String.valueOf(index));
        }

        PatchApplier.Spec toSpec() {
            return new PatchApplier.Spec(startOffset, endOffset, original, proposed);
        }
    }
}
