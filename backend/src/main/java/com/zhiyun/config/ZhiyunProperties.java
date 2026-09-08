package com.zhiyun.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "zhiyun")
public class ZhiyunProperties {
    public static final String DEV_MCP_TOKEN = "dev-mcp-token";
    public static final String DEFAULT_JWT_SECRET = "change-me-in-prod-please-32chars!!";
    public static final String DEFAULT_CORS_ORIGIN = "http://127.0.0.1:5173";
    public static final String DEFAULT_CORS_ORIGIN_LOCALHOST = "http://localhost:5173";
    public static final String DEFAULT_CORS_ORIGINS =
            DEFAULT_CORS_ORIGIN + "," + DEFAULT_CORS_ORIGIN_LOCALHOST;

    private Jwt jwt = new Jwt();
    private Storage storage = new Storage();
    private Elasticsearch elasticsearch = new Elasticsearch();
    private Llm llm = new Llm();
    private Quota quota = new Quota();
    private Cs cs = new Cs();
    private Cors cors = new Cors();
    private Harness harness = new Harness();
    private Mq mq = new Mq();
    private Fault fault = new Fault();
    private Eval eval = new Eval();
    private Pay pay = new Pay();
    private Ops ops = new Ops();
    private String evalDir = "../eval";

    @Data
    public static class Jwt {
        private String secret;
        private int expireHours = 72;
    }

    @Data
    public static class Storage {
        private String dir = "./data/uploads";
        private String avatarDir = "./data/avatars";
    }

    @Data
    public static class Elasticsearch {
        private String url = "http://127.0.0.1:9200";
        private boolean enabled = true;
        private String index = "zhiyun_chunks";
    }

    @Data
    public static class Llm {
        private String mode = "auto";
        private String provider = "auto";
        private String baseUrl = "https://api.siliconflow.cn/v1";
        private String apiKey = "";
        private String dashscopeKey = "";
        private String paperModel = "qwen3.8-flash";
        private String csModel = "qwen3.7-flash";
        private String visionModel = "qwen3.8-flash";
        private String imageModel = "qwen-image-3.0";
        private String embeddingModel = "qwen3.7-text-embedding";
        private String rerankModel = "qwen3.7-text-rerank";
        private int embeddingDims = 1024;

        public String resolvedApiKey() {
            if (apiKey != null && !apiKey.isBlank()) {
                return apiKey;
            }
            return dashscopeKey == null ? "" : dashscopeKey;
        }

        public String resolvedProvider() {
            if (provider != null && !provider.isBlank() && !"auto".equalsIgnoreCase(provider.trim())) {
                return provider.trim().toLowerCase();
            }
            String base = baseUrl == null ? "" : baseUrl.toLowerCase();
            if (base.contains("dashscope") || base.contains("aliyuncs.com")) {
                return "dashscope";
            }
            if (base.contains("siliconflow")) {
                return "siliconflow";
            }
            return "openai";
        }
    }

    @Data
    public static class Quota {
        private int tokensPerPoint = 2000;
        private int capCitation = 3;
        private int capQuick = 5;
        private int capFull = 10;
    }

    @Data
    public static class Cs {
        private String runtime = "dify";
        private String difyApiUrl = "";
        private String difyApiKey = "";
        private String mcpToken = "";
    }

    @Data
    public static class Cors {
        /** 逗号分隔。默认同时放行 127.0.0.1 与 localhost 的 Vite；不要写 *。 */
        private String allowedOrigins = DEFAULT_CORS_ORIGINS;
    }

    @Data
    public static class Harness {
        private int leaseTtlSeconds = 60;
        private int renewSeconds = 20;
    }

    @Data
    public static class Mq {
        private String reviewQueue = "zhiyun.review.tasks";
    }

    @Data
    public static class Fault {
        private String illegalOutputAgent = "";
        private String timeoutAgent = "";
        private String killAfterAgent = "";
        private boolean skipLease = false;
    }

    @Data
    public static class Pay {
        /** mock 渠道异步到账延迟。测试置 0，同线程结算。 */
        private int mockDelayMs = 400;
    }

    @Data
    public static class Ops {
        /** 逗号分隔的运营邮箱；demo@zhiyun.dev 始终可操作公共知识。 */
        private String emails = "demo@zhiyun.dev";

        public boolean isOperator(String email) {
            if (email == null || email.isBlank()) {
                return false;
            }
            String needle = email.trim().toLowerCase();
            if ("demo@zhiyun.dev".equals(needle)) {
                return true;
            }
            if (emails == null || emails.isBlank()) {
                return false;
            }
            for (String part : emails.split(",")) {
                if (needle.equals(part.trim().toLowerCase())) {
                    return true;
                }
            }
            return false;
        }
    }

    @Data
    public static class Eval {
        /**
         * 空：一律关闭。显式 true 仅非 prod 开启。评估接口是自建回归，不是产品 SLA。
         */
        private String apiEnabled = "";
    }

    public static boolean productionProfile(String... activeProfiles) {
        if (activeProfiles == null) {
            return false;
        }
        for (String profile : activeProfiles) {
            if (profile != null && ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile))) {
                return true;
            }
        }
        return false;
    }

    public boolean evalApiEnabled(String... activeProfiles) {
        if (productionProfile(activeProfiles)) {
            return false;
        }
        String flag = eval == null ? "" : eval.getApiEnabled();
        if (flag != null && !flag.isBlank()) {
            return Boolean.parseBoolean(flag.trim());
        }
        return false;
    }

    /**
     * 可对外配置里不写死 dev token。本机未设置时回落到 {@link #DEV_MCP_TOKEN}；
     * prod 未设置或仍是该值则 MCP 不可用。
     */
    public String resolvedMcpToken(String... activeProfiles) {
        String configured = cs == null ? "" : cs.getMcpToken();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        if (productionProfile(activeProfiles)) {
            return "";
        }
        return DEV_MCP_TOKEN;
    }

    public boolean mcpEnabled(String... activeProfiles) {
        String token = resolvedMcpToken(activeProfiles);
        if (token == null || token.isBlank()) {
            return false;
        }
        return !productionProfile(activeProfiles) || !DEV_MCP_TOKEN.equals(token);
    }

    public List<String> corsAllowedOrigins() {
        String raw = cors == null ? "" : cors.getAllowedOrigins();
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String origin = part.trim();
                if (origin.isEmpty() || origin.contains("*")) {
                    continue;
                }
                out.add(origin);
            }
        }
        if (out.isEmpty()) {
            return List.of(DEFAULT_CORS_ORIGIN, DEFAULT_CORS_ORIGIN_LOCALHOST);
        }
        return List.copyOf(out);
    }

    public boolean jwtSecretStrongEnough() {
        String secret = jwt == null ? null : jwt.getSecret();
        if (secret == null || secret.isBlank() || secret.length() < 32) {
            return false;
        }
        String trimmed = secret.trim();
        if (DEFAULT_JWT_SECRET.equals(trimmed) || "change-me-in-prod-please-32chars".equals(trimmed)) {
            return false;
        }
        return !trimmed.toLowerCase().contains("change-me-in-prod");
    }

    public boolean dryRun() {
        String mode = llm.mode == null ? "auto" : llm.mode.trim();
        if ("live".equalsIgnoreCase(mode)) {
            return false;
        }
        if ("dry-run".equalsIgnoreCase(mode)) {
            return true;
        }
        String key = llm.resolvedApiKey();
        return key == null || key.isBlank();
    }
}
