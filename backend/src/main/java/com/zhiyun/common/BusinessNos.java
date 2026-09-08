package com.zhiyun.common;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 对外业务号：前缀 + 上海时间 + 随机，不是 AUTO_INCREMENT。
 * 订单 {@code ZY}、任务 {@code ZYT}、流水 {@code ZYL}。
 */
public final class BusinessNos {
    public static final String ORDER = "ZY";
    public static final String TASK = "ZYT";
    public static final String LEDGER = "ZYL";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZONE);
    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private BusinessNos() {
    }

    public static String next(String prefix) {
        String p = prefix == null || prefix.isBlank() ? ORDER : prefix;
        byte[] buf = new byte[5];
        RNG.nextBytes(buf);
        StringBuilder hex = new StringBuilder(10);
        for (byte b : buf) {
            hex.append(HEX[(b >> 4) & 0xf]);
            hex.append(HEX[b & 0xf]);
        }
        return p + TS.format(Instant.now()) + hex;
    }

    public static String nextOrder() {
        return next(ORDER);
    }

    public static String nextTask() {
        return next(TASK);
    }

    public static String nextLedger() {
        return next(LEDGER);
    }

    /** 旧自增行的稳定映射，例如 {@code ZYT0000000000000007}。 */
    public static String migrated(String prefix, long id) {
        return prefix + String.format("%016d", id);
    }

    public static boolean looksNumericPk(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String s = raw.trim();
        if (s.length() > 18) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean looksTaskNo(String raw) {
        return looksPrefixed(raw, TASK);
    }

    public static boolean looksLedgerNo(String raw) {
        return looksPrefixed(raw, LEDGER);
    }

    public static boolean looksBusinessNo(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String s = raw.trim();
        return looksPrefixed(s, TASK) || looksPrefixed(s, LEDGER) || looksPrefixed(s, ORDER);
    }

    private static boolean looksPrefixed(String raw, String prefix) {
        if (raw == null || prefix == null) {
            return false;
        }
        String s = raw.trim();
        return s.length() > prefix.length() && s.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}
