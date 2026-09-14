package com.zhiyun.harness;

/**
 * Prompt / RAG 的 token 预算。上限语义仍是正文 / 私有 / 公共 / 上游四段，
 * 截断按估算 token，不是 {@code substring} 字符数。
 */
public final class TokenBudget {
    public static final int MANUSCRIPT = 4000;
    public static final int PRIVATE_RAG = 2000;
    public static final int PUBLIC_RAG = 1500;
    public static final int UPSTREAM = 3000;

    private TokenBudget() {
    }

    /**
     * 轻量估算：CJK 一字符约 1 token；拉丁按约 4 字符 1 token（按词切）。
     * 不引入 JNI tiktoken，口径稳定可单测。
     */
    public static int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                tokens += flushLatin(latin);
                latin = 0;
                tokens++;
            } else if (Character.isWhitespace(cp)) {
                tokens += flushLatin(latin);
                latin = 0;
            } else {
                latin++;
            }
        }
        return tokens + flushLatin(latin);
    }

    public static String cap(String text, int maxTokens) {
        if (text == null) {
            return "";
        }
        if (maxTokens <= 0) {
            return "";
        }
        if (count(text) <= maxTokens) {
            return text;
        }
        int tokens = 0;
        int latin = 0;
        int cut = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int next = i + Character.charCount(cp);
            int projected;
            int nextLatin;
            if (isCjk(cp)) {
                projected = tokens + flushLatin(latin) + 1;
                nextLatin = 0;
            } else if (Character.isWhitespace(cp)) {
                projected = tokens + flushLatin(latin);
                nextLatin = 0;
            } else {
                nextLatin = latin + 1;
                projected = tokens + flushLatin(nextLatin);
            }
            if (projected > maxTokens) {
                break;
            }
            if (isCjk(cp) || Character.isWhitespace(cp)) {
                tokens = projected;
                latin = 0;
            } else {
                latin = nextLatin;
            }
            i = next;
            cut = next;
        }
        return text.substring(0, cut);
    }

    private static int flushLatin(int latinChars) {
        if (latinChars <= 0) {
            return 0;
        }
        return (latinChars + 3) / 4;
    }

    private static boolean isCjk(int cp) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
        if (block == null) {
            return false;
        }
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }
}
