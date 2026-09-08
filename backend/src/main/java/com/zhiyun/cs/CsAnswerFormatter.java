package com.zhiyun.cs;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 客服标准作答结构。数字必须来自 Tool JSON，禁止让模型口算。
 */
public final class CsAnswerFormatter {
    private CsAnswerFormatter() {
    }

    public static String writerGuide() {
        return """
先判断意图再答，禁止每次把全部订单无差别倾倒。
「花了多少钱 / 消费合计」：先两行说清人民币合计——已支付充值（PAID，字段 totalPaidYuan）vs 额度消耗（quotaConsumed）。不要把订单清单当消费。
「历史订单 / 充值 / 消费情况」：无日期时充值合计 + 额度消耗 + 最近几笔（orders 已截断）。
用户说今天/昨天/本周时，先把日历日算成 from/to 再调 order_query；只根据 Tool 返回的 range 与匹配列表作答。有日期条件时禁止改用「最近 5 笔」全局模板；0 条就说这两天没有订单。禁止让模型写 SQL。
金额、余额、合计只能抄 Grounded 里的 totalPaidYuan / totalPendingYuan / quotaBalance / quotaConsumed，禁止口算。
问怎么用、怎么操作某功能时，先检索 PUBLIC RAG 产品说明，或根据 account_profile / model_config 指路真实页面；禁止编造未上线的开放 API、通用审校 API Key、联系商务定制。
模型配置：打开设置 → 点模型配置（/account?panel=models）→ 选提供商、填 Base URL 与 Key → 保存。论文 7 个 Agent 可填用户自己的 OpenAI 兼容 Key，平台收技能费；云笺和 RAG 仍走平台模型，不可换。Style 不授 AcademicSearch。额度在右上角，充值在旁边弹窗。
必须用短段落 + Markdown 项目符号；每个关键字段一行。查不到时单独一行写「本账户没有该 ID」。
每条回复用 1 到 3 个贴切 emoji。可用 **加粗** 和列表，不要表格或代码块。
配额对外一律称「额度」。套餐标价只能来自 plans。不要输出 JSON。
""";
    }

    public static String formatCsAnswer(String intent, Map<String, Object> toolResult) {
        String kind = intent == null ? "" : intent.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> tool = toolResult == null ? Map.of() : toolResult;
        return switch (kind) {
            case CsIntent.FORMAT_SPENT -> formatSpent(tool);
            case CsIntent.FORMAT_ORDERS -> formatOrders(tool);
            case CsIntent.FORMAT_QUOTA -> formatQuota(tool);
            case CsIntent.FORMAT_TASK -> formatTask(tool);
            case CsIntent.FORMAT_MISSING -> formatMissing();
            case CsIntent.FORMAT_PLANS -> formatPlans(tool);
            case CsIntent.FORMAT_MODELS -> formatHowToModels();
            default -> "";
        };
    }

    /**
     * 模型若把「花了多少钱」答成订单倾倒，用标准结构替换。失败标 regression，不是 SLA。
     */
    public static String guard(String intent, Map<String, Object> toolResult, String live) {
        String canonical = formatCsAnswer(intent, toolResult);
        if (canonical.isBlank()) {
            return live == null ? "" : live.trim();
        }
        if (live == null || live.isBlank()) {
            return canonical;
        }
        String text = live.trim();
        if (matchesKeypoints(intent, toolResult, text)) {
            return text;
        }
        return canonical;
    }

    public static boolean matchesKeypoints(String intent, Map<String, Object> toolResult, String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        Map<String, Object> tool = toolResult == null ? Map.of() : toolResult;
        String kind = intent == null ? "" : intent.trim().toLowerCase(Locale.ROOT);
        String paid = "¥" + yuan(tool, "totalPaidYuan");
        return switch (kind) {
            case CsIntent.FORMAT_SPENT -> textHas(answer, paid)
                    && answer.contains("额度")
                    && countNeedle(answer, "订单号") == 0
                    && !looksLikeOrderDump(answer);
            case CsIntent.FORMAT_ORDERS -> rangeOf(tool) != null
                    ? matchesRangedOrders(tool, answer)
                    : textHas(answer, paid)
                    && (answer.contains("充值") || answer.contains("订单"))
                    && answer.contains("额度")
                    && !answer.equals(formatSpent(tool));
            case CsIntent.FORMAT_QUOTA -> answer.contains(String.valueOf(num(tool, "quotaBalance")))
                    && answer.contains("额度");
            case CsIntent.FORMAT_TASK -> answer.contains("任务") && answer.contains("\n");
            case CsIntent.FORMAT_MISSING -> answer.contains("本账户没有该 ID");
            case CsIntent.FORMAT_PLANS -> answer.contains("套餐") && answer.contains("¥");
            case CsIntent.FORMAT_MODELS -> matchesHowToModels(answer);
            default -> true;
        };
    }

