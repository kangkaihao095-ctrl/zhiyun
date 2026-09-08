package com.zhiyun.cs;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asia/Shanghai 日历日闭区间。只解析日期，不把用户原文拼进 SQL。
 */
public record CsDateRange(LocalDate fromDay, LocalDate toDay) {
    private static final Pattern ISO = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

    public CsDateRange {
        if (fromDay == null || toDay == null) {
            throw new IllegalArgumentException("from/to required");
        }
        if (toDay.isBefore(fromDay)) {
            LocalDate swap = fromDay;
            fromDay = toDay;
            toDay = swap;
        }
    }

    public Instant fromInclusive() {
        return fromDay.atStartOfDay(CsLocalTimes.SHANGHAI).toInstant();
    }

    public Instant toExclusive() {
        return toDay.plusDays(1).atStartOfDay(CsLocalTimes.SHANGHAI).toInstant();
    }

    public long dayCount() {
        return fromDay.until(toDay).getDays() + 1L;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", fromDay.toString());
        out.put("to", toDay.toString());
        out.put("zone", CsLocalTimes.SHANGHAI.getId());
        return out;
    }

    public static CsDateRange ofDays(String from, String to, Clock clock) {
        LocalDate today = today(clock);
        LocalDate start = parseDay(from, today);
        LocalDate end = parseDay(to, today);
        if (start == null && end == null) {
            return null;
        }
        if (start == null) {
            start = end;
        }
        if (end == null) {
            end = start;
        }
        return new CsDateRange(start, end);
    }

    public static CsDateRange parseQuestion(String raw, Clock clock) {
        LocalDate today = today(clock);
        String q = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        List<LocalDate> explicit = new ArrayList<>();
        Matcher m = ISO.matcher(raw == null ? "" : raw);
        while (m.find()) {
            LocalDate day = parseIso(m.group(1));
            if (day != null) {
                explicit.add(day);
            }
        }
        boolean hasToday = q.contains("今天") || q.contains("今日") || q.contains("today");
        boolean hasYesterday = q.contains("昨天") || q.contains("昨日") || q.contains("yesterday");
        boolean hasWeek = q.contains("本周") || q.contains("这周") || q.contains("this week");
        if (!explicit.isEmpty()) {
            LocalDate start = explicit.get(0);
            LocalDate end = explicit.get(0);
            for (LocalDate day : explicit) {
                if (day.isBefore(start)) {
                    start = day;
                }
                if (day.isAfter(end)) {
                    end = day;
                }
            }
            if (hasYesterday) {
                start = min(start, today.minusDays(1));
            }
            if (hasToday) {
                end = max(end, today);
            }
            return new CsDateRange(start, end);
        }
        if (hasWeek) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return new CsDateRange(monday, today);
        }
        if (hasToday && hasYesterday) {
            return new CsDateRange(today.minusDays(1), today);
        }
        if (hasToday) {
            return new CsDateRange(today, today);
        }
        if (hasYesterday) {
            LocalDate y = today.minusDays(1);
            return new CsDateRange(y, y);
        }
        return null;
    }

    static LocalDate parseDay(String raw, LocalDate today) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if ("today".equals(s) || "今天".equals(s) || "今日".equals(s)) {
            return today;
        }
        if ("yesterday".equals(s) || "昨天".equals(s) || "昨日".equals(s)) {
            return today.minusDays(1);
        }
        if (s.length() >= 10) {
            return parseIso(s.substring(0, 10));
        }
        return parseIso(s);
    }

    private static LocalDate parseIso(String raw) {
        try {
            return LocalDate.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate today(Clock clock) {
        Clock c = clock == null ? Clock.system(CsLocalTimes.SHANGHAI) : clock;
        return LocalDate.now(c.withZone(CsLocalTimes.SHANGHAI));
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }
}
