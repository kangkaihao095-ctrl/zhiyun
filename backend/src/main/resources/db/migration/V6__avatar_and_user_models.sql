ALTER TABLE app_user
    ADD COLUMN avatar_path VARCHAR(512) NULL AFTER display_name;

CREATE TABLE user_agent_model (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    agent_id VARCHAR(64) NOT NULL,
    provider VARCHAR(64) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    model_id VARCHAR(191) NOT NULL,
    api_key_cipher VARCHAR(2048) NOT NULL,
    api_key_suffix VARCHAR(8) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_agent (tenant_id, user_id, agent_id),
    KEY idx_uam_user (tenant_id, user_id)
);
