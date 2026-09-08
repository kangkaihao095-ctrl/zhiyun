package com.zhiyun.repo;

import com.zhiyun.domain.QuotaLedger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface QuotaLedgerRepo extends JpaRepository<QuotaLedger, Long>, JpaSpecificationExecutor<QuotaLedger> {
    boolean existsByReasonAndRefId(String reason, String refId);

    List<QuotaLedger> findTop30ByTenantIdAndUserIdOrderByIdDesc(Long tenantId, Long userId);

    @Query("""
            SELECT COALESCE(SUM(-l.delta), 0)
            FROM QuotaLedger l
            WHERE l.tenantId = :tenantId AND l.userId = :userId AND l.delta < 0
            """)
    Integer sumConsumed(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    List<QuotaLedger> findByTenantIdAndUserIdAndRefIdOrderByIdDesc(Long tenantId, Long userId, String refId);

    @Query("""
            SELECT l FROM QuotaLedger l
            WHERE l.tenantId = :tenantId AND l.userId = :userId
              AND (:blank = 1
                   OR LOCATE(:q, LOWER(l.reason)) > 0
                   OR LOCATE(:q, LOWER(COALESCE(l.refId, ''))) > 0
                   OR LOCATE(:q, LOWER(COALESCE(l.ledgerNo, ''))) > 0
                   OR LOCATE(:q, LOWER(CONCAT(l.delta, ''))) > 0
                   OR (:hasReasons = 1 AND l.reason IN :reasons))
            """)
    Page<QuotaLedger> search(@Param("tenantId") Long tenantId,
                             @Param("userId") Long userId,
                             @Param("blank") int blank,
                             @Param("q") String q,
                             @Param("hasReasons") int hasReasons,
                             @Param("reasons") List<String> reasons,
                             Pageable pageable);
}
