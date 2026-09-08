package com.zhiyun.repo;

import com.zhiyun.domain.AgentSpan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentSpanRepo extends JpaRepository<AgentSpan, Long> {
    List<AgentSpan> findByTaskIdAndTenantIdOrderByIdAsc(Long taskId, Long tenantId);

    Optional<AgentSpan> findByTaskIdAndAgent(Long taskId, String agent);
}
