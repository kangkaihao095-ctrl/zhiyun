package com.zhiyun.repo;

import com.zhiyun.domain.Artifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ArtifactRepo extends JpaRepository<Artifact, Long> {
    List<Artifact> findByTaskIdAndTenantIdOrderByIdAsc(Long taskId, Long tenantId);

    Optional<Artifact> findByTaskIdAndAgentAndArtifactType(Long taskId, String agent, String artifactType);

    boolean existsByTaskIdAndAgent(Long taskId, String agent);
}
