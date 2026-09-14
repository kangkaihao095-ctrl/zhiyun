package com.zhiyun.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyun.agent.SkillRegistry;
import com.zhiyun.common.PublicError;
import com.zhiyun.domain.AgentSpan;
import com.zhiyun.domain.Artifact;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.domain.TaskLease;
import com.zhiyun.repo.AgentSpanRepo;
import com.zhiyun.repo.ArtifactRepo;
import com.zhiyun.repo.TaskLeaseRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Harness Trace：按 Agent 节点展示名称、状态、耗时、token、checkpoint、fencing、错误降级码。
 * 优先读 agent_span；没有则从 artifact / checkpoint / lease 拼只读时间线。不是 SLA 看板。
 */
@Service
public class AgentTraceService {
    private final AgentSpanRepo spanRepo;
    private final ArtifactRepo artifactRepo;
    private final TaskLeaseRepo taskLeaseRepo;
    private final ObjectMapper objectMapper;
    private final SkillRegistry skillRegistry;

    public AgentTraceService(AgentSpanRepo spanRepo, ArtifactRepo artifactRepo,
                             TaskLeaseRepo taskLeaseRepo, ObjectMapper objectMapper,
                             SkillRegistry skillRegistry) {
        this.spanRepo = spanRepo;
        this.artifactRepo = artifactRepo;
        this.taskLeaseRepo = taskLeaseRepo;
        this.objectMapper = objectMapper;
        this.skillRegistry = skillRegistry;
    }

    @Transactional
    public void begin(ReviewTask task, String agent, long fencingToken) {
        AgentSpan span = spanRepo.findByTaskIdAndAgent(task.getId(), agent).orElseGet(AgentSpan::new);
        if (Codes.DONE.equals(span.getStatus())) {
            return;
        }
        applyIdentity(span, task, agent, fencingToken);
        span.setStatus(Codes.RUNNING);
        span.setStartedAt(Instant.now());
        span.setEndedAt(null);
        span.setDurationMs(null);
        span.setTokens(null);
        span.setCheckpoint(false);
        span.setSkipped(false);
        span.setErrorMessage(null);
        span.setErrorCode(null);
        spanRepo.save(span);
    }

    /**
     * 重投时 Artifact 已在：记 checkpoint 跳过，不重复跑模型。已有 DONE span 只打 skipped。
     */
    @Transactional
    public void skip(ReviewTask task, String agent, long fencingToken) {
        AgentSpan span = spanRepo.findByTaskIdAndAgent(task.getId(), agent).orElseGet(AgentSpan::new);
        applyIdentity(span, task, agent, fencingToken);
        if (span.getStartedAt() == null) {
            Instant now = Instant.now();
            span.setStartedAt(now);
            span.setEndedAt(now);
            span.setDurationMs(0L);
            span.setTokens(0);
        }
        span.setStatus(Codes.DONE);
        span.setSkipped(true);
        span.setCheckpoint(agent.equals(task.getCheckpointAgent()));
        span.setErrorMessage(null);
        span.setErrorCode(null);
        spanRepo.save(span);
        markCheckpoint(task.getId(), task.getTenantId(), task.getCheckpointAgent());
    }

    @Transactional
    public void complete(ReviewTask task, String agent, int tokens, long fencingToken) {
        AgentSpan span = spanRepo.findByTaskIdAndAgent(task.getId(), agent).orElseGet(AgentSpan::new);
        Instant end = Instant.now();
        Instant start = span.getStartedAt() == null ? end : span.getStartedAt();
        applyIdentity(span, task, agent, fencingToken);
        span.setStatus(Codes.DONE);
        if (span.getStartedAt() == null) {
            span.setStartedAt(start);
        }
        span.setEndedAt(end);
        span.setDurationMs(Math.max(0, Duration.between(start, end).toMillis()));
        span.setTokens(Math.max(0, tokens));
        span.setCheckpoint(true);
        span.setSkipped(Boolean.TRUE.equals(span.getSkipped()));
        span.setErrorMessage(null);
        span.setErrorCode(null);
        applyToolCalls(span);
        spanRepo.save(span);
        markCheckpoint(task.getId(), task.getTenantId(), agent);
    }

