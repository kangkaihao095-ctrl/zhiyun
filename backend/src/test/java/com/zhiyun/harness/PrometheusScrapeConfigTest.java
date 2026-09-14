package com.zhiyun.harness;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusScrapeConfigTest {
    @Test
    void composeDoesNotStartPrometheusOrGrafana() throws Exception {
        String compose = Files.readString(Path.of("..", "docker-compose.yml"));
        String prom = Files.readString(Path.of("..", "prometheus.yml"));
        assertThat(compose).doesNotContain("image: prom/prometheus");
        assertThat(compose).doesNotContain("zhiyun-prometheus");
        assertThat(compose).doesNotContain("grafana:");
        assertThat(compose).contains("不作为智云观测台");
        assertThat(compose).contains("/ops");
        assertThat(prom).contains("job_name: zhiyun");
        assertThat(prom).contains("/actuator/prometheus");
        assertThat(prom).contains("不是产品观测台");
        assertThat(prom).doesNotContain("grafana:");
    }
}
