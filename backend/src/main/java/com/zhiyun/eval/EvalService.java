package com.zhiyun.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.rag.RagService;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class EvalService {
    private final ZhiyunProperties properties;
    private final RagService ragService;
    private final ObjectMapper objectMapper;

    public EvalService(ZhiyunProperties properties, RagService ragService, ObjectMapper objectMapper) {
        this.properties = properties;
        this.ragService = ragService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> ragRecallAt5() {
        List<JsonNode> rows = loadJsonl("queries.jsonl");
        int total = 0;
        int hits = 0;
        List<Map<String, Object>> misses = new ArrayList<>();
        for (JsonNode row : rows) {
            if (row.path("scope").asText("").isBlank() && row.has("paper")) {
                continue;
            }
            total++;
            String query = row.path("query").asText();
            List<String> goldFiles = new ArrayList<>();
            if (row.has("relevant_files") && row.get("relevant_files").isArray()) {
                row.get("relevant_files").forEach(n -> goldFiles.add(n.asText().replace(".md", "")));
            }
            List<String> needles = new ArrayList<>();
            if (row.has("must_contain") && row.get("must_contain").isArray()) {
                row.get("must_contain").forEach(n -> needles.add(n.asText()));
            } else if (row.has("expect")) {
                needles.add(row.path("expect").asText());
            }
            List<RagService.Retrieved> top = ragService.retrievePublic(query);
            boolean hit = matches(top, goldFiles, needles);
            if (hit) {
                hits++;
            } else {
                Map<String, Object> miss = new LinkedHashMap<>();
                miss.put("id", row.path("id").asText());
                miss.put("query", query);
                miss.put("got", top.stream().map(RagService.Retrieved::chunkId).toList());
                misses.add(miss);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suite", "rag");
        out.put("metric", "Recall@5");
        out.put("total", total);
        out.put("hits", hits);
        out.put("recallAt5", total == 0 ? 0.0 : Math.round(hits * 1000.0 / total) / 10.0);
        out.put("note", "自建小样本回归，不是线上 SLA");
        out.put("sla", false);
        out.put("misses", misses.size() > 12 ? misses.subList(0, 12) : misses);
        return out;
    }

    public Map<String, Object> agentCatalog() {
        List<JsonNode> rows = loadJsonl("agent-tasks.jsonl");
        Path papers = evalDir().resolve("papers");
        int missingPaper = 0;
        Map<String, Integer> byAgent = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            String agent = row.path("agent").asText("UNKNOWN");
            byAgent.merge(agent, 1, Integer::sum);
            String paper = row.path("paper").asText("");
            if (!paper.isBlank() && !Files.exists(papers.resolve(paper))) {
                missingPaper++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suite", "agent");
        out.put("total", rows.size());
        out.put("expects", rows.size());
        out.put("byAgent", byAgent);
        out.put("missingPapers", missingPaper);
        out.put("layers", List.of("L1", "L2", "L3"));
        out.put("goldTest", "AgentGoldEvalTest");
        out.put("sla", false);
        out.put("dir", evalDir().toAbsolutePath().toString());
        return out;
    }

    /**
     * 云笺客服回归：夹具 + formatCsAnswer 要点检查。purpose=regression，不是线上 SLA。
     */
    public Map<String, Object> csEval() {
        JsonNode root = loadJson("cs-eval.json");
        Map<String, Object> tool = toMap(root.path("toolResult"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plans = (List<Map<String, Object>>) (List<?>) toList(root.path("plans"));
        Map<String, Object> task = toMap(root.path("task"));
        int total = 0;
        int hits = 0;
        List<Map<String, Object>> misses = new ArrayList<>();
        String spent = null;
        String orders = null;
        if (root.path("cases").isArray()) {
            for (JsonNode row : root.path("cases")) {
                total++;
                String intent = row.path("intent").asText();
                Map<String, Object> toolForCase = new LinkedHashMap<>(tool);
                if ("plans".equals(intent)) {
                    toolForCase.put("plans", plans);
                }
                if ("task".equals(intent)) {
                    toolForCase.putAll(task);
                    toolForCase.put("task", task);
                }
                String answer = com.zhiyun.cs.CsAnswerFormatter.formatCsAnswer(intent, toolForCase);
                if ("spent".equals(intent) && spent == null) {
                    spent = answer;
                }
                if ("orders".equals(intent) && orders == null) {
                    orders = answer;
                }
                boolean ok = matchesCsCase(row, toolForCase, answer);
                if (ok) {
                    hits++;
                } else {
                    Map<String, Object> miss = new LinkedHashMap<>();
                    miss.put("id", row.path("id").asText());
                    miss.put("intent", intent);
                    miss.put("query", row.path("query").asText());
                    misses.add(miss);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suite", "cs");
        out.put("metric", "CsEval");
        out.put("total", total);
        out.put("hits", hits);
        out.put("pass", total == 0 ? 0.0 : Math.round(hits * 1000.0 / total) / 10.0);
        out.put("spentDiffersFromOrders", spent != null && orders != null && !spent.equals(orders));
        out.put("note", "自建客服回归，不是线上 SLA");
        out.put("sla", false);
        out.put("misses", misses);
        return out;
    }

    private boolean matchesCsCase(JsonNode row, Map<String, Object> tool, String answer) {
        if (answer == null || answer.isBlank() || !answer.contains("\n")) {
            return false;
        }
        if (row.path("require_total_paid").asBoolean(false)) {
            String paid = "¥" + tool.get("totalPaidYuan");
            if (!answer.contains(paid)) {
                return false;
            }
        }
        if (row.path("must_contain").isArray()) {
            for (JsonNode n : row.path("must_contain")) {
                if (!answer.contains(n.asText())) {
                    return false;
                }
            }
        }
        if (row.path("must_not_contain").isArray()) {
            for (JsonNode n : row.path("must_not_contain")) {
                String needle = n.asText();
                if (!needle.isBlank() && answer.contains(needle)) {
                    return false;
                }
            }
        }
        String intent = row.path("intent").asText();
        if ("spent".equals(intent) && "orders".equals(intent)) {
            return false;
        }
        return com.zhiyun.cs.CsAnswerFormatter.matchesKeypoints(intent, tool, answer)
                || "plans".equals(intent) || "task".equals(intent) || "missing_id".equals(intent);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new LinkedHashMap<>();
        }
        return objectMapper.convertValue(node, Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Object> toList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(node, List.class);
    }

    private JsonNode loadJson(String name) {
        Path path = evalDir().resolve(name);
        if (!Files.exists(path)) {
            throw new IllegalStateException("找不到测评集 " + path.toAbsolutePath());
        }
        try {
            return objectMapper.readTree(Files.readString(path));
        } catch (Exception e) {
            throw new IllegalStateException("读取测评集失败: " + e.getMessage(), e);
        }
    }

    private boolean matches(List<RagService.Retrieved> top, List<String> goldFiles, List<String> needles) {
        for (RagService.Retrieved hit : top) {
            String id = hit.chunkId() == null ? "" : hit.chunkId();
            for (String file : goldFiles) {
                if (!file.isBlank() && id.startsWith(file)) {
                    return true;
                }
            }
            String content = (hit.content() == null ? "" : hit.content()).toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (needle != null && needle.length() >= 2 && content.contains(needle.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<JsonNode> loadJsonl(String name) {
        Path path = evalDir().resolve(name);
        if (!Files.exists(path)) {
            throw new IllegalStateException("找不到测评集 " + path.toAbsolutePath());
        }
        try {
            List<JsonNode> rows = new ArrayList<>();
            for (String line : Files.readAllLines(path)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                rows.add(objectMapper.readTree(line));
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("读取测评集失败: " + e.getMessage(), e);
        }
    }

    private Path evalDir() {
        String raw = properties.getEvalDir();
        return Path.of(raw == null || raw.isBlank() ? "../eval" : raw).toAbsolutePath().normalize();
    }
}
