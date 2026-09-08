package com.zhiyun.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * prod 无强 JWT 时拒绝启动。MCP 弱 token 不挡整个进程，由过滤器拒绝 /api/mcp。
 */
@Component
public class ProdSafetyValidator implements InitializingBean {
    private final ZhiyunProperties properties;
    private final Environment environment;

    public ProdSafetyValidator(ZhiyunProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (!ZhiyunProperties.productionProfile(environment.getActiveProfiles())) {
            return;
        }
        if (!properties.jwtSecretStrongEnough()) {
            throw new IllegalStateException("prod 必须设置强 JWT_SECRET（勿用仓库默认值，至少 32 字符）");
        }
    }
}
