package com.zhiyun.llm;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FirstTokenClockTest {
    @Test
    void marksFirstTokenOnceAndIgnoresEmptyChunks() {
        Instant start = Instant.parse("2026-09-16T03:00:00Z");
        FirstTokenClock clock = new FirstTokenClock(1_000_000_000L, start);

        assertThat(clock.markIfFirst(false, FirstTokenClock.SOURCE_STREAM, 1_080_000_000L, start.plusMillis(80)))
                .isFalse();
        assertThat(clock.marked()).isFalse();
        assertThat(clock.firstTokenAt()).isNull();
        assertThat(clock.firstTokenMs()).isNull();

        Instant first = start.plusMillis(120);
        assertThat(clock.markIfFirst(true, FirstTokenClock.SOURCE_STREAM, 1_120_000_000L, first)).isTrue();
        assertThat(clock.firstTokenAt()).isEqualTo(first);
        assertThat(clock.firstTokenMs()).isEqualTo(120L);
        assertThat(clock.source()).isEqualTo(FirstTokenClock.SOURCE_STREAM);

        Instant later = start.plusMillis(400);
        assertThat(clock.markIfFirst(true, FirstTokenClock.SOURCE_STREAM, 1_400_000_000L, later)).isFalse();
        assertThat(clock.firstTokenAt()).isEqualTo(first);
        assertThat(clock.firstTokenMs()).isEqualTo(120L);
    }

    @Test
    void completeSourceIsDistinctFromStream() {
        FirstTokenClock clock = new FirstTokenClock(0L, Instant.parse("2026-09-16T03:00:00Z"));
        Instant at = Instant.parse("2026-09-16T03:00:00.250Z");
        assertThat(clock.markIfFirst(true, FirstTokenClock.SOURCE_COMPLETE, 250_000_000L, at)).isTrue();
        assertThat(clock.source()).isEqualTo(FirstTokenClock.SOURCE_COMPLETE);
        assertThat(clock.firstTokenMs()).isEqualTo(250L);
    }
}