    private static String formatSpent(Map<String, Object> tool) {
        int paid = yuan(tool, "totalPaidYuan");
        int pending = yuan(tool, "totalPendingYuan");
        int consumed = num(tool, "quotaConsumed");
        int balance = num(tool, "quotaBalance");
        StringBuilder out = new StringBuilder();
        out.append("😊 先说合计：你已经支付的充值是 **¥").append(paid).append("**，额度消耗是另一笔账。\n\n");
        out.append("- **已支付充值**：¥").append(paid).append("（仅 PAID；¥0 演示单不计入）\n");
        out.append("- **额度消耗**：").append(consumed).append(" 额度（流水扣减，不是订单）\n");
        out.append("- **当前余额**：").append(balance).append(" 额度\n\n");
        out.append("待支付 ¥").append(pending).append(" 尚未计入「花了多少钱」。订单在本产品里是充值单，不能当成消费清单。");
        return out.toString();
    }

    private static String formatOrders(Map<String, Object> tool) {
        Map<?, ?> range = rangeOf(tool);
        if (range != null) {
            return formatRangedOrders(tool, range);
        }
        int paid = yuan(tool, "totalPaidYuan");
        int pending = yuan(tool, "totalPendingYuan");
        int consumed = num(tool, "quotaConsumed");
        int balance = num(tool, "quotaBalance");
        int recharge = num(tool, "rechargeCount");
        int total = num(tool, "totalOrders");
        int limit = Math.max(1, num(tool, "recentLimit") == 0 ? 5 : num(tool, "recentLimit"));
        StringBuilder out = new StringBuilder();
        out.append("📋 订单在本产品里是**充值单**，不是消费清单。\n\n");
        out.append("- **已支付充值合计**：¥").append(paid).append("（").append(recharge).append(" 笔已支付）\n");
        out.append("- **额度消耗**：").append(consumed).append(" 额度\n");
        out.append("- **当前余额**：").append(balance).append(" 额度\n");
        out.append("- **待支付**：¥").append(pending).append('\n');
        List<?> orders = listOf(tool.get("orders"));
        if (orders.isEmpty()) {
            out.append("\n本账户没有充值订单。");
            return out.toString();
        }
        int shown = Math.min(limit, orders.size());
        out.append('\n');
        if (total > shown) {
            out.append("共 ").append(total).append(" 笔，这里只列最近 ").append(shown).append(" 笔：\n");
        } else {
            out.append("最近充值：\n");
        }
        int n = 0;
        for (Object item : orders) {
            if (n++ >= shown) {
                break;
            }
            if (item instanceof Map<?, ?> order) {
                out.append("- **订单号**：").append(order.get("orderNo"));
                out.append(" · ¥").append(orderYuan(order));
                out.append(" · ").append(blankToDash(order.get("status")));
                if (truthy(order.get("demo"))) {
                    out.append(" · 演示单");
                }
                out.append(" · ").append(blankToDash(order.get("createdAt"))).append('\n');
            }
        }
        return out.toString().trim();
    }

