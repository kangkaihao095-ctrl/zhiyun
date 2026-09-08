package com.zhiyun.llm;

public final class UsageMeter {
    private static final ThreadLocal<int[]> TOKENS = new ThreadLocal<>();
    private static final ThreadLocal<int[]> BYOK = new ThreadLocal<>();

    private UsageMeter() {
    }

    public static void open() {
        TOKENS.set(new int[]{0});
        BYOK.set(new int[]{0});
    }

    public static void add(int tokens) {
        if (tokens <= 0) {
            return;
        }
        int[] box = TOKENS.get();
        if (box != null) {
            box[0] += tokens;
        }
    }

    /** 本次审校有 Agent 走了用户自备 Key。 */
    public static void markByok() {
        int[] box = BYOK.get();
        if (box != null) {
            box[0] += 1;
        }
    }

    public static int byokAgents() {
        int[] box = BYOK.get();
        return box == null ? 0 : box[0];
    }

    public static int snapshot() {
        int[] box = TOKENS.get();
        return box == null ? 0 : box[0];
    }

    public static int close() {
        int n = snapshot();
        TOKENS.remove();
        BYOK.remove();
        return n;
    }
}
