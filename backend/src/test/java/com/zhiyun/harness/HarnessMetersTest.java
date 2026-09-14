package com.zhiyun.harness;

import com.zhiyun.domain.Codes;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.domain.TaskLease;
import com.zhiyun.rag.RagService;
import com.zhiyun.repo.ReviewTaskRepo;
import com.zhiyun.repo.TaskLeaseRepo;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class HarnessMetersTest {
    @Autowired
    MeterRegistry meterRegistry;
    @Autowired
    HarnessMeters harnessMeters;
    @Autowired
    LeaseService leaseService;
    @Autowired
    ReviewTaskRepo reviewTaskRepo;
    @Autowired
    TaskLeaseRepo taskLeaseRepo;
    @Autowired
    RagService ragService;

    @Test
    void customMetersAreRegisteredAndMoveOnLeaseRetrievalAndLlm() {
        assertThat(meterRegistry.find(HarnessMeters.LLM_CALLS).counter()).isNotNull();
        assertThat(meterRegistry.find(HarnessMeters.LLM_DURATION).timer()).isNotNull();
        assertThat(meterRegistry.find(HarnessMeters.LLM_TOKENS).counter()).isNotNull();
        assertThat(meterRegistry.find(HarnessMeters.LEASE_HELD).gauge()).isNotNull();
        assertThat(meterRegistry.find(HarnessMeters.LEASE_EXPIRED).counter()).isNotNull();
        assertThat(meterRegistry.find(HarnessMeters.RAG_KNN).counter()).isNotNull();
        assertThat(meterRegistry.find(HarnessMeters.RAG_KNN_DURATION).timer()).isNotNull();
        assertThat(meterRegistry.find(HarnessMeters.RAG_DURATION).timer()).isNotNull();
        assertThat(meterRegistry.find(HarnessMeters.RAG_RETRIEVALS).counter()).isNotNull();
        assertThat(meterRegistry.find(HarnessMeters.TOOL_FAILURES).counter()).isNotNull();

        double retrievalsBefore = meterRegistry.find(HarnessMeters.RAG_RETRIEVALS).counter().count();
        ragService.retrievePublic("quota plan");
        assertThat(meterRegistry.find(HarnessMeters.RAG_RETRIEVALS).counter().count())
                .isGreaterThan(retrievalsBefore);

        ReviewTask task = new ReviewTask();
        task.setTenantId(1L);
        task.setUserId(1L);
        task.setManuscriptId(1L);
        task.setWorkflow(Codes.CITATION_ONLY);
        task.setStatus(Codes.PENDING);
        task.setSourceVersion(1);
        task.setFencingToken(0L);
        task.setIdempotencyKey("meters-" + System.nanoTime());
        task = reviewTaskRepo.saveAndFlush(task);

        double heldBefore = meterRegistry.find(HarnessMeters.LEASE_HELD).gauge().value();
        String owner = leaseService.newOwner();
        leaseService.acquire(task, owner);
        assertThat(meterRegistry.find(HarnessMeters.LEASE_HELD).gauge().value()).isGreaterThan(heldBefore);
        leaseService.release(task.getId(), owner);
        assertThat(meterRegistry.find(HarnessMeters.LEASE_HELD).gauge().value()).isEqualTo(heldBefore);

        double callsBefore = meterRegistry.find(HarnessMeters.LLM_CALLS).counter().count();
        harnessMeters.recordLlmCall();
        harnessMeters.recordLlmTokens(12);
        assertThat(meterRegistry.find(HarnessMeters.LLM_CALLS).counter().count()).isEqualTo(callsBefore + 1);
        assertThat(harnessMeters.snapshot().get("caption").toString()).contains("非 SLA");

        double failBefore = meterRegistry.find(HarnessMeters.TOOL_FAILURES).counter().count();
        double toolBefore = meterRegistry.find(HarnessMeters.TOOL_CALLS).counter().count();
        harnessMeters.recordToolCall(true);
        harnessMeters.recordToolCall(false);
        assertThat(meterRegistry.find(HarnessMeters.TOOL_CALLS).counter().count()).isEqualTo(toolBefore + 2);
        assertThat(meterRegistry.find(HarnessMeters.TOOL_FAILURES).counter().count()).isEqualTo(failBefore + 1);

        harnessMeters.timeLlm(() -> "ok");
        assertThat(meterRegistry.find(HarnessMeters.LLM_DURATION).timer().count()).isGreaterThan(0);
        harnessMeters.recordKnn(1_000_000L);
        harnessMeters.recordRetrievalPublic(2_000_000L);
        assertThat(meterRegistry.find(HarnessMeters.RAG_KNN_DURATION).timer().count()).isGreaterThan(0);
        assertThat(meterRegistry.find(HarnessMeters.RAG_DURATION).timer().count()).isGreaterThan(0);
        assertThat(harnessMeters.snapshot()).containsKeys("llmDurationMs", "knnDurationMs", "ragDurationMs",
                "toolFailures", "leaseExpired");

        ReviewTask expiredTask = new ReviewTask();
        expiredTask.setTenantId(1L);
        expiredTask.setUserId(1L);
        expiredTask.setManuscriptId(1L);
        expiredTask.setWorkflow(Codes.CITATION_ONLY);
        expiredTask.setStatus(Codes.PENDING);
        expiredTask.setSourceVersion(1);
        expiredTask.setFencingToken(0L);
        expiredTask.setIdempotencyKey("meters-exp-" + System.nanoTime());
        expiredTask = reviewTaskRepo.saveAndFlush(expiredTask);
        String first = leaseService.newOwner();
        leaseService.acquire(expiredTask, first);
        TaskLease row = taskLeaseRepo.findById(expiredTask.getId()).orElseThrow();
        row.setExpireAt(Instant.now().minusSeconds(90));
        taskLeaseRepo.saveAndFlush(row);
        double expiredBefore = meterRegistry.find(HarnessMeters.LEASE_EXPIRED).counter().count();
        leaseService.acquire(expiredTask, leaseService.newOwner());
        assertThat(meterRegistry.find(HarnessMeters.LEASE_EXPIRED).counter().count()).isEqualTo(expiredBefore + 1);
    }
}
