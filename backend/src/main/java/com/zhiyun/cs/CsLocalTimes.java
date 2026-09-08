package com.zhiyun.cs;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 客服对外时间：Asia/Shanghai 可读，不丢 ISO-Z。 */
public final class CsLocalTimes {
    public static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private CsLocalTimes() {
    }

    public static String format(Instant instant) {
        if (instant == null) {
            return "";
        }
        return instant.atZone(SHANGHAI).format(LOCAL);
    }
}
