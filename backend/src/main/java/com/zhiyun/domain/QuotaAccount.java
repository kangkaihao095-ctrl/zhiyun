package com.zhiyun.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
@Entity
@Table(name = "quota_account")
@IdClass(QuotaAccount.Pk.class)
public class QuotaAccount {
    @Id
    @Column(name = "tenant_id")
    private Long tenantId;
    @Id
    @Column(name = "user_id")
    private Long userId;
    private Integer balance;
    @Version
    private Long version;

    @Getter
    @Setter
    public static class Pk implements Serializable {
        private Long tenantId;
        private Long userId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Pk pk)) return false;
            return java.util.Objects.equals(tenantId, pk.tenantId) && java.util.Objects.equals(userId, pk.userId);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(tenantId, userId);
        }
    }
}
