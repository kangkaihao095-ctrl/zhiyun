-- Agent span 补 skill/prompt 版本、checkpoint 跳过、错误降级码。供本租户运行观测，不是 SLA。

ALTER TABLE agent_span
    ADD COLUMN skill_version VARCHAR(16) NULL AFTER agent,
    ADD COLUMN prompt_version VARCHAR(16) NULL AFTER skill_version,
    ADD COLUMN skipped TINYINT NOT NULL DEFAULT 0 AFTER checkpoint,
    ADD COLUMN error_code VARCHAR(32) NULL AFTER error_message;
