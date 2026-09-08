package com.zhiyun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ResumeModulesTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    @Test
    void operatorCanUploadPublicKnowledgeAndStrangerIsForbidden() throws Exception {
        String demo = loginOrRegister("demo@zhiyun.dev");
        JsonNode me = mapper.readTree(mvc.perform(get("/api/me").header("Authorization", bearer(demo)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(me.get("operator").asBoolean()).isTrue();

        MockMultipartFile file = new MockMultipartFile(
                "file", "ops-acl.md", "text/markdown", "# ACL\n投稿用 A4。\n".getBytes());
        JsonNode uploaded = mapper.readTree(mvc.perform(multipart("/api/ops/knowledge").file(file)
                        .header("Authorization", bearer(demo)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(uploaded.get("uploaded").size()).isGreaterThan(0);
        assertThat(uploaded.get("publicChunks").asInt()).isGreaterThan(0);
        assertThat(uploaded.get("groups").size()).isEqualTo(3);
        assertThat(uploaded.get("groups").get(0).get("title").asText()).isEqualTo("期刊规范");
        assertThat(uploaded.get("bundled").get(0).has("content")).isFalse();
        assertThat(uploaded.get("bundled").get(0).has("title")).isTrue();
        assertThat(uploaded.get("bundled").get(0).get("summary").asText().length()).isLessThanOrEqualTo(160);

        String stranger = register("ops-stranger-" + System.nanoTime() + "@zhiyun.dev");
        mvc.perform(get("/api/ops/knowledge").header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/ops/knowledge").file(file).header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());

        long id = uploaded.get("uploaded").get(0).get("id").asLong();
        mvc.perform(delete("/api/ops/knowledge/" + id).header("Authorization", bearer(demo)))
                .andExpect(status().isOk());
    }

    @Test
    void meExposesUnreadInboxField() throws Exception {
        String token = register("inbox-me-" + System.nanoTime() + "@zhiyun.dev");
        JsonNode me = mapper.readTree(mvc.perform(get("/api/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(me.get("unreadInbox").asInt()).isEqualTo(0);
        assertThat(me.get("operator").asBoolean()).isFalse();
    }

    private String loginOrRegister(String email) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"demo123456\",\"displayName\":\"Demo\"}";
        var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        if (login.getResponse().getStatus() == 200) {
            return mapper.readTree(login.getResponse().getContentAsString()).get("token").asText();
        }
        return mapper.readTree(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private String register(String email) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"demo123456\",\"displayName\":\"U\"}";
        return mapper.readTree(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
