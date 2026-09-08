package com.zhiyun;

import com.zhiyun.rag.VenueQuery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VenueQueryTest {
    private static final String CITATION_QUERY = "DOI citation Crossref claim support";
    private static final String FIGURE_QUERY = "PDF page size figure DPI caption ACL A4 NeurIPS Letter";

    @Test
    void withVenuePrefixesCitationQuery() {
        String body = """
                # Tenant-Isolated Multi-Agent Review
                This two-page draft is intended for ACL, yet it was prepared on US Letter.
                This draft mixes venues. NeurIPS uses US Letter. IEEE camera-ready usually passes PDF eXpress.
                """;
        String query = VenueQuery.publicQuery("Conference draft", body, CITATION_QUERY);
        assertThat(query).isEqualTo("ACL " + CITATION_QUERY);
        assertThat(query).isNotEqualTo(CITATION_QUERY);
        assertThat(VenueQuery.extract("Conference draft", body)).isEqualTo("ACL");
    }

    @Test
    void withoutVenueKeepsFixedQuery() {
        String body = """
                # Introduction
                Firstly, we propose a novel method that outperforms all prior work.
                We retrieve evidence with metadata scope. No journal or conference is named.
                """;
        assertThat(VenueQuery.publicQuery("Untitled notes", body, CITATION_QUERY)).isEqualTo(CITATION_QUERY);
        assertThat(VenueQuery.publicQuery("Untitled notes", body, FIGURE_QUERY)).isEqualTo(FIGURE_QUERY);
        assertThat(VenueQuery.extract("Untitled notes", body)).isNull();
    }

    @Test
    void selectedVenueBeatsBodyExtraction() {
        String body = """
                # Introduction
                This draft is intended for NeurIPS.
                """;
        assertThat(VenueQuery.publicQuery("ACL", "Letter draft", body, FIGURE_QUERY))
                .isEqualTo("ACL " + FIGURE_QUERY);
        assertThat(VenueQuery.publicQuery("acl", null, body, CITATION_QUERY))
                .startsWith("ACL ");
        assertThat(VenueQuery.publicQuery("", "Untitled", body, CITATION_QUERY))
                .startsWith("NeurIPS ");
        assertThat(VenueQuery.publicQuery(null, "Untitled notes", """
                # Introduction
                No journal is named here at all.
                """, FIGURE_QUERY)).isEqualTo(FIGURE_QUERY);
    }

    @Test
    void labeledChineseAndLatexFallBackSafelyOnNull() {
        assertThat(VenueQuery.publicQuery(null, "拟投 NeurIPS 2026。方法见下。", FIGURE_QUERY))
                .startsWith("NeurIPS ");
        assertThat(VenueQuery.extract(null, "\\documentclass[10pt,conference]{IEEEtran}\n\\begin{document}")).isEqualTo("IEEE");
        assertThat(VenueQuery.publicQuery(null, null, CITATION_QUERY)).isEqualTo(CITATION_QUERY);
        assertThat(VenueQuery.extract(null, "The nature of attention is discussed.")).isNull();
    }
}
