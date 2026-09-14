package com.zhiyun.harness;

import com.zhiyun.common.ApiException;
import com.zhiyun.common.PublicError;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.domain.AgentSpan;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.domain.TaskLease;
import com.zhiyun.domain.Tenant;
import com.zhiyun.repo.AgentSpanRepo;
import com.zhiyun.repo.ReviewTaskRepo;
import com.zhiyun.repo.TaskLeaseRepo;
import com.zhiyun.repo.TenantRepo;
import com.zhiyun.repo.UserRepo;
import com.zhiyun.security.AuthUser;
import com.zhiyun.security.TenantContext;
import com.zhiyun.workflow.WorkflowCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内部观测台：全租户只读聚合 review_task / agent_span / task_lease。
 * 交付面是 /ops，不是 Prometheus / Grafana；不读进程内 Micrometer。
 * 仅 ops JWT（claim ops=true，且邮箱在运营名单）可进；C 端 JWT 即使是 demo 也 403。
 */
@Service
public class ObservabilityService {
    static final int MAX_WINDOW = 8000;
    static final int RECENT = 20;
    static final String CAPTION = "运行观测，非 SLA。评估集 Recall 与要点命中不在此页。无 TTFT。";
    static final String ALERT_CAPTION = "窗口规则，不是 SLA、不是 pager。";

    private final ReviewTaskRepo reviewTaskRepo;
    private final AgentSpanRepo agentSpanRepo;
    private final TaskLeaseRepo taskLeaseRepo;
    private final WorkflowCatalog workflowCatalog;
    private final UserRepo userRepo;
    private final TenantRepo tenantRepo;
    private final ZhiyunProperties properties;
    private final ObjectMapper objectMapper;

