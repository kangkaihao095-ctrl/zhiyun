package com.zhiyun.harness;

import java.time.Instant;

/**
 * 单次 Agent 执行内的 LLM 首 token 账本。写入 agent_span，不进 C 端用量页。
 * 只记第一次有效 token；失败或无 token 保持空。
 */
public final class FirstTokenRecorder {
    private static final ThreadLocal<Holder> TL = new ThreadLocal<>();

    private FirstTokenRecorder() {
    }

    public static void open() {
        TL.set(new Holder());
    }

    public static void record(Instant firstTokenAt, long firstTokenMs) {
        Holder holder = TL.get();
        if (holder == null || firstTokenAt == null || firstTokenMs < 0) {
            return;
        }
        if (holder.sample != null) {
            return;
        }
        holder.sample = new Sample(firstTokenAt, firstTokenMs);
    }

    public static Sample snapshot() {
        Holder holder = TL.get();
        if (holder == null) {
            return null;
        }
        return holder.sample;
    }

    public static void close() {
        TL.remove();
    }

    public record Sample(Instant firstTokenAt, long firstTokenMs) {
    }

    private static final class Holder {
        private Sample sample;
    }
}