    private static String formatRangedOrders(Map<String, Object> tool, Map<?, ?> range) {
        String from = String.valueOf(range.get("from"));
        String to = String.valueOf(range.get("to"));
        int matched = num(tool, "matchedCount");
        if (matched == 0) {
            matched = listOf(tool.get("orders")).size();
        }
        int paid = yuan(tool, "totalPaidYuan");
        int pending = yuan(tool, "totalPendingYuan");
        StringBuilder out = new StringBuilder();
        out.append("📋 已按 **").append(from).append("～").append(to)
                .append("**（Asia/Shanghai）查过本账户订单。\n\n");
        out.append("- **查询范围**：").append(from).append(" 至 ").append(to).append('\n');
        out.append("- **命中**：").append(matched).append(" 笔\n");
        out.append("- **范围内已支付充值**：¥").append(paid).append('\n');
        out.append("- **范围内待支付**：¥").append(pending).append('\n');
        List<?> orders = listOf(tool.get("orders"));
        if (orders.isEmpty() || matched == 0) {
            if (daySpan(from, to) == 2) {
                out.append("\n这两天没有订单。");
            } else {
                out.append("\n该日期范围内没有订单。");
            }
            return out.toString().trim();
        }
        out.append('\n').append("范围内订单：\n");
        for (Object item : orders) {
            if (item instanceof Map<?, ?> order) {
                out.append("- **订单号**：").append(order.get("orderNo"));
                out.append(" · ¥").append(orderYuan(order));
                out.append(" · ").append(blankToDash(order.get("status")));
                if (truthy(order.get("demo"))) {
                    out.append(" · 演示单");
                }
                out.append(" · ").append(blankToDash(order.get("createdAt"))).append('\n');
            }
        }
        return out.toString().trim();
    }

    private static boolean matchesRangedOrders(Map<String, Object> tool, String answer) {
        Map<?, ?> range = rangeOf(tool);
        if (range == null) {
            return false;
        }
        String from = String.valueOf(range.get("from"));
        String to = String.valueOf(range.get("to"));
        if (!answer.contains(from) || !answer.contains(to)) {
            return false;
        }
        if (answer.contains("最近")) {
            return false;
        }
        int matched = num(tool, "matchedCount");
        if (matched == 0 && listOf(tool.get("orders")).isEmpty()) {
            return answer.contains("没有订单");
        }
        return answer.contains("订单");
    }

    private static Map<?, ?> rangeOf(Map<String, Object> tool) {
        Object raw = tool.get("range");
        return raw instanceof Map<?, ?> map ? map : null;
    }

