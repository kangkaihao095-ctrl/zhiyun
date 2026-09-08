package com.zhiyun.repo;

import com.zhiyun.domain.AppOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepo extends JpaRepository<AppOrder, Long>, JpaSpecificationExecutor<AppOrder> {
    List<AppOrder> findByTenantIdAndUserIdOrderByIdDesc(Long tenantId, Long userId);

    Optional<AppOrder> findByIdAndTenantIdAndUserId(Long id, Long tenantId, Long userId);

    Optional<AppOrder> findByOrderNoAndTenantIdAndUserId(String orderNo, Long tenantId, Long userId);

    Optional<AppOrder> findByOrderNo(String orderNo);

    @Query("""
            SELECT o FROM AppOrder o
            LEFT JOIN Plan p ON p.id = o.planId
            WHERE o.tenantId = :tenantId AND o.userId = :userId
              AND (:blank = 1
                   OR LOCATE(:q, LOWER(COALESCE(o.orderNo, ''))) > 0
                   OR LOCATE(:q, LOWER(CONCAT(o.id, ''))) > 0
                   OR LOCATE(:q, LOWER(o.status)) > 0
                   OR LOCATE(:q, LOWER(COALESCE(p.name, '灵活充值'))) > 0
                   OR (:hasStatus = 1 AND o.status IN :statuses))
            """)
    Page<AppOrder> search(@Param("tenantId") Long tenantId,
                          @Param("userId") Long userId,
                          @Param("blank") int blank,
                          @Param("q") String q,
                          @Param("hasStatus") int hasStatus,
                          @Param("statuses") List<String> statuses,
                          Pageable pageable);
}
