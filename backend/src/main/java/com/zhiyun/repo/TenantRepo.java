package com.zhiyun.repo;

import com.zhiyun.domain.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantRepo extends JpaRepository<Tenant, Long> {
}
