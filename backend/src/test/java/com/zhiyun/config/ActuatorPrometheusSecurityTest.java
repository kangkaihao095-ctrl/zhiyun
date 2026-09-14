package com.zhiyun.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
class ActuatorPrometheusSecurityTest {
    @Autowired
    MockMvc mvc;

    @Test
    void prometheusAndHealthArePermitAllWhileMetricsNeedAuth() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("zhiyun_llm_calls")));
        int metrics = mvc.perform(get("/actuator/metrics")).andReturn().getResponse().getStatus();
        int me = mvc.perform(get("/api/me")).andReturn().getResponse().getStatus();
        assertThat(metrics).as("/actuator/metrics is not permitAll").isIn(401, 403);
        assertThat(me).as("/api/me still requires JWT").isIn(401, 403);
    }
}
