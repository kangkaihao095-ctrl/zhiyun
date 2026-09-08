package com.zhiyun.cs;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 云笺意图。花了多少钱 ≠ 列订单；订单 ≈ 充值，消费 ≈ 额度流水扣减。
 */
public final class CsIntent {
    public static final String SPEND = "SPEND";
    public static final String ORDERS = "ORDERS";
    public static final String USAGE = "USAGE";
    public static final String PLANS = "PLANS";
    public static final String TASK = "TASK";
    public static final String PAPER = "PAPER";
    public static final String CITATION = "CITATION";
    public static final String INBOX = "INBOX";
    public static final String PROFILE = "PROFILE";
    public static final String MODELS = "MODELS";
    public static final String RAG = "RAG";

    public static final String FORMAT_SPENT = "spent";
    public static final String FORMAT_ORDERS = "orders";
    public static final String FORMAT_QUOTA = "quota";
    public static final String FORMAT_TASK = "task";
    public static final String FORMAT_MISSING = "missing_id";
    public static final String FORMAT_PLANS = "plans";
    public static final String FORMAT_MODELS = "howto_models";

    private CsIntent() {
    }

    public static Set<String> classify(String raw) {
        return classify(raw, "");
    }

    public static Set<String> classify(String raw, List<CustomerService.ChatTurn> window) {
        return classify(raw, CsChatMemory.priorUserText(window));
    }

    public static Set<String> classify(String raw, String priorUserText) {
        Set<String> out = classifyOne(raw);
        if (!looksFollowUp(raw)) {
            return out;
        }
        if (priorUserText == null || priorUserText.isBlank()) {
            return out;
        }
        out.addAll(classifyOne(priorUserText));
        return out;
    }

