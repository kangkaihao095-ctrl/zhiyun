package com.zhiyun.workflow;

import com.zhiyun.domain.Codes;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.harness.AgentIds;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户任务页本次用量。只保留 name / durationMs / tokens / quota 等消费字段。
 * fencing、skillVersion、errorCode、lease、工具名不进 DTO。不是 SLA。
 */
public final class ReviewUsage {
    private ReviewUsage() {
    }

    @SuppressWarnings("unchecked")
    public static int tokensOf(ReviewTask task, Map<String, Object> trace) {
        return sumTokens(visibleNodes(task, nodesOf(trace)));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> view(ReviewTask task, Map<String, Object> trace, int quota, boolean settled) {
        List<Map<String, Object>> raw = nodesOf(trace);
        List<Map<String, Object>> visible = visibleNodes(task, raw);
        allocateQuota(visible, quota);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("durationMs", sumDuration(visible, raw));
        out.put("tokens", sumTokens(visible));
        out.put("quota", Math.max(0, quota));
        out.put("settled", settled);
        out.put("inProgress", inProgress(task));
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Map<String, Object> node : visible) {
            nodes.add(strip(node));
        }
        out.put("nodes", nodes);
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> nodesOf(Map<String, Object> trace) {
        if (trace == null) {
            return List.of();
        }
        Object raw = trace.get("nodes");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                out.add((Map<String, Object>) map);
            }
        }
        return out;
    }

    static List<Map<String, Object>> visibleNodes(ReviewTask task, List<Map<String, Object>> raw) {
        List<String> agents = AgentIds.ofWorkflow(task == null ? "" : task.getWorkflow());
        Map<String, Map<String, Object>> byAgent = new LinkedHashMap<>();
        for (Map<String, Object> node : raw) {
            Object agent = node.get("agent");
            if (agent != null) {
                byAgent.put(String.valueOf(agent), node);
            }
        }
        boolean terminal = terminal(task);
        List<Map<String, Object>> out = new ArrayList<>();
        boolean sawCurrent = false;
        for (String agent : agents) {
            Map<String, Object> src = byAgent.get(agent);
            String status = statusOf(src, task, agent);
            boolean skipped = bool(src, "skipped");
            if (!terminal && Codes.PENDING.equals(status) && !skipped) {
                if (sawCurrent) {
                    continue;
                }
                status = Codes.RUNNING;
            }
            if (!terminal && Codes.RUNNING.equals(status)) {
                sawCurrent = true;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", displayName(src, agent));
            row.put("status", status);
            row.put("skipped", skipped);
            row.put("durationMs", src == null ? null : src.get("durationMs"));
            row.put("tokens", src == null ? null : src.get("tokens"));
            row.put("startedAt", src == null ? null : src.get("startedAt"));
            out.add(row);
            if (!terminal && Codes.RUNNING.equals(status)) {
                break;
            }
        }
        if (!terminal && out.isEmpty() && !agents.isEmpty()) {
            Map<String, Object> placeholder = new LinkedHashMap<>();
            placeholder.put("name", AgentIds.displayName(agents.get(0)));
            placeholder.put("status", Codes.RUNNING);
            placeholder.put("skipped", false);
            placeholder.put("durationMs", null);
            placeholder.put("tokens", null);
            out.add(placeholder);
        }
        return out;
    }

    static Map<String, Object> strip(Map<String, Object> node) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", node.get("name"));
        out.put("durationMs", asLong(node.get("durationMs")));
        out.put("tokens", asInt(node.get("tokens")));
        out.put("quota", node.get("quota"));
        out.put("skipped", Boolean.TRUE.equals(node.get("skipped")));
        out.put("status", node.get("status"));
        return out;
    }

    private static void allocateQuota(List<Map<String, Object>> nodes, int total) {
        int safe = Math.max(0, total);
        List<Integer> billed = new ArrayList<>();
        int weight = 0;
        for (int i = 0; i < nodes.size(); i++) {
            Map<String, Object> node = nodes.get(i);
            if (Boolean.TRUE.equals(node.get("skipped"))) {
                node.put("quota", 0);
                continue;
            }
            String status = String.valueOf(node.get("status"));
            if (Codes.RUNNING.equals(status) || Codes.PENDING.equals(status)) {
                node.put("quota", null);
                continue;
            }
            billed.add(i);
            weight += Math.max(0, intVal(node.get("tokens")));
        }
        if (billed.isEmpty()) {
            return;
        }
        if (weight <= 0) {
            for (int i = 0; i < billed.size(); i++) {
                nodes.get(billed.get(i)).put("quota", i == 0 ? safe : 0);
            }
            return;
        }
        int assigned = 0;
        for (int i = 0; i < billed.size(); i++) {
            int idx = billed.get(i);
            int share;
            if (i == billed.size() - 1) {
                share = Math.max(0, safe - assigned);
            } else {
                share = (int) (safe * (long) Math.max(0, intVal(nodes.get(idx).get("tokens"))) / weight);
                assigned += share;
            }
            nodes.get(idx).put("quota", share);
        }
    }

    private static int sumTokens(List<Map<String, Object>> nodes) {
        int sum = 0;
        for (Map<String, Object> node : nodes) {
            if (Boolean.TRUE.equals(node.get("skipped"))) {
                continue;
            }
            Integer n = asInt(node.get("tokens"));
            if (n != null) {
                sum += Math.max(0, n);
            }
        }
        return sum;
    }

    private static long sumDuration(List<Map<String, Object>> visible, List<Map<String, Object>> raw) {
        long sum = 0;
        for (Map<String, Object> node : visible) {
            Long ms = asLong(node.get("durationMs"));
            if (ms != null) {
                sum += Math.max(0, ms);
                continue;
            }
            if (Codes.RUNNING.equals(String.valueOf(node.get("status")))) {
                Instant started = instant(node.get("startedAt"));
                if (started != null) {
                    sum += Math.max(0, Duration.between(started, Instant.now()).toMillis());
                }
            }
        }
        if (sum == 0) {
            for (Map<String, Object> node : raw) {
                Long ms = asLong(node.get("durationMs"));
                if (ms != null && !bool(node, "skipped")) {
                    sum += Math.max(0, ms);
                }
            }
        }
        return sum;
    }

    private static String statusOf(Map<String, Object> src, ReviewTask task, String agent) {
        if (src != null && src.get("status") != null && !String.valueOf(src.get("status")).isBlank()) {
            return String.valueOf(src.get("status"));
        }
        if (inProgress(task)) {
            String checkpoint = task.getCheckpointAgent();
            List<String> agents = AgentIds.ofWorkflow(task.getWorkflow());
            int doneIdx = agents.indexOf(checkpoint == null ? "" : checkpoint);
            int i = agents.indexOf(agent);
            if (i >= 0 && i <= doneIdx) {
                return Codes.DONE;
            }
            if (i == doneIdx + 1) {
                return Codes.RUNNING;
            }
            return Codes.PENDING;
        }
        if (task != null && Codes.FAILED.equals(task.getStatus())) {
            return Codes.FAILED;
        }
        return Codes.DONE;
    }

    private static String displayName(Map<String, Object> src, String agent) {
        if (src != null && src.get("name") instanceof String name && !name.isBlank()) {
            return name;
        }
        return AgentIds.displayName(agent);
    }

    private static boolean inProgress(ReviewTask task) {
        if (task == null || task.getStatus() == null) {
            return false;
        }
        return Codes.PENDING.equals(task.getStatus()) || Codes.RUNNING.equals(task.getStatus());
    }

    private static boolean terminal(ReviewTask task) {
        if (task == null || task.getStatus() == null) {
            return true;
        }
        return Codes.DONE.equals(task.getStatus())
                || Codes.WAITING_ACCEPT.equals(task.getStatus())
                || Codes.FAILED.equals(task.getStatus());
    }

    private static boolean bool(Map<String, Object> src, String key) {
        return src != null && Boolean.TRUE.equals(src.get(key));
    }

    private static Instant instant(Object raw) {
        if (raw instanceof Instant instant) {
            return instant;
        }
        if (raw instanceof String s && !s.isBlank()) {
            try {
                return Instant.parse(s);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static Long asLong(Object raw) {
        if (raw instanceof Number n) {
            return n.longValue();
        }
        return null;
    }

    private static Integer asInt(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    private static int intVal(Object raw) {
        Integer n = asInt(raw);
        return n == null ? 0 : n;
    }
}
