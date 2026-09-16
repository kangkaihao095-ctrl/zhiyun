package com.zhiyun.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.harness.FirstTokenRecorder;
import com.zhiyun.harness.HarnessMeters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LlmGatewayFirstTokenTest {
    @AfterEach
    void tearDown() {
        FirstTokenRecorder.close();
    }

    @Test
    void streamRecordsFirstDeltaOnly() {
        FirstTokenRecorder.open();
        Fixture fx = liveGateway();
        fx.server.expect(requestTo("http://llm.test/v1/chat/completions"))
                .andRespond(withSuccess("""
                        data: {"choices":[{"delta":{}}]}

                        data: {"choices":[{"delta":{"content":"Hel"}}]}

                        data: {"choices":[{"delta":{"content":"lo"}}]}

                        data: [DONE]
                        """, MediaType.TEXT_EVENT_STREAM));

        List<String> deltas = new ArrayList<>();
        String full = fx.gateway.completeStream("qwen", 0.2, List.of(Map.of("role", "user", "content", "hi")), deltas::add);

        assertThat(full).isEqualTo("Hello");
        assertThat(deltas).containsExactly("Hel", "lo");
        assertThat(fx.registry.find(HarnessMeters.LLM_TTFT).timer().count()).isEqualTo(1);
        assertThat(FirstTokenRecorder.snapshot()).isNotNull();
        assertThat(FirstTokenRecorder.snapshot().firstTokenMs()).isGreaterThanOrEqualTo(0);
        assertThat(fx.meters.snapshot().get("lastFirstTokenAt")).isNotNull();
        fx.server.verify();
    }

    @Test
    void streamFailureOrEmptyChunksDoNotInventFirstToken() {
        Fixture empty = liveGateway();
        empty.server.expect(requestTo("http://llm.test/v1/chat/completions"))
                .andRespond(withSuccess("""
                        data: {"choices":[{"delta":{}}]}

                        data: [DONE]
                        """, MediaType.TEXT_EVENT_STREAM));
        assertThat(empty.gateway.completeStream("qwen", 0.2, "sys", "user", delta -> {
        })).isNull();
        assertThat(empty.registry.find(HarnessMeters.LLM_TTFT).timer().count()).isZero();
        empty.server.verify();

        Fixture failed = liveGateway();
        failed.server.expect(requestTo("http://llm.test/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom"));
        assertThat(failed.gateway.completeStream("qwen", 0.2, "sys", "user", delta -> {
        })).isNull();
        assertThat(failed.registry.find(HarnessMeters.LLM_TTFT).timer().count()).isZero();
        failed.server.verify();
    }

    @Test
    void completeRecordsWhenBodyArrivesAndSkipsEmptyChoices() {
        Fixture ok = liveGateway();
        ok.server.expect(requestTo("http://llm.test/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"done"}}]}
                        """, MediaType.APPLICATION_JSON));
        assertThat(ok.gateway.complete("qwen", 0.1, "sys", "user")).isEqualTo("done");
        assertThat(ok.registry.find(HarnessMeters.LLM_TTFT).timer().count()).isEqualTo(1);
        ok.server.verify();

        Fixture empty = liveGateway();
        empty.server.expect(requestTo("http://llm.test/v1/chat/completions"))
                .andRespond(withSuccess("""
                        {"choices":[]}
                        """, MediaType.APPLICATION_JSON));
        assertThat(empty.gateway.complete("qwen", 0.1, "sys", "user")).isNull();
        assertThat(empty.registry.find(HarnessMeters.LLM_TTFT).timer().count()).isZero();
        empty.server.verify();
    }

    @Test
    void dryRunDoesNotRecordFirstToken() {
        ZhiyunProperties properties = new ZhiyunProperties();
        properties.getLlm().setMode("dry-run");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmGateway gateway = new LlmGateway(properties, RestClient.create(), new ObjectMapper(), new HarnessMeters(registry));
        assertThat(gateway.complete("qwen", 0.1, "sys", "user")).isNull();
        assertThat(gateway.completeStream("qwen", 0.1, "sys", "user", delta -> {
        })).isNull();
        assertThat(registry.find(HarnessMeters.LLM_TTFT).timer().count()).isZero();
    }

    private Fixture liveGateway() {
        ZhiyunProperties properties = new ZhiyunProperties();
        properties.getLlm().setMode("live");
        properties.getLlm().setApiKey("test-key");
        properties.getLlm().setBaseUrl("http://llm.test/v1");
        properties.getLlm().setProvider("openai");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HarnessMeters meters = new HarnessMeters(registry);
        return new Fixture(new LlmGateway(properties, builder.build(), new ObjectMapper(), meters), server, registry, meters);
    }

    private record Fixture(LlmGateway gateway, MockRestServiceServer server, SimpleMeterRegistry registry,
                           HarnessMeters meters) {
    }
}
