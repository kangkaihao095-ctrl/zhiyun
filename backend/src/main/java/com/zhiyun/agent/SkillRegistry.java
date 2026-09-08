package com.zhiyun.agent;

import com.zhiyun.harness.AgentIds;
import com.zhiyun.harness.ToolPolicy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt / Skill 版本注册表。Skill = 这类任务怎么做；Prompt = 这一次具体做什么。
 */
@Component
public class SkillRegistry {
    public static final String VERSION = "1";

    private static final Map<String, String> FILES = Map.of(
            AgentIds.CITATION, "citation",
            AgentIds.FIGURE, "figure-pdf",
            AgentIds.REVIEWER, "reviewer",
            AgentIds.STYLE, "style",
            AgentIds.PLANNING, "planning",
            AgentIds.EXECUTION, "execution",
            AgentIds.VERIFICATION, "verification",
            AgentIds.CS, "customer-service"
    );

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    public String skillVersion() {
        return VERSION;
    }

    public String promptVersion() {
        return VERSION;
    }

    public String skill(String agentId) {
        return load("skills/" + file(agentId) + ".md");
    }

    public String prompt(String agentId) {
        return load("prompts/" + file(agentId) + ".md");
    }

    public String systemMessage(String agentId) {
        String allow = String.join(", ", ToolPolicy.allowed(agentId));
        if (allow.isBlank()) {
            allow = "(none)";
        }
        return prompt(agentId)
                + "\n\n---\nSkill SOP (skillVersion=" + VERSION + ", promptVersion=" + VERSION + ")\n"
                + skill(agentId)
                + "\n\nToolPolicy allowlist: " + allow
                + "\nReturn JSON only unless this is CUSTOMER_SERVICE.";
    }

    public boolean registered(String agentId) {
        return FILES.containsKey(agentId);
    }

    private String file(String agentId) {
        String name = FILES.get(agentId);
        if (name == null) {
            throw new IllegalArgumentException("no skill/prompt registered for " + agentId);
        }
        return name;
    }

    private String load(String classpath) {
        return cache.computeIfAbsent(classpath, path -> {
            try {
                ClassPathResource resource = new ClassPathResource(path);
                return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException("missing classpath resource " + path, e);
            }
        });
    }
}
