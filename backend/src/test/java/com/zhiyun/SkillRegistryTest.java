package com.zhiyun;

import com.zhiyun.agent.SkillRegistry;
import com.zhiyun.harness.AgentIds;
import com.zhiyun.harness.ToolPolicy;
import com.zhiyun.llm.AgentModelRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SkillRegistryTest {
    @Autowired
    SkillRegistry skillRegistry;
    @Autowired
    AgentModelRouter modelRouter;

    @Test
    void everyAgentHasPromptSkillAndToolPolicy() {
        List<String> agents = List.of(
                AgentIds.CITATION, AgentIds.FIGURE, AgentIds.REVIEWER, AgentIds.STYLE,
                AgentIds.PLANNING, AgentIds.EXECUTION, AgentIds.VERIFICATION, AgentIds.CS
        );
        for (String agent : agents) {
            assertThat(skillRegistry.registered(agent)).isTrue();
            assertThat(skillRegistry.skill(agent)).contains("SOP");
            assertThat(skillRegistry.prompt(agent)).contains("Role");
            assertThat(skillRegistry.prompt(agent)).contains("Self-check");
            assertThat(skillRegistry.systemMessage(agent)).contains("ToolPolicy");
        }
        assertThat(ToolPolicy.allowed(AgentIds.STYLE)).doesNotContain(ToolPolicy.ACADEMIC_SEARCH);
        assertThat(ToolPolicy.allowed(AgentIds.STYLE)).doesNotContain(ToolPolicy.WEB_SEARCH);
        assertThat(ToolPolicy.allowed(AgentIds.CITATION)).contains(ToolPolicy.WEB_SEARCH);
        assertThat(ToolPolicy.allowed(AgentIds.EXECUTION)).contains(ToolPolicy.DOCX);
        assertThat(ToolPolicy.allowed(AgentIds.PLANNING)).isEmpty();
        assertThat(ToolPolicy.allowed(AgentIds.CS)).contains(ToolPolicy.USAGE_QUERY, ToolPolicy.KNOWLEDGE_RETRIEVAL);
        assertThat(skillRegistry.skill(AgentIds.STYLE)).contains("humanizer");
        assertThat(skillRegistry.skill(AgentIds.STYLE)).contains("delve");
        assertThat(skillRegistry.skill(AgentIds.VERIFICATION)).contains("basedOnExecutionSelfReport");
        assertThat(skillRegistry.skill(AgentIds.REVIEWER)).contains("Weaknesses");
        assertThat(skillRegistry.prompt(AgentIds.STYLE)).contains("中译英");
        assertThat(skillRegistry.prompt(AgentIds.STYLE)).contains("TargetVenue");
        assertThat(skillRegistry.skill(AgentIds.STYLE)).doesNotContain("AcademicSearchTool");
        assertThat(modelRouter.resolve(AgentIds.CS)).isNull();
        assertThat(modelRouter.chatModel(AgentIds.CS)).isEqualTo("qwen3.7-flash");
    }
}
