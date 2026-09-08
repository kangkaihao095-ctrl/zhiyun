package com.zhiyun.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "artifact")
public class Artifact {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id")
    private Long tenantId;
    @Column(name = "task_id")
    private Long taskId;
    private String agent;
    @Column(name = "artifact_type")
    private String artifactType;
    @Column(columnDefinition = "LONGTEXT")
    private String payload;
    @Column(name = "fencing_token")
    private Long fencingToken;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
