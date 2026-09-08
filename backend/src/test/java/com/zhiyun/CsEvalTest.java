package com.zhiyun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyun.cs.CsAnswerFormatter;
import com.zhiyun.cs.CsIntent;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsEvalTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fixtureCoversRequiredIntentsAndSpentDiffersFromOrders() throws Exception {
        JsonNode root = mapper.readTree(Files.readString(evalFile()));
        assertThat(root.path("purpose").asText()).isEqualTo("regression");
        assertThat(root.path("sla").asBoolean()).isFalse();
        JsonNode cases = root.path("cases");
        assertThat(cases).isNotEmpty();
        assertThat(cases.size()).isGreaterThanOrEqualTo(6);

        @SuppressWarnings("unchecked")
        Map<String, Object> tool = mapper.convertValue(root.path("toolResult"), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> plans = mapper.convertValue(root.path("plans"), List.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> task = mapper.convertValue(root.path("task"), Map.class);

        String spent = null;
        String orders = null;
        boolean sawSpend = false;
        boolean sawOrders = false;
        boolean sawTask = false;
        boolean sawQuota = false;
        boolean sawMissing = false;
        boolean sawPlans = false;
        for (JsonNode row : cases) {
            String intent = row.path("intent").asText();
            String query = row.path("query").asText();
            Map<String, Object> toolForCase = row.has("toolResult")
                    ? mapper.convertValue(row.path("toolResult"), Map.class)
                    : new LinkedHashMap<>(tool);
            if ("plans".equals(intent)) {
                toolForCase.put("plans", plans);
            }
            if ("task".equals(intent)) {
                toolForCase.putAll(task);
                toolForCase.put("task", task);
            }
            String answer = CsAnswerFormatter.formatCsAnswer(intent, toolForCase);
            assertThat(answer).as(row.path("id").asText()).isNotBlank();
            assertThat(answer).contains("\n");
            if (row.path("require_total_paid").asBoolean(false)) {
                String paid = "¥" + tool.get("totalPaidYuan");
                assertThat(answer).contains(paid);
            }
            if (row.path("must_contain").isArray()) {
                for (JsonNode n : row.path("must_contain")) {
                    assertThat(answer).as(row.path("id").asText() + " " + n.asText()).contains(n.asText());
                }
            }
            if (row.path("must_not_contain").isArray()) {
                for (JsonNode n : row.path("must_not_contain")) {
                    if (!n.asText().isBlank()) {
                        assertThat(answer).as(row.path("id").asText()).doesNotContain(n.asText());
                    }
                }
            }
            if ("spent".equals(intent)) {
                sawSpend = true;
                if (spent == null) {
                    spent = answer;
                }
                assertThat(CsIntent.classify(query)).contains(CsIntent.SPEND);
                assertThat(answer).doesNotContain("ZY-PAID-10");
            }
            if ("orders".equals(intent)) {
                sawOrders = true;
                if (orders == null) {
                    orders = answer;
                }
            }
            if ("task".equals(intent)) {
                sawTask = true;
            }
            if ("quota".equals(intent)) {
                sawQuota = true;
            }
            if ("missing_id".equals(intent)) {
                sawMissing = true;
            }
            if ("plans".equals(intent)) {
                sawPlans = true;
            }
            if ("howto_models".equals(intent)) {
                assertThat(CsIntent.classify(query)).contains(CsIntent.MODELS);
            }
        }
        assertThat(sawSpend && sawOrders && sawTask && sawQuota && sawMissing && sawPlans).isTrue();
        assertThat(spent).isNotEqualTo(orders);
    }

    private static Path evalFile() {
        return Path.of("..", "eval", "cs-eval.json");
    }
}