    public ObservabilityService(ReviewTaskRepo reviewTaskRepo, AgentSpanRepo agentSpanRepo,
                                TaskLeaseRepo taskLeaseRepo, WorkflowCatalog workflowCatalog,
                                UserRepo userRepo, TenantRepo tenantRepo, ZhiyunProperties properties,
                                ObjectMapper objectMapper) {
        this.reviewTaskRepo = reviewTaskRepo;
        this.agentSpanRepo = agentSpanRepo;
        this.taskLeaseRepo = taskLeaseRepo;
        this.workflowCatalog = workflowCatalog;
        this.userRepo = userRepo;
        this.tenantRepo = tenantRepo;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> dashboard() {
        return dashboard("7d", null);
    }

    public Map<String, Object> dashboard(String rangeRaw) {
        return dashboard(rangeRaw, null);
    }

    public Map<String, Object> dashboard(String rangeRaw, Long tenantIdRaw) {
        requireOpsSession();
        String range = normalizeRange(rangeRaw);
        Instant now = Instant.now();
        Instant since = sinceOf(range, now);
        Long focus = tenantIdRaw == null || tenantIdRaw <= 0 ? null : tenantIdRaw;
        List<ReviewTask> all = reviewTaskRepo.findByCreatedAtGreaterThanEqualOrderByIdDesc(since);
        boolean truncated = false;
        if (!"all".equals(range) && all.size() > MAX_WINDOW) {
            all = new ArrayList<>(all.subList(0, MAX_WINDOW));
            truncated = true;
        }
        List<ReviewTask> window = focus == null
                ? all
                : all.stream().filter(task -> focus.equals(task.getTenantId())).toList();

        Map<String, Integer> statusCounts = new LinkedHashMap<>();
        statusCounts.put("started", window.size());
        statusCounts.put("pending", 0);
        statusCounts.put("running", 0);
        statusCounts.put("waitingAccept", 0);
        statusCounts.put("succeeded", 0);
        statusCounts.put("failed", 0);
        Map<String, int[]> byWorkflow = new LinkedHashMap<>();
        Map<String, Integer> errorCodes = new LinkedHashMap<>();
        int fencingRaised = 0;
        int fencingRejected = 0;
        Set<Long> fencingTaskIds = new HashSet<>();
        Set<Long> windowIds = new HashSet<>();
        List<Long> allIds = new ArrayList<>();
        Set<Long> tenantIds = new HashSet<>();
        for (ReviewTask task : all) {
            allIds.add(task.getId());
            if (task.getTenantId() != null) {
                tenantIds.add(task.getTenantId());
            }
        }
        if (focus != null) {
            tenantIds.add(focus);
        }
        DurAgg taskDur = new DurAgg();
        for (ReviewTask task : window) {
            windowIds.add(task.getId());
            bumpStatus(statusCounts, task.getStatus());
            String status = task.getStatus();
            if (Codes.DONE.equals(status) || Codes.WAITING_ACCEPT.equals(status) || Codes.FAILED.equals(status)) {
                taskDur.add(durationMs(task, now));
            }
            int[] wf = byWorkflow.computeIfAbsent(task.getWorkflow(), key -> new int[]{0, 0, 0});
            wf[0]++;
            if (Codes.DONE.equals(task.getStatus()) || Codes.WAITING_ACCEPT.equals(task.getStatus())) {
                wf[1]++;
            } else if (Codes.FAILED.equals(task.getStatus())) {
                wf[2]++;
            }
            if (task.getFencingToken() != null && task.getFencingToken() > 1) {
                fencingRaised++;
            }
            if (Codes.FAILED.equals(task.getStatus())) {
                String code = PublicError.code(task.getErrorMessage());
                if (code == null || code.isBlank()) {
                    code = "unknown";
                }
                errorCodes.merge(code, 1, Integer::sum);
            }
        }

        Map<Long, AgentSpanAgg> spanAgg = new LinkedHashMap<>();
        Map<String, AgentRollup> agents = new LinkedHashMap<>();
        Map<String, ToolAgg> toolsByTool = new LinkedHashMap<>();
        Map<String, ToolAgg> toolsByAgent = new LinkedHashMap<>();
        int checkpointSkipped = 0;
        int tokenTotal = 0;
        int structuredFail = 0;
        int lookupDoi = 0;
        int lookupOk = 0;
        int notVerified = 0;
        int inventedDropped = 0;
        int ragPrivate = 0;
        int ragPublic = 0;
        int knnCalls = 0;
        int ragPrivateEmpty = 0;
        int ragPublicEmpty = 0;
        int knnEmpty = 0;
        DurAgg llmDur = new DurAgg();
        DurAgg ragPrivateDur = new DurAgg();
        DurAgg ragPublicDur = new DurAgg();
        DurAgg knnDur = new DurAgg();
        int toolFailed = 0;
        int spanFailed = 0;
        if (!allIds.isEmpty()) {
            for (AgentSpan row : agentSpanRepo.findByTaskIdIn(allIds)) {
                AgentSpanAgg agg = spanAgg.computeIfAbsent(row.getTaskId(), key -> new AgentSpanAgg());
                if (row.getTokens() != null) {
                    agg.tokens += row.getTokens();
                }
                if (row.getDurationMs() != null) {
                    agg.durationMs += row.getDurationMs();
                }
                if (Boolean.TRUE.equals(row.getSkipped())) {
                    agg.skipped++;
                }
                absorbCitation(agg, row.getToolCalls());
                if (!windowIds.contains(row.getTaskId())) {
                    continue;
                }
                if (row.getTokens() != null) {
                    tokenTotal += row.getTokens();
                }
                if (Boolean.TRUE.equals(row.getSkipped())) {
                    checkpointSkipped++;
                } else if (row.getDurationMs() != null) {
                    llmDur.add(row.getDurationMs());
                }
                if (Codes.FAILED.equals(row.getStatus())) {
                    spanFailed++;
                }
                if (isFencing(row.getErrorCode(), row.getErrorMessage())) {
                    fencingRejected++;
                    if (row.getTaskId() != null) {
                        fencingTaskIds.add(row.getTaskId());
                    }
                }
                if ("structured_output".equals(row.getErrorCode())
                        || "structured_output".equals(PublicError.code(row.getErrorMessage()))) {
                    structuredFail++;
                }
                AgentRollup roll = agents.computeIfAbsent(row.getAgent(), key -> new AgentRollup());
                roll.runs++;
                if (Codes.FAILED.equals(row.getStatus())) {
                    roll.failed++;
                    String code = row.getErrorCode();
                    if (code == null || code.isBlank()) {
                        code = PublicError.code(row.getErrorMessage());
                    }
                    if (code != null && !code.isBlank()) {
                        roll.errorCodes.merge(code, 1, Integer::sum);
                    }
                }
                if (Codes.DONE.equals(row.getStatus())) {
                    roll.succeeded++;
                }
                if (row.getDurationMs() != null) {
                    roll.durationMs += row.getDurationMs();
                    roll.durationN++;
                }
                if (row.getTokens() != null) {
                    roll.tokens += row.getTokens();
                }
                if (Boolean.TRUE.equals(row.getSkipped())) {
                    roll.skipped++;
                }
                for (JsonNode call : toolCallNodes(row.getToolCalls())) {
                    String tool = call.path("tool").asText("");
                    int calls = call.path("calls").asInt(0);
                    int ok = call.path("ok").asInt(0);
                    int failed = call.path("failed").asInt(0);
                    long dur = call.path("durationMs").asLong(0);
                    bumpTool(toolsByTool, tool, calls, ok, failed, dur);
                    bumpTool(toolsByAgent, AgentIds.displayName(row.getAgent()), calls, ok, failed, dur);
                    toolFailed += Math.max(0, failed);
                    lookupDoi += call.path("lookupDoi").asInt(0);
                    if ("AcademicSearch".equals(tool) && lookupDoi == 0 && calls > 0) {
                        lookupDoi += calls;
                        lookupOk += ok;
                    } else if ("AcademicSearch".equals(tool)) {
                        lookupOk += call.path("lookupOk").asInt(ok);
                    }
                    notVerified += call.path("notVerified").asInt(0);
                    inventedDropped += call.path("inventedDropped").asInt(0);
                    if (ToolPolicy.MANUSCRIPT_RETRIEVAL.equals(tool) || "RAG".equals(tool)) {
                        ragPrivate += calls;
                        ragPrivateEmpty += emptyHitsOf(call);
                        ragPrivateDur.add(dur);
                    }
                    if (ToolPolicy.KNOWLEDGE_RETRIEVAL.equals(tool) || "RAGPublic".equals(tool)) {
                        ragPublic += calls;
                        ragPublicEmpty += emptyHitsOf(call);
                        ragPublicDur.add(dur);
                    }
                    if ("kNN".equals(tool)) {
                        knnCalls += calls;
                        knnEmpty += emptyHitsOf(call);
                        knnDur.add(dur);
                    }
                }
            }
        }

        for (ReviewTask task : window) {
            if (!Codes.FAILED.equals(task.getStatus()) || fencingTaskIds.contains(task.getId())) {
                continue;
            }
            if (isFencing(PublicError.code(task.getErrorMessage()), task.getErrorMessage())) {
                fencingRejected++;
                fencingTaskIds.add(task.getId());
            }
        }

        Map<Long, TaskLease> leasesByTask = new LinkedHashMap<>();
        if (!allIds.isEmpty()) {
            for (TaskLease lease : taskLeaseRepo.findByTaskIdIn(allIds)) {
                leasesByTask.put(lease.getTaskId(), lease);
            }
        }

        Map<Long, String> tenantNames = tenantNames(tenantIds);
        List<Map<String, Object>> tenantRows = tenantRows(all, spanAgg, leasesByTask, now, tenantNames);

        List<Map<String, Object>> leases = new ArrayList<>();
        List<Map<String, Object>> recent = new ArrayList<>();
        int shown = 0;
        for (ReviewTask task : window) {
            TaskLease lease = leasesByTask.get(task.getId());
            boolean live = lease != null && lease.getExpireAt() != null && lease.getExpireAt().isAfter(now)
                    && (Codes.PENDING.equals(task.getStatus()) || Codes.RUNNING.equals(task.getStatus()));
            if (live) {
                leases.add(leaseRow(task, lease, tenantNames));
            }
            if (shown < RECENT) {
                recent.add(taskRow(task, spanAgg.get(task.getId()), live ? lease : null, now, tenantNames));
                shown++;
            }
        }

        List<Map<String, Object>> workflowRows = new ArrayList<>();
        for (Map.Entry<String, int[]> e : byWorkflow.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workflow", e.getKey());
            row.put("name", workflowCatalog.nameOf(e.getKey()));
            row.put("total", e.getValue()[0]);
            row.put("succeeded", e.getValue()[1]);
            row.put("failed", e.getValue()[2]);
            workflowRows.add(row);
        }

        List<Map<String, Object>> errorRows = errorRows(errorCodes);

        List<Map<String, Object>> agentRows = new ArrayList<>();
        for (String agent : List.of(
                AgentIds.CITATION, AgentIds.FIGURE, AgentIds.REVIEWER, AgentIds.STYLE,
                AgentIds.PLANNING, AgentIds.EXECUTION, AgentIds.VERIFICATION)) {
            AgentRollup roll = agents.get(agent);
            if (roll == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("agent", agent);
            row.put("name", AgentIds.displayName(agent));
            row.put("runs", roll.runs);
            row.put("failed", roll.failed);
            row.put("tokens", roll.tokens);
            row.put("skipped", roll.skipped);
            row.put("avgDurationMs", roll.durationN == 0 ? 0L : roll.durationMs / roll.durationN);
            row.put("successRatePct", roll.runs <= 0 ? 0 : (int) Math.round(roll.succeeded * 100.0 / roll.runs));
            row.put("errorCodes", errorRows(roll.errorCodes));
            agentRows.add(row);
        }

        int inProgress = statusCounts.get("pending") + statusCounts.get("running");
        int completed = statusCounts.get("succeeded") + statusCounts.get("waitingAccept");
        int started = statusCounts.get("started");
        int successRatePct = started <= 0 ? 0 : (int) Math.round(completed * 100.0 / started);
        int pending = statusCounts.get("pending");
        int running = statusCounts.get("running");
        int waitingAccept = statusCounts.get("waitingAccept");
        int emptyHits = ragPrivateEmpty + ragPublicEmpty + knnEmpty;
        DurAgg latency = llmDur.samples > 0 ? llmDur : taskDur;
        Long taskP50 = percentile(latency.values, 0.50);
        Long taskP95 = percentile(latency.values, 0.95);
        int activeTenants = tenantRows.size();
        int failedTenants = 0;
        for (Map<String, Object> row : tenantRows) {
            if (((Integer) row.get("failed")) > 0) {
                failedTenants++;
            }
        }

        Map<Long, Integer> tokensByTask = new LinkedHashMap<>();
        for (Map.Entry<Long, AgentSpanAgg> e : spanAgg.entrySet()) {
            tokensByTask.put(e.getKey(), e.getValue().tokens);
        }
        List<Map<String, Object>> trendRows = trendOf(window, range, now, tokensByTask);
        Integer tokenDeltaPct = tokenDeltaPct(trendRows);

        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("tasks", started);
        kpis.put("successRatePct", successRatePct);
        kpis.put("inProgress", inProgress);
        kpis.put("pending", pending);
        kpis.put("running", running);
        kpis.put("waitingAccept", waitingAccept);
        kpis.put("leasesHeld", leases.size());
        kpis.put("failed", statusCounts.get("failed"));
        kpis.put("checkpointSkipped", checkpointSkipped);
        kpis.put("tokens", tokenTotal);
        kpis.put("tenants", activeTenants);
        kpis.put("activeTenants", activeTenants);
        kpis.put("failedTenants", failedTenants);
        kpis.put("p50Ms", taskP50);
        kpis.put("p95Ms", taskP95);
        kpis.put("tokenDeltaPct", tokenDeltaPct);

        int leasesExpired = 0;
        for (ReviewTask task : window) {
            TaskLease lease = leasesByTask.get(task.getId());
            if (lease != null && lease.getExpireAt() != null && !lease.getExpireAt().isAfter(now)) {
                leasesExpired++;
            }
        }
        Map<String, Object> harness = new LinkedHashMap<>();
        harness.put("leasesHeld", leases.size());
        harness.put("leasesExpired", leasesExpired);
        harness.put("fencingRaised", fencingRaised);
        harness.put("fencingRejected", fencingRejected);
        harness.put("checkpointSkipped", checkpointSkipped);

        Map<String, Object> backlog = new LinkedHashMap<>();
        backlog.put("caption", "窗口内 PENDING / RUNNING / 待确认 / lease 持有。不是 MQ 管理面，不是 SLA。");
        backlog.put("pending", pending);
        backlog.put("running", running);
        backlog.put("waitingAccept", waitingAccept);
        backlog.put("inProgress", inProgress);
        backlog.put("leasesHeld", leases.size());

        Map<String, Object> crossTenant = new LinkedHashMap<>();
        crossTenant.put("caption", "窗口内全平台汇总，C 端不可见。不是 SLA。");
        crossTenant.put("activeTenants", activeTenants);
        crossTenant.put("failedTenants", failedTenants);
        crossTenant.put("tasks", all.size());

        List<Map<String, Object>> alertItems = alerts(started, statusCounts.get("failed"), fencingRejected,
                toolFailed, emptyHits, inProgress);
        Map<String, Object> alerts = new LinkedHashMap<>();
        alerts.put("caption", ALERT_CAPTION);
        alerts.put("items", alertItems);

        Map<String, Object> inbox = new LinkedHashMap<>();
        inbox.put("unread", 0);
        inbox.put("failed", 0);
        inbox.put("waitingAccept", 0);

        Map<String, Object> windowMeta = new LinkedHashMap<>();
        windowMeta.put("range", range);
        windowMeta.put("since", since);
        windowMeta.put("until", now);
        windowMeta.put("taskLimit", "all".equals(range) ? all.size() : MAX_WINDOW);
        windowMeta.put("truncated", truncated);
        windowMeta.put("recentLimit", RECENT);
        if ("all".equals(range)) {
            windowMeta.put("note", "至今未按任务数分页截断");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caption", CAPTION);
        out.put("window", windowMeta);
        out.put("scope", focus == null ? "all" : "tenant");
        out.put("tenantId", focus);
        out.put("tenantName", focus == null ? "" : tenantNames.getOrDefault(focus, "实验室 " + focus));
        out.put("kpis", kpis);
        out.put("tasks", statusCounts);
        out.put("tokens", tokenTotal);
        out.put("trend", trendRows);
        out.put("byWorkflow", workflowRows);
        out.put("errorCodes", errorRows);
        out.put("harness", harness);
        out.put("inbox", inbox);
        out.put("leases", leases);
        out.put("agents", agentRows);
        out.put("recent", recent);
        out.put("tenants", tenantRows);
        out.put("tools", toolBoard(toolsByTool, toolsByAgent, toolFailed, spanFailed));
        out.put("llm", llmBoard(tokenTotal, structuredFail, agents, llmDur, tokenDeltaPct));
        out.put("citation", citationBoard(lookupDoi, lookupOk, notVerified, inventedDropped));
        out.put("rag", ragBoard(ragPrivate, ragPublic, knnCalls, ragPrivateEmpty, ragPublicEmpty, knnEmpty,
                ragPrivateDur, ragPublicDur, knnDur));
        out.put("backlog", backlog);
        out.put("alerts", alerts);
        out.put("crossTenant", crossTenant);
        return out;
    }

    static String normalizeRange(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        if ("15m".equals(s)) {
            return "15m";
        }
        if ("1h".equals(s) || "60m".equals(s)) {
            return "1h";
        }
        if ("24h".equals(s) || "1d".equals(s)) {
            return "24h";
        }
        if ("15d".equals(s) || "半月".equals(s) || "half".equals(s)) {
            return "15d";
        }
        if ("6m".equals(s) || "半年".equals(s) || "halfyear".equals(s)) {
            return "6m";
        }
        if ("12m".equals(s) || "1y".equals(s) || "一年".equals(s) || "year".equals(s)) {
            return "12m";
        }
        if ("all".equals(s) || "since".equals(s) || "至今".equals(s)) {
            return "all";
        }
        return "7d";
    }

    static Duration durationOf(String range) {
        return switch (normalizeRange(range)) {
            case "15m" -> Duration.ofMinutes(15);
            case "1h" -> Duration.ofHours(1);
            case "24h" -> Duration.ofHours(24);
            case "15d" -> Duration.ofDays(15);
            case "6m" -> Duration.ofDays(183);
            case "12m" -> Duration.ofDays(366);
            case "all" -> Duration.ofDays(36500);
            default -> Duration.ofDays(7);
        };
    }

    private Instant sinceOf(String range, Instant now) {
        if ("all".equals(range)) {
            return reviewTaskRepo.findTopByOrderByCreatedAtAsc()
                    .map(ReviewTask::getCreatedAt)
                    .orElse(now);
        }
        YearMonth end = YearMonth.from(now.atZone(ZoneOffset.UTC));
        if ("6m".equals(range)) {
            return end.minusMonths(5).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        if ("12m".equals(range)) {
            return end.minusMonths(11).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        return now.minus(durationOf(range));
    }

    private void requireOpsSession() {
        AuthUser auth = TenantContext.require();
        if (!auth.ops()) {
            throw ApiException.forbidden("只有管理员可以打开观测台");
        }
        String email = userRepo.findById(auth.userId()).map(u -> u.getEmail()).orElse(auth.email());
        if (!properties.getOps().isOperator(email)) {
            throw ApiException.forbidden("只有管理员可以打开观测台");
        }
    }

    private Map<Long, String> tenantNames(Set<Long> ids) {
        Map<Long, String> names = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) {
            return names;
        }
        for (Tenant tenant : tenantRepo.findAllById(ids)) {
            names.put(tenant.getId(), tenant.getName() == null || tenant.getName().isBlank()
                    ? "实验室 " + tenant.getId()
                    : tenant.getName());
        }
        return names;
    }

    private List<Map<String, Object>> tenantRows(List<ReviewTask> all, Map<Long, AgentSpanAgg> spanAgg,
                                                 Map<Long, TaskLease> leasesByTask, Instant now,
                                                 Map<Long, String> tenantNames) {
        Map<Long, TenantAgg> aggs = new LinkedHashMap<>();
        for (ReviewTask task : all) {
            Long tenantId = task.getTenantId();
            if (tenantId == null) {
                continue;
            }
            TenantAgg agg = aggs.computeIfAbsent(tenantId, key -> new TenantAgg());
            agg.tasks++;
            bumpStatus(agg.status, task.getStatus());
            AgentSpanAgg span = spanAgg.get(task.getId());
            if (span != null) {
                agg.tokens += span.tokens;
            }
            TaskLease lease = leasesByTask.get(task.getId());
            boolean live = lease != null && lease.getExpireAt() != null && lease.getExpireAt().isAfter(now)
                    && (Codes.PENDING.equals(task.getStatus()) || Codes.RUNNING.equals(task.getStatus()));
            if (live) {
                agg.leasesHeld++;
            }
            if (Codes.FAILED.equals(task.getStatus())) {
                String code = PublicError.code(task.getErrorMessage());
                if (code == null || code.isBlank()) {
                    code = "unknown";
                }
                agg.errorCodes.merge(code, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Long, TenantAgg> e : aggs.entrySet()) {
            TenantAgg agg = e.getValue();
            int completed = agg.status.getOrDefault("succeeded", 0) + agg.status.getOrDefault("waitingAccept", 0);
            int rate = agg.tasks <= 0 ? 0 : (int) Math.round(completed * 100.0 / agg.tasks);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tenantId", e.getKey());
            row.put("tenantName", tenantNames.getOrDefault(e.getKey(), "实验室 " + e.getKey()));
            row.put("tasks", agg.tasks);
            row.put("sharePct", all.isEmpty() ? 0 : (int) Math.round(agg.tasks * 100.0 / all.size()));
            row.put("successRatePct", rate);
            row.put("failed", agg.status.getOrDefault("failed", 0));
            row.put("inProgress", agg.status.getOrDefault("pending", 0) + agg.status.getOrDefault("running", 0));
            row.put("leasesHeld", agg.leasesHeld);
            row.put("tokens", agg.tokens);
            row.put("errorCodes", errorRows(agg.errorCodes));
            rows.add(row);
        }
        rows.sort(Comparator
                .comparingInt((Map<String, Object> row) -> (Integer) row.get("tasks")).reversed()
                .thenComparingLong(row -> (Long) row.get("tenantId")));
        return rows;
    }

    private static List<Map<String, Object>> errorRows(Map<String, Integer> errorCodes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> e : errorCodes.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", e.getKey());
            row.put("count", e.getValue());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> trend(List<ReviewTask> window, String range, Instant now) {
        return trendOf(window, range, now, Map.of());
    }

    /** 近半年 / 近一年 / 至今按月（YYYY-MM）；近半月按天；短窗按分钟或小时。 */
    static List<Map<String, Object>> trendOf(List<ReviewTask> window, String range, Instant now) {
        return trendOf(window, range, now, Map.of());
    }

    static List<Map<String, Object>> trendOf(List<ReviewTask> window, String range, Instant now,
                                            Map<Long, Integer> tokensByTask) {
        Map<Long, Integer> tokens = tokensByTask == null ? Map.of() : tokensByTask;
        if ("6m".equals(range) || "12m".equals(range) || "all".equals(range)) {
            return trendByMonth(window, range, now, tokens);
        }
        int buckets;
        Duration step;
        DateTimeFormatter fmt;
        if ("15m".equals(range)) {
            buckets = 15;
            step = Duration.ofMinutes(1);
            fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);
        } else if ("1h".equals(range)) {
            buckets = 12;
            step = Duration.ofMinutes(5);
            fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);
        } else if ("24h".equals(range)) {
            buckets = 24;
            step = Duration.ofHours(1);
            fmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);
        } else if ("15d".equals(range)) {
            buckets = 15;
            step = Duration.ofDays(1);
            fmt = DateTimeFormatter.ofPattern("MM-dd").withZone(ZoneOffset.UTC);
        } else {
            buckets = 7;
            step = Duration.ofDays(1);
            fmt = DateTimeFormatter.ofPattern("MM-dd").withZone(ZoneOffset.UTC);
        }
        long stepMs = Math.max(1L, step.toMillis());
        Instant origin = Instant.ofEpochMilli((now.toEpochMilli() / stepMs) * stepMs)
                .minus(step.multipliedBy(buckets - 1L));
        List<Map<String, Object>> rows = new ArrayList<>();
        int[] started = new int[buckets];
        int[] succeeded = new int[buckets];
        int[] failed = new int[buckets];
        int[] tokenSum = new int[buckets];
        for (ReviewTask task : window == null ? List.<ReviewTask>of() : window) {
            Instant created = task.getCreatedAt();
            if (created == null) {
                continue;
            }
            int idx = (int) (Duration.between(origin, created).toMillis() / stepMs);
            if (idx < 0 || idx >= buckets) {
                continue;
            }
            started[idx]++;
            tokenSum[idx] += tokenOf(task, tokens);
            if (Codes.DONE.equals(task.getStatus()) || Codes.WAITING_ACCEPT.equals(task.getStatus())) {
                succeeded[idx]++;
            } else if (Codes.FAILED.equals(task.getStatus())) {
                failed[idx]++;
            }
        }
        for (int i = 0; i < buckets; i++) {
            Instant t = origin.plus(step.multipliedBy(i));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bucket", fmt.format(t));
            row.put("at", t);
            row.put("started", started[i]);
            row.put("succeeded", succeeded[i]);
            row.put("failed", failed[i]);
            row.put("tokens", tokenSum[i]);
            rows.add(row);
        }
        return rows;
    }

    static List<Map<String, Object>> trendByMonth(List<ReviewTask> window, String range, Instant now) {
        return trendByMonth(window, range, now, Map.of());
    }

    static List<Map<String, Object>> trendByMonth(List<ReviewTask> window, String range, Instant now,
                                                 Map<Long, Integer> tokensByTask) {
        Map<Long, Integer> tokens = tokensByTask == null ? Map.of() : tokensByTask;
        YearMonth end = YearMonth.from(now.atZone(ZoneOffset.UTC));
        YearMonth start;
        if ("6m".equals(range)) {
            start = end.minusMonths(5);
        } else if ("12m".equals(range)) {
            start = end.minusMonths(11);
        } else {
            YearMonth oldest = end;
            boolean any = false;
            for (ReviewTask task : window == null ? List.<ReviewTask>of() : window) {
                if (task.getCreatedAt() == null) {
                    continue;
                }
                YearMonth ym = YearMonth.from(task.getCreatedAt().atZone(ZoneOffset.UTC));
                if (!any || ym.isBefore(oldest)) {
                    oldest = ym;
                    any = true;
                }
            }
            start = any ? oldest : end;
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM");
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth ym = start; !ym.isAfter(end); ym = ym.plusMonths(1)) {
            months.add(ym);
        }
        Map<YearMonth, int[]> counts = new LinkedHashMap<>();
        for (YearMonth ym : months) {
            counts.put(ym, new int[4]);
        }
        for (ReviewTask task : window == null ? List.<ReviewTask>of() : window) {
            if (task.getCreatedAt() == null) {
                continue;
            }
            YearMonth ym = YearMonth.from(task.getCreatedAt().atZone(ZoneOffset.UTC));
            int[] slot = counts.get(ym);
            if (slot == null) {
                continue;
            }
            slot[0]++;
            slot[3] += tokenOf(task, tokens);
            if (Codes.DONE.equals(task.getStatus()) || Codes.WAITING_ACCEPT.equals(task.getStatus())) {
                slot[1]++;
            } else if (Codes.FAILED.equals(task.getStatus())) {
                slot[2]++;
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (YearMonth ym : months) {
            int[] slot = counts.get(ym);
            Map<String, Object> row = new LinkedHashMap<>();
            Instant at = ym.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            row.put("bucket", ym.format(fmt));
            row.put("at", at);
            row.put("started", slot[0]);
            row.put("succeeded", slot[1]);
            row.put("failed", slot[2]);
            row.put("tokens", slot[3]);
            rows.add(row);
        }
        return rows;
    }

    private Map<String, Object> taskRow(ReviewTask task, AgentSpanAgg agg, TaskLease lease, Instant now,
                                        Map<Long, String> tenantNames) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", task.publicId());
        row.put("tenantId", task.getTenantId());
        row.put("tenantName", tenantNames.getOrDefault(task.getTenantId(),
                task.getTenantId() == null ? "" : "实验室 " + task.getTenantId()));
        row.put("workflow", task.getWorkflow());
        row.put("workflowName", workflowCatalog.nameOf(task.getWorkflow()));
        row.put("status", task.getStatus());
        row.put("checkpointAgent", task.getCheckpointAgent());
        row.put("fencingToken", task.getFencingToken() == null ? 0L : task.getFencingToken());
        row.put("createdAt", task.getCreatedAt());
        row.put("updatedAt", task.getUpdatedAt());
        long wall = durationMs(task, now);
        long agentMs = agg == null ? 0L : agg.durationMs;
        row.put("durationMs", wall > 0 ? wall : agentMs);
        row.put("agentDurationMs", agentMs);
        row.put("tokens", agg == null ? 0 : agg.tokens);
        row.put("skipped", agg == null ? 0 : agg.skipped);
        row.put("errorCode", Codes.FAILED.equals(task.getStatus()) ? PublicError.code(task.getErrorMessage()) : "");
        row.put("errorMessage", PublicError.message(task.getErrorMessage()));
        if (lease != null) {
            Map<String, Object> leaseMap = new LinkedHashMap<>();
            leaseMap.put("owner", lease.getOwner());
            leaseMap.put("expireAt", lease.getExpireAt());
            leaseMap.put("fencingToken", lease.getFencingToken());
            row.put("lease", leaseMap);
        }
        return row;
    }

    private Map<String, Object> leaseRow(ReviewTask task, TaskLease lease, Map<Long, String> tenantNames) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", task.publicId());
        row.put("tenantId", task.getTenantId());
        row.put("tenantName", tenantNames.getOrDefault(task.getTenantId(),
                task.getTenantId() == null ? "" : "实验室 " + task.getTenantId()));
        row.put("status", task.getStatus());
        row.put("owner", lease.getOwner());
        row.put("expireAt", lease.getExpireAt());
        row.put("fencingToken", lease.getFencingToken());
        return row;
    }

    private static long durationMs(ReviewTask task, Instant now) {
        Instant start = task.getCreatedAt();
        if (start == null) {
            return 0L;
        }
        Instant end = task.getUpdatedAt();
        if (Codes.PENDING.equals(task.getStatus()) || Codes.RUNNING.equals(task.getStatus()) || end == null) {
            end = now;
        }
        long ms = Duration.between(start, end).toMillis();
        return Math.max(0, ms);
    }

    private static void bumpStatus(Map<String, Integer> counts, String status) {
        if (Codes.PENDING.equals(status)) {
            counts.merge("pending", 1, Integer::sum);
        } else if (Codes.RUNNING.equals(status)) {
            counts.merge("running", 1, Integer::sum);
        } else if (Codes.WAITING_ACCEPT.equals(status)) {
            counts.merge("waitingAccept", 1, Integer::sum);
        } else if (Codes.DONE.equals(status)) {
            counts.merge("succeeded", 1, Integer::sum);
        } else if (Codes.FAILED.equals(status)) {
            counts.merge("failed", 1, Integer::sum);
        }
    }

    private Map<String, Object> toolBoard(Map<String, ToolAgg> byTool, Map<String, ToolAgg> byAgent,
                                         int failed, int spanFailed) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caption", "窗口内 span.tool_calls。没有调用就是 0。不是 SLA。");
        out.put("failed", failed);
        out.put("spanFailed", spanFailed);
        out.put("byTool", toolRows(byTool));
        out.put("byAgent", toolRows(byAgent));
        return out;
    }

    private List<Map<String, Object>> toolRows(Map<String, ToolAgg> map) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, ToolAgg> e : map.entrySet()) {
            ToolAgg agg = e.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", e.getKey());
            row.put("calls", agg.calls);
            row.put("ok", agg.ok);
            row.put("failed", agg.failed);
            row.put("successRatePct", agg.calls <= 0 ? 0 : (int) Math.round(agg.ok * 100.0 / agg.calls));
            row.put("avgDurationMs", agg.calls <= 0 ? 0L : agg.durationMs / agg.calls);
            rows.add(row);
        }
        rows.sort(Comparator.comparingInt((Map<String, Object> row) -> (Integer) row.get("calls")).reversed());
        return rows;
    }

    private Map<String, Object> llmBoard(int tokenTotal, int structuredFail, Map<String, AgentRollup> agents,
                                        DurAgg duration, Integer tokenDeltaPct) {
        int runs = 0;
        for (AgentRollup roll : agents.values()) {
            runs += roll.runs - roll.skipped;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caption", "窗口内 Agent span。无 TTFT。prompt/completion 窗口无拆分则为 0。");
        out.put("calls", runs);
        out.put("tokens", tokenTotal);
        out.put("promptTokens", 0);
        out.put("completionTokens", 0);
        out.put("structuredFail", structuredFail);
        out.put("tokenDeltaPct", tokenDeltaPct);
        putDuration(out, duration);
        putPercentile(out, duration);
        return out;
    }

    private Map<String, Object> citationBoard(int lookup, int ok, int notVerified, int invented) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caption", "Java lookupDoi。没有调用就是 0。不是 SLA。");
        out.put("lookupDoi", lookup);
        out.put("lookupOk", ok);
        out.put("notVerified", notVerified);
        out.put("notVerifiedPct", lookup <= 0 ? 0 : (int) Math.round(notVerified * 100.0 / Math.max(lookup, notVerified)));
        out.put("inventedDoiDropped", invented);
        return out;
    }

    private Map<String, Object> ragBoard(int priv, int pub, int knn, int privEmpty, int pubEmpty, int knnEmpty,
                                        DurAgg privateDur, DurAgg publicDur, DurAgg knnDur) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caption", "运行观测，非 SLA。空召回是窗口内 hits=0 次数，不是召回率。");
        out.put("privateRetrievals", priv);
        out.put("publicRetrievals", pub);
        out.put("knn", knn);
        out.put("emptyHits", privEmpty + pubEmpty + knnEmpty);
        out.put("privateEmptyHits", privEmpty);
        out.put("publicEmptyHits", pubEmpty);
        out.put("knnEmptyHits", knnEmpty);
        out.put("hasDuration", privateDur.samples > 0 || publicDur.samples > 0 || knnDur.samples > 0);
        putNamedDuration(out, "private", privateDur);
        putNamedDuration(out, "public", publicDur);
        putNamedDuration(out, "knn", knnDur);
        return out;
    }

