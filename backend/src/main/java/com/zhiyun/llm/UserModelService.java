package com.zhiyun.llm;

import com.zhiyun.common.ApiException;
import com.zhiyun.crypto.SecretBox;
import com.zhiyun.domain.UserAgentModel;
import com.zhiyun.harness.AgentIds;
import com.zhiyun.repo.UserAgentModelRepo;
import com.zhiyun.security.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class UserModelService {
    public static final Set<String> PAPER_AGENTS = Set.of(
            AgentIds.CITATION, AgentIds.FIGURE, AgentIds.REVIEWER, AgentIds.STYLE,
            AgentIds.PLANNING, AgentIds.EXECUTION, AgentIds.VERIFICATION
    );

    private static final List<AgentCard> CARDS = List.of(
            new AgentCard(AgentIds.CITATION, "引用核验", "核对参考文献与 DOI。"),
            new AgentCard(AgentIds.FIGURE, "图表检查", "看图与版式，必要时走视觉模型。"),
            new AgentCard(AgentIds.REVIEWER, "学术审稿", "按主张取证，不空口下结论。"),
            new AgentCard(AgentIds.STYLE, "语言润色", "去套话，保留数字与引用。"),
            new AgentCard(AgentIds.PLANNING, "改稿计划", "把问题拆成可执行的修改任务。"),
            new AgentCard(AgentIds.EXECUTION, "修改执行", "只写候选稿，不覆盖正式稿。"),
            new AgentCard(AgentIds.VERIFICATION, "结果复核", "独立核验，不采信执行自述。")
    );

    private final UserAgentModelRepo repo;
    private final SecretBox secretBox;
    private final com.zhiyun.config.ZhiyunProperties properties;

    public UserModelService(UserAgentModelRepo repo, SecretBox secretBox,
                            com.zhiyun.config.ZhiyunProperties properties) {
        this.repo = repo;
        this.secretBox = secretBox;
        this.properties = properties;
    }

    public Map<String, Object> catalog() {
        var llm = properties.getLlm();
        Map<String, Object> platform = new LinkedHashMap<>();
        platform.put("paperModel", llm.getPaperModel());
        platform.put("csModel", llm.getCsModel());
        platform.put("visionModel", llm.getVisionModel());
        platform.put("embeddingModel", llm.getEmbeddingModel());
        platform.put("rerankModel", llm.getRerankModel());
        platform.put("provider", llm.resolvedProvider());
        platform.put("live", !properties.dryRun());

        Map<String, Object> locked = new LinkedHashMap<>();
        locked.put("cs", Map.of(
                "name", "云笺",
                "model", llm.getCsModel(),
                "note", "由平台提供，不可更换"
        ));
        locked.put("rag", Map.of(
                "name", "检索（embedding / rerank）",
                "embeddingModel", llm.getEmbeddingModel(),
                "rerankModel", llm.getRerankModel(),
                "note", "由平台提供，不可更换"
        ));

        List<UserAgentModel> rows = repo.findByTenantIdAndUserId(TenantContext.tenantId(), TenantContext.userId());
        Map<String, UserAgentModel> byAgent = new LinkedHashMap<>();
        for (UserAgentModel row : rows) {
            byAgent.put(row.getAgentId(), row);
        }

        List<Map<String, Object>> agents = new ArrayList<>();
        for (AgentCard card : CARDS) {
            UserAgentModel row = byAgent.get(card.id());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", card.id());
            item.put("name", card.name());
            item.put("summary", card.summary());
            boolean byok = row != null && Boolean.TRUE.equals(row.getEnabled());
            item.put("mode", byok ? "BYOK" : "PLATFORM");
            item.put("provider", byok ? row.getProvider() : "platform");
            item.put("baseUrl", byok ? row.getBaseUrl() : "");
            item.put("modelId", byok ? row.getModelId() : llm.getPaperModel());
            item.put("apiKeyMasked", byok && row.getApiKeySuffix() != null ? "sk-****" + row.getApiKeySuffix() : "");
            agents.add(item);
        }

        Map<String, Object> skillFee = new LinkedHashMap<>();
        skillFee.put("citation", 1);
        skillFee.put("quick", 1);
        skillFee.put("full", 2);
        skillFee.put("note", "自备模型时 token 走你自己的云账单，平台只收技能与提示词服务费：引用核验 1 额度、快速审读 1 额度、完整审校 2 额度。");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform", platform);
        out.put("locked", locked);
        out.put("agents", agents);
        out.put("skillFee", skillFee);
        out.put("providers", List.of(
                Map.of("id", "dashscope", "name", "阿里云百炼（compatible-mode）",
                        "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
                Map.of("id", "openai", "name", "OpenAI 兼容接口",
                        "baseUrl", "https://api.openai.com/v1")
        ));
        return out;
    }

    @Transactional
    public Map<String, Object> save(String agentId, SaveReq req) {
        String id = requirePaperAgent(agentId);
        if (req == null || "PLATFORM".equalsIgnoreCase(req.mode())) {
            repo.findByTenantIdAndUserIdAndAgentId(TenantContext.tenantId(), TenantContext.userId(), id)
                    .ifPresent(repo::delete);
            return catalog();
        }
        String provider = req.provider() == null || req.provider().isBlank() ? "openai" : req.provider().trim();
        String baseUrl = requireHttp(req.baseUrl());
        String modelId = requireText(req.modelId(), "请填写模型 ID");
        UserAgentModel row = repo.findByTenantIdAndUserIdAndAgentId(
                        TenantContext.tenantId(), TenantContext.userId(), id)
                .orElseGet(UserAgentModel::new);
        String key = req.apiKey() == null ? "" : req.apiKey().trim();
        if (key.isEmpty()) {
            if (row.getId() == null || row.getApiKeyCipher() == null || row.getApiKeyCipher().isBlank()) {
                throw ApiException.bad("请填写 API Key");
            }
        } else {
            row.setApiKeyCipher(secretBox.encrypt(key));
            row.setApiKeySuffix(SecretBox.suffix(key));
        }
        row.setTenantId(TenantContext.tenantId());
        row.setUserId(TenantContext.userId());
        row.setAgentId(id);
        row.setProvider(provider);
        row.setBaseUrl(baseUrl);
        row.setModelId(modelId);
        row.setEnabled(true);
        row.setUpdatedAt(Instant.now());
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(Instant.now());
        }
        repo.save(row);
        return catalog();
    }

    public UserLlmOverride resolve(long tenantId, long userId, String agentId) {
        if (agentId == null || !PAPER_AGENTS.contains(agentId)) {
            return null;
        }
        return repo.findByTenantIdAndUserIdAndAgentId(tenantId, userId, agentId)
                .filter(row -> Boolean.TRUE.equals(row.getEnabled()))
                .map(row -> {
                    String key = secretBox.decrypt(row.getApiKeyCipher());
                    if (key == null || key.isBlank()) {
                        return null;
                    }
                    return new UserLlmOverride(row.getProvider(), row.getBaseUrl(), row.getModelId(), key);
                })
                .orElse(null);
    }

    private static String requirePaperAgent(String agentId) {
        if (agentId == null) {
            throw ApiException.bad("未知 Agent");
        }
        String id = agentId.trim();
        if (AgentIds.CS.equals(id) || "CUSTOMER_SERVICE".equals(id) || "cs".equalsIgnoreCase(id)) {
            throw ApiException.bad("云笺由平台提供，不可更换");
        }
        if ("RAG".equalsIgnoreCase(id) || "EMBEDDING".equalsIgnoreCase(id) || "RERANK".equalsIgnoreCase(id)) {
            throw ApiException.bad("检索与重排由平台提供，不可更换");
        }
        if (!PAPER_AGENTS.contains(id)) {
            throw ApiException.bad("只能为论文 7 Agent 接通自己的模型");
        }
        return id;
    }

    private static String requireText(String raw, String message) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.bad(message);
        }
        return raw.trim();
    }

    private static String requireHttp(String raw) {
        String url = requireText(raw, "请填写 Base URL");
        String lower = url.toLowerCase();
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            throw ApiException.bad("Base URL 需要是 http 或 https 地址");
        }
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    public record SaveReq(String mode, String provider, String baseUrl, String modelId, String apiKey) {
    }

    private record AgentCard(String id, String name, String summary) {
    }
}
