package com.zhiyun.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "inbox_message")
public class InboxMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id")
    private Long tenantId;
    @Column(name = "user_id")
    private Long userId;
    private String kind;
    private String title;
    private String body;
    @Column(name = "ref_type")
    private String refType;
    @Column(name = "ref_id")
    private String refId;
    @Column(name = "read_at")
    private Instant readAt;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
