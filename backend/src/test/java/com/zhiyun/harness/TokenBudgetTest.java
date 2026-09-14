package com.zhiyun.harness;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBudgetTest {
    @Test
    void cjkCostsOneTokenPerCharWhileLatinIsAboutFourChars() {
        String cjk = "审校核验引用图表规范".repeat(10);
        String latin = "abcd".repeat(cjk.length() / 4);
        assertThat(latin.length()).isEqualTo(cjk.length());
        assertThat(TokenBudget.count(cjk)).isEqualTo(cjk.length());
        assertThat(TokenBudget.count(latin)).isEqualTo(cjk.length() / 4);
        assertThat(TokenBudget.count(latin)).isLessThan(TokenBudget.count(cjk));
    }

    @Test
    void capTruncatesByTokenNotCharacterLength() {
        String cjk = "甲".repeat(40);
        String latin = "word ".repeat(40);
        assertThat(cjk.length()).isEqualTo(40);
        assertThat(latin.length()).isGreaterThan(cjk.length());

        String cutCjk = TokenBudget.cap(cjk, 10);
        String cutLatin = TokenBudget.cap(latin, 10);
        assertThat(TokenBudget.count(cutCjk)).isLessThanOrEqualTo(10);
        assertThat(TokenBudget.count(cutLatin)).isLessThanOrEqualTo(10);
        assertThat(cutCjk.length()).isEqualTo(10);
        assertThat(cutLatin.length()).isGreaterThan(cutCjk.length());
        assertThat(cutLatin).isNotEqualTo(latin.substring(0, 10));
    }

    @Test
    void existingBucketLimitsStayManuscriptPrivatePublic() {
        assertThat(TokenBudget.MANUSCRIPT).isEqualTo(4000);
        assertThat(TokenBudget.PRIVATE_RAG).isEqualTo(2000);
        assertThat(TokenBudget.PUBLIC_RAG).isEqualTo(1500);
        assertThat(TokenBudget.cap(null, TokenBudget.MANUSCRIPT)).isEmpty();
        assertThat(TokenBudget.cap("ok", 0)).isEmpty();
    }
}
