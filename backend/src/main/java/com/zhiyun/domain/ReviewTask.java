package com.zhiyun.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyun.common.BusinessNos;
import com.zhiyun.common.PublicError;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "review_task", uniqueConstraints = @UniqueConstraint(name = "uk_task_no", columnNames = "task_no"))
public class ReviewTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "task_no", nullable = false, length = 32)
    @JsonIgnore
    private String taskNo;
    @Column(name = "tenant_id")
    private Long tenantId;
    @Column(name = "user_id")
    private Long userId;
    @Column(name = "manuscript_id")
    private Long manuscriptId;
    private String workflow;
    @Column(name = "target_venue")
    private String targetVenue;
    private String status;
    @Column(name = "source_version")
    private Integer sourceVersion;
    @Column(name = "candidate_version")
    private Integer candidateVersion;
    @Column(name = "checkpoint_agent")
    private String checkpointAgent;
    @Column(name = "fencing_token")
    private Long fencingToken = 0L;
    @Column(name = "idempotency_key")
    private String idempotencyKey;
    @Getter(onMethod_ = @JsonIgnore)
    @Column(name = "error_message")
    private String errorMessage;

    @JsonProperty("errorMessage")
    public String getPublicErrorMessage() {
        return PublicError.message(errorMessage);
    }

    @JsonProperty("errorCode")
    public String getErrorCode() {
        return PublicError.code(errorMessage);
    }

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @JsonIgnore
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    @JsonProperty("id")
    public String publicId() {
        if (taskNo != null && !taskNo.isBlank()) {
            return taskNo;
        }
        return id == null ? null : Long.toString(id);
    }

    @PrePersist
    void ensureTaskNo() {
        if (taskNo == null || taskNo.isBlank()) {
            taskNo = BusinessNos.nextTask();
        }
    }
}
