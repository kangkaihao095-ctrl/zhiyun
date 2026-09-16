package com.zhiyun.harness;

import com.zhiyun.domain.Codes;
import com.zhiyun.domain.ReviewTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityRangeTest {
    @Test
    void normalizeRangeDropsThirtyDaysAndAddsHalfAndFullYear() {
        assertThat(ObservabilityService.normalizeRange("15m")).isEqualTo("15m");
        assertThat(ObservabilityService.normalizeRange("1h")).isEqualTo("1h");
        assertThat(ObservabilityService.normalizeRange("24h")).isEqualTo("24h");
        assertThat(ObservabilityService.normalizeRange("7d")).isEqualTo("7d");
        assertThat(ObservabilityService.normalizeRange("15d")).isEqualTo("15d");
        assertThat(ObservabilityService.normalizeRange("6m")).isEqualTo("6m");
        assertThat(ObservabilityService.normalizeRange("12m")).isEqualTo("12m");
        assertThat(ObservabilityService.normalizeRange("all")).isEqualTo("all");
        assertThat(ObservabilityService.normalizeRange("30d")).isEqualTo("7d");
        assertThat(ObservabilityService.normalizeRange("1m")).isEqualTo("7d");
    }

    @Test
    void longWindowsAggregateByCalendarMonth() {
        Instant now = Instant.parse("2026-09-11T12:00:00Z");
        ReviewTask marchOk = task("2026-03-02T00:00:00Z", Codes.DONE);
        ReviewTask marchFail = task("2026-03-20T00:00:00Z", Codes.FAILED);
        ReviewTask septOk = task("2026-09-01T00:00:00Z", Codes.DONE);
        List<ReviewTask> window = List.of(marchOk, marchFail, septOk);

        List<Map<String, Object>> half = ObservabilityService.trendOf(window, "6m", now);
        assertThat(half).hasSize(6);
        assertThat(half.get(0).get("bucket")).isEqualTo("2026-04");
        assertThat(half.get(5).get("bucket")).isEqualTo("2026-09");
        assertThat(half.get(5).get("started")).isEqualTo(1);
        assertThat(half.stream().allMatch(row -> String.valueOf(row.get("bucket")).matches("\\d{4}-\\d{2}"))).isTrue();

        List<Map<String, Object>> year = ObservabilityService.trendOf(window, "12m", now);
        assertThat(year).hasSize(12);
        assertThat(year.get(0).get("bucket")).isEqualTo("2025-10");
        Map<String, Object> mar = year.stream()
                .filter(row -> "2026-03".equals(row.get("bucket")))
                .findFirst()
                .orElseThrow();
        assertThat(mar.get("started")).isEqualTo(2);
        assertThat(mar.get("succeeded")).isEqualTo(1);
        assertThat(mar.get("failed")).isEqualTo(1);

        List<Map<String, Object>> all = ObservabilityService.trendOf(window, "all", now);
        assertThat(all.get(0).get("bucket")).isEqualTo("2026-03");
        assertThat(all.get(all.size() - 1).get("bucket")).isEqualTo("2026-09");
        assertThat(all).hasSize(7);
        assertThat(all.stream().allMatch(row -> String.valueOf(row.get("bucket")).matches("\\d{4}-\\d{2}"))).isTrue();
    }

    @Test
    void halfMonthStaysDaily() {
        Instant now = Instant.parse("2026-09-11T12:00:00Z");
        List<Map<String, Object>> rows = ObservabilityService.trendOf(List.of(), "15d", now);
        assertThat(rows).hasSize(15);
        assertThat(String.valueOf(rows.get(0).get("bucket"))).matches("\\d{2}-\\d{2}");
    }

    @Test
    void shortWindowsKeepMinuteAndHourBuckets() {
        Instant now = Instant.parse("2026-09-11T12:00:00Z");
        List<Map<String, Object>> fifteen = ObservabilityService.trendOf(List.of(), "15m", now);
        assertThat(fifteen).hasSize(15);
        assertThat(String.valueOf(fifteen.get(0).get("bucket"))).matches("\\d{2}:\\d{2}");
        List<Map<String, Object>> hour = ObservabilityService.trendOf(List.of(), "1h", now);
        assertThat(hour).hasSize(12);
        assertThat(String.valueOf(hour.get(0).get("bucket"))).matches("\\d{2}:\\d{2}");
    }

    @Test
    void tokenDeltaUsesAdjacentBucketsAndStaysNullWithoutBaseline() {
        assertThat(ObservabilityService.tokenDeltaPct(List.of())).isNull();
        assertThat(ObservabilityService.tokenDeltaPct(List.of(Map.of("tokens", 10)))).isNull();
        assertThat(ObservabilityService.tokenDeltaPct(List.of(
                Map.of("tokens", 0),
                Map.of("tokens", 40)
        ))).isNull();
        assertThat(ObservabilityService.tokenDeltaPct(List.of(
                Map.of("tokens", 100),
                Map.of("tokens", 110)
        ))).isEqualTo(10);
        assertThat(ObservabilityService.tokenDeltaPct(List.of(
                Map.of("tokens", 200),
                Map.of("tokens", 100)
        ))).isEqualTo(-50);
    }

    @Test
    void percentileUsesWindowSamplesAndDashWhenEmpty() {
        assertThat(ObservabilityService.percentile(List.of(), 0.95)).isNull();
        assertThat(ObservabilityService.percentile(List.of(400L, 900L, 1200L), 0.50)).isEqualTo(900L);
        assertThat(ObservabilityService.percentile(List.of(400L, 900L, 1200L), 0.95)).isEqualTo(1200L);
        assertThat(ObservabilityService.alerts(1, 0, 1, 0, 1, 0))
                .extracting(row -> row.get("id"))
                .contains("fencing", "empty-rag");
        assertThat(ObservabilityService.alerts(1, 0, 1, 0, 1, 0).get(0).get("caption").toString())
                .contains("不是 pager");
    }

    private static ReviewTask task(String iso, String status) {
        ReviewTask task = new ReviewTask();
        task.setCreatedAt(Instant.parse(iso));
        task.setStatus(status);
        return task;
    }
}
