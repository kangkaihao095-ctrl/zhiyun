package com.zhiyun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对本机已启动的 8080 云笺做一小轮真实调用。失败标 regression，不是 SLA。
 * 默认 mvn test 跳过；重启 8080 后：{@code ZHIYUN_CS_LIVE_EVAL=1 mvn -q -Dtest=CsLiveEvalTest test}
 */
class CsLiveEvalTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @EnabledIfEnvironmentVariable(named = "ZHIYUN_CS_LIVE_EVAL", matches = "1")
    void liveYunjianSpendVsOrdersAreDifferent() throws Exception {
        Assumptions.assumeTrue(up(), "8080 未启动，跳过真实云笺回归");
        String email = "cs-live-" + System.nanoTime() + "@zhiyun.dev";
        JsonNode auth = mapper.readTree(postJson("/api/auth/register",
                "{\"email\":\"" + email + "\",\"password\":\"demo123456\",\"displayName\":\"Live\"}"));
        String token = auth.path("token").asText();
        Assumptions.assumeTrue(!token.isBlank(), "注册失败");

        String spent = chat(token, "看下我过去花了多少钱了");
        String history = chat(token, "帮我查下我的历史订单消费情况");
        assertThat(spent).as("regression: 花了多少钱应给出合计").contains("¥");
        assertThat(spent).contains("额度");
        assertThat(spent.split("订单号")).hasSizeLessThanOrEqualTo(2);
        assertThat(history).contains("额度");
        assertThat(history).isNotEqualTo(spent);
        assertThat(spent).contains("\n");
        assertThat(history).contains("\n");
    }

    private boolean up() {
        try {
            HttpURLConnection conn = open("POST", "/api/auth/login", "{\"email\":\"x\",\"password\":\"x\"}", null);
            conn.getResponseCode();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String chat(String token, String question) throws Exception {
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":" + mapper.writeValueAsString(question) + "}]}";
        HttpURLConnection conn = open("POST", "/api/cs/chat", body, token);
        assertThat(conn.getResponseCode()).as("regression: 云笺 HTTP").isEqualTo(200);
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String raw = line.startsWith("data: ") ? line.substring(6) : line.substring(5);
                if ("[DONE]".equals(raw) || "\"[DONE]\"".equals(raw)) {
                    continue;
                }
                try {
                    JsonNode n = mapper.readTree(raw);
                    text.append(n.isTextual() ? n.asText() : raw);
                } catch (Exception e) {
                    text.append(raw);
                }
            }
        } catch (java.io.IOException eof) {
            if (text.length() < 12) {
                throw eof;
            }
        }
        return text.toString();
    }

    private String postJson(String path, String json) throws Exception {
        HttpURLConnection conn = open("POST", path, json, null);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line);
            }
            return out.toString();
        }
    }

    private static HttpURLConnection open(String method, String path, String json, String token) throws Exception {
        URL url = URI.create("http://127.0.0.1:8080" + path).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(token != null ? 60_000 : 8000);
        conn.setRequestMethod(method);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Accept", "text/event-stream");
        }
        conn.getOutputStream().write(json.getBytes(StandardCharsets.UTF_8));
        return conn;
    }
}
