package com.zhiyun;

import com.zhiyun.config.ZhiyunProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvalApiPolicyTest {
    @Test
    void evalApiIsOffByDefaultAndStaysOffInProd() {
        ZhiyunProperties properties = new ZhiyunProperties();
        assertThat(properties.evalApiEnabled()).isFalse();
        assertThat(properties.evalApiEnabled("local")).isFalse();
        assertThat(properties.evalApiEnabled("test")).isFalse();
        assertThat(properties.evalApiEnabled("prod")).isFalse();
        assertThat(properties.evalApiEnabled("production")).isFalse();
        properties.getEval().setApiEnabled("false");
        assertThat(properties.evalApiEnabled("local")).isFalse();
        properties.getEval().setApiEnabled("true");
        assertThat(properties.evalApiEnabled("local")).isTrue();
        assertThat(properties.evalApiEnabled("test")).isTrue();
        assertThat(properties.evalApiEnabled("prod")).isFalse();
        assertThat(properties.evalApiEnabled("production")).isFalse();
    }
}
