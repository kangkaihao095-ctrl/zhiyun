package com.zhiyun;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional MySQL smoke test. Run with: ZHIYUN_TC=1 mvn test
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "ZHIYUN_TC", matches = "1")
@SpringBootTest
class MysqlTestcontainersIT {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("zhiyun")
            .withUsername("zhiyun")
            .withPassword("zhiyun");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration");
        registry.add("zhiyun.elasticsearch.enabled", () -> "false");
    }

    @Test
    void mysqlIsRunning() {
        assertThat(mysql.isRunning()).isTrue();
    }
}
