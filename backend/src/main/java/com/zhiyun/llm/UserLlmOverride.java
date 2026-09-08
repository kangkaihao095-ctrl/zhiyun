package com.zhiyun.llm;

/** 论文 Agent 的用户自备模型。客服与 RAG 不得使用。 */
public record UserLlmOverride(String provider, String baseUrl, String modelId, String apiKey) {
    public boolean present() {
        return apiKey != null && !apiKey.isBlank() && modelId != null && !modelId.isBlank();
    }
}
