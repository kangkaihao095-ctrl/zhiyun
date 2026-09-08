package com.zhiyun;

import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.domain.TaskLease;
import com.zhiyun.harness.LeaseService;
import com.zhiyun.repo.ReviewTaskRepo;
import com.zhiyun.repo.TaskLeaseRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LeaseRenewTest {
    @Autowired
    LeaseService leaseService;
    @Autowired
    TaskLeaseRepo taskLeaseRepo;
    @Autowired
    ReviewTaskRepo reviewTaskRepo;
    @Autowired
    ZhiyunProperties properties;

    @Test
    void renewExtendsExpireAtWithoutChangingFencingToken() throws Exception {
        ReviewTask task = newTask("lease-renew-");
        String owner = leaseService.newOwner();
        long token = leaseService.acquire(task, owner);
        TaskLease before = taskLeaseRepo.findById(task.getId()).orElseThrow();
        Instant t0 = before.getExpireAt();
        Thread.sleep(30);
        leaseService.renew(task.getId(), owner);
        TaskLease after = taskLeaseRepo.findById(task.getId()).orElseThrow();
        assertThat(after.getExpireAt()).isAfter(t0);
        assertThat(after.getFencingToken()).isEqualTo(before.getFencingToken());
        assertThat(after.getFencingToken()).isEqualTo(token);
        assertThat(after.getOwner()).isEqualTo(owner);
    }

    @Test
    void heartbeatRenewsLeaseDuringLongTask() throws Exception {
        int old = properties.getHarness().getRenewSeconds();
        properties.getHarness().setRenewSeconds(1);
        ReviewTask task = newTask("lease-beat-");
        String owner = leaseService.newOwner();
        long token = leaseService.acquire(task, owner);
        Instant t0 = taskLeaseRepo.findById(task.getId()).orElseThrow().getExpireAt();
        try (AutoCloseable ignored = leaseService.startRenewal(task.getId(), owner)) {
            Thread.sleep(1800);
        } finally {
            properties.getHarness().setRenewSeconds(old);
        }
        TaskLease after = taskLeaseRepo.findById(task.getId()).orElseThrow();
        assertThat(after.getExpireAt()).isAfter(t0);
        assertThat(after.getFencingToken()).isEqualTo(token);
        leaseService.release(task.getId(), owner);
        assertThat(taskLeaseRepo.findById(task.getId())).isEmpty();
    }

    private ReviewTask newTask(String prefix) {
        ReviewTask task = new ReviewTask();
        task.setTenantId(1L);
        task.setUserId(1L);
        task.setManuscriptId(1L);
        task.setWorkflow(Codes.CITATION_ONLY);
        task.setStatus(Codes.RUNNING);
        task.setSourceVersion(1);
        task.setFencingToken(0L);
        task.setIdempotencyKey(prefix + System.nanoTime());
        return reviewTaskRepo.saveAndFlush(task);
    }
}
