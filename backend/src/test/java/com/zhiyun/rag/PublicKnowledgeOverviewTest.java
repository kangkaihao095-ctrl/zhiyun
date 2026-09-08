package com.zhiyun.rag;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PublicKnowledgeOverviewTest {

    @Test
    void classifiesVenuesSkillsAndIndexFiles() {
        assertThat(PublicKnowledgeOverview.categoryOf("02-ieee.md")).isEqualTo("venue");
        assertThat(PublicKnowledgeOverview.categoryOf("06-acl.md")).isEqualTo("venue");
        assertThat(PublicKnowledgeOverview.categoryOf("25-prompt-en-polish.md")).isEqualTo("skill");
        assertThat(PublicKnowledgeOverview.categoryOf("28-prompt-latex-review.md")).isEqualTo("skill");
        assertThat(PublicKnowledgeOverview.categoryOf("33-public-index.md")).isEqualTo("source");
        assertThat(PublicKnowledgeOverview.categoryOf("34-how-to-models.md")).isEqualTo("source");
    }

    @Test
    void ieeeOverviewIsAShortCardNotALatexDump() throws Exception {
        String ieee = read("02-ieee.md");
        String summary = PublicKnowledgeOverview.summaryOf("02-ieee.md", ieee);
        assertThat(PublicKnowledgeOverview.titleOf("02-ieee.md", ieee)).contains("IEEE");
        assertThat(summary).contains("IEEEtran");
        assertThat(summary).doesNotContain("documentclass");
        assertThat(summary.length()).isLessThanOrEqualTo(PublicKnowledgeOverview.MAX_SUMMARY);
        assertThat(PublicKnowledgeOverview.sectionsOf(ieee)).contains("LaTeX", "PDF 与版式");
        Map<String, Object> row = PublicKnowledgeOverview.row("classpath", "02-ieee.md", ieee, null, null);
        assertThat(row).doesNotContainKey("content");
        assertThat(row.get("category")).isEqualTo("venue");
    }

    @Test
    void groupsCoverVenuesSkillsAndSourceCounts() throws Exception {
        String ieee = read("02-ieee.md");
        String polish = read("25-prompt-en-polish.md");
        String index = read("33-public-index.md");
        List<Map<String, Object>> bundled = List.of(
                PublicKnowledgeOverview.row("classpath", "02-ieee.md", ieee, null, null),
                PublicKnowledgeOverview.row("classpath", "25-prompt-en-polish.md", polish, null, null),
                PublicKnowledgeOverview.row("classpath", "33-public-index.md", index, null, null)
        );
        List<Map<String, Object>> groups = PublicKnowledgeOverview.groups(bundled, 1, 168);
        assertThat(groups).hasSize(3);
        assertThat(groups.get(0).get("title")).isEqualTo("期刊规范");
        assertThat(groups.get(1).get("title")).isEqualTo("写作 skill");
        assertThat(String.valueOf(groups.get(1).get("summary"))).contains("英文润色");
        @SuppressWarnings("unchecked")
        List<String> sourceHighlights = (List<String>) groups.get(2).get("highlights");
        assertThat(sourceHighlights).anyMatch(h -> h.contains("3 篇"));
        assertThat(sourceHighlights).anyMatch(h -> h.contains("168"));
        assertThat(sourceHighlights).anyMatch(h -> h.contains("1 份"));
        assertThat(String.valueOf(bundled)).doesNotContain("documentclass");
    }

    private static String read(String filename) throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        for (var resource : resolver.getResources("classpath:knowledge/*.md")) {
            if (filename.equals(resource.getFilename())) {
                return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("missing " + filename);
    }
}
