package com.zhiyun.repo;

import com.zhiyun.domain.ChunkHash;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ChunkHashRepo extends JpaRepository<ChunkHash, Long> {
    List<ChunkHash> findByManuscriptIdAndVersionNoAndTenantId(Long manuscriptId, Integer versionNo, Long tenantId);

    List<ChunkHash> findByManuscriptIdAndTenantId(Long manuscriptId, Long tenantId);

    @Query("select c from ChunkHash c where c.tenantId = 0")
    List<ChunkHash> findPublicChunks();

    @Modifying
    @Transactional
    @Query("delete from ChunkHash c where c.tenantId = 0")
    void deletePublicChunks();
}
