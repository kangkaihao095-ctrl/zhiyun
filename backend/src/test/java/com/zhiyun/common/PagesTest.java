package com.zhiyun.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PagesTest {
    @Test
    void defaultPageSizeIsFive() {
        assertThat(Pages.DEFAULT_SIZE).isEqualTo(5);
        assertThat(Pages.size(null)).isEqualTo(5);
        assertThat(Pages.size(0)).isEqualTo(5);
        assertThat(Pages.size(10)).isEqualTo(10);
        assertThat(Pages.size(200)).isEqualTo(Pages.MAX_SIZE);
    }
}
