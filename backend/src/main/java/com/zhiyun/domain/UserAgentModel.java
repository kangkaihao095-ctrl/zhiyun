package com.zhiyun.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "user_agent_model")
public class UserAgentModel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id")
    private Long tenantId;
    @Column(name = "user_id")
    private Long userId;
    @Column(name = "agent_id")
    private String agentId;
    private String provider;
    @Column(name = "base_url")
    private String baseUrl;
    @Column(name = "model_id")
    private String modelId;
    @Column(name = "api_key_cipher")
    private String apiKeyCipher;
    @Column(name = "api_key_suffix")
    private String apiKeySuffix;
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(nullable = false)
    private Boolean enabled = true;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}