    private static void putDuration(Map<String, Object> out, DurAgg duration) {
        out.put("durationSamples", duration.samples);
        if (duration.samples <= 0) {
            out.put("durationMs", null);
            out.put("avgDurationMs", null);
            return;
        }
        out.put("durationMs", duration.durationMs);
        out.put("avgDurationMs", duration.avg());
    }

    private static void putPercentile(Map<String, Object> out, DurAgg duration) {
        out.put("p50Ms", percentile(duration.values, 0.50));
        out.put("p95Ms", percentile(duration.values, 0.95));
    }

    private static void putNamedDuration(Map<String, Object> out, String prefix, DurAgg duration) {
        if (duration.samples <= 0) {
            out.put(prefix + "AvgDurationMs", null);
            out.put(prefix + "DurationMs", null);
            return;
        }
        out.put(prefix + "AvgDurationMs", duration.avg());
        out.put(prefix + "DurationMs", duration.durationMs);
    }

    private static boolean isFencing(String code, String message) {
        if ("fencing".equals(code)) {
            return true;
        }
        String text = message == null ? "" : message.toLowerCase();
        return text.contains("fencing");
    }

    private List<JsonNode> toolCallNodes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (!node.isArray()) {
                return List.of();
            }
            List<JsonNode> out = new ArrayList<>();
            node.forEach(out::add);
            return out;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void absorbCitation(AgentSpanAgg agg, String raw) {
        for (JsonNode call : toolCallNodes(raw)) {
            agg.lookupDoi += call.path("lookupDoi").asInt(0);
            agg.notVerified += call.path("notVerified").asInt(0);
            agg.inventedDropped += call.path("inventedDropped").asInt(0);
        }
    }

