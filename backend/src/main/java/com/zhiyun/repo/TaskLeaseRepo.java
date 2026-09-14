package com.zhiyun.repo;

import com.zhiyun.domain.TaskLease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface TaskLeaseRepo extends JpaRepository<TaskLease, Long> {
    List<TaskLease> findByTaskIdIn(Collection<Long> taskIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update TaskLease l set l.expireAt = :expireAt where l.taskId = :taskId and l.owner = :owner")
    int renew(@Param("taskId") Long taskId, @Param("owner") String owner, @Param("expireAt") Instant expireAt);
}
