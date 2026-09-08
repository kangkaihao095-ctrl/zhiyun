package com.zhiyun.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "document_version")
public class DocumentVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id")
    private Long tenantId;
    @Column(name = "manuscript_id")
    private Long manuscriptId;
    @Column(name = "version_no")
    private Integer versionNo;
    private String status;
    @Column(name = "storage_path")
    private String storagePath;
    @Column(name = "content_text", columnDefinition = "LONGTEXT")
    private String contentText;
    @Column(name = "content_sha256", length = 64, columnDefinition = "CHAR(64)")
    private String contentSha256;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
