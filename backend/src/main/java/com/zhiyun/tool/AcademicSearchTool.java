package com.zhiyun.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyun.config.ZhiyunProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AcademicSearchTool {
    private static final Pattern DOI = Pattern.compile("10\\.\\d{4,9}/[-._;()/:A-Z0-9]+", Pattern.CASE_INSENSITIVE);
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ZhiyunProperties properties;

    public AcademicSearchTool(RestClient restClient, ObjectMapper objectMapper, ZhiyunProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public ArrayNode search(String query) {
        ArrayNode results = objectMapper.createArrayNode();
        Matcher matcher = DOI.matcher(query);
        if (matcher.find()) {
            ObjectNode hit = lookupDoi(matcher.group());
            if (hit != null) {
                results.add(hit);
            }
            return results;
        }
        if (properties.dryRun()) {
            if (query.toLowerCase().contains("fabricated") || query.toLowerCase().contains("ghost")) {
                return results;
            }
            ObjectNode demo = objectMapper.createObjectNode();
            demo.put("doi", "10.1145/example.2019");
            demo.put("title", query.length() > 80 ? query.substring(0, 80) : query);
            demo.putArray("authors").add("Smith").add("Lee");
            demo.put("year", 2019);
            demo.put("venue", "Demo Conference");
            demo.put("abstractText", "Dry-run Crossref stub. Live mode calls api.crossref.org.");
            results.add(demo);
            return results;
        }
        try {
            String url = "https://api.crossref.org/works?query.bibliographic="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&rows=3";
            Map<?, ?> resp = restClient.get().uri(url)
                    .header("User-Agent", "Zhiyun/1.0 (mailto:support@zhiyun.dev)")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Map.class);
            Map<?, ?> message = (Map<?, ?>) resp.get("message");
            List<?> items = (List<?>) message.get("items");
            for (Object item : items) {
                results.add(fromWork((Map<?, ?>) item));
            }
        } catch (Exception ignored) {
            // empty candidate list leads to NOT_VERIFIED
        }
        return results;
    }

    public ObjectNode lookupDoi(String doi) {
        if (properties.dryRun()) {
            if (doi.toLowerCase().contains("0000/ghost")) {
                return null;
            }
            ObjectNode demo = objectMapper.createObjectNode();
            demo.put("doi", doi);
            demo.put("title", "Dry-run verified work " + doi);
            demo.putArray("authors").add("Smith");
            demo.put("year", 2019);
            demo.put("venue", "Demo Journal");
            demo.put("abstractText", "Metadata returned by dry-run AcademicSearchTool.");
            return demo;
        }
        try {
            Map<?, ?> resp = restClient.get()
                    .uri("https://api.crossref.org/works/" + doi)
                    .header("User-Agent", "Zhiyun/1.0 (mailto:support@zhiyun.dev)")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Map.class);
            return fromWork((Map<?, ?>) ((Map<?, ?>) resp.get("message")));
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private ObjectNode fromWork(Map<?, ?> work) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("doi", work.get("DOI") == null ? "" : String.valueOf(work.get("DOI")));
        Object title = work.get("title");
        if (title instanceof List<?> list && !list.isEmpty()) {
            node.put("title", String.valueOf(list.get(0)));
        } else {
            node.put("title", "");
        }
        ArrayNode authors = node.putArray("authors");
        Object author = work.get("author");
        if (author instanceof List<?> list) {
            for (Object a : list) {
                Map<String, Object> m = (Map<String, Object>) a;
                authors.add((m.getOrDefault("given", "") + " " + m.getOrDefault("family", "")).trim());
            }
        }
        Object issued = work.get("issued");
        int year = 0;
        if (issued instanceof Map<?, ?> issuedMap) {
            Object dateParts = issuedMap.get("date-parts");
            if (dateParts instanceof List<?> outer && !outer.isEmpty() && outer.get(0) instanceof List<?> inner
                    && !inner.isEmpty()) {
                year = ((Number) inner.get(0)).intValue();
            }
        }
        node.put("year", year);
        Object container = work.get("container-title");
        node.put("venue", container instanceof List<?> list && !list.isEmpty() ? String.valueOf(list.get(0)) : "");
        node.put("abstractText", work.get("abstract") == null ? null : String.valueOf(work.get("abstract")));
        return node;
    }
}
