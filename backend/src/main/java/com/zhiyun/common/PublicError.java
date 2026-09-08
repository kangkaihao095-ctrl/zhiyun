package com.zhiyun.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.util.Locale;

/**
 * 审校失败面向租户的文案。不把供应商欠费 JSON、help URL、request_id 当产品文案。
 */
public final class PublicError {
    public static final String CANCELLED = "已取消";
    public static final String ARREARAGE = "平台模型额度不足，请稍后再试。";
    public static final String INVALID_KEY = "模型密钥无效或已过期。";
    public static final String TIMEOUT = "审校超时，请从检查点继续。";
    public static final String STRUCTURED = "模型输出格式校验失败，请重试。";
    public static final String NETWORK = "网络异常，请稍后重试。";
    public static final String UNKNOWN = "审校没能完成，请稍后重试。";

    private PublicError() {
    }

    public static String message(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return switch (code(raw)) {
            case "cancelled" -> CANCELLED;
            case "arrearage" -> ARREARAGE;
            case "invalid_key" -> INVALID_KEY;
            case "timeout" -> TIMEOUT;
            case "structured_output" -> STRUCTURED;
            case "network" -> NETWORK;
            default -> UNKNOWN;
        };
    }

    public static String code(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.trim();
        if (CANCELLED.equals(text) || "cancelled".equalsIgnoreCase(text) || "canceled".equalsIgnoreCase(text)) {
            return "cancelled";
        }
        if (ARREARAGE.equals(text)) {
            return "arrearage";
        }
        if (INVALID_KEY.equals(text)) {
            return "invalid_key";
        }
        if (TIMEOUT.equals(text)) {
            return "timeout";
        }
        if (STRUCTURED.equals(text)) {
            return "structured_output";
        }
        if (NETWORK.equals(text)) {
            return "network";
        }
        if (UNKNOWN.equals(text)) {
            return "unknown";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("arrearage") || lower.contains("overdue-payment") || lower.contains("overdue_payment")
                || lower.contains("access denied") || lower.contains("good standing")
                || lower.contains("insufficient_quota") || lower.contains("quota exceeded")
                || text.contains("欠费") || text.contains("余额不足") || text.contains("额度不足")) {
            return "arrearage";
        }
        if (lower.contains("invalid api key") || lower.contains("incorrect api key") || lower.contains("invalid_api_key")
                || lower.contains("authentication") || lower.contains("unauthorized")
                || lower.contains("401") || text.contains("密钥无效") || lower.contains("api key")) {
            return "invalid_key";
        }
        if (lower.contains("timeout") || lower.contains("timed out") || lower.contains("time-out")
                || text.contains("超时")) {
            return "timeout";
        }
        if (lower.contains("structured output") || lower.contains("schema validation")
                || lower.contains("illegal output")) {
            return "structured_output";
        }
        if (lower.contains("econnrefused") || lower.contains("enotfound") || lower.contains("socket")
                || lower.contains("unknownhost") || lower.contains("connection refused")
                || lower.contains("network") || lower.contains("bad gateway")
                || lower.contains("502") || lower.contains("503") || lower.contains("504")) {
            return "network";
        }
        return "unknown";
    }

    public static class MessageSerializer extends JsonSerializer<String> {
        @Override
        public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(message(value));
        }
    }
}
