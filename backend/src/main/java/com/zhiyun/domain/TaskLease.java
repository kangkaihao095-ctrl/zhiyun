package com.zhiyun.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "task_lease")
public class TaskLease {
    @Id
    @Column(name = "task_id")
    private Long taskId;
    @Column(name = "tenant_id")
    private Long tenantId;
    private String owner;
    @Column(name = "expire_at")
    private Instant expireAt;
    @Column(name = "fencing_token")
    private Long fencingToken;
}