    public static boolean looksFollowUp(String raw) {
        String q = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return false;
        }
        return q.startsWith("那") || q.startsWith("这个") || q.startsWith("刚才")
                || q.contains("那昨天") || q.contains("那这个") || q.contains("刚才的")
                || q.contains("还有呢") || q.endsWith("呢") || q.endsWith("呢？")
                || q.contains("同上") || (q.contains("这个") && (q.contains("咋") || q.contains("怎么")));
    }

    static Set<String> classifyOne(String raw) {
        String q = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        boolean spend = isSpend(q);
        boolean orderList = isOrderHistory(q);
        boolean personal = q.contains("我的") || q.contains("还剩") || q.contains("余额") || q.contains("当前");
        boolean planish = isPlanish(q, spend);
        boolean quotaWord = q.contains("额度") || q.contains("quota") || q.contains("credit") || q.contains("点数")
                || q.contains("流水");
        if (spend) {
            out.add(SPEND);
            out.add(USAGE);
        }
        if (personal && quotaWord) {
            out.add(USAGE);
        } else if (quotaWord && !planish && !spend) {
            out.add(USAGE);
        }
        if (planish) {
            out.add(PLANS);
        }
        if (orderList) {
            out.add(ORDERS);
            out.add(USAGE);
        }
        boolean paperish = q.contains("任务") || q.contains("进度") || q.contains("task id") || q.contains("taskid")
                || q.contains("论文") || q.contains("稿件") || q.contains("审校") || q.contains("manuscript")
                || q.contains("task");
        String lookup = extractLookupKey(raw);
        boolean bareId = raw != null && (raw.trim().matches("\\d{1,18}") || looksBusinessNo(raw.trim()));
        boolean idLookup = lookup != null && !spend && !orderList && !planish;
        if (paperish || bareId || idLookup) {
            out.add(TASK);
            out.add(PAPER);
        }
        if (q.contains("citation") || q.contains("doi") || q.contains("引用核验") || q.contains("幽灵")) {
            out.add(CITATION);
        }
        if (q.contains("站内信") || q.contains("未读") || q.contains("通知")) {
            out.add(INBOX);
        }
        if (q.contains("邮箱") || q.contains("显示名") || q.contains("账户名") || q.contains("我是谁")) {
            out.add(PROFILE);
        }
        if (q.contains("模型") || q.contains("api key") || q.contains("apikey") || q.contains("byok")
                || q.contains("base url") || q.contains("接通我的")) {
            out.add(MODELS);
        }
        out.add(RAG);
        return out;
    }

    public static String formatIntent(Set<String> intents, String raw) {
        String q = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (intents.contains(SPEND) && !orderListOverridesSpend(q)) {
            return FORMAT_SPENT;
        }
        if (intents.contains(ORDERS)) {
            return FORMAT_ORDERS;
        }
        if (intents.contains(SPEND)) {
            return FORMAT_SPENT;
        }
        if (intents.contains(USAGE)) {
            return FORMAT_QUOTA;
        }
        if (intents.contains(PLANS)) {
            return FORMAT_PLANS;
        }
        if (intents.contains(MODELS)) {
            return FORMAT_MODELS;
        }
        if (intents.contains(TASK) || intents.contains(PAPER)) {
            return FORMAT_TASK;
        }
        return "";
    }

    public static boolean billingOnly(Set<String> intents) {
        boolean billing = intents.contains(SPEND) || intents.contains(ORDERS) || intents.contains(USAGE);
        boolean other = intents.contains(PLANS) || intents.contains(TASK) || intents.contains(PAPER)
                || intents.contains(CITATION) || intents.contains(INBOX) || intents.contains(PROFILE)
                || intents.contains(MODELS);
        return billing && !other;
    }

    static boolean isSpend(String q) {
        if (q.contains("花了") || q.contains("一共花") || q.contains("总共花") || q.contains("消费合计")) {
            return true;
        }
        if (q.contains("花了多少") || (q.contains("多少钱") && (q.contains("过去") || q.contains("花")))) {
            return true;
        }
        return q.contains("消费") && !q.contains("订单") && !q.contains("充值") && !q.contains("记录");
    }

    static boolean isOrderHistory(String q) {
        if (q.contains("充值记录") || q.contains("历史充值") || q.contains("订单明细") || q.contains("历史订单")) {
            return true;
        }
        if (q.contains("消费情况") && (q.contains("订单") || q.contains("历史") || q.contains("充值"))) {
            return true;
        }
        if (q.contains("充值") && (q.contains("记录") || q.contains("历史") || q.contains("流水") || q.contains("订单"))) {
            return true;
        }
        return q.contains("订单") || q.contains("order");
    }

    static boolean isPlanish(String q, boolean spend) {
        if (spend) {
            return q.contains("套餐") || q.contains("怎么买") || q.contains("怎么充");
        }
        return q.contains("套餐") || q.contains("starter") || q.contains("pro")
                || q.contains("多少钱") || q.contains("价格") || q.contains("计费") || q.contains("模拟支付")
                || q.contains("怎么扣") || q.contains("一次审校") || q.contains("1 点") || q.contains("1点")
                || q.contains("最低") || q.contains("怎么买") || q.contains("怎么充")
                || (q.contains("充值") && !isOrderHistory(q));
    }

    private static boolean orderListOverridesSpend(String q) {
        return q.contains("订单") || q.contains("充值记录") || q.contains("消费情况");
    }

    public static Long extractId(String text) {
        if (text == null) {
            return null;
        }
        var m = java.util.regex.Pattern.compile("(\\d{1,18})").matcher(text);
        return m.find() ? Long.parseLong(m.group()) : null;
    }

    public static String extractLookupKey(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var biz = java.util.regex.Pattern.compile("\\b(ZYT|ZYL|ZY)[0-9A-Fa-f]{10,}\\b").matcher(text);
        if (biz.find()) {
            return biz.group();
        }
        Long id = extractId(text);
        return id == null ? null : Long.toString(id);
    }

    private static boolean looksBusinessNo(String raw) {
        return raw != null && raw.matches("(?i)(ZYT|ZYL|ZY)[0-9A-F]{10,}");
    }
}
