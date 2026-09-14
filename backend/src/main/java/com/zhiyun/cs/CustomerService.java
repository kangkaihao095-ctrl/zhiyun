package com.zhiyun.cs;

import com.zhiyun.agent.SkillRegistry;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.domain.Plan;
import com.zhiyun.harness.AgentIds;
import com.zhiyun.llm.AgentModelRouter;
import com.zhiyun.llm.LlmGateway;
import com.zhiyun.security.AuthUser;
import com.zhiyun.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

@Service
public class CustomerService {
    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    public record ChatTurn(String role, String content) {
    }

    public record ChatReq(List<ChatTurn> messages) {
    }

    private final ReadOnlyTools tools;
    private final LlmGateway llmGateway;
    private final ZhiyunProperties properties;
    private final DifyGateway difyGateway;
    private final SkillRegistry skillRegistry;
    private final AgentModelRouter modelRouter;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public CustomerService(ReadOnlyTools tools, LlmGateway llmGateway, ZhiyunProperties properties,
                           DifyGateway difyGateway, SkillRegistry skillRegistry, AgentModelRouter modelRouter,
                           ObjectMapper objectMapper) {
        this.tools = tools;
        this.llmGateway = llmGateway;
        this.properties = properties;
        this.difyGateway = difyGateway;
        this.skillRegistry = skillRegistry;
        this.modelRouter = modelRouter;
        this.objectMapper = objectMapper;
        this.clock = Clock.system(CsLocalTimes.SHANGHAI);
    }

