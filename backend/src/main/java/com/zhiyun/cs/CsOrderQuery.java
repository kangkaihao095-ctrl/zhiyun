package com.zhiyun.cs;

import com.zhiyun.domain.Codes;

import java.time.Clock;
import java.util.Locale;

/**
 * 订单/流水结构化过滤。Java 用参数绑定拼 WHERE，模型不得写 SQL。
 */
public record CsOrderQuery(String orderId, String from, String to, String status, Integer limit) {
    public static final int DEFAULT_RECENT = 5;
    public static final int DEFAULT_RANGED = 50;
    public static final int MAX_LIMIT = 50;

    public static CsOrderQuery none() {
        return new CsOrderQuery(null, null, null, null, null);
    }

    public static CsOrderQuery byOrderId(String orderId) {
        return new CsOrderQuery(orderId, null, null, null, null);
    }

    public static CsOrderQuery fromQuestion(String question, Clock clock) {
        CsDateRange range = CsDateRange.parseQuestion(question, clock);
        return new CsOrderQuery(
                null,
                range == null ? null : range.fromDay().toString(),
                range == null ? null : range.toDay().toString(),
                parseStatus(question),
                null);
    }

    public CsDateRange range(Clock clock) {
        return CsDateRange.ofDays(from, to, clock);
    }

    public boolean hasFilter(Clock clock) {
        return range(clock) != null || whitelistStatus() != null || limit != null;
    }

    public String whitelistStatus() {
        return parseStatus(status);
    }

    public int resolvedLimit(boolean ranged) {
        int fallback = ranged ? DEFAULT_RANGED : DEFAULT_RECENT;
        if (limit == null) {
            return fallback;
        }
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    static String parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        String upper = s.toUpperCase(Locale.ROOT);
        if (Codes.ORDER_PAID.equals(upper) || s.contains("已支付") || s.contains("已付款")) {
            return Codes.ORDER_PAID;
        }
        if (Codes.ORDER_PENDING.equals(upper) || s.contains("待支付") || s.contains("未支付") || s.contains("待付款")) {
            return Codes.ORDER_PENDING;
        }
        return null;
    }
}
