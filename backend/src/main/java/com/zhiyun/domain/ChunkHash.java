package com.zhiyun.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "chunk_hash")
public class ChunkHash {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id")
    private Long tenantId;
    @Column(name = "manuscript_id")
    private Long manuscriptId;
    @Column(name = "version_no")
    private Integer versionNo;
    @Column(name = "chunk_id")
    private String chunkId;
    private String section;
    @Column(columnDefinition = "LONGTEXT")
    private String content;
    @Column(name = "content_sha256", length = 64, columnDefinition = "CHAR(64)")
    private String contentSha256;
}
