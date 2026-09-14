package com.zhiyun.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyun.domain.AgentSpan;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.repo.AgentSpanRepo;
import com.zhiyun.repo.ReviewTaskRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ObservabilityBoardTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    ReviewTaskRepo reviewTaskRepo;
    @Autowired
    AgentSpanRepo agentSpanRepo;
    @Autowired
    HarnessMeters harnessMeters;

    @Test
    void opsBoardUsesWindowSpansNotProcessMeters() throws Exception {
        String body = "{\"email\":\"ops-board-" + System.nanoTime() + "@zhiyun.dev\",\"password\":\"demo123456\",\"displayName\":\"U\"}";
        String token = mapper.readTree(mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
        JsonNode me = mapper.readTree(mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        ReviewTask task = new ReviewTask();
        task.setTenantId(me.get("tenantId").asLong());
        task.setUserId(me.get("userId").asLong());
        task.setManuscriptId(1L);
        task.setWorkflow(Codes.FULL_REVIEW);
        task.setStatus(Codes.DONE);
        task.setSourceVersion(1);
        task.setFencingToken(3L);
        task.setIdempotencyKey("ops-board-" + System.nanoTime());
        task = reviewTaskRepo.saveAndFlush(task);

        AgentSpan citation = span(task, "CITATION_INTEGRITY", Codes.DONE, 900L, 400);
        citation.setToolCalls("""
                [{"tool":"AcademicSearch","calls":3,"ok":2,"failed":1,"durationMs":40,"lookupDoi":3,"lookupOk":2,"notVerified":1,"inventedDropped":1},
                 {"tool":"WebSearch","calls":1,"ok":1,"failed":0,"durationMs":80}]
                """);
        agentSpanRepo.saveAndFlush(citation);

        AgentSpan reviewer = span(task, "ACADEMIC_REVIEWER", Codes.DONE, 1200L, 600);
        reviewer.setToolCalls("""
                [{"tool":"ManuscriptRetrieval","calls":2,"ok":2,"failed":0,"durationMs":50,"hits":0,"emptyHits":2},
                 {"tool":"KnowledgeRetrieval","calls":1,"ok":1,"failed":0,"durationMs":30,"hits":4},
                 {"tool":"kNN","calls":1,"ok":1,"failed":0,"durationMs":12,"hits":0,"emptyHits":1}]
                """);
        agentSpanRepo.saveAndFlush(reviewer);

        AgentSpan execution = span(task, "REVISION_EXECUTION", Codes.FAILED, 400L, 50);
        execution.setErrorCode("fencing");
        execution.setErrorMessage("stale fencing token rejected");
        execution.setToolCalls("""
                [{"tool":"DocxTool","calls":2,"ok":1,"failed":1,"durationMs":20}]
                """);
        agentSpanRepo.saveAndFlush(execution);

        String demoBody = "{\"email\":\"demo@zhiyun.dev\",\"password\":\"demo123456\",\"displayName\":\"Demo\"}";
        var demoLogin = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(demoBody))
                .andReturn();
        if (demoLogin.getResponse().getStatus() != 200) {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(demoBody))
                    .andExpect(status().isOk());
        }
        String ops = mapper.readTree(mvc.perform(post("/api/auth/ops/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"demo@zhiyun.dev\",\"password\":\"demo123456\"}"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("token").asText();

        JsonNode obs = mapper.readTree(mvc.perform(get("/api/ops/observability")
                        .param("range", "24h")
                        .param("tenantId", String.valueOf(task.getTenantId()))
                        .header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(obs.get("caption").asText()).contains("非 SLA");
        assertThat(obs.get("caption").asText()).contains("无 TTFT");
        assertThat(obs.has("meters")).isFalse();

        JsonNode llm = obs.get("llm");
        assertThat(llm.get("calls").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(llm.get("tokens").asInt()).isGreaterThanOrEqualTo(1050);
        assertThat(llm.get("promptTokens").asInt()).isEqualTo(0);
        assertThat(llm.get("completionTokens").asInt()).isEqualTo(0);
        assertThat(llm.get("avgDurationMs").asLong()).isEqualTo((900L + 1200L + 400L) / 3);
        assertThat(llm.get("durationMs").asLong()).isEqualTo(2500L);
        assertThat(llm.get("p50Ms").asLong()).isEqualTo(900L);
        assertThat(llm.get("p95Ms").asLong()).isEqualTo(1200L);
        assertThat(llm.get("caption").asText()).doesNotContain("TTFT 达标");

        JsonNode citationBoard = obs.get("citation");
        assertThat(citationBoard.get("lookupDoi").asInt()).isEqualTo(3);
        assertThat(citationBoard.get("lookupOk").asInt()).isEqualTo(2);
        assertThat(citationBoard.get("inventedDoiDropped").asInt()).isEqualTo(1);
        assertThat(citationBoard.get("notVerified").asInt()).isEqualTo(1);

        JsonNode rag = obs.get("rag");
        assertThat(rag.get("privateRetrievals").asInt()).isEqualTo(2);
        assertThat(rag.get("publicRetrievals").asInt()).isEqualTo(1);
        assertThat(rag.get("knn").asInt()).isEqualTo(1);
        assertThat(rag.get("hasDuration").asBoolean()).isTrue();
        assertThat(rag.get("privateAvgDurationMs").asLong()).isEqualTo(50L);
        assertThat(rag.get("publicAvgDurationMs").asLong()).isEqualTo(30L);
        assertThat(rag.get("knnAvgDurationMs").asLong()).isEqualTo(12L);
        assertThat(rag.get("emptyHits").asInt()).isEqualTo(3);
        assertThat(rag.get("privateEmptyHits").asInt()).isEqualTo(2);
        assertThat(rag.get("publicEmptyHits").asInt()).isEqualTo(0);
        assertThat(rag.get("knnEmptyHits").asInt()).isEqualTo(1);
        assertThat(rag.get("caption").asText()).contains("运行观测，非 SLA");
        assertThat(rag.get("caption").asText()).doesNotContain("召回率 SLA");

        JsonNode backlog = obs.get("backlog");
        assertThat(backlog.get("pending").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(backlog.get("running").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(backlog.get("waitingAccept").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(backlog.get("leasesHeld").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(backlog.get("caption").asText()).contains("不是 SLA");

        assertThat(obs.get("kpis").get("p50Ms").asLong()).isEqualTo(900L);
        assertThat(obs.get("kpis").get("p95Ms").asLong()).isEqualTo(1200L);
        assertThat(obs.get("kpis").get("activeTenants").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(obs.get("crossTenant").get("activeTenants").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(obs.get("crossTenant").get("caption").asText()).contains("C 端不可见");

        JsonNode alerts = obs.get("alerts");
        assertThat(alerts.get("caption").asText()).contains("窗口规则");
        assertThat(alerts.get("caption").asText()).contains("不是 pager");
        assertThat(alerts.get("items").toString()).contains("fencing");
        assertThat(alerts.get("items").toString()).contains("empty-rag");
        assertThat(obs.toString()).doesNotContain("TTFT 达标");
        assertThat(obs.has("meters")).isFalse();

        JsonNode tools = obs.get("tools");
        assertThat(tools.get("failed").asInt()).isEqualTo(2);
        assertThat(tools.get("spanFailed").asInt()).isEqualTo(1);
        assertThat(tools.get("byTool").toString()).contains("WebSearch");
        assertThat(tools.get("byTool").toString()).contains("DocxTool");
        assertThat(tools.has("seed")).isFalse();
        assertThat(tools.get("caption").asText()).doesNotContain("种子");
        assertThat(obs.get("citation").has("seed")).isFalse();
        assertThat(obs.get("citation").get("caption").asText()).doesNotContain("种子");

        JsonNode harness = obs.get("harness");
        assertThat(harness.get("fencingRaised").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(harness.get("fencingRejected").asInt()).isGreaterThanOrEqualTo(1);

        long rejectedBefore = harness.get("fencingRejected").asLong();
        harnessMeters.recordFencingRejected();
        harnessMeters.recordFencingRejected();
        JsonNode after = mapper.readTree(mvc.perform(get("/api/ops/observability")
                        .param("range", "24h")
                        .param("tenantId", String.valueOf(task.getTenantId()))
                        .header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(after.get("harness").get("fencingRejected").asLong()).isEqualTo(rejectedBefore);
    }

    @Test
    void emptyTenantWindowReturnsOkWithoutMetersOrNullDurationNpe() throws Exception {
        String body = "{\"email\":\"ops-empty-" + System.nanoTime() + "@zhiyun.dev\",\"password\":\"demo123456\",\"displayName\":\"U\"}";
        String token = mapper.readTree(mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
        JsonNode me = mapper.readTree(mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        ensureOpsDemo();
        String ops = mapper.readTree(mvc.perform(post("/api/auth/ops/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"demo@zhiyun.dev\",\"password\":\"demo123456\"}"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("token").asText();

        JsonNode obs = mapper.readTree(mvc.perform(get("/api/ops/observability")
                        .param("range", "15m")
                        .param("tenantId", me.get("tenantId").asText())
                        .header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(obs.has("meters")).isFalse();
        assertThat(obs.get("kpis").get("tasks").asInt()).isEqualTo(0);
        assertThat(obs.get("llm").get("calls").asInt()).isEqualTo(0);
        assertThat(obs.get("llm").get("avgDurationMs").isNull()).isTrue();
        assertThat(obs.get("rag").get("hasDuration").asBoolean()).isFalse();
        assertThat(obs.get("rag").get("knnAvgDurationMs").isNull()).isTrue();
        assertThat(obs.get("rag").get("emptyHits").asInt()).isEqualTo(0);
        assertThat(obs.get("llm").get("p50Ms").isNull()).isTrue();
        assertThat(obs.get("llm").get("p95Ms").isNull()).isTrue();
        assertThat(obs.get("kpis").get("p50Ms").isNull()).isTrue();
        assertThat(obs.get("backlog").get("pending").asInt()).isEqualTo(0);
        assertThat(obs.get("alerts").get("items")).isEmpty();
        assertThat(obs.get("tools").get("failed").asInt()).isEqualTo(0);
        assertThat(obs.get("harness").get("fencingRejected").asInt()).isEqualTo(0);
    }

    private void ensureOpsDemo() throws Exception {
        String demoBody = "{\"email\":\"demo@zhiyun.dev\",\"password\":\"demo123456\",\"displayName\":\"Demo\"}";
        var demoLogin = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(demoBody))
                .andReturn();
        if (demoLogin.getResponse().getStatus() != 200) {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(demoBody))
                    .andExpect(status().isOk());
        }
    }

    private static AgentSpan span(ReviewTask task, String agent, String status, long durationMs, int tokens) {
        AgentSpan row = new AgentSpan();
        row.setTenantId(task.getTenantId());
        row.setTaskId(task.getId());
        row.setAgent(agent);
        row.setStatus(status);
        row.setDurationMs(durationMs);
        row.setTokens(tokens);
        row.setSkipped(false);
        return row;
    }
}
