package com.zhiyun.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.flow.LiteflowResponse;
import com.zhiyun.billing.BillingService;
import com.zhiyun.common.PublicError;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.DocumentVersion;
import com.zhiyun.domain.Manuscript;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.harness.AgentIds;
import com.zhiyun.harness.ArtifactStore;
import com.zhiyun.harness.LeaseService;
import com.zhiyun.harness.ReviewSlot;
import com.zhiyun.llm.UsageMeter;
import com.zhiyun.manuscript.ManuscriptService;
import com.zhiyun.notify.InboxService;
import com.zhiyun.rag.RagService;
import com.zhiyun.rag.SemanticChunker;
import com.zhiyun.repo.DocumentVersionRepo;
import com.zhiyun.repo.ManuscriptRepo;
import com.zhiyun.repo.ReviewTaskRepo;
import com.zhiyun.security.AuthUser;
import com.zhiyun.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class ReviewOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(ReviewOrchestrator.class);

    private final FlowExecutor flowExecutor;
    private final ReviewTaskRepo reviewTaskRepo;
    private final ManuscriptRepo manuscriptRepo;
    private final DocumentVersionRepo documentVersionRepo;
    private final LeaseService leaseService;
    private final ArtifactStore artifactStore;
    private final ManuscriptService manuscriptService;
    private final SemanticChunker chunker;
    private final RagService ragService;
    private final BillingService billingService;
    private final InboxService inboxService;

    public ReviewOrchestrator(FlowExecutor flowExecutor, ReviewTaskRepo reviewTaskRepo, ManuscriptRepo manuscriptRepo,
                              DocumentVersionRepo documentVersionRepo, LeaseService leaseService,
                              ArtifactStore artifactStore, ManuscriptService manuscriptService,
                              SemanticChunker chunker, RagService ragService, BillingService billingService,
                              InboxService inboxService) {
        this.flowExecutor = flowExecutor;
        this.reviewTaskRepo = reviewTaskRepo;
        this.manuscriptRepo = manuscriptRepo;
        this.documentVersionRepo = documentVersionRepo;
        this.leaseService = leaseService;
        this.artifactStore = artifactStore;
        this.manuscriptService = manuscriptService;
        this.chunker = chunker;
        this.ragService = ragService;
        this.billingService = billingService;
        this.inboxService = inboxService;
    }

    /**
     * 首次执行、lease 过期重投、以及 FAILED 的 {@code POST /reviews/{id}/retry} 共用此入口。
     * 已完成 Agent 由 ArtifactStore.completed 跳过；同一 taskId 结算一次。
     */
    public void execute(long taskId) {
        ReviewTask task = reviewTaskRepo.findById(taskId).orElseThrow();
        // 已取消或已结束：不抢 lease、不结算，避免把 FAILED 改回 RUNNING/DONE
        if (!Codes.PENDING.equals(task.getStatus()) && !Codes.RUNNING.equals(task.getStatus())) {
            log.info("review {} skip execute; status={}", taskId, task.getStatus());
            return;
        }
        TenantContext.set(new AuthUser(task.getUserId(), task.getTenantId(), "worker", "worker"));
        String owner = leaseService.newOwner();
        boolean started = false;
        long token = 0L;
        AutoCloseable renewal = null;
        try {
            token = leaseService.acquire(task, owner);
            renewal = leaseService.startRenewal(taskId, owner);
            task = reviewTaskRepo.findById(taskId).orElseThrow();
            if (!Codes.PENDING.equals(task.getStatus()) && !Codes.RUNNING.equals(task.getStatus())) {
                log.info("review {} cancelled before run; status={}", taskId, task.getStatus());
                return;
            }
            if (reviewTaskRepo.markRunning(taskId, Codes.RUNNING) == 0) {
                log.info("review {} not marked running (cancelled)", taskId);
                return;
            }
            task = reviewTaskRepo.findById(taskId).orElseThrow();
            UsageMeter.open();
            started = true;

            Manuscript manuscript = manuscriptRepo.findById(task.getManuscriptId()).orElseThrow();
            DocumentVersion source = documentVersionRepo
                    .findByManuscriptIdAndVersionNoAndTenantId(manuscript.getId(), task.getSourceVersion(), task.getTenantId())
                    .orElseThrow();

            ReviewSlot slot = new ReviewSlot();
            slot.setTask(task);
            slot.setManuscript(manuscript);
            slot.setSource(source);
            slot.setFencingToken(token);
            slot.setOwner(owner);

            LiteflowResponse response = flowExecutor.execute2Resp(task.getWorkflow(), null, slot);
            if (!response.isSuccess()) {
                throw new IllegalStateException(response.getCause() == null ? "liteflow failed" : response.getCause().getMessage(),
                        response.getCause());
            }
            finish(task, token);
            inboxService.notifyReview(reviewTaskRepo.findById(taskId).orElse(task));
        } catch (Exception e) {
            log.error("review {} failed: {}", taskId, e.getMessage());
            // 旧 token / 已取消：CAS 0 行，不把 FAILED 改回、不覆盖新 Worker
            if (leaseService.holds(taskId, token)) {
                String message = PublicError.message(e.getMessage() == null ? "failed" : e.getMessage());
                if (reviewTaskRepo.casFail(taskId, Codes.FAILED, message, token) > 0) {
                    inboxService.notifyReview(reviewTaskRepo.findById(taskId).orElse(task));
                }
            }
        } finally {
            int byok = UsageMeter.byokAgents();
            int tokens = UsageMeter.close();
            if (started && leaseService.holds(taskId, token)) {
                try {
                    String ref = "task-" + (task.publicId() != null ? task.publicId() : taskId);
                    int points = billingService.settleUsage(
                            task.getTenantId(), task.getUserId(), task.getWorkflow(), tokens, ref, byok);
                    log.info("review {} settled {} tokens → {} points (byok={})", taskId, tokens, points, byok);
                } catch (Exception e) {
                    log.warn("review {} settle failed: {}", taskId, e.getMessage());
                }
            }
            if (renewal != null) {
                try {
                    renewal.close();
                } catch (Exception e) {
                    log.warn("lease heartbeat stop failed for {}: {}", taskId, e.getMessage());
                }
            }
            leaseService.release(taskId, owner);
            TenantContext.clear();
        }
    }

    @Transactional
    public void finish(ReviewTask task, long fencingToken) {
        if (!leaseService.holds(task.getId(), fencingToken)) {
            return;
        }
        ReviewTask fresh = reviewTaskRepo.findById(task.getId()).orElseThrow();
        if (!Codes.PENDING.equals(fresh.getStatus()) && !Codes.RUNNING.equals(fresh.getStatus())) {
            // 已取消（FAILED）或已被其他路径结束，不要写成 DONE / WAITING_ACCEPT
            return;
        }
        String next = Codes.DONE;
        if (Codes.FULL_REVIEW.equals(fresh.getWorkflow())) {
            applyCandidate(fresh);
            next = Codes.WAITING_ACCEPT;
        }
        reviewTaskRepo.casComplete(fresh.getId(), next, fresh.getCandidateVersion(), fencingToken);
    }

    private void applyCandidate(ReviewTask task) {
        if (task.getCandidateVersion() != null) {
            return;
        }
        DocumentVersion source = documentVersionRepo
                .findByManuscriptIdAndVersionNoAndTenantId(task.getManuscriptId(), task.getSourceVersion(), task.getTenantId())
                .orElseThrow();
        int next = task.getSourceVersion() + 1;
        var existing = documentVersionRepo
                .findByManuscriptIdAndVersionNoAndTenantId(task.getManuscriptId(), next, task.getTenantId());
        if (existing.isPresent()) {
            task.setCandidateVersion(next);
            return;
        }
        String text = source.getContentText();
        JsonNode patches = artifactStore.body(task.getId(), AgentIds.EXECUTION, "RevisionPatch");
        if (patches.isArray()) {
            List<PatchApplier.Spec> specs = new ArrayList<>();
            for (JsonNode patch : patches) {
                specs.add(PatchApplier.fromJson(patch));
            }
            text = PatchApplier.applyAll(text, specs).text();
        }
        Manuscript ms = manuscriptRepo.findById(task.getManuscriptId()).orElseThrow();
        DocumentVersion candidate = manuscriptService.saveVersion(ms, next, Codes.CANDIDATE, source.getStoragePath(), text);
        ragService.indexManuscript(ms.getTenantId(), ms.getProjectId(), ms.getId(), next,
                chunker.split(text), "PRIVATE");
        task.setCandidateVersion(candidate.getVersionNo());
    }
}
