package com.zhiyun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EvalDatasetTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void ragQueriesAreFiftyLabeledItems() throws Exception {
        Path path = Path.of("..", "eval", "queries.jsonl");
        var lines = Files.readAllLines(path).stream().filter(l -> !l.isBlank()).toList();
        assertThat(lines).hasSize(50);
        Set<String> ids = new HashSet<>();
        for (String line : lines) {
            JsonNode row = mapper.readTree(line);
            assertThat(row.path("id").asText()).isNotBlank();
            assertThat(row.path("query").asText()).isNotBlank();
            assertThat(row.path("scope").asText()).isEqualTo("PUBLIC");
            ids.add(row.path("id").asText());
        }
        assertThat(ids).hasSize(50);
    }

    @Test
    void agentTasksCoverSevenAgentsAndCustomerService() throws Exception {
        Path path = Path.of("..", "eval", "agent-tasks.jsonl");
        var lines = Files.readAllLines(path).stream().filter(l -> !l.isBlank()).toList();
        assertThat(lines).hasSize(50);
        Set<String> agents = new HashSet<>();
        for (String line : lines) {
            JsonNode row = mapper.readTree(line);
            agents.add(row.path("agent").asText());
            String paper = row.path("paper").asText("");
            if (!paper.isBlank()) {
                assertThat(Path.of("..", "eval", "papers", paper)).exists();
            }
        }
        assertThat(agents).contains(
                "CITATION_INTEGRITY",
                "ACADEMIC_STYLE",
                "ACADEMIC_REVIEWER",
                "FIGURE_PDF",
                "REVISION_PLANNING",
                "REVISION_EXECUTION",
                "FINAL_VERIFICATION",
                "CUSTOMER_SERVICE");
    }

    @Test
    void csEvalFixtureHasQuestionsAndKeypoints() throws Exception {
        Path path = Path.of("..", "eval", "cs-eval.json");
        assertThat(path).exists();
        JsonNode root = mapper.readTree(Files.readString(path));
        assertThat(root.path("purpose").asText()).isEqualTo("regression");
        assertThat(root.path("sla").asBoolean()).isFalse();
        assertThat(root.path("cases").size()).isGreaterThanOrEqualTo(6);
        assertThat(root.path("toolResult").path("totalPaidYuan").asInt()).isPositive();
    }
}
