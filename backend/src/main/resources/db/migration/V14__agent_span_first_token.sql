-- Agent span 补 LLM 首 token 时间戳与相对请求开始的耗时（ms）。
-- 流式为首个 content/delta；非流式为完整响应到达。运行观测，不是 SLA。

ALTER TABLE agent_span
    ADD COLUMN first_token_at TIMESTAMP NULL AFTER duration_ms,
    ADD COLUMN first_token_ms BIGINT NULL AFTER first_token_at;
