CREATE TABLE tenant (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE app_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    email VARCHAR(191) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_email (email),
    KEY idx_user_tenant (tenant_id)
);

CREATE TABLE research_project (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(191) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_project_tenant (tenant_id)
);

CREATE TABLE manuscript (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    title VARCHAR(512) NOT NULL,
    current_version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_ms_tenant (tenant_id),
    KEY idx_ms_project (project_id)
);

CREATE TABLE document_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    manuscript_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    storage_path VARCHAR(1024) NOT NULL,
    content_text LONGTEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ms_ver (manuscript_id, version_no),
    KEY idx_dv_tenant (tenant_id)
);

CREATE TABLE review_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    manuscript_id BIGINT NOT NULL,
    workflow VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    source_version INT NOT NULL,
    candidate_version INT NULL,
    checkpoint_agent VARCHAR(64) NULL,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(64) NOT NULL,
    error_message VARCHAR(1024) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_task_idem (idempotency_key),
    KEY idx_task_tenant (tenant_id),
    KEY idx_task_ms (manuscript_id)
);

CREATE TABLE task_lease (
    task_id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    owner VARCHAR(191) NOT NULL,
    expire_at TIMESTAMP NOT NULL,
    fencing_token BIGINT NOT NULL
);

CREATE TABLE artifact (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    agent VARCHAR(64) NOT NULL,
    artifact_type VARCHAR(64) NOT NULL,
    payload LONGTEXT NOT NULL,
    fencing_token BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_artifact (task_id, agent, artifact_type),
    KEY idx_art_tenant (tenant_id)
);

CREATE TABLE chunk_hash (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    manuscript_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    chunk_id VARCHAR(64) NOT NULL,
    section VARCHAR(191) NOT NULL,
    content LONGTEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    UNIQUE KEY uk_chunk (manuscript_id, version_no, chunk_id),
    KEY idx_chunk_tenant (tenant_id)
);

CREATE TABLE plan (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    quota_amount INT NOT NULL,
    price_cents INT NOT NULL,
    description VARCHAR(512) NOT NULL,
    UNIQUE KEY uk_plan_code (code)
);

CREATE TABLE app_order (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    plan_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    amount_cents INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_order_tenant_user (tenant_id, user_id)
);

CREATE TABLE quota_account (
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    balance INT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, user_id)
);

CREATE TABLE quota_ledger (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    delta INT NOT NULL,
    reason VARCHAR(64) NOT NULL,
    ref_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_ledger_user (tenant_id, user_id)
);
