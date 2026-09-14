package com.zhiyun.tool;

import com.zhiyun.harness.ToolPolicy;
import com.zhiyun.workflow.PatchApplier;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Locale;

/**
 * Execution 白名单上的独立 DOCX Bean。上传解析走 {@link #extractText}；
 * Agent 侧读写必须带 ToolPolicy，且只写候选 documentVersion。
 */
@Component
public class DocxTool {

    public boolean supports(String filenameOrPath) {
        if (filenameOrPath == null || filenameOrPath.isBlank()) {
            return false;
        }
        return filenameOrPath.toLowerCase(Locale.ROOT).endsWith(".docx");
    }

    /** 上传 / 再解析用，不校验 Agent 白名单。 */
    public String extractText(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            String text = extractor.getText();
            return text == null ? "" : text;
        }
    }

    public String read(String agentId, byte[] bytes) throws Exception {
        ToolPolicy.assertAllowed(agentId, ToolPolicy.DOCX);
        return extractText(bytes);
    }

    /**
     * 把正文写成新的 DOCX 字节。调用方只应落到候选 documentVersion，禁止覆盖正式稿。
     */
    public byte[] writeCandidate(String agentId, String text) throws Exception {
        ToolPolicy.assertAllowed(agentId, ToolPolicy.DOCX);
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : splitParagraphs(text)) {
                XWPFParagraph paragraph = doc.createParagraph();
                paragraph.createRun().setText(line);
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    public byte[] applyPatches(String agentId, byte[] sourceDocx, List<PatchApplier.Spec> specs) throws Exception {
        String original = read(agentId, sourceDocx);
        String next = PatchApplier.applyAll(original, specs).text();
        return writeCandidate(agentId, next);
    }

    private static List<String> splitParagraphs(String text) {
        if (text == null || text.isBlank()) {
            return List.of("");
        }
        String[] lines = text.split("\\R");
        return lines.length == 0 ? List.of("") : List.of(lines);
    }
}
