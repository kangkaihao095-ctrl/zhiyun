package com.zhiyun.repo;

import com.zhiyun.domain.ReviewTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface ReviewTaskRepo extends JpaRepository<ReviewTask, Long> {
    Optional<ReviewTask> findByIdAndTenantId(Long id, Long tenantId);

    Optional<ReviewTask> findByTaskNoAndTenantId(String taskNo, Long tenantId);

    Optional<ReviewTask> findByTaskNo(String taskNo);

    List<ReviewTask> findByManuscriptIdAndTenantIdOrderByIdDesc(Long manuscriptId, Long tenantId);

    List<ReviewTask> findByTenantIdAndUserIdOrderByIdDesc(Long tenantId, Long userId);

    Optional<ReviewTask> findByIdempotencyKey(String key);

    /** 取消：仅 PENDING/RUNNING 本租户任务转入 FAILED，并抬 fencing，让在跑 Worker 的旧 token 写入失败。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE ReviewTask t
            SET t.status = :failed, t.errorMessage = :message, t.fencingToken = t.fencingToken + 1
            WHERE t.id = :id AND t.tenantId = :tenantId AND t.status IN ('PENDING', 'RUNNING')
            """)
    int markCancelled(@Param("id") Long id,
                      @Param("tenantId") Long tenantId,
                      @Param("failed") String failed,
                      @Param("message") String message);

    /** 授予 lease 时抬 fencing，以库里的新值为准。已结束/已取消则 0 行。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE ReviewTask t SET t.fencingToken = t.fencingToken + 1
            WHERE t.id = :id AND t.status IN ('PENDING', 'RUNNING')
            """)
    int incrementFencing(@Param("id") Long id);

    /** 写入 checkpoint：CAS `fencing_token <= :token`，旧 token 0 行。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE ReviewTask t SET t.checkpointAgent = :agent
            WHERE t.id = :id AND t.fencingToken <= :token
            """)
    int casCheckpoint(@Param("id") Long id,
                      @Param("agent") String agent,
                      @Param("token") Long token);

    /** 状态流转：CAS `fencing_token <= :token`，只动 PENDING/RUNNING。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE ReviewTask t
            SET t.status = :status, t.candidateVersion = :candidateVersion
            WHERE t.id = :id AND t.fencingToken <= :token AND t.status IN ('PENDING', 'RUNNING')
            """)
    int casComplete(@Param("id") Long id,
                    @Param("status") String status,
                    @Param("candidateVersion") Integer candidateVersion,
                    @Param("token") Long token);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE ReviewTask t SET t.status = :failed, t.errorMessage = :message
            WHERE t.id = :id AND t.fencingToken <= :token AND t.status IN ('PENDING', 'RUNNING')
            """)
    int casFail(@Param("id") Long id,
                @Param("failed") String failed,
                @Param("message") String message,
                @Param("token") Long token);

    /** 从检查点重投：抬 fencing，释放旧 Worker 的写入权。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE ReviewTask t
            SET t.status = :pending, t.errorMessage = null, t.fencingToken = t.fencingToken + 1
            WHERE t.id = :id AND t.tenantId = :tenantId AND t.status = :failed
            """)
    int markRetry(@Param("id") Long id,
                  @Param("tenantId") Long tenantId,
                  @Param("pending") String pending,
                  @Param("failed") String failed);

    /** 抢到 lease 后进入 RUNNING；若用户已取消则 0 行，Worker 应退出。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            UPDATE ReviewTask t SET t.status = :running
            WHERE t.id = :id AND t.status IN ('PENDING', 'RUNNING')
            """)
    int markRunning(@Param("id") Long id, @Param("running") String running);

    @Query("""
            SELECT t FROM ReviewTask t
            LEFT JOIN Manuscript m ON m.id = t.manuscriptId
            WHERE t.tenantId = :tenantId AND t.userId = :userId
              AND (:statusBlank = 1 OR t.status = :status OR (:active = 1 AND t.status IN ('PENDING', 'RUNNING')))
              AND (:blank = 1
                   OR LOCATE(:q, LOWER(CONCAT(t.id, ''))) > 0
                   OR LOCATE(:q, LOWER(COALESCE(t.taskNo, ''))) > 0
                   OR LOCATE(:q, LOWER(COALESCE(m.title, ''))) > 0
                   OR LOCATE(:q, LOWER(t.workflow)) > 0
                   OR LOCATE(:q, LOWER(t.status)) > 0
                   OR (:hasWorkflows = 1 AND t.workflow IN :workflows)
                   OR (:hasStatuses = 1 AND t.status IN :statuses))
            """)
    Page<ReviewTask> search(@Param("tenantId") Long tenantId,
                            @Param("userId") Long userId,
                            @Param("blank") int blank,
                            @Param("q") String q,
                            @Param("hasWorkflows") int hasWorkflows,
                            @Param("workflows") List<String> workflows,
                            @Param("hasStatuses") int hasStatuses,
                            @Param("statuses") List<String> statuses,
                            @Param("statusBlank") int statusBlank,
                            @Param("status") String status,
                            @Param("active") int active,
                            Pageable pageable);
}
