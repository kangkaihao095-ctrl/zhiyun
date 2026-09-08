package com.zhiyun;

import com.zhiyun.config.ZhiyunProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(ZhiyunProperties.class)
public class ZhiyunApplication {
    public static void main(String[] args) {
        SpringApplication.run(ZhiyunApplication.class, args);
    }
}
