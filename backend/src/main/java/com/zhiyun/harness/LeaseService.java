package com.zhiyun.harness;

import com.zhiyun.common.ApiException;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.domain.TaskLease;
import com.zhiyun.repo.ReviewTaskRepo;
import com.zhiyun.repo.TaskLeaseRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class LeaseService {
    private static final Logger log = LoggerFactory.getLogger(LeaseService.class);

    private final TaskLeaseRepo taskLeaseRepo;
    private final ReviewTaskRepo reviewTaskRepo;
    private final ZhiyunProperties properties;

    public LeaseService(TaskLeaseRepo taskLeaseRepo, ReviewTaskRepo reviewTaskRepo, ZhiyunProperties properties) {
        this.taskLeaseRepo = taskLeaseRepo;
        this.reviewTaskRepo = reviewTaskRepo;
        this.properties = properties;
    }

    public String newOwner() {
        return hostname() + ":" + ProcessHandle.current().pid() + ":" + UUID.randomUUID();
    }

    @Transactional
    public long acquire(ReviewTask task, String owner) {
        // 必须按库里的任务写 fencing，避免把已取消的 FAILED 用内存旧对象盖回去
        ReviewTask fresh = reviewTaskRepo.findById(task.getId()).orElse(task);
        if (!Codes.PENDING.equals(fresh.getStatus()) && !Codes.RUNNING.equals(fresh.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "task not runnable");
        }
        Instant now = Instant.now();
        Optional<TaskLease> existing = taskLeaseRepo.findById(task.getId());
        if (!properties.getFault().isSkipLease()
                && existing.isPresent() && existing.get().getExpireAt().isAfter(now)
                && !existing.get().getOwner().equals(owner)) {
            throw new ApiException(HttpStatus.CONFLICT, "task leased by another worker");
        }
        if (reviewTaskRepo.incrementFencing(fresh.getId()) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "task not runnable");
        }
        ReviewTask after = reviewTaskRepo.findById(fresh.getId()).orElseThrow();
        long token = after.getFencingToken() == null ? 0L : after.getFencingToken();
        if (!properties.getFault().isSkipLease()) {
            TaskLease lease = existing.orElseGet(TaskLease::new);
            lease.setTaskId(after.getId());
            lease.setTenantId(after.getTenantId());
            lease.setOwner(owner);
            lease.setExpireAt(now.plusSeconds(properties.getHarness().getLeaseTtlSeconds()));
            lease.setFencingToken(token);
            taskLeaseRepo.save(lease);
        }
        task.setFencingToken(token);
        return token;
    }

    @Transactional
    public void renew(long taskId, String owner) {
        if (properties.getFault().isSkipLease()) {
            return;
        }
        taskLeaseRepo.renew(taskId, owner,
                Instant.now().plusSeconds(properties.getHarness().getLeaseTtlSeconds()));
    }

    /**
     * 执行中按 TTL/3（默认 20s）续期；只改 expireAt，不碰 fencing token。
     */
    public AutoCloseable startRenewal(long taskId, String owner) {
        if (properties.getFault().isSkipLease()) {
            return () -> {
            };
        }
        int period = Math.max(1, properties.getHarness().getRenewSeconds());
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "lease-renew-" + taskId);
            thread.setDaemon(true);
            return thread;
        });
        exec.scheduleAtFixedRate(() -> {
            try {
                renew(taskId, owner);
            } catch (Exception e) {
                log.warn("lease renew failed for task {}: {}", taskId, e.getMessage());
            }
        }, period, period, TimeUnit.SECONDS);
        return () -> {
            exec.shutdownNow();
            try {
                exec.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    @Transactional
    public void release(long taskId, String owner) {
        taskLeaseRepo.findById(taskId).ifPresent(lease -> {
            if (owner.equals(lease.getOwner())) {
                taskLeaseRepo.delete(lease);
            }
        });
    }

    /** 取消任务时按 taskId 释放，不核对 owner；无 lease 行则忽略。 */
    @Transactional
    public void forceRelease(long taskId) {
        taskLeaseRepo.findById(taskId).ifPresent(taskLeaseRepo::delete);
    }

    public boolean isStale(long writeToken, long currentToken) {
        return writeToken < currentToken;
    }

    /** 写入方 token ≥ 库里当前值才可推进；以库为准。 */
    public boolean holds(long taskId, long writeToken) {
        return reviewTaskRepo.findById(taskId)
                .map(t -> writeToken >= (t.getFencingToken() == null ? 0L : t.getFencingToken()))
                .orElse(false);
    }

    public void assertWritable(long taskId, long writeToken) {
        if (!holds(taskId, writeToken)) {
            throw new ApiException(HttpStatus.CONFLICT, "stale fencing token rejected");
        }
    }

    private String hostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "worker";
        }
    }
}
