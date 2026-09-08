-- Agent Trace、站内信、支付渠道字段、公共知识运营稿。公共知识仍是全局一份，不是按租户拆库。

CREATE TABLE agent_span (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    agent VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMP NULL,
    ended_at TIMESTAMP NULL,
    duration_ms BIGINT NULL,
    tokens INT NULL,
    fencing_token BIGINT NULL,
    checkpoint TINYINT NOT NULL DEFAULT 0,
    error_message VARCHAR(1024) NULL,
    UNIQUE KEY uk_span_task_agent (task_id, agent),
    KEY idx_span_tenant_task (tenant_id, task_id)
);

CREATE TABLE inbox_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    kind VARCHAR(64) NOT NULL,
    title VARCHAR(191) NOT NULL,
    body VARCHAR(1024) NOT NULL,
    ref_type VARCHAR(32) NOT NULL,
    ref_id VARCHAR(64) NOT NULL,
    read_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_inbox_kind_ref (tenant_id, user_id, kind, ref_id),
    KEY idx_inbox_unread (tenant_id, user_id, read_at)
);

CREATE TABLE public_knowledge_doc (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    filename VARCHAR(191) NOT NULL,
    content LONGTEXT NOT NULL,
    uploaded_by_user_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pub_knowledge_filename (filename)
);

ALTER TABLE app_order
    ADD COLUMN pay_channel VARCHAR(32) NULL AFTER status,
    ADD COLUMN pay_txn_id VARCHAR(64) NULL AFTER pay_channel;
