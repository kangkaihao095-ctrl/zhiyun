package com.zhiyun.repo;

import com.zhiyun.domain.QuotaAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface QuotaAccountRepo extends JpaRepository<QuotaAccount, QuotaAccount.Pk> {
    Optional<QuotaAccount> findByTenantIdAndUserId(Long tenantId, Long userId);
}
