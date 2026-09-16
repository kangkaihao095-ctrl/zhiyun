package com.zhiyun.llm;

import java.time.Instant;

/**
 * 一次 LLM chat 请求的首 token 探针。
 * 流式：第一个非空 content/delta 打点；非流式：完整响应体到达时打点（不是流式 TTFT）。
 * 失败或没有 token 不打点；多次 chunk 只保留第一次。
 */
final class FirstTokenClock {
    static final String SOURCE_STREAM = "stream";
    static final String SOURCE_COMPLETE = "complete";

    private final long startedNanos;
    private final Instant startedAt;
    private Instant firstTokenAt;
    private Long firstTokenMs;
    private String source;

    FirstTokenClock() {
        this.startedNanos = System.nanoTime();
        this.startedAt = Instant.now();
    }

    FirstTokenClock(long startedNanos, Instant startedAt) {
        this.startedNanos = startedNanos;
        this.startedAt = startedAt;
    }

    /**
     * @return true 表示本次是第一次有效 token
     */
    boolean markIfFirst(boolean hasToken, String source, long nowNanos, Instant now) {
        if (!hasToken || firstTokenAt != null || now == null) {
            return false;
        }
        this.firstTokenAt = now;
        this.firstTokenMs = Math.max(0L, (nowNanos - startedNanos) / 1_000_000L);
        this.source = source;
        return true;
    }

    boolean markIfFirst(boolean hasToken, String source) {
        return markIfFirst(hasToken, source, System.nanoTime(), Instant.now());
    }

    Instant startedAt() {
        return startedAt;
    }

    Instant firstTokenAt() {
        return firstTokenAt;
    }

    Long firstTokenMs() {
        return firstTokenMs;
    }

    String source() {
        return source;
    }

    boolean marked() {
        return firstTokenAt != null;
    }
}