    public String sse(ChatReq req) {
        StringBuilder buf = new StringBuilder();
        stream(req, token -> {
            try {
                buf.append("event: token\ndata:").append(objectMapper.writeValueAsString(token)).append("\n\n");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        buf.append("event: done\ndata:\"[DONE]\"\n\n");
        return buf.toString();
    }

    public void stream(ChatReq req, Consumer<String> onToken) {
        AuthUser user = TenantContext.require();
        List<ChatTurn> window = CsChatMemory.window(req.messages());
        String last = CsChatMemory.lastUser(window);
        Set<String> intents = CsIntent.classify(last, window);
        String grounded = ground(user, last, intents, window);
        if (CsIntent.billingOnly(intents)) {
            emitChunks(compose(user, last, intents, grounded, false), onToken);
            return;
        }
        if (difyGateway.enabled()) {
            try {
                StringBuilder acc = new StringBuilder();
                String dify = difyGateway.stream(last, user, window, grounded, acc::append);
                String body = dify != null && !dify.isBlank() ? dify : acc.toString();
                String guarded = guardLive(user, last, intents, body);
                if (guarded != null && !guarded.isBlank()) {
                    emitChunks(guarded, onToken);
                    return;
                }
            } catch (Exception e) {
                log.warn("dify chat failed, fallback to compose: {}", e.getMessage());
            }
            emitChunks(compose(user, last, intents, grounded, true), onToken);
            return;
        }
        if (!properties.dryRun()) {
            try {
                String live = llmGateway.complete(
                        modelRouter.chatModel(AgentIds.CS),
                        0.35,
                        llmMessages(window, grounded));
                String guarded = guardLive(user, last, intents, live);
                if (guarded != null && !guarded.isBlank()) {
                    emitChunks(guarded, onToken);
                    return;
                }
            } catch (Exception e) {
                log.warn("cs llm stream failed, fallback to compose: {}", e.getMessage());
                emitChunks(compose(user, last, intents, grounded, true), onToken);
                return;
            }
        }
        emitChunks(compose(user, last, intents, grounded, false), onToken);
    }

    private void emitChunks(String text, Consumer<String> onToken) {
        if (text == null || text.isBlank() || onToken == null) {
            return;
        }
        int i = 0;
        while (i < text.length()) {
            int end = Math.min(text.length(), i + 4);
            onToken.accept(text.substring(i, end));
            i = end;
            try {
                Thread.sleep(12);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                onToken.accept(text.substring(i));
                return;
            }
        }
    }

    public String reply(AuthUser user, String last, List<ChatTurn> window) {
        List<ChatTurn> turns = CsChatMemory.windowWithLast(window, last);
        Set<String> intents = CsIntent.classify(last, turns);
        String grounded = ground(user, last, intents, turns);
        if (CsIntent.billingOnly(intents)) {
            return compose(user, last, intents, grounded, false);
        }
        if (difyGateway.enabled()) {
            try {
                String dify = difyGateway.chat(last, user, turns, grounded);
                String guarded = guardLive(user, last, intents, dify);
                if (guarded != null && !guarded.isBlank()) {
                    return guarded;
                }
            } catch (Exception e) {
                log.warn("dify chat failed, fallback to compose: {}", e.getMessage());
            }
            return compose(user, last, intents, grounded, true);
        }
        if (!properties.dryRun()) {
            try {
                String live = llmGateway.complete(
                        modelRouter.chatModel(AgentIds.CS),
                        0.35,
                        llmMessages(turns, grounded));
                String guarded = guardLive(user, last, intents, live);
                if (guarded != null && !guarded.isBlank()) {
                    return guarded;
                }
            } catch (Exception e) {
                log.warn("cs llm failed, fallback to compose: {}", e.getMessage());
                return compose(user, last, intents, grounded, true);
            }
        }
        return compose(user, last, intents, grounded, false);
    }

    private List<Map<String, Object>> llmMessages(List<ChatTurn> window, String grounded) {
        String system = skillRegistry.systemMessage(AgentIds.CS)
                + "\nChat model=" + modelRouter.chatModel(AgentIds.CS)
                + "\n" + CsAnswerFormatter.writerGuide();
        return CsChatMemory.llmMessages(system, grounded, window);
    }

    private String guardLive(AuthUser user, String last, Set<String> intents, String live) {
        String format = CsIntent.formatIntent(intents, last);
        if (format.isBlank() || live == null || live.isBlank()) {
            return live == null ? "" : live.trim();
        }
        if (CsIntent.FORMAT_SPENT.equals(format) || CsIntent.FORMAT_ORDERS.equals(format)
                || CsIntent.FORMAT_QUOTA.equals(format)) {
            return CsAnswerFormatter.guard(format, billingTool(user, last, intents), live);
        }
        if (CsIntent.FORMAT_MODELS.equals(format)) {
            return CsAnswerFormatter.guard(format, tools.modelConfig(user), live);
        }
        return live.trim();
    }

    private String ground(AuthUser user, String last, Set<String> intents, List<ChatTurn> window) {
        StringBuilder sb = new StringBuilder();
        boolean billing = intents.contains(CsIntent.SPEND) || intents.contains(CsIntent.ORDERS)
                || intents.contains(CsIntent.USAGE);
        Map<String, Object> snapshot = billing ? billingTool(user, last, intents) : Map.of();
        if (billing) {
            sb.append("usage_query.totalPaidYuan=").append(snapshot.get("totalPaidYuan"))
                    .append(" totalPendingYuan=").append(snapshot.get("totalPendingYuan"))
                    .append(" rechargeCount=").append(snapshot.get("rechargeCount"))
                    .append(" quotaBalance=").append(snapshot.get("quotaBalance"))
                    .append(" quotaConsumed=").append(snapshot.get("quotaConsumed"))
                    .append(" （合计由 Java Tool 给出，禁止口算；订单≠消费）\n");
            String format = CsIntent.formatIntent(intents, last);
            if (!format.isBlank()) {
                sb.append("canonical_answer=\n")
                        .append(CsAnswerFormatter.formatCsAnswer(format, snapshot)).append('\n');
            }
        }
        if (intents.contains(CsIntent.PLANS)) {
            sb.append("plans:\n");
            List<Map<String, Object>> planRows = new ArrayList<>();
            for (Plan plan : tools.plans()) {
                sb.append("- ").append(plan.getName()).append(" / ").append(plan.getCode())
                        .append(" ：").append(plan.getQuotaAmount()).append(" 额度 / ¥")
                        .append(plan.getPriceCents() / 100)
                        .append("。").append(plan.getDescription()).append('\n');
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", plan.getName());
                row.put("quotaAmount", plan.getQuotaAmount());
                row.put("priceYuan", plan.getPriceCents() / 100);
                planRows.add(row);
            }
            sb.append(billingPolicy()).append(" 支付在「充值」页确认即可入账。\n");
            sb.append("canonical_plans=\n").append(CsAnswerFormatter.formatCsAnswer(CsIntent.FORMAT_PLANS,
                    Map.of("plans", planRows))).append('\n');
        }
        if (intents.contains(CsIntent.ORDERS)) {
            sb.append("order_query.range=").append(snapshot.get("range"))
                    .append(" matchedCount=").append(snapshot.get("matchedCount"))
                    .append(" recentLimit=").append(snapshot.get("recentLimit"))
                    .append(" totalOrders=").append(snapshot.get("totalOrders"))
                    .append(" orders=").append(snapshot.get("orders")).append('\n');
            if (snapshot.get("range") != null) {
                sb.append("有日期条件：只根据 range 内的匹配列表作答，禁止改用「最近 5 笔」全局模板。\n");
            }
        }
        if (intents.contains(CsIntent.USAGE) && !intents.contains(CsIntent.ORDERS) && !intents.contains(CsIntent.SPEND)) {
            sb.append("ledger_query=").append(tools.ledgerQuery(user, CsOrderQuery.fromQuestion(last, clock))).append('\n');
        }
        if (intents.contains(CsIntent.TASK) || intents.contains(CsIntent.PAPER)) {
            String id = CsIntent.extractLookupKey(last);
            sb.append("task_list=").append(tools.listMyTasks(user)).append('\n');
            if (id == null) {
                sb.append("paper_lookup：用户未给数字。可先列出任务/稿件，或请对方提供任务 ID / 稿件 ID。\n");
            } else {
                try {
                    sb.append("paper_lookup=").append(tools.lookupPaper(user, id)).append('\n');
                } catch (Exception e) {
                    sb.append("paper_lookup：本账户没有该 id ").append(id)
                            .append("。可提供任务 ID / 稿件 ID / 订单号。\n");
                }
            }
        }
        if (intents.contains(CsIntent.INBOX)) {
            sb.append("inbox_unread=").append(tools.unreadInbox(user)).append('\n');
        }
        if (intents.contains(CsIntent.PROFILE)) {
            sb.append("account_profile=").append(tools.accountProfile(user)).append('\n');
        }
        if (intents.contains(CsIntent.MODELS)) {
            sb.append("model_config=").append(tools.modelConfig(user)).append('\n');
            sb.append("canonical_howto_models=\n")
                    .append(CsAnswerFormatter.formatCsAnswer(CsIntent.FORMAT_MODELS, Map.of())).append('\n');
        }
        if (intents.contains(CsIntent.CITATION)) {
            String id = CsIntent.extractLookupKey(last);
            if (id != null) {
                try {
                    sb.append("citation_result=").append(tools.citationResult(user, id)).append('\n');
                } catch (Exception e) {
                    sb.append("citation_result：找不到任务 ").append(id).append('\n');
                }
            }
        }
        String ragQuery = CsChatMemory.retrievalQuery(window, last);
        List<String> rag = tools.knowledge(ragQuery.isBlank() ? "platform billing workflow" : ragQuery);
        if (!rag.isEmpty()) {
            sb.append("PUBLIC_RAG:\n");
            int n = 0;
            for (String chunk : rag) {
                if (n++ >= 4) {
                    break;
                }
                sb.append("- ").append(chunk.replace('\n', ' ')).append('\n');
            }
        }
        return sb.toString();
    }

    private String compose(AuthUser user, String last, Set<String> intents, String grounded, boolean modelFailed) {
        StringBuilder out = new StringBuilder();
        Map<String, Object> snapshot = billingTool(user, last, intents);
        String format = CsIntent.formatIntent(intents, last);
        if (CsIntent.FORMAT_SPENT.equals(format) || CsIntent.FORMAT_ORDERS.equals(format)
                || CsIntent.FORMAT_QUOTA.equals(format)) {
            out.append(CsAnswerFormatter.formatCsAnswer(format, snapshot)).append("\n\n");
        }
        if (intents.contains(CsIntent.PLANS)) {
            List<Map<String, Object>> planRows = new ArrayList<>();
            for (Plan plan : tools.plans()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", plan.getName());
                row.put("quotaAmount", plan.getQuotaAmount());
                row.put("priceYuan", plan.getPriceCents() / 100);
                planRows.add(row);
            }
            out.append(CsAnswerFormatter.formatCsAnswer(CsIntent.FORMAT_PLANS, Map.of("plans", planRows)));
            out.append("\n\n");
            out.append(billingPolicy()).append("\n\n");
        }
        if (intents.contains(CsIntent.PROFILE)) {
            Map<String, Object> profile = tools.accountProfile(user);
            out.append("这是当前登录账户：\n\n");
            out.append("- **显示名**：").append(profile.get("displayName")).append('\n');
            out.append("- **邮箱**：").append(profile.get("email")).append('\n').append('\n');
        }
        if (intents.contains(CsIntent.INBOX)) {
            appendInbox(out, user);
        }
        if (intents.contains(CsIntent.MODELS)) {
            out.append(CsAnswerFormatter.formatCsAnswer(CsIntent.FORMAT_MODELS, tools.modelConfig(user)));
            out.append("\n\n");
        }
        if (intents.contains(CsIntent.TASK) || intents.contains(CsIntent.PAPER)) {
            appendPaper(out, user, last);
        }
        String ragHint = intents.contains(CsIntent.MODELS) ? "" : knowledgeAnswer(last, grounded);
        if (!ragHint.isBlank()) {
            out.append(ragHint);
            if (!ragHint.endsWith("\n")) {
                out.append('\n');
            }
            out.append('\n');
        }
        if (out.isEmpty()) {
            out.append("😊 我可以帮你查额度、充值记录、订单、审校任务和稿件。\n\n");
            out.append("- 直接发任务 ID 或稿件 ID\n");
            out.append("- 也可以问套餐价格、IEEE / ACL / NeurIPS 投稿规范\n");
        }
        if (modelFailed) {
            out.append("\n（模型暂时不可用，以上为规则答复。）");
        } else if (properties.dryRun()) {
            out.append("\n（当前 dry-run，没有调用大模型；数字来自账户查询，规则来自知识库。）");
        }
        return out.toString().trim();
    }

    private String knowledgeAnswer(String last, String grounded) {
        String q = last.toLowerCase(Locale.ROOT);
        if (q.contains("acl") && (q.contains("a4") || q.contains("letter") || q.contains("纸") || q.contains("模板") || q.contains("latex") || q.contains("word"))) {
            return "📄 ACL 必须用 A4（21cm × 29.7cm）双栏，终稿只收嵌入全部字体的 PDF。不要套用 NeurIPS 的 US Letter 单栏，也不要用 IEEE 模板冒充 ACL。";
        }
        if (q.contains("neurips") && (q.contains("word") || q.contains("latex") || q.contains("模板") || q.contains("交"))) {
            return "📄 NeurIPS 主赛道 Word 模板已停用，只能用当年官方 LaTeX 生成单个 PDF（常见 US Letter、单栏、10pt）。不能交 Word。";
        }
        if (q.contains("ieee") && (q.contains("express") || q.contains("pdf") || q.contains("word") || q.contains("latex") || q.contains("模板"))) {
            return "📄 IEEE 会议可用官方 Word 或 LaTeX（IEEEtran）排版，camera-ready 几乎一律交 PDF，且通常要过 IEEE PDF eXpress。纸张是 A4 还是 Letter 以该会 CFP 为准。";
        }
        if (q.contains("firstly") || q.contains("润色") || q.contains("humanizer") || q.contains("ai味") || q.contains("ai 味")) {
            return "✨ 学术润色应去掉 Firstly / In conclusion, this paper has demonstrated 等套话，保护数字、公式和引用。语言润色不会去检索文献。";
        }
        if (q.contains("额度") || q.contains("套餐") || q.contains("订单") || q.contains("多少钱")
                || q.contains("花了") || q.contains("消费")) {
            return "";
        }
        boolean venue = q.contains("ieee") || q.contains("acl") || q.contains("neurips") || q.contains("nature")
                || q.contains("elsevier") || q.contains("springer") || q.contains("icml") || q.contains("iclr")
                || q.contains("aaai") || q.contains("colm") || q.contains("osdi") || q.contains("投稿") || q.contains("模板")
                || q.contains("引用") || q.contains("doi") || q.contains("latex") || q.contains("word");
        return venue ? firstRagExcerpt(grounded) : "";
    }

    private String firstRagExcerpt(String grounded) {
        int idx = grounded.indexOf("PUBLIC_RAG:");
        if (idx < 0) {
            return "";
        }
        String[] lines = grounded.substring(idx).split("\n");
        for (String line : lines) {
            String t = line.startsWith("- ") ? line.substring(2).trim() : line.trim();
            if (t.length() < 24 || t.startsWith("PUBLIC_RAG")) {
                continue;
            }
            return t.length() > 420 ? t.substring(0, 420) + "…" : t;
        }
        return "";
    }

    private Map<String, Object> billingTool(AuthUser user, String last, Set<String> intents) {
        if (intents.contains(CsIntent.ORDERS)) {
            return tools.orderQuery(user, CsOrderQuery.fromQuestion(last, clock));
        }
        return tools.accountSnapshot(user);
    }

    private void appendPaper(StringBuilder out, AuthUser user, String last) {
        String id = CsIntent.extractLookupKey(last);
        if (id != null) {
            try {
                Map<String, Object> hit = tools.lookupPaper(user, id);
                out.append("📋 已按本账户查过 ID **").append(id).append("**。\n\n");
                if ("taskId".equals(hit.get("matched")) && hit.get("task") instanceof Map<?, ?> task) {
                    appendTaskFields(out, task);
                } else if ("manuscriptId".equals(hit.get("matched"))) {
                    if (hit.get("manuscript") instanceof Map<?, ?> ms) {
                        out.append("- **稿件 ID**：").append(ms.get("id")).append('\n');
                        out.append("- **标题**：").append(ms.get("title")).append('\n');
                        out.append("- **课题**：").append(ms.get("projectName")).append('\n');
                        out.append("- **版本**：").append(ms.get("currentVersion")).append('\n');
                    }
                    if (hit.get("tasks") instanceof List<?> tasks && !tasks.isEmpty()) {
                        out.append('\n').append("相关审校任务：\n");
                        for (Object item : tasks) {
                            if (item instanceof Map<?, ?> task) {
                                out.append('\n');
                                appendTaskFields(out, task);
                            }
                        }
                    } else {
                        out.append('\n').append("这篇稿件还没有审校任务。\n");
                    }
                }
                out.append('\n');
                return;
            } catch (Exception e) {
                out.append(CsAnswerFormatter.formatCsAnswer(CsIntent.FORMAT_MISSING, Map.of())).append("\n\n");
                return;
            }
        }
        List<Map<String, Object>> tasks = tools.listMyTasks(user);
        if (tasks.isEmpty()) {
            out.append("本账户还没有审校任务。\n\n");
            out.append("可以从「我的论文」上传后开始一次，或直接发任务 ID / 稿件 ID。\n\n");
            return;
        }
        out.append("📋 这是你最近的审校任务：\n\n");
        for (Map<String, Object> task : tasks) {
            appendTaskFields(out, task);
            out.append('\n');
        }
    }

    private static void appendTaskFields(StringBuilder out, Map<?, ?> task) {
        out.append("- **任务 ID**：").append(task.get("taskId")).append('\n');
        out.append("- **状态**：").append(task.get("status")).append('\n');
        out.append("- **流程**：").append(task.get("workflow")).append('\n');
        Object venue = task.get("targetVenue");
        if (venue != null && !String.valueOf(venue).isBlank()) {
            out.append("- **投稿目标**：").append(venue).append('\n');
        }
        out.append("- **稿件 ID**：").append(task.get("manuscriptId")).append('\n');
        Object title = task.get("manuscriptTitle");
        if (title != null && !String.valueOf(title).isBlank()) {
            out.append("- **稿件标题**：").append(title).append('\n');
        }
    }

    private void appendInbox(StringBuilder out, AuthUser user) {
        Map<String, Object> inbox = tools.unreadInbox(user);
        int unread = ((Number) inbox.get("unreadCount")).intValue();
        out.append("未读站内信 **").append(unread).append("** 条。\n\n");
        if (unread == 0) {
            return;
        }
        Object items = inbox.get("items");
        if (items instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> row) {
                    out.append("- **").append(row.get("title")).append("**（").append(row.get("refId")).append("）\n");
                }
            }
        }
        out.append('\n');
    }

    private String billingPolicy() {
        ZhiyunProperties.Quota q = properties.getQuota();
        return "扣费按本次审校实际消耗折算：每 " + q.getTokensPerPoint()
                + " token 计 1 额度，向上取整，最少 1 额度。"
                + " 引用核验上限 " + q.getCapCitation()
                + " 额度，快速审读上限 " + q.getCapQuick()
                + " 额度，投稿前完整审校上限 " + q.getCapFull()
                + " 额度。余额不足 1 额度无法开始。"
                + " 灵活充值最低 10 元、1 元 1 额度；买套餐同样的钱能拿到更多额度。";
    }
}

