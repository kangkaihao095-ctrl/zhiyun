package com.zhiyun.cs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 云笺滑动窗口。模型侧最近 5 轮（最多 10 条）；只在请求体里传，不落盘。
 */
public final class CsChatMemory {
    public static final int MAX_ROUNDS = 5;
    public static final int MAX_TURNS = MAX_ROUNDS * 2;

    private CsChatMemory() {
    }

    public static List<CustomerService.ChatTurn> window(List<CustomerService.ChatTurn> messages) {
        List<CustomerService.ChatTurn> clean = new ArrayList<>();
        if (messages == null) {
            return List.of();
        }
        for (CustomerService.ChatTurn turn : messages) {
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            String role = normalizeRole(turn.role());
            clean.add(new CustomerService.ChatTurn(role, turn.content().trim()));
        }
        if (clean.size() <= MAX_TURNS) {
            return List.copyOf(clean);
        }
        return List.copyOf(clean.subList(clean.size() - MAX_TURNS, clean.size()));
    }

    public static List<CustomerService.ChatTurn> windowWithLast(List<CustomerService.ChatTurn> messages, String last) {
        List<CustomerService.ChatTurn> copy = new ArrayList<>(messages == null ? List.of() : messages);
        if (last != null && !last.isBlank()) {
            boolean already = !copy.isEmpty()
                    && last.equals(copy.get(copy.size() - 1).content())
                    && "user".equalsIgnoreCase(copy.get(copy.size() - 1).role());
            if (!already) {
                copy.add(new CustomerService.ChatTurn("user", last.trim()));
            }
        }
        return window(copy);
    }

    public static String lastUser(List<CustomerService.ChatTurn> window) {
        if (window == null || window.isEmpty()) {
            return "";
        }
        for (int i = window.size() - 1; i >= 0; i--) {
            CustomerService.ChatTurn turn = window.get(i);
            if (turn != null && "user".equalsIgnoreCase(turn.role()) && turn.content() != null) {
                return turn.content().trim();
            }
        }
        CustomerService.ChatTurn last = window.get(window.size() - 1);
        return last == null || last.content() == null ? "" : last.content().trim();
    }

    public static String priorUserText(List<CustomerService.ChatTurn> window) {
        if (window == null || window.isEmpty()) {
            return "";
        }
        int end = window.size();
        if ("user".equalsIgnoreCase(window.get(end - 1).role())) {
            end -= 1;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < end; i++) {
            CustomerService.ChatTurn turn = window.get(i);
            if (turn == null || !"user".equalsIgnoreCase(turn.role()) || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(turn.content().trim());
        }
        return sb.toString();
    }

    public static String retrievalQuery(List<CustomerService.ChatTurn> window, String last) {
        String q = last == null ? "" : last.trim();
        String prior = priorUserText(window);
        if (CsIntent.looksFollowUp(q) && !prior.isBlank()) {
            return prior + "\n" + q;
        }
        return q;
    }

    /**
     * OpenAI 兼容 messages：system → RAG/Tool grounded → 最近至多 10 条 user/assistant。
     * 每轮单独一条，禁止把历史拼进一句 user。
     */
    public static List<Map<String, Object>> llmMessages(String system, String grounded,
                                                       List<CustomerService.ChatTurn> window) {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(message("system", system == null ? "" : system));
        if (grounded != null && !grounded.isBlank()) {
            out.add(message("system", "Grounded (RAG/Tool，金额只信这些数字，禁止口算；操作说明以本文为准)：\n" + grounded));
        }
        if (window != null) {
            for (CustomerService.ChatTurn turn : window) {
                if (turn == null || turn.content() == null || turn.content().isBlank()) {
                    continue;
                }
                out.add(message(normalizeRole(turn.role()), turn.content().trim()));
            }
        }
        return out;
    }

    public static String formatHistory(List<CustomerService.ChatTurn> window, String query) {
        if (window == null || window.isEmpty()) {
            return "";
        }
        String q = query == null ? "" : query.trim();
        StringBuilder sb = new StringBuilder();
        int end = window.size();
        if (end > 0) {
            CustomerService.ChatTurn last = window.get(end - 1);
            if (last != null && "user".equalsIgnoreCase(last.role()) && q.equals(last.content() == null ? "" : last.content().trim())) {
                end -= 1;
            }
        }
        for (int i = 0; i < end; i++) {
            CustomerService.ChatTurn turn = window.get(i);
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(normalizeRole(turn.role())).append(": ").append(turn.content().trim());
        }
        return sb.toString();
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("role", role);
        row.put("content", content == null ? "" : content);
        return row;
    }

    private static String normalizeRole(String role) {
        if (role != null && "assistant".equalsIgnoreCase(role.trim())) {
            return "assistant";
        }
        if (role != null && "system".equalsIgnoreCase(role.trim())) {
            return "system";
        }
        return "user";
    }
}
