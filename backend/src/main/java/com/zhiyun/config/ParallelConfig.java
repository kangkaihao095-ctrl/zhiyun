package com.zhiyun.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class ParallelConfig {
    @Bean(name = "citationExecutor")
    public Executor citationExecutor() {
        return pool("citation-", 4, 8);
    }

    @Bean(name = "figureExecutor")
    public Executor figureExecutor() {
        return pool("figure-", 4, 8);
    }

    @Bean(name = "payExecutor")
    public Executor payExecutor() {
        return pool("pay-", 1, 2);
    }

    private static Executor pool(String prefix, int core, int max) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix(prefix);
        executor.initialize();
        return executor;
    }
}
