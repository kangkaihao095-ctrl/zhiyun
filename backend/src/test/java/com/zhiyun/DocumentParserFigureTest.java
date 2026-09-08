package com.zhiyun;

import com.zhiyun.rag.DocumentParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentParserFigureTest {
    private final DocumentParser parser = new DocumentParser();

    @Test
    void inspectsMarkdownMetaWithoutCallingVisionTargets() {
        String text = """
                %%PDF_META pageSize=612x792 pages=2 figures=0
                ACL requires A4. Figure 1 is a blurry figure. Figure 3 appears first.
                Table 2 is referenced. Caption is missing.
                """;
        DocumentParser.PdfInspection inspection = parser.inspect(null, text);
        assertThat(inspection.pageSpec()).isEqualTo("LETTER");
        assertThat(inspection.figures()).isEmpty();
        assertThat(inspection.issues()).extracting(DocumentParser.ProgramIssue::code)
                .contains("PAGE_SPEC", "NO_FIGURES", "BLUR_CANDIDATE", "NUMBERING", "MISSING_CAPTION");
        assertThat(inspection.needsVision()).isTrue();
        assertThat(parser.visionTargets(null, inspection)).isEmpty();
        assertThat(DocumentParser.captionsOf("Figure 1. Blurry architecture diagram.\nFig. 2: Results on ACL.\n"))
                .extracting(DocumentParser.CaptionRef::number)
                .containsExactly(1, 2);
    }

    @Test
    void extractsAllFiguresAndVisionsOnlyFlaggedOnes(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("figs.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            BufferedImage blur = new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB);
            BufferedImage stretch = new BufferedImage(400, 20, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 400; x++) {
                stretch.setRGB(x, 10, 0xFFFFFF);
            }
            var blurX = LosslessFactory.createFromImage(doc, blur);
            var stretchX = LosslessFactory.createFromImage(doc, stretch);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(blurX, 40, 400, 80, 80);
                cs.drawImage(stretchX, 40, 200, 400, 20);
            }
            doc.save(pdf.toFile());
        }

        DocumentParser.PdfInspection inspection = parser.inspect(pdf.toString(), "ACL draft on Letter.");
        assertThat(inspection.pageSpec()).isEqualTo("LETTER");
        assertThat(inspection.figures()).hasSize(2);
        assertThat(inspection.figures()).allMatch(f -> f.page() == 1);
        assertThat(inspection.issues()).extracting(DocumentParser.ProgramIssue::code)
                .contains("PAGE_SPEC");
        assertThat(inspection.figures().stream().anyMatch(DocumentParser.FigureMeta::needsVision)).isTrue();
        List<DocumentParser.VisionTarget> targets = parser.visionTargets(pdf.toString(), inspection);
        assertThat(targets).isNotEmpty();
        assertThat(targets).allMatch(t -> t.png() != null && t.png().length > 0);
        assertThat(targets.size()).isLessThanOrEqualTo(inspection.figures().size());
    }
}
