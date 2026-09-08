package com.zhiyun.cs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CsChatMemoryTest {

    @Test
    void windowKeepsLastFiveRounds() {
        List<CustomerService.ChatTurn> turns = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            turns.add(new CustomerService.ChatTurn("user", "U" + i));
            turns.add(new CustomerService.ChatTurn("assistant", "A" + i));
        }
        List<CustomerService.ChatTurn> window = CsChatMemory.window(turns);
        assertThat(window).hasSize(CsChatMemory.MAX_TURNS);
        assertThat(window.get(0).content()).isEqualTo("U4");
        assertThat(window.get(window.size() - 1).content()).isEqualTo("A8");
    }

    @Test
    void llmMessagesKeepHistoryAsSeparateTurnsNotOneBlob() {
        List<CustomerService.ChatTurn> window = List.of(
                new CustomerService.ChatTurn("user", "我的额度还剩多少？"),
                new CustomerService.ChatTurn("assistant", "还剩 3 额度"),
                new CustomerService.ChatTurn("user", "那昨天的订单")
        );
        List<Map<String, Object>> messages = CsChatMemory.llmMessages(
                "system-prompt", "quotaBalance=3", window);
        assertThat(messages.get(0).get("role")).isEqualTo("system");
        assertThat(messages.get(1).get("role")).isEqualTo("system");
        assertThat(String.valueOf(messages.get(1).get("content"))).contains("quotaBalance=3");
        assertThat(messages).hasSize(5);
        assertThat(messages.get(2)).containsEntry("role", "user").containsEntry("content", "我的额度还剩多少？");
        assertThat(messages.get(3)).containsEntry("role", "assistant").containsEntry("content", "还剩 3 额度");
        assertThat(messages.get(4)).containsEntry("role", "user").containsEntry("content", "那昨天的订单");
        long userTurns = messages.stream().filter(m -> "user".equals(m.get("role"))).count();
        assertThat(userTurns).isEqualTo(2);
        assertThat(messages.stream().filter(m -> "user".equals(m.get("role")))
                .map(m -> String.valueOf(m.get("content"))))
                .noneMatch(c -> c.contains("History:") && c.contains("那昨天的订单"));
    }

    @Test
    void followUpInheritsOrderIntentFromPriorTurn() {
        List<CustomerService.ChatTurn> prior = List.of(
                new CustomerService.ChatTurn("user", "帮我查下我的历史订单"),
                new CustomerService.ChatTurn("assistant", "这是最近几笔充值")
        );
        assertThat(CsIntent.classify("那昨天的呢", prior)).contains(CsIntent.ORDERS);
        assertThat(CsIntent.classify("那昨天的订单", prior)).contains(CsIntent.ORDERS);
    }

    @Test
    void remoteHistoryOmitsCurrentQuestion() {
        List<CustomerService.ChatTurn> window = List.of(
                new CustomerService.ChatTurn("user", "我的额度还剩多少？"),
                new CustomerService.ChatTurn("assistant", "还剩 3 额度"),
                new CustomerService.ChatTurn("user", "那昨天的订单")
        );
        String history = CsChatMemory.formatHistory(window, "那昨天的订单");
        assertThat(history).contains("我的额度还剩多少？");
        assertThat(history).doesNotContain("那昨天的订单");
    }
}
