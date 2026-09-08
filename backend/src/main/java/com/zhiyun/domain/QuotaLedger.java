package com.zhiyun.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.zhiyun.common.BusinessNos;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "quota_ledger", uniqueConstraints = @UniqueConstraint(name = "uk_ledger_no", columnNames = "ledger_no"))
public class QuotaLedger {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "ledger_no", nullable = false, length = 32)
    @JsonIgnore
    private String ledgerNo;
    @Column(name = "tenant_id")
    private Long tenantId;
    @Column(name = "user_id")
    private Long userId;
    private Integer delta;
    private String reason;
    @Column(name = "ref_id")
    private String refId;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @JsonIgnore
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    @JsonProperty("id")
    public String publicId() {
        if (ledgerNo != null && !ledgerNo.isBlank()) {
            return ledgerNo;
        }
        return id == null ? null : Long.toString(id);
    }

    @PrePersist
    void ensureLedgerNo() {
        if (ledgerNo == null || ledgerNo.isBlank()) {
            ledgerNo = BusinessNos.nextLedger();
        }
    }
}
