package com.zhiyun.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.harness.HarnessMeters;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Service
public class LlmGateway {
    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);
    private final ZhiyunProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final HarnessMeters harnessMeters;
    private final AtomicReference<String> lastError = new AtomicReference<>("");

    public LlmGateway(ZhiyunProperties properties, RestClient restClient, ObjectMapper objectMapper,
                      HarnessMeters harnessMeters) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.harnessMeters = harnessMeters;
    }

    @PostConstruct
    public void logBinding() {
        ZhiyunProperties.Llm llm = properties.getLlm();
        log.info("LLM {} provider={} paper={} cs={} vision={} image={} embed={}@{} rerank={}",
                properties.dryRun() ? "dry-run" : "live",
                llm.resolvedProvider(),
                llm.getPaperModel(), llm.getCsModel(), llm.getVisionModel(), llm.getImageModel(),
                llm.getEmbeddingModel(), llm.getEmbeddingDims(), llm.getRerankModel());
    }

    public Map<String, Object> status() {
        ZhiyunProperties.Llm llm = properties.getLlm();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("live", !properties.dryRun());
        out.put("keyConfigured", llm.resolvedApiKey() != null && !llm.resolvedApiKey().isBlank());
        out.put("provider", llm.resolvedProvider());
        out.put("paperModel", llm.getPaperModel());
        out.put("csModel", llm.getCsModel());
        out.put("visionModel", llm.getVisionModel());
        out.put("imageModel", llm.getImageModel());
        out.put("embeddingModel", llm.getEmbeddingModel());
        out.put("embeddingDims", llm.getEmbeddingDims());
        out.put("rerankModel", llm.getRerankModel());
        out.put("csRuntime", properties.getCs().getRuntime());
        String difyUrl = properties.getCs().getDifyApiUrl();
        String difyKey = properties.getCs().getDifyApiKey();
        out.put("difyRemote", difyUrl != null && !difyUrl.isBlank() && difyKey != null && !difyKey.isBlank());
        out.put("lastError", lastError.get());
        return out;
    }

    public String complete(String model, double temperature, String system, String user) {
        return complete(model, temperature, system, user, null);
    }

    public String complete(String model, double temperature, String system, String user, UserLlmOverride override) {
        return complete(model, temperature, List.of(
                Map.of("role", "system", "content", system == null ? "" : system),
                Map.of("role", "user", "content", user == null ? "" : user)
        ), override);
    }

    public String complete(String model, double temperature, List<Map<String, Object>> messages) {
        return complete(model, temperature, messages, null);
    }

    public String complete(String model, double temperature, List<Map<String, Object>> messages, UserLlmOverride override) {
        if (properties.dryRun()) {
            return null;
        }
        requireLiveKey(override);
        String modelId = modelOf(model, override);
        Map<String, Object> body = new HashMap<>();
        body.put("model", modelId);
        body.put("temperature", temperature);
        body.put("max_tokens", 2048);
        body.put("messages", messages);
        body.put("enable_thinking", false);
        return chatContent(body, override);
    }

    /**
     * OpenAI 兼容流式对话。每段 delta 回调一次；返回拼接全文。dry-run 或失败返回 null。
     */
    public String completeStream(String model, double temperature, String system, String user, Consumer<String> onDelta) {
        return completeStream(model, temperature, List.of(
                Map.of("role", "system", "content", system == null ? "" : system),
                Map.of("role", "user", "content", user == null ? "" : user)
        ), onDelta);
    }

    public String completeStream(String model, double temperature, List<Map<String, Object>> messages, Consumer<String> onDelta) {
        if (properties.dryRun()) {
            return null;
        }
        requireLiveKey();
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", 2048);
        body.put("stream", true);
        body.put("enable_thinking", false);
        body.put("messages", messages);
        return harnessMeters.timeLlm(() -> {
        FirstTokenClock clock = new FirstTokenClock();
        try {
            String full = restClient.post()
                    .uri(endpoint("/chat/completions", null))
                    .header("Authorization", "Bearer " + apiKey(null))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((req, res) -> {
                        if (res.getStatusCode().isError()) {
                            String err = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                            RestClientResponseException ex = new RestClientResponseException(
                                    "stream " + res.getStatusCode(),
                                    res.getStatusCode(),
                                    res.getStatusText(),
                                    res.getHeaders(),
                                    err.getBytes(StandardCharsets.UTF_8),
                                    StandardCharsets.UTF_8);
                            rememberError(ex);
                            throw ex;
                        }
                        StringBuilder acc = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(res.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.isBlank() || !line.startsWith("data:")) {
                                    continue;
                                }
                                String data = line.substring(5).trim();
                                if ("[DONE]".equals(data)) {
                                    break;
                                }
                                String piece = streamDelta(data);
                                if (piece != null && !piece.isEmpty()) {
                                    if (clock.markIfFirst(true, FirstTokenClock.SOURCE_STREAM)) {
                                        harnessMeters.recordFirstToken(clock.firstTokenAt(), clock.firstTokenMs());
                                    }
                                    acc.append(piece);
                                    if (onDelta != null) {
                                        onDelta.accept(piece);
                                    }
                                }
                            }
                        }
                        lastError.set("");
                        return acc.toString();
                    });
            return full == null || full.isBlank() ? null : full;
        } catch (Exception e) {
            rememberError(e);
            log.warn("stream chat failed: {}", lastError.get());
            return null;
        }
        });
    }

    private String streamDelta(String data) {
        try {
            Map<String, Object> json = objectMapper.readValue(data, Map.class);
            recordUsage(json);
            Object choices = json.get("choices");
            if (!(choices instanceof List<?> list) || list.isEmpty()) {
                return "";
            }
            Object first = list.get(0);
            if (!(first instanceof Map<?, ?> choice)) {
                return "";
            }
            Map<?, ?> msg;
            Object delta = choice.get("delta");
            if (delta instanceof Map<?, ?> d) {
                msg = d;
            } else if (choice.get("message") instanceof Map<?, ?> m) {
                msg = m;
            } else {
                msg = Map.of();
            }
            String content = firstText(msg.get("content"));
            if (content == null || content.isBlank()) {
                content = firstText(msg.get("reasoning_content"));
            }
            return content == null ? "" : content;
        } catch (Exception e) {
            return "";
        }
    }

    public String vision(String model, String prompt, byte[] png) {
        return vision(model, prompt, png, null);
    }

    public String vision(String model, String prompt, byte[] png, UserLlmOverride override) {
        if (properties.dryRun() || png == null || png.length == 0) {
            return null;
        }
        requireLiveKey(override);
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)));
        content.add(Map.of("type", "text", "text", prompt));
        Map<String, Object> body = new HashMap<>();
        body.put("model", modelOf(model, override));
        body.put("temperature", 0.1);
        body.put("max_tokens", 1024);
        body.put("enable_thinking", false);
        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        return chatContent(body, override);
    }

    /**
     * 百炼原生文生图。qwen-image-3.0 不走 compatible-mode /chat/completions。
     * Figure Agent 看图仍用 vision()；本方法只给需要出图的场景。
     */
    public String generateImage(String prompt) {
        if (properties.dryRun() || prompt == null || prompt.isBlank()) {
            return null;
        }
        if (!dashscope()) {
            lastError.set("生图只支持阿里云百炼原生接口");
            return null;
        }
        requireLiveKey();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getLlm().getImageModel());
        body.put("input", Map.of(
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(Map.of("text", prompt))
                ))
        ));
        body.put("parameters", Map.of(
                "n", 1,
                "prompt_extend", false,
                "enable_thinking", false,
                "watermark", false
        ));
        return harnessMeters.timeLlm(() -> {
        try {
            Map<?, ?> resp = post(dashscopeImageUrl(), body);
            Object code = resp.get("code");
            if (code != null && !String.valueOf(code).isBlank()) {
                lastError.set(humanizeError(400, String.valueOf(resp.get("message"))));
                return null;
            }
            return parseGeneratedImage(resp);
        } catch (Exception e) {
            rememberError(e);
            log.warn("image generation failed: {}", lastError.get());
            return null;
        }
        });
    }

    public float[] embed(String text) {
        List<float[]> batch = embedBatch(List.of(text == null ? "" : text));
        return batch.isEmpty() ? hashVector("", properties.getLlm().getEmbeddingDims()) : batch.get(0);
    }

    public List<float[]> embedBatch(List<String> texts) {
        int dims = properties.getLlm().getEmbeddingDims();
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (properties.dryRun() || properties.getLlm().resolvedApiKey().isBlank()) {
            return texts.stream().map(t -> hashVector(t, dims)).toList();
        }
        List<float[]> out = new ArrayList<>();
        int batch = dashscope() ? 10 : 16;
        for (int i = 0; i < texts.size(); i += batch) {
            List<String> slice = texts.subList(i, Math.min(i + batch, texts.size()));
            out.addAll(embedSlice(slice, dims));
        }
        return out;
    }

    private List<float[]> embedSlice(List<String> texts, int dims) {
        return harnessMeters.timeLlm(() -> {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", properties.getLlm().getEmbeddingModel());
            body.put("input", texts.size() == 1 ? texts.get(0) : List.copyOf(texts));
            body.put("dimensions", dims);
            body.put("encoding_format", "float");
            Map<?, ?> resp = post(endpoint("/embeddings", null), body, apiKey(null));
            recordUsage(resp);
            List<?> data = (List<?>) resp.get("data");
            List<float[]> out = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                Map<?, ?> row = (Map<?, ?>) data.get(Math.min(i, data.size() - 1));
                List<?> embedding = (List<?>) row.get("embedding");
                float[] vec = new float[dims];
                int n = Math.min(embedding.size(), dims);
                for (int j = 0; j < n; j++) {
                    vec[j] = ((Number) embedding.get(j)).floatValue();
                }
                out.add(vec);
            }
            return out;
        } catch (Exception e) {
            rememberError(e);
            log.warn("embedding fallback to hash: {}", lastError.get());
            return texts.stream().map(t -> hashVector(t, dims)).toList();
        }
        });
    }

    /**
     * 按相关性降序返回 documents 下标。硅基流动走 /rerank；百炼 qwen3-rerank 走 compatible-api /reranks；
     * qwen3.7-text-rerank 走原生 text-rerank。
     */
    public List<Integer> rerank(String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        int n = Math.min(topN, documents.size());
        if (properties.dryRun() || properties.getLlm().resolvedApiKey().isBlank()) {
            return fallbackOrder(documents.size(), n);
        }
        return harnessMeters.timeLlm(() -> {
        try {
            Map<?, ?> resp = dashscope()
                    ? post(dashscopeRerankUrl(), dashscopeRerankBody(query, documents, n))
                    : post(endpoint("/rerank"), siliconflowRerankBody(query, documents, n));
            recordUsage(resp);
            List<Integer> order = parseRerankIndexes(resp);
            return order.isEmpty() ? fallbackOrder(documents.size(), n) : order;
        } catch (Exception e) {
            rememberError(e);
            log.warn("rerank fallback to original order: {}", lastError.get());
            return fallbackOrder(documents.size(), n);
        }
        });
    }

    private Map<String, Object> siliconflowRerankBody(String query, List<String> documents, int topN) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", properties.getLlm().getRerankModel());
        body.put("query", query);
        body.put("documents", documents);
        body.put("return_documents", true);
        body.put("top_n", topN);
        return body;
    }

    private Map<String, Object> dashscopeRerankBody(String query, List<String> documents, int topN) {
        String model = properties.getLlm().getRerankModel();
        if (model != null && model.toLowerCase().contains("qwen3-rerank") && !model.toLowerCase().contains("vl")) {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("query", query);
            body.put("documents", documents);
            body.put("top_n", topN);
            return body;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", Map.of("query", query, "documents", documents));
        body.put("parameters", Map.of("top_n", topN, "return_documents", true));
        return body;
    }

    private List<Integer> parseRerankIndexes(Map<?, ?> resp) {
        List<?> results = (List<?>) resp.get("results");
        if (results == null && resp.get("output") instanceof Map<?, ?> output) {
            results = (List<?>) output.get("results");
        }
        List<Integer> order = new ArrayList<>();
        if (results == null) {
            return order;
        }
        for (Object item : results) {
            Map<?, ?> row = (Map<?, ?>) item;
            Object idx = row.get("index");
            if (idx instanceof Number number) {
                order.add(number.intValue());
            }
        }
        return order;
    }

    private String chatContent(Map<String, Object> body, UserLlmOverride override) {
        return harnessMeters.timeLlm(() -> {
            FirstTokenClock clock = new FirstTokenClock();
            Map<?, ?> resp = post(endpoint("/chat/completions", override), body, apiKey(override));
            if (override == null) {
                recordUsage(resp);
            }
            List<?> choices = (List<?>) resp.get("choices");
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            Map<?, ?> choice = (Map<?, ?>) choices.get(0);
            Map<?, ?> msg = (Map<?, ?>) choice.get("message");
            if (msg == null) {
                msg = (Map<?, ?>) choice.get("delta");
            }
            if (msg == null) {
                return null;
            }
            String content = firstText(msg.get("content"));
            if (content == null || content.isBlank()) {
                content = firstText(msg.get("reasoning_content"));
            }
            if (content == null || content.isBlank()) {
                return null;
            }
            // 非流式：完整响应体到达即记首 token，不是流式 TTFT。
            if (clock.markIfFirst(true, FirstTokenClock.SOURCE_COMPLETE)) {
                harnessMeters.recordFirstToken(clock.firstTokenAt(), clock.firstTokenMs());
            }
            return content;
        });
    }

    private static String firstText(Object content) {
        if (content == null) {
            return null;
        }
        if (content instanceof List<?> list) {
            StringBuilder acc = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object text = map.get("text");
                    if (text != null && !String.valueOf(text).isBlank()) {
                        acc.append(text);
                    }
                } else if (item != null) {
                    acc.append(item);
                }
            }
            return acc.toString();
        }
        String raw = String.valueOf(content);
        return raw.isBlank() || "null".equals(raw) ? null : raw;
    }

    private String parseGeneratedImage(Map<?, ?> resp) {
        Object output = resp.get("output");
        if (!(output instanceof Map<?, ?> out)) {
            return null;
        }
        Object choices = out.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> choice)) {
            return null;
        }
        Object message = choice.get("message");
        if (!(message instanceof Map<?, ?> msg)) {
            return null;
        }
        Object content = msg.get("content");
        if (!(content instanceof List<?> parts)) {
            return firstText(content);
        }
        for (Object part : parts) {
            if (part instanceof Map<?, ?> map) {
                Object image = map.get("image");
                if (image != null && !String.valueOf(image).isBlank()) {
                    return String.valueOf(image);
                }
            }
        }
        return null;
    }

    private Map<?, ?> post(String url, Map<String, Object> body) {
        return post(url, body, apiKey(null));
    }

    private Map<?, ?> post(String url, Map<String, Object> body, String apiKey) {
        try {
            Map<?, ?> resp = restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            lastError.set("");
            return resp == null ? Map.of() : resp;
        } catch (RestClientResponseException e) {
            rememberError(e);
            throw e;
        }
    }

    private void rememberError(Exception e) {
        if (e instanceof RestClientResponseException ex) {
            String body = ex.getResponseBodyAsString();
            lastError.set(humanizeError(ex.getStatusCode().value(), body));
            return;
        }
        lastError.set(trim(e.getMessage(), 180));
    }

    private String humanizeError(int status, String body) {
        String raw = body == null ? "" : body;
        String lower = raw.toLowerCase();
        String provider = properties.getLlm().resolvedProvider();
        if (lower.contains("insufficient") || lower.contains("30001") || lower.contains("arrearage")) {
            return dashscope() || "dashscope".equals(provider)
                    ? "阿里云百炼额度不足或已欠费，对话/向量/重排会回退本地逻辑"
                    : "模型平台账户余额不足，对话/向量/重排会回退本地逻辑";
        }
        if (lower.contains("invalidapikey") || lower.contains("invalid api-key") || lower.contains("unauthorized")) {
            return "API Key 无效，请检查阿里云百炼密钥和地域是否匹配";
        }
        if (lower.contains("accessdenied") || lower.contains("model.notfound") || lower.contains("does not exist")) {
            return "模型未开通或不存在，请到百炼控制台开通对应模型：" + trim(raw, 120);
        }
        return status + " " + trim(raw, 180);
    }

    private static String trim(String raw, int max) {
        if (raw == null) {
            return "";
        }
        String t = raw.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private void recordUsage(Map<?, ?> resp) {
        Object usage = resp.get("usage");
        if (usage instanceof Map<?, ?> map) {
            int prompt = intVal(map.get("prompt_tokens"));
            int completion = intVal(map.get("completion_tokens"));
            int total = intVal(map.get("total_tokens"));
            if (total <= 0) {
                total = prompt + completion;
            }
            UsageMeter.add(total);
            if (prompt > 0 || completion > 0) {
                harnessMeters.recordLlmTokens(prompt, completion);
            } else {
                harnessMeters.recordLlmTokens(total);
            }
            return;
        }
        Object tokens = resp.get("tokens");
        if (tokens instanceof Map<?, ?> map) {
            int prompt = intVal(map.get("input_tokens"));
            int completion = intVal(map.get("output_tokens"));
            int total = prompt + completion;
            UsageMeter.add(total);
            if (prompt > 0 || completion > 0) {
                harnessMeters.recordLlmTokens(prompt, completion);
            } else {
                harnessMeters.recordLlmTokens(total);
            }
        }
    }

    private static int intVal(Object raw) {
        return raw instanceof Number number ? number.intValue() : 0;
    }

    private static List<Integer> fallbackOrder(int size, int topN) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < Math.min(size, topN); i++) {
            out.add(i);
        }
        return out;
    }

    private void requireLiveKey() {
        requireLiveKey(null);
    }

    private void requireLiveKey(UserLlmOverride override) {
        String key = apiKey(override);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("live LLM requires an API key");
        }
    }

    private String apiKey(UserLlmOverride override) {
        if (override != null && override.apiKey() != null && !override.apiKey().isBlank()) {
            return override.apiKey();
        }
        return properties.getLlm().resolvedApiKey();
    }

    private static String modelOf(String model, UserLlmOverride override) {
        if (override != null && override.modelId() != null && !override.modelId().isBlank()) {
            return override.modelId();
        }
        return model;
    }

    private boolean dashscope() {
        return "dashscope".equals(properties.getLlm().resolvedProvider());
    }

    private String endpoint(String path) {
        return endpoint(path, null);
    }

    private String endpoint(String path, UserLlmOverride override) {
        String base = normalizedBase(override);
        return base + path;
    }

    private String normalizedBase() {
        return normalizedBase(null);
    }

    private String normalizedBase(UserLlmOverride override) {
        String base = override != null && override.baseUrl() != null && !override.baseUrl().isBlank()
                ? override.baseUrl()
                : properties.getLlm().getBaseUrl();
        if (base == null || base.isBlank()) {
            base = dashscope()
                    ? "https://dashscope.aliyuncs.com/compatible-mode/v1"
                    : "https://api.siliconflow.cn/v1";
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private String dashscopeImageUrl() {
        return originOf(normalizedBase()) + "/api/v1/services/aigc/multimodal-generation/generation";
    }

    private String dashscopeRerankUrl() {
        String model = properties.getLlm().getRerankModel();
        String origin = originOf(normalizedBase());
        if (model != null && model.toLowerCase().contains("qwen3-rerank") && !model.toLowerCase().contains("vl")) {
            return origin + "/compatible-api/v1/reranks";
        }
        return origin + "/api/v1/services/rerank/text-rerank/text-rerank";
    }

    private static String originOf(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url;
        }
        int slash = url.indexOf('/', scheme + 3);
        return slash < 0 ? url : url.substring(0, slash);
    }

    public static float[] hashVector(String text, int dims) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            float[] vec = new float[dims];
            for (int i = 0; i < dims; i++) {
                vec[i] = (digest[i % digest.length] - 128) / 128f;
            }
            return vec;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
