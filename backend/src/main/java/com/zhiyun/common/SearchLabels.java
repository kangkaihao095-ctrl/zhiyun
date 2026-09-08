package com.zhiyun.common;

import com.zhiyun.domain.Codes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 把用户输入的中文文案映射到库里的状态 / 原因码，便于包含检索。 */
public final class SearchLabels {
    private SearchLabels() {
    }

    public static List<String> ledgerReasons(String q) {
        String n = norm(q);
        List<String> out = new ArrayList<>();
        if (n.isEmpty()) {
            return out;
        }
        addIf(out, n, "PURCHASE", "充值到账", "充值", "到账", "purchase");
        addIf(out, n, "REVIEW_USAGE", "审校消耗", "消耗", "review_usage", "review_consume");
        addIf(out, n, "REVIEW_CONSUME", "审校消耗", "消耗");
        addIf(out, n, "SKILL_FEE", "技能与提示词服务费", "技能费", "提示词", "skill_fee");
        return out;
    }

    public static List<String> orderStatuses(String q) {
        String n = norm(q);
        List<String> out = new ArrayList<>();
        if (n.isEmpty()) {
            return out;
        }
        addIf(out, n, Codes.ORDER_PAID, "已到账", "paid");
        addIf(out, n, Codes.ORDER_PENDING, "待支付", "pending");
        addIf(out, n, "CANCELLED", "已取消", "cancelled", "canceled");
        return out;
    }

    public static List<String> reviewStatuses(String q) {
        String n = norm(q);
        List<String> out = new ArrayList<>();
        if (n.isEmpty()) {
            return out;
        }
        addIf(out, n, Codes.PENDING, "排队中", "pending");
        addIf(out, n, Codes.RUNNING, "正在审校", "进行中", "running");
        addIf(out, n, Codes.WAITING_ACCEPT, "等你确认修改", "待确认", "waiting_accept");
        addIf(out, n, Codes.DONE, "已完成", "done");
        addIf(out, n, Codes.FAILED, "没能完成", "未完成", "failed");
        return out;
    }

    public static List<String> workflows(String q) {
        String n = norm(q);
        List<String> out = new ArrayList<>();
        if (n.isEmpty()) {
            return out;
        }
        addIf(out, n, Codes.CITATION_ONLY, "引用核验", "citation_only", "citation");
        addIf(out, n, Codes.QUICK_REVIEW, "快速审读", "quick_review", "quick");
        addIf(out, n, Codes.FULL_REVIEW, "投稿前完整审校", "完整审校", "full_review", "full");
        return out;
    }

    private static void addIf(List<String> out, String q, String code, String... labels) {
        if (contains(code, q) || contains(q, code.toLowerCase(Locale.ROOT))) {
            if (!out.contains(code)) {
                out.add(code);
            }
            return;
        }
        for (String label : labels) {
            if (contains(label, q) || contains(q, label.toLowerCase(Locale.ROOT))) {
                if (!out.contains(code)) {
                    out.add(code);
                }
                return;
            }
        }
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && needle != null && !needle.isEmpty()
                && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String norm(String q) {
        return Pages.q(q).toLowerCase(Locale.ROOT);
    }
}
