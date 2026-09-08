package com.zhiyun.repo;

import com.zhiyun.domain.Manuscript;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ManuscriptRepo extends JpaRepository<Manuscript, Long> {
    List<Manuscript> findByTenantIdOrderByIdDesc(Long tenantId);

    Optional<Manuscript> findByIdAndTenantId(Long id, Long tenantId);

    @Query("""
            SELECT m FROM Manuscript m
            WHERE m.tenantId = :tenantId
              AND (:projectBlank = 1 OR m.projectId = :projectId)
              AND (:blank = 1 OR LOCATE(:q, LOWER(COALESCE(m.title, ''))) > 0)
            """)
    Page<Manuscript> search(@Param("tenantId") Long tenantId,
                            @Param("projectBlank") int projectBlank,
                            @Param("projectId") Long projectId,
                            @Param("blank") int blank,
                            @Param("q") String q,
                            Pageable pageable);
}
