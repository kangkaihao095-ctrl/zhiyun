package com.zhiyun.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProdSafetyValidatorTest {
    @Test
    void prodRefusesDefaultJwtSecret() {
        ZhiyunProperties properties = new ZhiyunProperties();
        properties.getJwt().setSecret(ZhiyunProperties.DEFAULT_JWT_SECRET);
        Environment env = new MockEnvironment().withProperty("ignored", "x");
        ((MockEnvironment) env).setActiveProfiles("prod");
        ProdSafetyValidator validator = new ProdSafetyValidator(properties, env);
        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void localAllowsDefaultJwtSecret() {
        ZhiyunProperties properties = new ZhiyunProperties();
        properties.getJwt().setSecret(ZhiyunProperties.DEFAULT_JWT_SECRET);
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        ProdSafetyValidator validator = new ProdSafetyValidator(properties, env);
        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void prodAcceptsStrongJwtSecret() {
        ZhiyunProperties properties = new ZhiyunProperties();
        properties.getJwt().setSecret("n0t-the-repo-default-secret-32ch");
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        ProdSafetyValidator validator = new ProdSafetyValidator(properties, env);
        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }
}
