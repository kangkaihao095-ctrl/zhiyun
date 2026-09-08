package com.zhiyun.cs;

import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.security.AuthUser;
import com.zhiyun.security.JwtService;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mcp")
public class McpController {
    private final ZhiyunProperties properties;
    private final ReadOnlyTools tools;
    private final JwtService jwtService;
    private final Environment environment;

    public McpController(ZhiyunProperties properties, ReadOnlyTools tools, JwtService jwtService,
                         Environment environment) {
        this.properties = properties;
        this.tools = tools;
        this.jwtService = jwtService;
        this.environment = environment;
    }

    public record ToolCall(String tool, Object taskId, Object manuscriptId, Object id, Object orderId,
                           String query, String userToken, String from, String to, String status, Integer limit) {
        public String orderKey() {
            return textOf(orderId);
        }

        public String lookupKey() {
            String task = textOf(taskId);
            if (task != null) {
                return task;
            }
            String ms = textOf(manuscriptId);
            if (ms != null) {
                return ms;
            }
            return textOf(id);
        }

        public CsOrderQuery orderFilter() {
            return new CsOrderQuery(orderKey(), from, to, status, limit);
        }

        public Long resolvedId() {
            String key = lookupKey();
            if (key == null) {
                return null;
            }
            try {
                return Long.parseLong(key);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static String textOf(Object raw) {
            if (raw == null) {
                return null;
            }
            String text = String.valueOf(raw).trim();
            if (text.isEmpty() || "null".equals(text)) {
                return null;
            }
            return text;
        }
    }

    @GetMapping("/tools")
    public Object catalog(@RequestHeader(value = "X-Zhiyun-Mcp-Token", required = false) String token) {
        requireMcp(token);
        return Map.of("tools", toolSchemas());
    }

    @PostMapping(value = "/call", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object call(@RequestHeader(value = "X-Zhiyun-Mcp-Token", required = false) String token,
                       @RequestBody ToolCall body) {
        return dispatch(token, body);
    }

    @PostMapping(value = "/usage_query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object usageQuery(@RequestHeader(value = "X-Zhiyun-Mcp-Token", required = false) String token,
                             @RequestBody ToolCall body) {
        return dispatch(token, withTool(body, "usage_query"));
    }

    @PostMapping(value = "/order_query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object orderQuery(@RequestHeader(value = "X-Zhiyun-Mcp-Token", required = false) String token,
                             @RequestBody ToolCall body) {
        return dispatch(token, withTool(body, "order_query"));
    }

    @PostMapping(value = "/task_status", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object taskStatus(@RequestHeader(value = "X-Zhiyun-Mcp-Token", required = false) String token,
                             @RequestBody ToolCall body) {
        return dispatch(token, withTool(body, "task_status"));
    }

    @PostMapping(value = "/task_list", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object taskList(@RequestHeader(value = "X-Zhiyun-Mcp-Token", required = false) String token,
                           @RequestBody ToolCall body) {
        return dispatch(token, withTool(body, "task_list"));
    }

    @PostMapping(value = "/paper_lookup", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object paperLookup(@RequestHeader(value = "X-Zhiyun-Mcp-Token", required = false) String token,
                              @RequestBody ToolCall body) {
        return dispatch(token, withTool(body, "paper_lookup"));
    }

    @PostMapping(value = "/manuscript_list", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object manuscriptList(@RequestHeader(value = "X-Zhiyun-Mcp-Token", required = false) String token,
                                 @RequestBody ToolCall body) {
        return dispatch(token, withTool(body, "manuscript_list"));
    }

    @PostMapping(value = "/manuscript_get", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object manuscriptGet(@RequestHeader(value = "X-Zhiyun-Mcp-Token", required = false) String token,
                                @RequestBody ToolCall body) {
        return dispatch(token, withTool(body, "manuscript_get"));
    }

    @PostMapping(value = "/ledger_query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object ledgerQuery(@RequestHeader(value = "X-Zhiyun-Mcp-Token", required = false) String token,
                              @RequestBody ToolCall body) {
        return dispatch(token, withTool(body, "ledger_query"));
    }

    @PostMapping(value = "/plan_list", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object planList(@RequestHeader(value = "X-Zhiyun-Mcp-Token", required = false) String token,
                           @RequestBody ToolCall body) {
        return dispatch(token, withTool(body, "plan_list"));
    }

    @PostMapping(value = "/inbox_unread", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object inboxUnread(@RequestHeader(value = "X-Zhiyun-Mcp-Token", required = false) String token,
                              @RequestBody ToolCall body) {
        return dispatch(token, withTool(body, "inbox_unread"));
    }

    @PostMapping(value = "/account_profile", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object accountProfile(@RequestHeader(value = "X-Zhiyun-Mcp-Token", required = false) String token,
                                 @RequestBody ToolCall body) {
        return dispatch(token, withTool(body, "account_profile"));
    }

    @PostMapping(value = "/model_config", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object modelConfig(@RequestHeader(value = "X-Zhiyun-Mcp-Token", required = false) String token,
                              @RequestBody ToolCall body) {
        return dispatch(token, withTool(body, "model_config"));
    }

    @PostMapping(value = "/citation_result", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object citationResult(@RequestHeader(value = "X-Zhiyun-Mcp-Token", required = false) String token,
                                 @RequestBody ToolCall body) {
        return dispatch(token, withTool(body, "citation_result"));
    }

    @PostMapping(value = "/knowledge_retrieval", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object knowledge(@RequestHeader(value = "X-Zhiyun-Mcp-Token", required = false) String token,
                            @RequestBody ToolCall body) {
        return dispatch(token, withTool(body, "knowledge_retrieval"));
    }

    private Object dispatch(String token, ToolCall body) {
        requireMcp(token);
        if (body == null || body.userToken() == null || body.userToken().isBlank()) {
            throw new com.zhiyun.common.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "user token required");
        }
        AuthUser user = jwtService.parse(body.userToken());
        String tool = body.tool();
        String key = body.lookupKey();
        Long id = body.resolvedId();
        return switch (tool == null ? "" : tool) {
            case "task_status" -> key == null ? tools.listMyTasks(user) : tools.lookupPaper(user, key);
            case "task_list" -> tools.listMyTasks(user);
            case "paper_lookup" -> tools.lookupPaper(user, key);
            case "manuscript_list" -> tools.listMyManuscripts(user);
            case "manuscript_get" -> tools.getManuscript(user, id);
            case "usage_query" -> tools.usage(user);
            case "ledger_query" -> tools.ledgerQuery(user, body.orderFilter());
            case "order_query" -> tools.orderQuery(user, body.orderFilter());
            case "plan_list" -> tools.plans();
            case "inbox_unread" -> tools.unreadInbox(user);
            case "account_profile" -> tools.accountProfile(user);
            case "model_config" -> tools.modelConfig(user);
            case "citation_result" -> tools.citationResult(user, key);
            case "knowledge_retrieval" -> tools.knowledge(body.query() == null ? "" : body.query());
            default -> Map.of("error", "unknown tool");
        };
    }

    private void requireMcp(String token) {
        String[] profiles = environment.getActiveProfiles();
        if (!properties.mcpEnabled(profiles)
                || token == null
                || !token.equals(properties.resolvedMcpToken(profiles))) {
            throw new com.zhiyun.common.ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "invalid MCP token");
        }
    }

    private static ToolCall withTool(ToolCall body, String tool) {
        if (body == null) {
            return new ToolCall(tool, null, null, null, null, null, null, null, null, null, null);
        }
        return new ToolCall(tool, body.taskId(), body.manuscriptId(), body.id(), body.orderId(),
                body.query(), body.userToken(), body.from(), body.to(), body.status(), body.limit());
    }

    private static List<Map<String, String>> toolSchemas() {
        return List.of(
                Map.of("name", "task_list", "args", "(none)", "desc", "列出我的审校任务"),
                Map.of("name", "task_status", "args", "taskId|manuscriptId|id", "desc", "按任务或稿件 ID 查状态"),
                Map.of("name", "paper_lookup", "args", "id", "desc", "数字可能是 taskId 或 manuscriptId"),
                Map.of("name", "manuscript_list", "args", "(none)", "desc", "列出我的稿件"),
                Map.of("name", "manuscript_get", "args", "manuscriptId|id", "desc", "查一篇稿件"),
                Map.of("name", "usage_query", "args", "(none)", "desc", "额度余额、消耗合计与充值汇总"),
                Map.of("name", "ledger_query", "args", "from?,to?,limit?", "desc", "额度流水；from/to 为 Asia/Shanghai 日历日，Java 参数化过滤"),
                Map.of("name", "order_query", "args", "orderId?,from?,to?,status?,limit?",
                        "desc", "结构化过滤后返回 range/命中条数/匹配列表；禁止模型写 SQL"),
                Map.of("name", "plan_list", "args", "(none)", "desc", "套餐目录只读"),
                Map.of("name", "inbox_unread", "args", "(none)", "desc", "未读站内信摘要"),
                Map.of("name", "account_profile", "args", "(none)", "desc", "显示名与邮箱，无密钥"),
                Map.of("name", "model_config", "args", "(none)", "desc", "当前模型配置，无完整 API Key"),
                Map.of("name", "citation_result", "args", "taskId", "desc", "引用核验结果"),
                Map.of("name", "knowledge_retrieval", "args", "query", "desc", "公共 FAQ / 套餐规则")
        );
    }
}
