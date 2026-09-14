package com.zhiyun.repo;

import com.zhiyun.domain.InboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface InboxMessageRepo extends JpaRepository<InboxMessage, Long> {
    List<InboxMessage> findByTenantIdAndUserIdOrderByIdDesc(Long tenantId, Long userId);

    long countByTenantIdAndUserIdAndReadAtIsNull(Long tenantId, Long userId);

    long countByTenantIdAndUserIdAndKind(Long tenantId, Long userId, String kind);

    Optional<InboxMessage> findByTenantIdAndUserIdAndKindAndRefId(Long tenantId, Long userId, String kind, String refId);

    Optional<InboxMessage> findByIdAndTenantIdAndUserId(Long id, Long tenantId, Long userId);

    List<InboxMessage> findByTenantIdAndUserIdAndReadAtIsNull(Long tenantId, Long userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update InboxMessage m set m.readAt = :now where m.tenantId = :tenantId and m.userId = :userId "
            + "and m.refId = :refId and m.readAt is null")
    int markReadByRef(@Param("tenantId") Long tenantId, @Param("userId") Long userId,
                      @Param("refId") String refId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update InboxMessage m set m.readAt = :now where m.tenantId = :tenantId and m.userId = :userId "
            + "and m.readAt is null")
    int markAllRead(@Param("tenantId") Long tenantId, @Param("userId") Long userId, @Param("now") Instant now);
}
