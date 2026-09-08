package com.zhiyun.cs;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class CsDateRangeTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-08T04:00:00Z"), SHANGHAI);

    @Test
    void todayAndYesterdayBecomeShanghaiCalendarDays() {
        CsDateRange range = CsDateRange.parseQuestion("查一下今天和昨天的订单", clock);
        assertThat(range).isNotNull();
        assertThat(range.fromDay()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(range.toDay()).isEqualTo(LocalDate.of(2026, 9, 8));
        assertThat(range.fromInclusive()).isEqualTo(Instant.parse("2026-09-06T16:00:00Z"));
        assertThat(range.toExclusive()).isEqualTo(Instant.parse("2026-09-08T16:00:00Z"));
    }

    @Test
    void thisWeekStartsMonday() {
        CsDateRange range = CsDateRange.parseQuestion("本周流水", clock);
        assertThat(range.fromDay()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(range.toDay()).isEqualTo(LocalDate.of(2026, 9, 8));
    }

    @Test
    void explicitIsoDaysWin() {
        CsDateRange range = CsDateRange.ofDays("2026-09-07", "2026-09-08", clock);
        assertThat(range.dayCount()).isEqualTo(2);
        assertThat(CsOrderQuery.parseStatus("PENDING; drop table")).isNull();
        assertThat(CsOrderQuery.parseStatus("待支付")).isEqualTo("PENDING");
    }
}
