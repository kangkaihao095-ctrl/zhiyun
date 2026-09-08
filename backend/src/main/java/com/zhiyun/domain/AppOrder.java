package com.zhiyun.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "app_order", uniqueConstraints = @UniqueConstraint(name = "uk_order_no", columnNames = "order_no"))
public class AppOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Column(name = "order_no", nullable = false, length = 32)
    @JsonIgnore
    private String orderNo;

    @Column(name = "tenant_id")
    private Long tenantId;
    @Column(name = "user_id")
    private Long userId;
    @Column(name = "plan_id")
    private Long planId;
    @Column(name = "quota_amount")
    private Integer quotaAmount = 0;
    private String status;
    @Column(name = "pay_channel")
    private String payChannel;
    @Column(name = "pay_txn_id")
    private String payTxnId;
    @Column(name = "amount_cents")
    private Integer amountCents;
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
        if (orderNo != null && !orderNo.isBlank()) {
            return orderNo;
        }
        return id == null ? null : Long.toString(id);
    }

    @JsonIgnore
    public String ledgerRef() {
        return "order-" + publicId();
    }

    @JsonIgnore
    public String legacyLedgerRef() {
        return id == null ? ledgerRef() : "order-" + id;
    }
}
