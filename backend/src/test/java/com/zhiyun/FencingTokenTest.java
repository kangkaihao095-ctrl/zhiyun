package com.zhiyun;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyun.billing.BillingService;
import com.zhiyun.common.ApiException;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.QuotaAccount;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.domain.TaskLease;
import com.zhiyun.harness.AgentIds;
import com.zhiyun.harness.ArtifactStore;
import com.zhiyun.harness.LeaseService;
import com.zhiyun.repo.ArtifactRepo;
import com.zhiyun.repo.QuotaAccountRepo;
import com.zhiyun.repo.QuotaLedgerRepo;
import com.zhiyun.repo.ReviewTaskRepo;
import com.zhiyun.repo.TaskLeaseRepo;
import com.zhiyun.workflow.ReviewOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class FencingTokenTest {
    @Autowired
    ArtifactStore artifactStore;
    @Autowired
    ReviewTaskRepo reviewTaskRepo;
    @Autowired
    TaskLeaseRepo taskLeaseRepo;
    @Autowired
    ArtifactRepo artifactRepo;
    @Autowired
    LeaseService leaseService;
    @Autowired
    ReviewOrchestrator orchestrator;
    @Autowired
    BillingService billingService;
    @Autowired
    QuotaAccountRepo quotaAccountRepo;
    @Autowired
    QuotaLedgerRepo quotaLedgerRepo;
    @Autowired
    ObjectMapper mapper;

    @Test
    void staleFencingTokenIsRejected() {
        ReviewTask task = newTask(Codes.RUNNING, 5L);
        ObjectNode body = mapper.createObjectNode().put("ok", true);
        ReviewTask finalTask = task;
        assertThatThrownBy(() -> artifactStore.save(finalTask, 3L, AgentIds.CITATION, "Bundle", body))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("stale fencing token");
        assertThat(artifactRepo.findByTaskIdAndAgentAndArtifactType(task.getId(), AgentIds.CITATION, "Bundle"))
                .isEmpty();
    }

    @Test
    void staleWorkerCannotWriteCancelledTaskDoneOrSettleTwice() {
        ReviewTask task = newTask(Codes.RUNNING, 0L);
        String ownerA = leaseService.newOwner();
        long tokenA = leaseService.acquire(task, ownerA);
        assertThat(tokenA).isGreaterThan(0L);
        assertThat(taskLeaseRepo.findById(task.getId())).isPresent();

        int cancelled = reviewTaskRepo.markCancelled(task.getId(), task.getTenantId(), Codes.FAILED, "已取消");
        assertThat(cancelled).isEqualTo(1);
        leaseService.forceRelease(task.getId());
        long current = reviewTaskRepo.findById(task.getId()).orElseThrow().getFencingToken();
        assertThat(current).isGreaterThan(tokenA);

        ObjectNode body = mapper.createObjectNode().put("ok", true);
        assertThatThrownBy(() -> artifactStore.save(task, tokenA, AgentIds.CITATION, "Bundle", body))
                .hasMessageContaining("stale fencing token");
        orchestrator.finish(task, tokenA);
        ReviewTask after = reviewTaskRepo.findById(task.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Codes.FAILED);
        assertThat(after.getErrorMessage()).isEqualTo("已取消");

        seedQuota(task);
        String ref = "task-" + task.getId();
        int first = billingService.settleUsage(task.getTenantId(), task.getUserId(), task.getWorkflow(), 2000, ref, 0);
        int second = billingService.settleUsage(task.getTenantId(), task.getUserId(), task.getWorkflow(), 2000, ref, 0);
        assertThat(first).isGreaterThan(0);
        assertThat(second).isEqualTo(0);
        assertThat(quotaLedgerRepo.findByTenantIdAndUserIdAndRefIdOrderByIdDesc(
                task.getTenantId(), task.getUserId(), ref)).hasSize(1);
    }

    @Test
    void expiredLeaseSecondWorkerFencesFirstWriter() {
        ReviewTask task = newTask(Codes.RUNNING, 0L);
        String ownerA = leaseService.newOwner();
        long tokenA = leaseService.acquire(task, ownerA);
        TaskLease lease = taskLeaseRepo.findById(task.getId()).orElseThrow();
        lease.setExpireAt(Instant.now().minusSeconds(120));
        taskLeaseRepo.saveAndFlush(lease);

        String ownerB = leaseService.newOwner();
        long tokenB = leaseService.acquire(task, ownerB);
        assertThat(tokenB).isGreaterThan(tokenA);
        assertThat(leaseService.holds(task.getId(), tokenA)).isFalse();
        assertThat(leaseService.holds(task.getId(), tokenB)).isTrue();

        ObjectNode body = mapper.createObjectNode().put("ok", true);
        assertThatThrownBy(() -> artifactStore.save(task, tokenA, AgentIds.CITATION, "Bundle", body))
                .hasMessageContaining("stale fencing token");
        artifactStore.save(task, tokenB, AgentIds.CITATION, "Bundle", body);
        assertThat(artifactRepo.findByTaskIdAndAgentAndArtifactType(task.getId(), AgentIds.CITATION, "Bundle"))
                .isPresent();

        orchestrator.finish(task, tokenA);
        assertThat(reviewTaskRepo.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(Codes.RUNNING);
        orchestrator.finish(task, tokenB);
        assertThat(reviewTaskRepo.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(Codes.DONE);
    }

    private ReviewTask newTask(String status, long fencing) {
        ReviewTask task = new ReviewTask();
        task.setTenantId(1L);
        task.setUserId(1L);
        task.setManuscriptId(1L);
        task.setWorkflow(Codes.CITATION_ONLY);
        task.setStatus(status);
        task.setSourceVersion(1);
        task.setFencingToken(fencing);
        task.setIdempotencyKey("fence-" + System.nanoTime());
        return reviewTaskRepo.saveAndFlush(task);
    }

    private void seedQuota(ReviewTask task) {
        QuotaAccount account = quotaAccountRepo.findByTenantIdAndUserId(task.getTenantId(), task.getUserId())
                .orElseGet(QuotaAccount::new);
        account.setTenantId(task.getTenantId());
        account.setUserId(task.getUserId());
        account.setBalance(20);
        quotaAccountRepo.saveAndFlush(account);
    }
}
