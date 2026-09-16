package com.zhiyun.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "agent_span", uniqueConstraints = @UniqueConstraint(name = "uk_span_task_agent", columnNames = {"task_id", "agent"}))
public class AgentSpan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "tenant_id")
    private Long tenantId;
    @Column(name = "task_id")
    private Long taskId;
    private String agent;
    @Column(name = "skill_version")
    private String skillVersion;
    @Column(name = "prompt_version")
    private String promptVersion;
    private String status;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "ended_at")
    private Instant endedAt;
    @Column(name = "duration_ms")
    private Long durationMs;
    /** LLM 第一个有效 token 的墙钟时间。失败或无 token 为空。 */
    @Column(name = "first_token_at")
    private Instant firstTokenAt;
    /** 相对该次 LLM 请求开始的首 token 耗时（ms）。流式为首个 delta，非流式为完整响应到达。 */
    @Column(name = "first_token_ms")
    private Long firstTokenMs;
    private Integer tokens;
    @Column(name = "fencing_token")
    private Long fencingToken;
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(nullable = false)
    private Boolean checkpoint = false;
    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(nullable = false)
    private Boolean skipped = false;
    @Column(name = "error_message")
    private String errorMessage;
    @Column(name = "error_code")
    private String errorCode;
    @Column(name = "tool_name")
    private String toolName;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_calls")
    private String toolCalls;
}