    private static void bumpTool(Map<String, ToolAgg> map, String name, int calls, int ok, int failed, long durationMs) {
        if (name == null || name.isBlank() || calls <= 0) {
            return;
        }
        ToolAgg agg = map.computeIfAbsent(name, key -> new ToolAgg());
        agg.calls += calls;
        agg.ok += ok;
        agg.failed += failed;
        agg.durationMs += durationMs;
    }

    private static final class AgentSpanAgg {
        int tokens;
        long durationMs;
        int skipped;
        int lookupDoi;
        int notVerified;
        int inventedDropped;
    }

    private static final class AgentRollup {
        int runs;
        int succeeded;
        int failed;
        int skipped;
        int tokens;
        long durationMs;
        int durationN;
        final Map<String, Integer> errorCodes = new LinkedHashMap<>();
    }

    private static final class ToolAgg {
        int calls;
        int ok;
        int failed;
        long durationMs;
    }

    private static final class TenantAgg {
        int tasks;
        int tokens;
        int leasesHeld;
        final Map<String, Integer> status = new LinkedHashMap<>();
        final Map<String, Integer> errorCodes = new LinkedHashMap<>();
    }

    private static final class DurAgg {
        long durationMs;
        int samples;
        final List<Long> values = new ArrayList<>();

        void add(long dur) {
            if (dur > 0) {
                durationMs += dur;
                samples++;
                values.add(dur);
            }
        }

