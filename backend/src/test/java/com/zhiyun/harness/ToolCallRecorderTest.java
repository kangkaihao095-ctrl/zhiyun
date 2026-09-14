package com.zhiyun.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallRecorderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        ToolCallRecorder.close();
    }

    @Test
    void extraKeepsZeroHitsForEmptyRecall() {
        ToolCallRecorder.open();
        ToolCallRecorder.record("ManuscriptRetrieval", true, 12);
        ToolCallRecorder.extra("ManuscriptRetrieval", "hits", 0);
        ToolCallRecorder.extra("ManuscriptRetrieval", "emptyHits", 1);
        JsonNode snap = ToolCallRecorder.snapshot(mapper);
        assertThat(snap).hasSize(1);
        assertThat(snap.get(0).get("tool").asText()).isEqualTo("ManuscriptRetrieval");
        assertThat(snap.get(0).get("hits").asInt(-1)).isEqualTo(0);
        assertThat(snap.get(0).get("emptyHits").asInt()).isEqualTo(1);
        assertThat(snap.get(0).get("calls").asInt()).isEqualTo(1);
    }
}
