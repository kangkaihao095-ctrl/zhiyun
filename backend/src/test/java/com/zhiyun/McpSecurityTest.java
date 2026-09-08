package com.zhiyun;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class McpSecurityTest {
    @Autowired
    MockMvc mvc;

    @Test
    void mcpWithoutTokenIsUnauthorizedNotPermitAll() throws Exception {
        mvc.perform(post("/api/mcp/usage_query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mcpWithWrongTokenIsUnauthorized() throws Exception {
        mvc.perform(post("/api/mcp/usage_query")
                        .header("X-Zhiyun-Mcp-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void localDevTokenStillRequiresUserToken() throws Exception {
        mvc.perform(post("/api/mcp/usage_query")
                        .header("X-Zhiyun-Mcp-Token", "dev-mcp-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