    private static long daySpan(String from, String to) {
        try {
            return java.time.LocalDate.parse(from).until(java.time.LocalDate.parse(to)).getDays() + 1L;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String formatQuota(Map<String, Object> tool) {
        int balance = num(tool, "quotaBalance");
        if (balance == 0 && tool.get("balance") != null) {
            balance = num(tool, "balance");
        }
        int consumed = num(tool, "quotaConsumed");
        StringBuilder out = new StringBuilder();
        out.append("😊 查过你的账户，现在还剩 **").append(balance).append("** 额度。\n\n");
        out.append("- 这是账户余额，不是套餐里的标称额度\n");
        out.append("- **已消耗**：").append(consumed).append(" 额度（流水扣减）\n");
        return out.toString().trim();
    }

    private static String formatTask(Map<String, Object> tool) {
        StringBuilder out = new StringBuilder();
        Object task = tool.get("task");
        Map<?, ?> row = task instanceof Map<?, ?> map ? map : tool;
        Object id = first(row.get("taskId"), tool.get("taskId"));
        out.append("📋 已按本账户查过");
        if (id != null) {
            out.append(" ID **").append(id).append("**");
        }
        out.append("。\n\n");
        out.append("- **任务 ID**：").append(blankToDash(first(row.get("taskId"), tool.get("taskId")))).append('\n');
        out.append("- **状态**：").append(blankToDash(first(row.get("status"), tool.get("status")))).append('\n');
        Object workflow = first(row.get("workflow"), tool.get("workflow"));
        if (workflow != null && !String.valueOf(workflow).isBlank()) {
            out.append("- **流程**：").append(workflow).append('\n');
        }
        Object venue = first(row.get("targetVenue"), tool.get("targetVenue"));
        if (venue != null && !String.valueOf(venue).isBlank()) {
            out.append("- **投稿目标**：").append(venue).append('\n');
        }
        Object ms = first(row.get("manuscriptId"), tool.get("manuscriptId"));
        if (ms != null) {
            out.append("- **稿件 ID**：").append(ms).append('\n');
        }
        Object title = first(row.get("manuscriptTitle"), tool.get("manuscriptTitle"));
        if (title != null && !String.valueOf(title).isBlank()) {
            out.append("- **稿件标题**：").append(title).append('\n');
        }
        return out.toString().trim();
    }

    private static String formatMissing() {
        return """
本账户没有该 ID

请再提供：
- 任务 ID
- 稿件 ID
- 订单号""";
    }

    private static String formatPlans(Map<String, Object> tool) {
        StringBuilder out = new StringBuilder();
        out.append("✨ 套餐规则可以按知识库说明来买；**你账户要付多少钱以套餐表 / Tool 为准**。\n\n");
        out.append("打开「充值」页选套餐或输入金额（灵活充值最低 10 元，1 元 1 额度），确认支付后额度到账。\n");
        List<?> plans = listOf(tool.get("plans"));
        if (!plans.isEmpty()) {
            out.append('\n').append("套餐标价如下（不是你的当前余额）：\n");
            for (Object item : plans) {
                if (item instanceof Map<?, ?> plan) {
                    out.append("- **").append(blankToDash(plan.get("name"))).append("**：")
                            .append(first(plan.get("quotaAmount"), plan.get("quota"))).append(" 额度 / ¥")
                            .append(first(plan.get("priceYuan"), plan.get("price"))).append('\n');
                }
            }
        }
        return out.toString().trim();
    }

    private static String formatHowToModels() {
        return """
🔧 论文模型在 **设置 → 模型配置**（`/account?panel=models`）里改。

操作步骤：
1. 打开左侧 **设置**
2. 点 **模型配置**
3. 点开某一个论文 Agent，选提供商，填写 Base URL 与 API Key
4. 点 **保存**

- 论文 7 个 Agent 可填你自己的 OpenAI 兼容 Key；平台只收技能费
- **云笺**和 **RAG 检索**仍走平台模型，不能换
- 语言润色（Style）不授予文献检索（AcademicSearch）
- 没有给开发者的通用审校 API Key，也不走商务定制开放 API

额度在右上角，充值点旁边的充值按钮会弹出窗口。""";
    }

    private static boolean matchesHowToModels(String answer) {
        if (inventsUnavailableApi(answer)) {
            return false;
        }
        return (answer.contains("设置") && answer.contains("模型配置"))
                || answer.contains("/account?panel=models");
    }

    static boolean inventsUnavailableApi(String answer) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        return answer.contains("联系商务")
                || answer.contains("通用 API Key 未上线")
                || answer.contains("企业 API")
                || answer.contains("开放 API 未上线")
                || answer.contains("给开发者通用");
    }

    private static boolean looksLikeOrderDump(String answer) {
        return countNeedle(answer, "订单号") >= 3 || countNeedle(answer, "PENDING") >= 2;
    }

    private static boolean textHas(String answer, String needle) {
        return needle != null && !needle.isBlank() && answer.contains(needle);
    }

    private static int countNeedle(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int n = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return n;
            }
            n++;
            from = at + needle.length();
        }
    }

    static int yuan(Map<String, ?> tool, String key) {
        return num(tool, key);
    }

    static int num(Map<?, ?> tool, String key) {
        Object raw = tool.get(key);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw == null) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(String.valueOf(raw).replace("¥", "").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int orderYuan(Map<?, ?> order) {
        if (order.get("amountYuan") != null) {
            return num(order, "amountYuan");
        }
        return num(order, "amountCents") / 100;
    }

    private static List<?> listOf(Object raw) {
        return raw instanceof List<?> list ? list : List.of();
    }

    private static boolean truthy(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(raw));
    }

    private static Object first(Object a, Object b) {
        if (a != null && !String.valueOf(a).isBlank() && !"null".equals(String.valueOf(a))) {
            return a;
        }
        return b;
    }

    private static String blankToDash(Object raw) {
        if (raw == null) {
            return "—";
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() || "null".equals(text) ? "—" : text;
    }

    public static boolean billingIntents(Set<String> intents) {
        return CsIntent.billingOnly(intents);
    }
}
