package com.zhiyun.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.ReviewTask;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewUsageTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void userDtoKeepsSpendFieldsAndDropsOps() throws Exception {
        ReviewTask task = new ReviewTask();
        task.setWorkflow(Codes.QUICK_REVIEW);
        task.setStatus(Codes.RUNNING);
        task.setCheckpointAgent("CITATION_INTEGRITY");
        task.setTaskNo("ZYTUSAGETEST01");

        Map<String, Object> citation = new LinkedHashMap<>();
        citation.put("agent", "CITATION_INTEGRITY");
        citation.put("name", "引用核验");
        citation.put("status", Codes.DONE);
        citation.put("durationMs", 12000);
        citation.put("tokens", 820);
        citation.put("fencingToken", 4);
        citation.put("skillVersion", "1");
        citation.put("promptVersion", "1");
        citation.put("errorCode", "timeout");
        citation.put("toolName", "AcademicSearchTool");
        citation.put("checkpoint", true);

        Map<String, Object> style = new LinkedHashMap<>();
        style.put("agent", "ACADEMIC_STYLE");
        style.put("name", "语言润色");
        style.put("status", Codes.RUNNING);
        style.put("durationMs", null);
        style.put("tokens", null);
        style.put("fencingToken", 4);

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("fencingToken", 4);
        trace.put("lease", Map.of("owner", "host:1:abc", "fencingToken", 4));
        trace.put("waterfall", List.of());
        trace.put("nodes", List.of(citation, style));

        Map<String, Object> usage = ReviewUsage.view(task, trace, 1, false);
        String json = mapper.writeValueAsString(usage);
        assertThat(json).doesNotContain("waterfall", "toolName", "errorCode", "skillVersion",
                "fencingToken", "promptVersion", "lease", "AcademicSearchTool", "checkpoint");
        assertThat(usage.keySet()).containsExactly("durationMs", "tokens", "quota", "settled", "inProgress", "nodes");
        assertThat(usage.get("tokens")).isEqualTo(820);
        assertThat(usage.get("quota")).isEqualTo(1);
        assertThat(usage.get("settled")).isEqualTo(false);
        assertThat(usage.get("inProgress")).isEqualTo(true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) usage.get("nodes");
        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0).keySet()).containsExactly("name", "durationMs", "tokens", "quota", "skipped", "status");
        assertThat(nodes.get(0).get("name")).isEqualTo("引用核验");
        assertThat(nodes.get(0).get("quota")).isEqualTo(1);
        assertThat(nodes.get(1).get("name")).isEqualTo("语言润色");
        assertThat(nodes.get(1).get("status")).isEqualTo(Codes.RUNNING);
        assertThat(nodes.get(1).get("quota")).isNull();
    }

    @Test
    void skippedNodeHasZeroQuota() {
        ReviewTask task = new ReviewTask();
        task.setWorkflow(Codes.CITATION_ONLY);
        task.setStatus(Codes.DONE);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("agent", "CITATION_INTEGRITY");
        node.put("name", "引用核验");
        node.put("status", Codes.DONE);
        node.put("skipped", true);
        node.put("durationMs", 0);
        node.put("tokens", 0);
        Map<String, Object> usage = ReviewUsage.view(task, Map.of("nodes", List.of(node)), 0, true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) usage.get("nodes");
        assertThat(nodes.get(0).get("skipped")).isEqualTo(true);
        assertThat(nodes.get(0).get("quota")).isEqualTo(0);
    }
}
