package com.zhiyun.repo;

import com.zhiyun.domain.UserAgentModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserAgentModelRepo extends JpaRepository<UserAgentModel, Long> {
    Optional<UserAgentModel> findByTenantIdAndUserIdAndAgentId(Long tenantId, Long userId, String agentId);

    List<UserAgentModel> findByTenantIdAndUserId(Long tenantId, Long userId);
}
