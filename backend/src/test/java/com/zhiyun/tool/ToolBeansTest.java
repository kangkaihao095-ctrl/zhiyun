package com.zhiyun.tool;

import com.zhiyun.harness.AgentIds;
import com.zhiyun.harness.ToolPolicy;
import com.zhiyun.workflow.PatchApplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ToolBeansTest {
    @Autowired
    ApplicationContext context;
    @Autowired
    WebSearchTool webSearchTool;
    @Autowired
    DocxTool docxTool;

    @Test
    void whitelistHasIndependentBeansAndStyleIsDeniedAcademicSearch() {
        assertThat(context.getBean("webSearchTool")).isSameAs(webSearchTool);
        assertThat(context.getBean("docxTool")).isSameAs(docxTool);
        assertThat(context.getBean("academicSearchTool")).isNotNull();

        assertThat(ToolPolicy.allowed(AgentIds.CITATION)).contains(ToolPolicy.WEB_SEARCH);
        assertThat(ToolPolicy.allowed(AgentIds.EXECUTION)).contains(ToolPolicy.DOCX);
        assertThat(ToolPolicy.allowed(AgentIds.STYLE))
                .doesNotContain(ToolPolicy.ACADEMIC_SEARCH, ToolPolicy.WEB_SEARCH, ToolPolicy.DOCX);

        assertThat(webSearchTool.search("ghost citation")).isNotEmpty();
        assertThatThrownBy(() -> webSearchTool.search(AgentIds.STYLE, "https://example.invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(AgentIds.STYLE)
                .hasMessageContaining("WebSearch");
        assertThatThrownBy(() -> docxTool.writeCandidate(AgentIds.STYLE, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DocxTool");
    }

    @Test
    void docxToolReadsAndWritesCandidateWithoutTouchingOfficialSemantics() throws Exception {
        byte[] docx = docxTool.writeCandidate(AgentIds.EXECUTION, "Firstly, we propose a novel method.");
        assertThat(docxTool.supports("paper.docx")).isTrue();
        assertThat(docxTool.read(AgentIds.EXECUTION, docx)).contains("Firstly");
        byte[] patched = docxTool.applyPatches(AgentIds.EXECUTION, docx, List.of(
                PatchApplier.Spec.of("Firstly, we propose a novel method.", "We describe the method.")));
        assertThat(docxTool.extractText(patched)).contains("We describe the method.");
        assertThat(docxTool.extractText(patched)).doesNotContain("Firstly");
    }
}
