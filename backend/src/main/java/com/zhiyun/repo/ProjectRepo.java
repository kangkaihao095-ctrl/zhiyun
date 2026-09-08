package com.zhiyun.repo;

import com.zhiyun.domain.ResearchProject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepo extends JpaRepository<ResearchProject, Long> {
    List<ResearchProject> findByTenantIdOrderByIdDesc(Long tenantId);

    Optional<ResearchProject> findByIdAndTenantId(Long id, Long tenantId);
}
