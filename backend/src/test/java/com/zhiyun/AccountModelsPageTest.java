package com.zhiyun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyun.domain.Codes;
import com.zhiyun.harness.AgentIds;
import com.zhiyun.llm.AgentModelRouter;
import com.zhiyun.llm.LlmGateway;
import com.zhiyun.rag.RagService;
import com.zhiyun.repo.PlanRepo;
import com.zhiyun.security.AuthUser;
import com.zhiyun.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccountModelsPageTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    PlanRepo planRepo;
    @Autowired
    AgentModelRouter modelRouter;
    @Autowired
    LlmGateway llmGateway;
    @Autowired
    RagService ragService;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void putMeUpdatesNameAndEmailAndKeepsJwt() throws Exception {
        String token = register("me-old-" + id() + "@zhiyun.dev");
        String nextEmail = "me-new-" + id() + "@zhiyun.dev";
        JsonNode updated = mapper.readTree(mvc.perform(put("/api/me")
                        .header("Authorization", bearer(token))
                        .characterEncoding("UTF-8")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Patched User\",\"email\":\"" + nextEmail + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(updated.get("displayName").asText()).isEqualTo("Patched User");
        assertThat(updated.get("email").asText()).isEqualTo(nextEmail);

        JsonNode me = mapper.readTree(mvc.perform(get("/api/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(me.get("email").asText()).isEqualTo(nextEmail);
        assertThat(me.get("displayName").asText()).isEqualTo("Patched User");

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + nextEmail + "\",\"password\":\"demo123456\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void ordersAndLedgerArePagedAndSearchable() throws Exception {
        seedPlan();
        String token = register("page-" + id() + "@zhiyun.dev");
        long planId = planRepo.findAll().get(0).getId();
        for (int i = 0; i < 11; i++) {
            String orderId = publicOrderId(mapper.readTree(mvc.perform(post("/api/orders")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"planId\":" + planId + "}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()));
            mvc.perform(post("/api/orders/" + orderId + "/mock-pay").header("Authorization", bearer(token)))
                    .andExpect(status().isOk());
        }
        JsonNode page1 = mapper.readTree(mvc.perform(get("/api/orders").param("page", "1").param("size", "10")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(page1.get("items").isArray()).isTrue();
        assertThat(page1.get("items").size()).isEqualTo(10);
        assertThat(page1.get("total").asInt()).isGreaterThanOrEqualTo(11);
        assertThat(page1.get("page").asInt()).isEqualTo(1);
        assertThat(page1.get("size").asInt()).isEqualTo(10);

        JsonNode pageDefault = mapper.readTree(mvc.perform(get("/api/orders").param("page", "1")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(pageDefault.get("size").asInt()).isEqualTo(5);
        assertThat(pageDefault.get("items").size()).isEqualTo(5);

        JsonNode pageSmall = mapper.readTree(mvc.perform(get("/api/orders").param("page", "1").param("size", "5")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(pageSmall.get("items").size()).isEqualTo(5);
        assertThat(pageSmall.get("size").asInt()).isEqualTo(5);

        JsonNode first = page1.get("items").get(0);
        String needle = first.get("id").asText();
        assertThat(needle).doesNotMatch("^\\d{1,6}$");
        JsonNode found = mapper.readTree(mvc.perform(get("/api/orders").param("q", needle)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(found.get("total").asInt()).isGreaterThan(0);
        assertThat(found.get("items").get(0).get("id").asText()).isEqualTo(needle);

        JsonNode ledger = mapper.readTree(mvc.perform(get("/api/ledger").param("q", "充值").param("size", "10")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(ledger.get("items").isArray()).isTrue();
        assertThat(ledger.get("total").asInt()).isGreaterThan(0);
        assertThat(ledger.get("items").get(0).get("reason").asText()).isEqualTo("PURCHASE");

        String suffix = needle.substring(Math.max(0, needle.length() - 2));
        JsonNode suffixHit = mapper.readTree(mvc.perform(get("/api/orders").param("q", suffix)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(suffixHit.get("total").asInt()).isGreaterThan(0);

        JsonNode restored = mapper.readTree(mvc.perform(get("/api/orders").param("q", " ")
                        .param("size", "50")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(restored.get("total").asInt()).isGreaterThanOrEqualTo(11);

        long projectId = mapper.readTree(mvc.perform(get("/api/projects").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asLong();
        String title = "AlphaZetaSearch-" + id() + ".md";
        MockMultipartFile file = new MockMultipartFile("file", title, "text/markdown", "# Intro\nDOI 10.1145/example.2019\n".getBytes());
        mvc.perform(multipart("/api/manuscripts").file(file)
                        .param("projectId", String.valueOf(projectId)).header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        JsonNode prefix = mapper.readTree(mvc.perform(get("/api/manuscripts").param("q", "Alpha")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(prefix.get("total").asInt()).isGreaterThan(0);
        assertThat(prefix.get("items").get(0).get("title").asText()).contains("AlphaZetaSearch");
        JsonNode mid = mapper.readTree(mvc.perform(get("/api/manuscripts").param("q", "Zeta")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(mid.get("total").asInt()).isGreaterThan(0);
        JsonNode miss = mapper.readTree(mvc.perform(get("/api/manuscripts").param("q", "no-such-paper-zzz")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(miss.get("total").asInt()).isEqualTo(0);
        JsonNode allMs = mapper.readTree(mvc.perform(get("/api/manuscripts")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(allMs.get("total").asInt()).isGreaterThan(0);
    }

    @Test
    void userModelOverrideDoesNotAffectCsOrRag() throws Exception {
        String token = register("model-" + id() + "@zhiyun.dev");
        JsonNode catalog = mapper.readTree(mvc.perform(get("/api/models").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(catalog.get("agents").size()).isEqualTo(7);
        assertThat(findAgent(catalog, AgentIds.CITATION).get("mode").asText()).isEqualTo("PLATFORM");
        mvc.perform(get("/api/me/models").header("Authorization", bearer(token))).andExpect(status().isOk());
        String userKey = "sk-user-" + UUID.randomUUID().toString().replace("-", "");
        mvc.perform(put("/api/models/CUSTOMER_SERVICE")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"BYOK\",\"provider\":\"openai\",\"baseUrl\":\"https://example.invalid/v1\",\"modelId\":\"x\",\"apiKey\":\"" + userKey + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/models/RAG")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"BYOK\",\"provider\":\"openai\",\"baseUrl\":\"https://example.invalid/v1\",\"modelId\":\"x\",\"apiKey\":\"" + userKey + "\"}"))
                .andExpect(status().isBadRequest());

        JsonNode saved = mapper.readTree(mvc.perform(put("/api/models/" + AgentIds.CITATION)
                        .header("Authorization", bearer(token))
                        .characterEncoding("UTF-8")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"BYOK\",\"provider\":\"openai\",\"baseUrl\":\"https://example.invalid/v1\",\"modelId\":\"my-better-model\",\"apiKey\":\"" + userKey + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        String body = saved.toString();
        assertThat(body).doesNotContain(userKey);
        JsonNode citation = findAgent(saved, AgentIds.CITATION);
        assertThat(citation.get("mode").asText()).isEqualTo("BYOK");
        assertThat(citation.get("apiKeyMasked").asText()).contains("****");
        assertThat(saved.get("locked").get("cs").get("note").asText()).isNotBlank();
        assertThat(saved.get("locked").get("cs").get("name").asText()).isEqualTo("云笺");
        assertThat(saved.get("locked").get("rag").get("note").asText()).isNotBlank();
        assertThat(saved.get("locked").get("cs").get("model").asText()).isEqualTo("qwen3.7-flash");

        JsonNode me = mapper.readTree(mvc.perform(get("/api/me").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString());
        TenantContext.set(new AuthUser(me.get("userId").asLong(), me.get("tenantId").asLong(), me.get("email").asText(), me.get("displayName").asText()));
        assertThat(modelRouter.resolve(AgentIds.CS)).isNull();
        assertThat(modelRouter.chatModel(AgentIds.CS)).isEqualTo("qwen3.7-flash");
        assertThat(modelRouter.resolve(AgentIds.CITATION)).isNotNull();
        assertThat(modelRouter.chatModel(AgentIds.CITATION)).isEqualTo("my-better-model");
        float[] vec = llmGateway.embed("platform rag stays on platform");
        assertThat(vec).isNotEmpty();
        assertThat(ragService).isNotNull();
    }

    @Test
    void byokReviewChargesSkillFee() throws Exception {
        seedPlan();
        String token = register("byok-" + id() + "@zhiyun.dev");
        String userKey = "sk-user-" + UUID.randomUUID().toString().replace("-", "");
        mvc.perform(put("/api/models/" + AgentIds.CITATION)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"BYOK\",\"provider\":\"openai\",\"baseUrl\":\"https://example.invalid/v1\",\"modelId\":\"my-better-model\",\"apiKey\":\"" + userKey + "\"}"))
                .andExpect(status().isOk());
        long projectId = mapper.readTree(mvc.perform(get("/api/projects").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asLong();
        MockMultipartFile file = new MockMultipartFile("file", "paper.md", "text/markdown", "# Intro\nDOI 10.1145/example.2019\n".getBytes());
        long msId = mapper.readTree(mvc.perform(multipart("/api/manuscripts").file(file)
                        .param("projectId", String.valueOf(projectId)).header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        JsonNode task = mapper.readTree(mvc.perform(post("/api/manuscripts/" + msId + "/reviews")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflow\":\"CITATION_ONLY\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(task.get("status").asText()).isEqualTo(Codes.DONE);
        JsonNode ledger = mapper.readTree(mvc.perform(get("/api/ledger").param("q", "技能")
                        .header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString());
        assertThat(ledger.get("total").asInt()).isGreaterThan(0);
        assertThat(ledger.get("items").get(0).get("reason").asText()).isEqualTo("SKILL_FEE");
        assertThat(ledger.toString()).doesNotContain(userKey);
    }

    private JsonNode findAgent(JsonNode catalog, String id) {
        for (JsonNode agent : catalog.get("agents")) {
            if (id.equals(agent.get("id").asText())) {
                return agent;
            }
        }
        throw new AssertionError("agent missing");
    }

    private void seedPlan() {
        if (planRepo.count() > 0) {
            return;
        }
        var plan = new com.zhiyun.domain.Plan();
        plan.setCode("starter");
        plan.setName("轻量套餐");
        plan.setQuotaAmount(10);
        plan.setPriceCents(0);
        plan.setDescription("test");
        planRepo.save(plan);
    }

    private String register(String email) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"demo123456\",\"displayName\":\"U\"}";
        return mapper.readTree(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private static String publicOrderId(JsonNode node) {
        String id = node.get("id").asText();
        assertThat(id).startsWith("ZY");
        assertThat(id).doesNotMatch("^\\d{1,8}$");
        assertThat(id.length()).isGreaterThan(10);
        return id;
    }

    private static String id() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