        Long avg() {
            return samples == 0 ? null : durationMs / samples;
        }
    }

    static Long percentile(List<Long> values, double p) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        int idx = (int) Math.ceil(p * n) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= n) {
            idx = n - 1;
        }
        return sorted.get(idx);
    }

    static int emptyHitsOf(JsonNode call) {
        if (call == null) {
            return 0;
        }
        if (call.has("emptyHits")) {
            return Math.max(0, call.path("emptyHits").asInt(0));
        }
        if (call.has("hits") && call.get("hits").asInt(-1) == 0 && call.path("calls").asInt(0) <= 1) {
            return 1;
        }
        return 0;
    }

    static Integer tokenDeltaPct(List<Map<String, Object>> trendRows) {
        if (trendRows == null || trendRows.size() < 2) {
            return null;
        }
        int last = intOf(trendRows.get(trendRows.size() - 1).get("tokens"));
        int prev = intOf(trendRows.get(trendRows.size() - 2).get("tokens"));
        if (prev <= 0) {
            return null;
        }
        return (int) Math.round((last - prev) * 100.0 / prev);
    }

    static List<Map<String, Object>> alerts(int started, int failed, int fencingRejected, int toolFailed,
                                            int emptyHits, int inProgress) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (started >= 5 && failed * 100 >= started * 30) {
            items.add(alert("failed-share", "danger", "失败码骤增", failed));
        }
        if (fencingRejected >= 1) {
            items.add(alert("fencing", "danger", "fencing 拒绝", fencingRejected));
        }
        if (toolFailed >= 3) {
            items.add(alert("tool-fail", "warn", "工具失败", toolFailed));
        }
        if (emptyHits >= 1) {
            items.add(alert("empty-rag", "warn", "RAG 空召回", emptyHits));
        }
        if (inProgress >= 5) {
            items.add(alert("backlog", "warn", "队列积压", inProgress));
        }
        return items;
    }

    private static Map<String, Object> alert(String id, String tone, String title, int count) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("tone", tone);
        row.put("title", title);
        row.put("count", count);
        row.put("caption", ALERT_CAPTION);
        return row;
    }

    private static int tokenOf(ReviewTask task, Map<Long, Integer> tokensByTask) {
        if (task == null || task.getId() == null || tokensByTask == null) {
            return 0;
        }
        return tokensByTask.getOrDefault(task.getId(), 0);
    }

    private static int intOf(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }
}
