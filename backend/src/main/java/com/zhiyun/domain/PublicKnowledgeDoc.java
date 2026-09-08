package com.zhiyun.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 公共投稿规范运营稿。全局一份，不是按租户拆库。 */
@Getter
@Setter
@Entity
@Table(name = "public_knowledge_doc")
public class PublicKnowledgeDoc {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String filename;
    @Column(columnDefinition = "LONGTEXT")
    private String content;
    @Column(name = "uploaded_by_user_id")
    private Long uploadedByUserId;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}
