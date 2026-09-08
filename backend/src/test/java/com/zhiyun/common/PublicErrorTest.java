package com.zhiyun.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicErrorTest {
    private static final String ARREARAGE_JSON = """
            structured output failed after retry: 400 Bad Request: "{"error":{"message":"Access denied, please make sure your account is in good standing. For details, see: https://help.aliyun.com/zh/model-studio/error-code#overdue-payment","type":"Arrearage","param":null,"code":"Arrearage"},"id":"chatcmpl-18bf2a2c-3472-9a54-ab61-78935a8301ad","request_id":"18bf2a2c-3472-9a54-ab61-78935a8301ad"}"
            """;

    @Test
    void mapsArrearageJsonToQuotaCopy() {
        assertThat(PublicError.code(ARREARAGE_JSON)).isEqualTo("arrearage");
        assertThat(PublicError.message(ARREARAGE_JSON)).isEqualTo(PublicError.ARREARAGE);
        assertThat(PublicError.message(ARREARAGE_JSON)).doesNotContain("help.aliyun", "request_id", "Arrearage");
    }

    @Test
    void classifies400AndTimeoutVariants() {
        assertThat(PublicError.code("400 Bad Request: {\"error\":{\"type\":\"Arrearage\"}}")).isEqualTo("arrearage");
        assertThat(PublicError.message("400 Invalid API Key")).isEqualTo(PublicError.INVALID_KEY);
        assertThat(PublicError.message("timeout")).isEqualTo(PublicError.TIMEOUT);
        assertThat(PublicError.message("structured output failed after retry: injected"))
                .isEqualTo(PublicError.STRUCTURED);
        assertThat(PublicError.message("ECONNREFUSED")).isEqualTo(PublicError.NETWORK);
        assertThat(PublicError.message("已取消")).isEqualTo(PublicError.CANCELLED);
        assertThat(PublicError.message("")).isEmpty();
        assertThat(PublicError.message("unexpected boom")).isEqualTo(PublicError.UNKNOWN);
    }
}
