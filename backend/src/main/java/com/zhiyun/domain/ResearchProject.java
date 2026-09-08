package com.zhiyun.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "research_project")
public class ResearchProject {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id")
    private Long tenantId;
    private String name;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
