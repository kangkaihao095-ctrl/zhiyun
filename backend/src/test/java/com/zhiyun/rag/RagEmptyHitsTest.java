package com.zhiyun.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyun.harness.ToolCallRecorder;
import com.zhiyun.harness.ToolPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RagEmptyHitsTest {
    @Autowired
    RagService ragService;

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        ToolCallRecorder.close();
    }

    @Test
    void liveRetrieveWritesHitsZeroOnEmptyRecall() {
        ToolCallRecorder.open();
        List<RagService.Retrieved> hits = ragService.retrievePrivate(
                9_700_001L, 9_700_002L, 1, "zzzz-no-chunk-" + System.nanoTime(), null);
        assertThat(hits).isEmpty();
        JsonNode snap = ToolCallRecorder.snapshot(mapper);
        JsonNode privateCall = null;
        for (JsonNode node : snap) {
            if (ToolPolicy.MANUSCRIPT_RETRIEVAL.equals(node.path("tool").asText())) {
                privateCall = node;
            }
        }
        assertThat(privateCall).isNotNull();
        assertThat(privateCall.get("hits").asInt(-1)).isEqualTo(0);
        assertThat(privateCall.get("emptyHits").asInt()).isEqualTo(1);
    }
}
