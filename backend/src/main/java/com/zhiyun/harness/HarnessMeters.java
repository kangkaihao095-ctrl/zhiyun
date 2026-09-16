package com.zhiyun.harness;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 进程内自定义 meter：LLM / RAG Timer、lease、Tool 失败。
 * 挂在 Actuator Prometheus；不是 SLA，也不替代 /ops 业务表。
 */
@Component
public class HarnessMeters {
    public static final String LLM_CALLS = "zhiyun.llm.calls";
    public static final String LLM_DURATION = "zhiyun.llm.duration";
    public static final String LLM_TTFT = "zhiyun.llm.ttft";
    public static final String LLM_TOKENS = "zhiyun.llm.tokens";
    public static final String LLM_PROMPT_TOKENS = "zhiyun.llm.tokens.prompt";
    public static final String LLM_COMPLETION_TOKENS = "zhiyun.llm.tokens.completion";
    public static final String LLM_STRUCTURED_FAIL = "zhiyun.llm.structured_fail";
    public static final String LEASE_HELD = "zhiyun.lease.held";
    public static final String LEASE_EXPIRED = "zhiyun.lease.expired";
    public static final String FENCING_REJECTED = "zhiyun.lease.fencing_rejected";
    public static final String RAG_KNN = "zhiyun.rag.knn";
    public static final String RAG_KNN_DURATION = "zhiyun.rag.knn.duration";
    public static final String RAG_DURATION = "zhiyun.rag.duration";
    public static final String RAG_RETRIEVALS = "zhiyun.rag.retrievals";
    public static final String RAG_RETRIEVALS_PUBLIC = "zhiyun.rag.retrievals.public";
    public static final String RAG_RETRIEVALS_PRIVATE = "zhiyun.rag.retrievals.private";
    public static final String TOOL_CALLS = "zhiyun.tool.calls";
    public static final String TOOL_FAILURES = "zhiyun.tool.failures";
    public static final String CITATION_LOOKUP = "zhiyun.citation.lookup_doi";
    public static final String CITATION_INVENTED = "zhiyun.citation.invented_doi_dropped";

    private final Counter llmCalls;
    private final Timer llmDuration;
    private final Timer llmTtft;
    private final AtomicReference<Instant> lastFirstTokenAt = new AtomicReference<>();
    private final AtomicLong lastFirstTokenMs = new AtomicLong(-1);
    private final Counter llmTokens;
    private final Counter llmPromptTokens;
    private final Counter llmCompletionTokens;
    private final Counter structuredFail;
    private final Counter knn;
    private final Timer knnDuration;
    private final Timer ragDuration;
    private final Counter retrievals;
    private final Counter retrievalsPublic;
    private final Counter retrievalsPrivate;
    private final Counter toolCalls;
    private final Counter toolFailures;
    private final Counter citationLookup;
    private final Counter inventedDoi;
    private final Counter fencingRejected;
    private final Counter leaseExpired;
    private final AtomicInteger leaseHeld = new AtomicInteger();

    public HarnessMeters(MeterRegistry registry) {
        this.llmCalls = Counter.builder(LLM_CALLS)
                .description("LLM chat/vision/embed/rerank invocations")
                .register(registry);
        this.llmDuration = Timer.builder(LLM_DURATION)
                .description("LLM invocation duration (count + total time; histogram for later P99)")
                .publishPercentileHistogram()
                .register(registry);
        this.llmTtft = Timer.builder(LLM_TTFT)
                .description("LLM time-to-first-token; stream = first delta, complete = full body. Not SLA.")
                .publishPercentileHistogram()
                .register(registry);
        this.llmTokens = Counter.builder(LLM_TOKENS)
                .description("LLM tokens recorded by the platform usage meter")
                .register(registry);
        this.llmPromptTokens = Counter.builder(LLM_PROMPT_TOKENS)
                .description("LLM prompt tokens when the provider reports them")
                .register(registry);
        this.llmCompletionTokens = Counter.builder(LLM_COMPLETION_TOKENS)
                .description("LLM completion tokens when the provider reports them")
                .register(registry);
        this.structuredFail = Counter.builder(LLM_STRUCTURED_FAIL)
                .description("Agent structured output failures after retry")
                .register(registry);
        Gauge.builder(LEASE_HELD, leaseHeld, AtomicInteger::get)
                .description("Review leases currently held by this process")
                .register(registry);
        this.leaseExpired = Counter.builder(LEASE_EXPIRED)
                .description("Acquire covered an expired lease row")
                .register(registry);
        this.fencingRejected = Counter.builder(FENCING_REJECTED)
                .description("Stale fencing token write rejections")
                .register(registry);
        this.knn = Counter.builder(RAG_KNN)
                .description("Elasticsearch kNN queries")
                .register(registry);
        this.knnDuration = Timer.builder(RAG_KNN_DURATION)
                .description("Elasticsearch kNN duration")
                .publishPercentileHistogram()
                .register(registry);
        this.ragDuration = Timer.builder(RAG_DURATION)
                .description("RAG retrievePrivate/retrievePublic duration")
                .publishPercentileHistogram()
                .register(registry);
        this.retrievals = Counter.builder(RAG_RETRIEVALS)
                .description("RAG retrievePrivate/retrievePublic calls")
                .register(registry);
        this.retrievalsPublic = Counter.builder(RAG_RETRIEVALS_PUBLIC)
                .description("RAG retrievePublic calls")
                .register(registry);
        this.retrievalsPrivate = Counter.builder(RAG_RETRIEVALS_PRIVATE)
                .description("RAG retrievePrivate calls")
                .register(registry);
        this.toolCalls = Counter.builder(TOOL_CALLS)
                .description("Allowlisted tool invocations")
                .register(registry);
        this.toolFailures = Counter.builder(TOOL_FAILURES)
                .description("Allowlisted tool invocations that failed")
                .register(registry);
        this.citationLookup = Counter.builder(CITATION_LOOKUP)
                .description("AcademicSearchTool.lookupDoi calls")
                .register(registry);
        this.inventedDoi = Counter.builder(CITATION_INVENTED)
                .description("Model-invented DOIs dropped by Java merge")
                .register(registry);
    }

