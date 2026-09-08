package com.zhiyun.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "app_user")
public class AppUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id")
    private Long tenantId;
    private String email;
    @Column(name = "password_hash")
    private String passwordHash;
    @Column(name = "display_name")
    private String displayName;
    @Column(name = "avatar_path")
    private String avatarPath;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
