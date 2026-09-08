package com.zhiyun;

import com.zhiyun.rag.VenueCatalog;
import com.zhiyun.rag.VenueQuery;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class VenueCatalogTest {

    @Test
    void directoryAlignsWithKnowledgeAndIsSearchable() {
        assertThat(VenueCatalog.all().size()).isGreaterThan(10);
        assertThat(VenueCatalog.canonical("ACL")).isEqualTo("ACL");
        assertThat(VenueCatalog.canonical("未指定")).isNull();
        assertThat(VenueCatalog.search("acl").stream().map(VenueCatalog.Venue::id))
                .contains("ACL");
        assertThat(VenueCatalog.search("nature").stream().map(VenueCatalog.Venue::id))
                .contains("Nature");
    }

    @Test
    void selectedAclPrefixesPublicQuery() {
        String fallback = "academic writing humanizer 英文润色 中文润色 中译英 LaTeX";
        String body = """
                # Introduction
                This draft is intended for NeurIPS.
                Firstly, we propose a novel method.
                """;
        String query = VenueQuery.publicQuery("ACL", "Letter draft", body, fallback);
        assertThat(query).startsWith("ACL ");
        assertThat(query).contains("ACL");
        assertThat(query).doesNotStartWith("NeurIPS ");
    }

    @Test
    void bundledKnowledgeTitlesCoverVenuesAndPolishSkills() throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        var resources = resolver.getResources("classpath:knowledge/*.md");
        StringBuilder all = new StringBuilder();
        for (var r : resources) {
            all.append(new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            all.append('\n');
        }
        String text = all.toString();
        assertThat(text).contains("IEEE", "ACM", "ACL", "Elsevier", "Springer", "Nature");
        assertThat(text).contains("英文润色", "中文润色", "中译英", "LaTeX 格式审查");
        assertThat(text).contains("humanizer", "20-ml-paper-writing");
    }
}
