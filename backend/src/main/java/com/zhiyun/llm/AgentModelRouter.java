package com.zhiyun.llm;

import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.harness.AgentIds;
import com.zhiyun.security.TenantContext;
import org.springframework.stereotype.Component;

/**
 * 论文 7 Agent 默认走 paper-model；用户可按 Agent 覆盖。
 * 客服始终 cs-model；RAG embedding/rerank 不走本路由。
 */
@Component
public class AgentModelRouter {
    private final ZhiyunProperties properties;
    private final UserModelService userModelService;

    public AgentModelRouter(ZhiyunProperties properties, UserModelService userModelService) {
        this.properties = properties;
        this.userModelService = userModelService;
    }

    public String chatModel(String agentId) {
        UserLlmOverride override = resolve(agentId);
        if (override != null && override.modelId() != null && !override.modelId().isBlank()) {
            return override.modelId();
        }
        if (AgentIds.CS.equals(agentId)) {
            return properties.getLlm().getCsModel();
        }
        return properties.getLlm().getPaperModel();
    }

    public String visionModel() {
        UserLlmOverride override = resolve(AgentIds.FIGURE);
        if (override != null && override.modelId() != null && !override.modelId().isBlank()) {
            return override.modelId();
        }
        return properties.getLlm().getVisionModel();
    }

    public String imageModel() {
        return properties.getLlm().getImageModel();
    }

    public boolean usesVision(String agentId) {
        return AgentIds.FIGURE.equals(agentId);
    }

    /** 客服与非论文 Agent 永远返回 null，忽略库里的覆盖行。 */
    public UserLlmOverride resolve(String agentId) {
        if (agentId == null || AgentIds.CS.equals(agentId) || !UserModelService.PAPER_AGENTS.contains(agentId)) {
            return null;
        }
        var auth = TenantContext.get();
        if (auth == null) {
            return null;
        }
        return userModelService.resolve(auth.tenantId(), auth.userId(), agentId);
    }
}
