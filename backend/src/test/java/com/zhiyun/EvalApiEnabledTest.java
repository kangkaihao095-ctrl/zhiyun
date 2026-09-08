package com.zhiyun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "zhiyun.eval.api-enabled=true")
class EvalApiEnabledTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    @Test
    void flagEnablesRegressionMetaThatIsNotSla() throws Exception {
        String token = mapper.readTree(mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"eval-on@zhiyun.dev\",\"password\":\"demo123456\",\"displayName\":\"E\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
        JsonNode agents = mapper.readTree(mvc.perform(get("/api/eval/agents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(agents.get("sla").asBoolean()).isFalse();
        assertThat(agents.get("purpose").asText()).isEqualTo("regression");
        assertThat(agents.get("note").asText()).contains("不是线上 SLA");
    }
}
