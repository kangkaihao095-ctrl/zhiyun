package com.zhiyun.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单次 Agent 执行内的工具调用账本。写入 agent_span.tool_calls，不进 C 端用量页。
 */
public final class ToolCallRecorder {
    private static final ThreadLocal<Map<String, Call>> TL = new ThreadLocal<>();

    private ToolCallRecorder() {
    }

    public static void open() {
        TL.set(new LinkedHashMap<>());
    }

    public static void record(String tool, boolean ok, long durationMs) {
        Map<String, Call> map = TL.get();
        if (map == null || tool == null || tool.isBlank()) {
            return;
        }
        Call row = map.computeIfAbsent(tool, key -> new Call());
        row.calls++;
        if (ok) {
            row.ok++;
        } else {
            row.failed++;
        }
        row.durationMs += Math.max(0, durationMs);
    }

    public static void extra(String tool, String key, int n) {
        Map<String, Call> map = TL.get();
        if (map == null || tool == null || tool.isBlank() || key == null || n < 0) {
            return;
        }
        Call row = map.computeIfAbsent(tool, k -> new Call());
        row.extra.merge(key, n, Integer::sum);
    }

    public static JsonNode snapshot(ObjectMapper mapper) {
        Map<String, Call> map = TL.get();
        ArrayNode arr = mapper.createArrayNode();
        if (map == null || map.isEmpty()) {
            return arr;
        }
        for (Map.Entry<String, Call> e : map.entrySet()) {
            Call row = e.getValue();
            ObjectNode node = arr.addObject();
            node.put("tool", e.getKey());
            node.put("calls", row.calls);
            node.put("ok", row.ok);
            node.put("failed", row.failed);
            node.put("durationMs", row.durationMs);
            for (Map.Entry<String, Integer> extra : row.extra.entrySet()) {
                node.put(extra.getKey(), extra.getValue());
            }
        }
        return arr;
    }

    public static String primaryTool() {
        Map<String, Call> map = TL.get();
        if (map == null || map.isEmpty()) {
            return null;
        }
        return map.keySet().iterator().next();
    }

    public static void close() {
        TL.remove();
    }

    private static final class Call {
        int calls;
        int ok;
        int failed;
        long durationMs;
        final Map<String, Integer> extra = new LinkedHashMap<>();
    }
}
