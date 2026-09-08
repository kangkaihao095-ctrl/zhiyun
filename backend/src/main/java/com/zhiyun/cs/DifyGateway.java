package com.zhiyun.cs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyun.agent.SkillRegistry;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.harness.AgentIds;
import com.zhiyun.llm.AgentModelRouter;
import com.zhiyun.llm.LlmGateway;
import com.zhiyun.security.AuthUser;
import com.zhiyun.security.JwtService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 客服 Dify Chatflow Adapter。远程 Dify 走官方 /chat-messages 流式协议；
 * 未配置 URL 时用同契约本地 Chatflow（qwen3.7-flash + Java 只读 Tool），MCP 仍是数据权威。
 */
@Component
public class DifyGateway {
    private static final Logger log = LoggerFactory.getLogger(DifyGateway.class);
    private final ZhiyunProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final LlmGateway llmGateway;
    private final SkillRegistry skillRegistry;
    private final AgentModelRouter modelRouter;
    private final JwtService jwtService;

    public DifyGateway(ZhiyunProperties properties, RestClient restClient, ObjectMapper objectMapper,
                       LlmGateway llmGateway, SkillRegistry skillRegistry, AgentModelRouter modelRouter,
                       JwtService jwtService) {
        this.properties = properties;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.llmGateway = llmGateway;
        this.skillRegistry = skillRegistry;
        this.modelRouter = modelRouter;
        this.jwtService = jwtService;
    }

    @PostConstruct
    public void logBinding() {
        log.info("CS runtime={} difyRemote={} model={}",
                properties.getCs().getRuntime(),
                remote(),
                properties.getLlm().getCsModel());
    }

    public boolean enabled() {
        if (properties.dryRun()) {
            return false;
        }
        return "dify".equalsIgnoreCase(properties.getCs().getRuntime());
    }

    public boolean remote() {
        String url = properties.getCs().getDifyApiUrl();
        String key = properties.getCs().getDifyApiKey();
        return url != null && !url.isBlank() && key != null && !key.isBlank();
    }

    public String chat(String query, AuthUser user, List<CustomerService.ChatTurn> window, String grounded) {
        StringBuilder acc = new StringBuilder();
        String live = stream(query, user, window, grounded, acc::append);
        return live == null || live.isBlank() ? acc.toString() : live;
    }

    public String stream(String query, AuthUser user, List<CustomerService.ChatTurn> window,
                         String grounded, Consumer<String> onDelta) {
        if (!enabled()) {
            return null;
        }
        if (remote()) {
            return streamRemote(query, user, window, grounded, onDelta);
        }
        return streamLocal(query, window, grounded, onDelta);
    }

    private String streamLocal(String query, List<CustomerService.ChatTurn> window,
                               String grounded, Consumer<String> onDelta) {
        String model = modelRouter.chatModel(AgentIds.CS);
        String system = skillRegistry.systemMessage(AgentIds.CS)
                + "\nChatflow=DifyAdapter"
                + "\nChat model=" + model
                + "\n" + writerGuide();
        return llmGateway.completeStream(model, 0.35, CsChatMemory.llmMessages(system, grounded, window), onDelta);
    }

    private String streamRemote(String query, AuthUser user, List<CustomerService.ChatTurn> window,
                                String grounded, Consumer<String> onDelta) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("history", CsChatMemory.formatHistory(window, query));
        inputs.put("grounded", grounded == null ? "" : grounded);
        inputs.put("user_token", jwtService.issue(user));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputs", inputs);
        body.put("query", query == null ? "" : query);
        body.put("response_mode", "streaming");
        body.put("user", "zhiyun-" + user.userId());
        try {
            return restClient.post()
                    .uri(chatMessagesUrl())
                    .header("Authorization", "Bearer " + properties.getCs().getDifyApiKey())
                    .header("User-Agent", "Mozilla/5.0 Zhiyun-DifyAdapter")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((req, res) -> {
                        if (res.getStatusCode().isError()) {
                            String err = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                            throw new RestClientResponseException(
                                    "dify " + res.getStatusCode(),
                                    res.getStatusCode(),
                                    res.getStatusText(),
                                    res.getHeaders(),
                                    err.getBytes(StandardCharsets.UTF_8),
                                    StandardCharsets.UTF_8);
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
                                if (data.isEmpty() || "[DONE]".equals(data)) {
                                    break;
                                }
                                String piece = parseDelta(data, acc);
                                if (piece != null && !piece.isEmpty() && onDelta != null) {
                                    onDelta.accept(piece);
                                }
                            }
                        }
                        return acc.toString();
                    });
        } catch (Exception e) {
            log.warn("dify remote stream failed: {}", e.getMessage());
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
    }

    private String parseDelta(String data, StringBuilder acc) {
        try {
            Map<?, ?> json = objectMapper.readValue(data, Map.class);
            Object event = json.get("event");
            String kind = event == null ? "message" : String.valueOf(event);
            if ("error".equals(kind) || "message_end".equals(kind) || "workflow_finished".equals(kind)) {
                return "";
            }
            Object answer = json.get("answer");
            if (answer == null) {
                return "";
            }
            String chunk = String.valueOf(answer);
            if (chunk.isBlank()) {
                return "";
            }
            String delta;
            if (!acc.isEmpty() && chunk.startsWith(acc.toString())) {
                delta = chunk.substring(acc.length());
                acc.setLength(0);
                acc.append(chunk);
            } else {
                delta = chunk;
                acc.append(chunk);
            }
            return delta;
        } catch (Exception e) {
            return "";
        }
    }

    private String chatMessagesUrl() {
        String base = properties.getCs().getDifyApiUrl().replaceAll("/$", "");
        if (base.endsWith("/chat-messages")) {
            return base;
        }
        if (base.endsWith("/v1")) {
            return base + "/chat-messages";
        }
        return base + "/v1/chat-messages";
    }

    private static String writerGuide() {
        return CsAnswerFormatter.writerGuide();
    }
}
