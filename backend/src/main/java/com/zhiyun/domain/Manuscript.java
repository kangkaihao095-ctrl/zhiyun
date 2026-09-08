package com.zhiyun.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "manuscript")
public class Manuscript {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id")
    private Long tenantId;
    @Column(name = "project_id")
    private Long projectId;
    private String title;
    @Column(name = "current_version")
    private Integer currentVersion = 1;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
