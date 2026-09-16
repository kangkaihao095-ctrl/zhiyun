package com.zhiyun.harness;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FirstTokenRecorderTest {
    @AfterEach
    void tearDown() {
        FirstTokenRecorder.close();
    }

    @Test
    void keepsOnlyTheFirstSampleAndStaysEmptyWhenClosed() {
        Instant first = Instant.parse("2026-09-16T03:00:00.080Z");
        Instant later = Instant.parse("2026-09-16T03:00:00.400Z");

        FirstTokenRecorder.record(first, 80);
        assertThat(FirstTokenRecorder.snapshot()).isNull();

        FirstTokenRecorder.open();
        FirstTokenRecorder.record(null, 12);
        FirstTokenRecorder.record(first, -1);
        assertThat(FirstTokenRecorder.snapshot()).isNull();

        FirstTokenRecorder.record(first, 80);
        FirstTokenRecorder.record(later, 400);
        FirstTokenRecorder.Sample sample = FirstTokenRecorder.snapshot();
        assertThat(sample).isNotNull();
        assertThat(sample.firstTokenAt()).isEqualTo(first);
        assertThat(sample.firstTokenMs()).isEqualTo(80L);
    }
}