    public void recordLlmCall() {
        llmCalls.increment();
    }

    public void recordLlmCall(long durationNanos) {
        llmCalls.increment();
        if (durationNanos > 0) {
            llmDuration.record(durationNanos, TimeUnit.NANOSECONDS);
        }
    }

    public <T> T timeLlm(Supplier<T> work) {
        long t0 = System.nanoTime();
        try {
            return work.get();
        } finally {
            recordLlmCall(System.nanoTime() - t0);
        }
    }

    /**
     * 记录一次有效首 token。失败或无 token 不要调用。
     * 流式：第一个 content/delta；非流式：完整响应体到达。
     */
    public void recordFirstToken(Instant firstTokenAt, long firstTokenMs) {
        if (firstTokenAt == null || firstTokenMs < 0) {
            return;
        }
        llmTtft.record(firstTokenMs, TimeUnit.MILLISECONDS);
        lastFirstTokenAt.set(firstTokenAt);
        lastFirstTokenMs.set(firstTokenMs);
        FirstTokenRecorder.record(firstTokenAt, firstTokenMs);
    }

    public void recordLlmTokens(int tokens) {
        if (tokens > 0) {
            llmTokens.increment(tokens);
        }
    }

    public void recordLlmTokens(int prompt, int completion) {
        if (prompt > 0) {
            llmPromptTokens.increment(prompt);
        }
        if (completion > 0) {
            llmCompletionTokens.increment(completion);
        }
        recordLlmTokens(prompt + completion);
    }

    public void recordStructuredFail() {
        structuredFail.increment();
    }

    public void leaseAcquired() {
        leaseHeld.incrementAndGet();
    }

    public void leaseReleased() {
        leaseHeld.updateAndGet(n -> Math.max(0, n - 1));
    }

    public void recordLeaseExpired() {
        leaseExpired.increment();
    }

    public void recordFencingRejected() {
        fencingRejected.increment();
    }

    public void recordKnn() {
        recordKnn(0L);
    }

    public void recordKnn(long durationNanos) {
        knn.increment();
        if (durationNanos > 0) {
            knnDuration.record(durationNanos, TimeUnit.NANOSECONDS);
        }
    }

    public void recordRetrieval() {
        retrievals.increment();
    }

    public void recordRetrievalPublic() {
        recordRetrievalPublic(0L);
    }

    public void recordRetrievalPublic(long durationNanos) {
        retrievals.increment();
        retrievalsPublic.increment();
        if (durationNanos > 0) {
            ragDuration.record(durationNanos, TimeUnit.NANOSECONDS);
        }
    }

    public void recordRetrievalPrivate() {
        recordRetrievalPrivate(0L);
    }

    public void recordRetrievalPrivate(long durationNanos) {
        retrievals.increment();
        retrievalsPrivate.increment();
        if (durationNanos > 0) {
            ragDuration.record(durationNanos, TimeUnit.NANOSECONDS);
        }
    }

    public void recordToolCall(boolean ok) {
        toolCalls.increment();
        if (!ok) {
            toolFailures.increment();
        }
    }

    public void recordCitationLookup(boolean ok) {
        citationLookup.increment();
        recordToolCall(ok);
    }

    public void recordInventedDoi(int n) {
        if (n > 0) {
            inventedDoi.increment(n);
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caption", "进程内 Micrometer，非 SLA。首 token 为 chat TTFT，不是 SLA。");
        out.put("llmCalls", (long) llmCalls.count());
        out.put("llmDurationMs", (long) llmDuration.totalTime(TimeUnit.MILLISECONDS));
        out.put("ttftCount", llmTtft.count());
        out.put("ttftMs", (long) llmTtft.totalTime(TimeUnit.MILLISECONDS));
        Instant lastTtftAt = lastFirstTokenAt.get();
        out.put("lastFirstTokenAt", lastTtftAt == null ? null : lastTtftAt.toString());
        long lastTtftMs = lastFirstTokenMs.get();
        out.put("lastFirstTokenMs", lastTtftMs < 0 ? null : lastTtftMs);
        out.put("llmTokens", (long) llmTokens.count());
        out.put("promptTokens", (long) llmPromptTokens.count());
        out.put("completionTokens", (long) llmCompletionTokens.count());
        out.put("structuredFail", (long) structuredFail.count());
        out.put("leaseHeld", leaseHeld.get());
        out.put("leaseExpired", (long) leaseExpired.count());
        out.put("fencingRejected", (long) fencingRejected.count());
        out.put("knn", (long) knn.count());
        out.put("knnDurationMs", (long) knnDuration.totalTime(TimeUnit.MILLISECONDS));
        out.put("retrievals", (long) retrievals.count());
        out.put("retrievalsPublic", (long) retrievalsPublic.count());
        out.put("retrievalsPrivate", (long) retrievalsPrivate.count());
        out.put("ragDurationMs", (long) ragDuration.totalTime(TimeUnit.MILLISECONDS));
        out.put("toolCalls", (long) toolCalls.count());
        out.put("toolFailures", (long) toolFailures.count());
        out.put("citationLookup", (long) citationLookup.count());
        out.put("inventedDoiDropped", (long) inventedDoi.count());
        return out;
    }
}
