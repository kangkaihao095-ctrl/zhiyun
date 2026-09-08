package com.zhiyun.repo;

import com.zhiyun.domain.DocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentVersionRepo extends JpaRepository<DocumentVersion, Long> {
    Optional<DocumentVersion> findByManuscriptIdAndVersionNoAndTenantId(Long manuscriptId, Integer versionNo, Long tenantId);

    List<DocumentVersion> findByManuscriptIdAndTenantIdOrderByVersionNoAsc(Long manuscriptId, Long tenantId);
}