    @Transactional
    public void fail(ReviewTask task, String agent, String error, long fencingToken) {
        AgentSpan span = spanRepo.findByTaskIdAndAgent(task.getId(), agent).orElseGet(AgentSpan::new);
        Instant end = Instant.now();
        Instant start = span.getStartedAt() == null ? end : span.getStartedAt();
        applyIdentity(span, task, agent, fencingToken);
        span.setStatus(Codes.FAILED);
        if (span.getStartedAt() == null) {
            span.setStartedAt(start);
        }
        span.setEndedAt(end);
        span.setDurationMs(Math.max(0, Duration.between(start, end).toMillis()));
        span.setCheckpoint(false);
        span.setSkipped(false);
        String raw = error == null ? "" : error;
        span.setErrorMessage(clip(PublicError.message(raw)));
        span.setErrorCode(PublicError.code(raw));
        applyToolCalls(span);
        spanRepo.save(span);
    }

    public Map<String, Object> timeline(ReviewTask task) {
        List<String> agents = AgentIds.ofWorkflow(task.getWorkflow());
        Map<String, AgentSpan> byAgent = new LinkedHashMap<>();
        for (AgentSpan span : spanRepo.findByTaskIdAndTenantIdOrderByIdAsc(task.getId(), task.getTenantId())) {
            byAgent.put(span.getAgent(), span);
        }
        List<Artifact> artifacts = artifactRepo.findByTaskIdAndTenantIdOrderByIdAsc(task.getId(), task.getTenantId());
        Optional<TaskLease> lease = taskLeaseRepo.findById(task.getId());
        String checkpoint = task.getCheckpointAgent();
        int doneIdx = agents.indexOf(checkpoint == null ? "" : checkpoint);

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int i = 0; i < agents.size(); i++) {
            String agent = agents.get(i);
            AgentSpan span = byAgent.get(agent);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agent", agent);
            row.put("name", AgentIds.displayName(agent));
            if (span != null) {
                row.put("status", spanStatus(span.getStatus(), task, i, doneIdx));
                row.put("startedAt", span.getStartedAt());
                row.put("endedAt", span.getEndedAt());
                row.put("durationMs", span.getDurationMs());
                row.put("tokens", span.getTokens());
                row.put("fencingToken", span.getFencingToken());
                row.put("checkpoint", agent.equals(checkpoint) || Boolean.TRUE.equals(span.getCheckpoint()));
                row.put("skipped", Boolean.TRUE.equals(span.getSkipped()));
                row.put("skillVersion", version(span.getSkillVersion()));
                row.put("promptVersion", version(span.getPromptVersion()));
                String raw = span.getErrorMessage() == null ? "" : span.getErrorMessage();
                row.put("errorMessage", PublicError.message(raw));
                String code = span.getErrorCode();
                row.put("errorCode", code == null || code.isBlank() ? PublicError.code(raw) : code);
                row.put("toolName", span.getToolName() == null ? "" : span.getToolName());
                row.put("toolCalls", parseToolCalls(span.getToolCalls()));
            } else {
                fillFromArtifacts(row, task, agent, i, doneIdx, artifacts, lease.orElse(null));
            }
            nodes.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.publicId());
        out.put("workflow", task.getWorkflow());
        out.put("taskStatus", task.getStatus());
        out.put("checkpointAgent", checkpoint);
        out.put("fencingToken", task.getFencingToken());
        out.put("caption", "运行观测 / 回归，非 SLA");
        lease.ifPresent(l -> {
            Map<String, Object> leaseRow = new LinkedHashMap<>();
            leaseRow.put("owner", l.getOwner());
            leaseRow.put("expireAt", l.getExpireAt());
            leaseRow.put("fencingToken", l.getFencingToken());
            out.put("lease", leaseRow);
        });
        out.put("nodes", nodes);
        return out;
    }

    private void applyIdentity(AgentSpan span, ReviewTask task, String agent, long fencingToken) {
        span.setTenantId(task.getTenantId());
        span.setTaskId(task.getId());
        span.setAgent(agent);
        span.setFencingToken(fencingToken);
        span.setSkillVersion(skillRegistry.skillVersion());
        span.setPromptVersion(skillRegistry.promptVersion());
    }

    private void fillFromArtifacts(Map<String, Object> row, ReviewTask task, String agent, int index, int doneIdx,
                                   List<Artifact> artifacts, TaskLease lease) {
        Artifact hit = null;
        for (Artifact artifact : artifacts) {
            if (agent.equals(artifact.getAgent())) {
                hit = artifact;
                break;
            }
        }
        String status;
        if (Codes.DONE.equals(task.getStatus()) || Codes.WAITING_ACCEPT.equals(task.getStatus())) {
            status = index <= Math.max(doneIdx, hit == null ? -1 : index) ? Codes.DONE : Codes.PENDING;
            if (hit != null) {
                status = Codes.DONE;
            }
        } else if (hit != null) {
            status = Codes.DONE;
        } else if (index == doneIdx + 1 && Codes.FAILED.equals(task.getStatus())) {
            status = Codes.FAILED;
        } else if (index == doneIdx + 1 && (Codes.RUNNING.equals(task.getStatus()) || Codes.PENDING.equals(task.getStatus()))) {
            status = Codes.RUNNING;
        } else if (index <= doneIdx) {
            status = Codes.DONE;
        } else {
            status = Codes.PENDING;
        }
        row.put("status", status);
        Instant produced = producedAt(hit);
        row.put("startedAt", produced);
        row.put("endedAt", produced);
        row.put("durationMs", null);
        row.put("tokens", null);
        row.put("fencingToken", hit != null ? hit.getFencingToken() : (lease != null && Codes.RUNNING.equals(status) ? lease.getFencingToken() : task.getFencingToken()));
        row.put("checkpoint", agent.equals(task.getCheckpointAgent()));
        row.put("skipped", false);
        row.put("skillVersion", version(null));
        row.put("promptVersion", version(null));
        String raw = Codes.FAILED.equals(status) ? (task.getErrorMessage() == null ? "" : task.getErrorMessage()) : "";
        row.put("errorMessage", PublicError.message(raw));
        row.put("errorCode", PublicError.code(raw));
        row.put("toolName", "");
        row.put("toolCalls", List.of());
    }

    private void applyToolCalls(AgentSpan span) {
        JsonNode calls = ToolCallRecorder.snapshot(objectMapper);
        if (calls == null || !calls.isArray() || calls.isEmpty()) {
            return;
        }
        span.setToolCalls(calls.toString());
        span.setToolName(ToolCallRecorder.primaryTool());
    }

    private List<Map<String, Object>> parseToolCalls(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (!node.isArray()) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode item : node) {
                if (item.isObject()) {
                    out.add(objectMapper.convertValue(item, Map.class));
                }
            }
            return out;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Instant producedAt(Artifact artifact) {
        if (artifact == null || artifact.getPayload() == null || artifact.getPayload().isBlank()) {
            return artifact == null ? null : artifact.getCreatedAt();
        }
        try {
            JsonNode root = objectMapper.readTree(artifact.getPayload());
            String raw = root.path("producedAt").asText("");
            if (!raw.isBlank()) {
                return Instant.parse(raw);
            }
        } catch (Exception ignored) {
            // 回退 created_at
        }
        return artifact.getCreatedAt();
    }

    private String spanStatus(String stored, ReviewTask task, int index, int doneIdx) {
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        if (index <= doneIdx) {
            return Codes.DONE;
        }
        if (index == doneIdx + 1 && Codes.FAILED.equals(task.getStatus())) {
            return Codes.FAILED;
        }
        return Codes.PENDING;
    }

    private void markCheckpoint(long taskId, long tenantId, String agent) {
        if (agent == null) {
            return;
        }
        for (AgentSpan span : spanRepo.findByTaskIdAndTenantIdOrderByIdAsc(taskId, tenantId)) {
            boolean on = agent.equals(span.getAgent());
            if (Boolean.TRUE.equals(span.getCheckpoint()) != on) {
                span.setCheckpoint(on);
                spanRepo.save(span);
            }
        }
    }

    private String version(String stored) {
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        return skillRegistry.skillVersion();
    }

    private static String clip(String error) {
        if (error == null) {
            return "";
        }
        return error.substring(0, Math.min(1000, error.length()));
    }
}
