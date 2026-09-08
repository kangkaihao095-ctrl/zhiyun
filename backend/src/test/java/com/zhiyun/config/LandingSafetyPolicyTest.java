package com.zhiyun.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LandingSafetyPolicyTest {
    @Test
    void mcpFallsBackToDevTokenOnlyOffProd() {
        ZhiyunProperties properties = new ZhiyunProperties();
        assertThat(properties.resolvedMcpToken("local")).isEqualTo(ZhiyunProperties.DEV_MCP_TOKEN);
        assertThat(properties.mcpEnabled("local")).isTrue();
        assertThat(properties.resolvedMcpToken("prod")).isEmpty();
        assertThat(properties.mcpEnabled("prod")).isFalse();

        properties.getCs().setMcpToken(ZhiyunProperties.DEV_MCP_TOKEN);
        assertThat(properties.mcpEnabled("local")).isTrue();
        assertThat(properties.mcpEnabled("prod")).isFalse();

        properties.getCs().setMcpToken("real-mcp-token");
        assertThat(properties.resolvedMcpToken("prod")).isEqualTo("real-mcp-token");
        assertThat(properties.mcpEnabled("prod")).isTrue();
    }

    @Test
    void corsDropsWildcardAndKeepsDefaultLoopback() {
        ZhiyunProperties properties = new ZhiyunProperties();
        assertThat(properties.corsAllowedOrigins()).containsExactly(
                ZhiyunProperties.DEFAULT_CORS_ORIGIN,
                ZhiyunProperties.DEFAULT_CORS_ORIGIN_LOCALHOST);

        properties.getCors().setAllowedOrigins("http://127.0.0.1:5173,https://zhiyun.example");
        assertThat(properties.corsAllowedOrigins())
                .containsExactly("http://127.0.0.1:5173", "https://zhiyun.example");

        properties.getCors().setAllowedOrigins("*");
        assertThat(properties.corsAllowedOrigins()).containsExactly(
                ZhiyunProperties.DEFAULT_CORS_ORIGIN,
                ZhiyunProperties.DEFAULT_CORS_ORIGIN_LOCALHOST);

        properties.getCors().setAllowedOrigins("https://*.evil,http://127.0.0.1:5173");
        assertThat(properties.corsAllowedOrigins()).containsExactly("http://127.0.0.1:5173");
    }

    @Test
    void jwtDefaultIsWeakAndCustom32CharsIsStrong() {
        ZhiyunProperties properties = new ZhiyunProperties();
        properties.getJwt().setSecret(ZhiyunProperties.DEFAULT_JWT_SECRET);
        assertThat(properties.jwtSecretStrongEnough()).isFalse();
        properties.getJwt().setSecret("short");
        assertThat(properties.jwtSecretStrongEnough()).isFalse();
        properties.getJwt().setSecret("test-secret-please-use-32-chars!!");
        assertThat(properties.jwtSecretStrongEnough()).isTrue();
    }
}
