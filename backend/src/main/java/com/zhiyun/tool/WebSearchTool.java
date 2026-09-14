package com.zhiyun.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.harness.AgentIds;
import com.zhiyun.harness.HarnessMeters;
import com.zhiyun.harness.ToolCallRecorder;
import com.zhiyun.harness.ToolPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Locale;

/**
 * Citation 白名单上的独立 Web 检索 Bean。学术元数据仍走 {@link AcademicSearchTool}，
 * 本工具只补网页侧 Evidence（source=WEB），Style 不得调用。
 */
@Component
public class WebSearchTool {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ZhiyunProperties properties;
    private final HarnessMeters harnessMeters;

    public WebSearchTool(RestClient restClient, ObjectMapper objectMapper, ZhiyunProperties properties) {
        this(restClient, objectMapper, properties, null);
    }

    @Autowired
    public WebSearchTool(RestClient restClient, ObjectMapper objectMapper, ZhiyunProperties properties,
                         HarnessMeters harnessMeters) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.harnessMeters = harnessMeters;
    }

    public ArrayNode search(String query) {
        return search(AgentIds.CITATION, query);
    }

    public ArrayNode search(String agentId, String query) {
        ToolPolicy.assertAllowed(agentId, ToolPolicy.WEB_SEARCH);
        long t0 = System.nanoTime();
        try {
            ArrayNode results = searchRaw(query);
            boolean ok = results.size() > 0;
            record(ok, t0);
            return results;
        } catch (RuntimeException e) {
            record(false, t0);
            throw e;
        }
    }

    private ArrayNode searchRaw(String query) {
        ArrayNode results = objectMapper.createArrayNode();
        String q = query == null ? "" : query.trim();
        if (q.isBlank()) {
            return results;
        }
        if (properties.dryRun()) {
            results.add(hit("dry-run", q, "Dry-run WebSearch stub. Live mode fetches a publisher URL when given."));
            return results;
        }
        if (looksLikeHttp(q)) {
            ObjectNode page = fetch(q);
            if (page != null) {
                results.add(page);
            }
        }
        return results;
    }

    private void record(boolean ok, long t0) {
        ToolCallRecorder.record(ToolPolicy.WEB_SEARCH, ok, (System.nanoTime() - t0) / 1_000_000L);
        if (harnessMeters != null) {
            harnessMeters.recordToolCall(ok);
        }
    }

    public ObjectNode fetch(String url) {
        try {
            String body = restClient.get()
                    .uri(URI.create(url))
                    .accept(MediaType.TEXT_HTML, MediaType.TEXT_PLAIN, MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            String excerpt = body == null ? "" : body.replaceAll("\\s+", " ").trim();
            if (excerpt.length() > 400) {
                excerpt = excerpt.substring(0, 400);
            }
            return hit(url, url, excerpt);
        } catch (Exception e) {
            return null;
        }
    }

    private ObjectNode hit(String url, String title, String excerpt) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("source", "WEB");
        node.put("url", url);
        node.put("title", title.length() > 120 ? title.substring(0, 120) : title);
        node.put("excerpt", excerpt);
        return node;
    }

    private static boolean looksLikeHttp(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}
