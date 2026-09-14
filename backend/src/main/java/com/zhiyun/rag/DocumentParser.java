package com.zhiyun.rag;

import com.zhiyun.tool.DocxTool;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DocumentParser {
    private static final Logger log = LoggerFactory.getLogger(DocumentParser.class);
    private static final Pattern META = Pattern.compile(
            "%%PDF_META\\s+pageSize=([0-9.]+)x([0-9.]+)\\s+pages=(\\d+)\\s+figures=(\\d+)");
    private static final Pattern FIGURE_NO = Pattern.compile("(?i)\\b(?:figure|fig\\.)\\s+(\\d+)");
    private static final Pattern TABLE_NO = Pattern.compile("(?i)\\btable\\s+(\\d+)");
    private static final Pattern CAPTION = Pattern.compile(
            "(?im)^\\s*(?:figure|fig\\.|图)\\s*(\\d+)\\s*[:.．、]\\s*(.+)$");
    private static final double BLUR_VARIANCE = 40.0;

    private final DocxTool docxTool;

    public DocumentParser(DocxTool docxTool) {
        this.docxTool = docxTool;
    }

    public ParsedDocument parse(MultipartFile file) throws Exception {
        String name = file.getOriginalFilename() == null ? "manuscript" : file.getOriginalFilename();
        return parseBytes(file.getBytes(), name);
    }

    /** 从磁盘再解析：上传后 content_text 为空时补抽正文。 */
    public String textFromStorage(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return "";
        }
        Path path = Path.of(storagePath);
        if (!Files.isRegularFile(path)) {
            return "";
        }
        try {
            return parseBytes(Files.readAllBytes(path), originalFilename(path.getFileName().toString())).text();
        } catch (Exception e) {
            log.warn("reparse storage failed: {}", e.getMessage());
            return "";
        }
    }

    public static String originalFilename(String stored) {
        if (stored == null || stored.isBlank()) {
            return "manuscript";
        }
        if (stored.length() > 37 && stored.charAt(36) == '-') {
            return stored.substring(37);
        }
        return stored;
    }

    public ParsedDocument parseBytes(byte[] bytes, String name) throws Exception {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return parsePdf(bytes, name);
        }
        if (lower.endsWith(".docx") || docxTool.supports(name)) {
            return new ParsedDocument(name, docxTool.extractText(bytes), List.of(), "DOCX");
        }
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")
                || lower.endsWith(".tex")) {
            return new ParsedDocument(name, new String(bytes), List.of(), "TEXT");
        }
        throw new IllegalArgumentException("only PDF / DOCX / Markdown / TXT / TeX are supported");
    }

    public ParsedDocument parsePdf(byte[] bytes, String name) throws Exception {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            List<FigureMeta> figures = extractFigures(doc);
            PDRectangle media = doc.getNumberOfPages() == 0 ? new PDRectangle() : doc.getPage(0).getMediaBox();
            String header = "%%PDF_META pageSize=" + media.getWidth() + "x" + media.getHeight()
                    + " pages=" + doc.getNumberOfPages() + " figures=" + figures.size() + "\n";
            return new ParsedDocument(name, header + text, figures, "PDF");
        }
    }

    /**
     * 程序检查：页规格 / DPI / 尺寸 / 编号。模糊、拉伸才标 needsVision。
     * 无 PDF 时仍解析正文里的 %%PDF_META 与 Figure/Table 编号。
     */
    public PdfInspection inspect(String storagePath, String contentText) {
        String text = contentText == null ? "" : contentText;
        List<PageSpec> pages = new ArrayList<>();
        List<FigureMeta> figures = new ArrayList<>();
        if (isPdfFile(storagePath)) {
            try (PDDocument doc = Loader.loadPDF(Path.of(storagePath).toFile())) {
                for (int i = 0; i < doc.getNumberOfPages(); i++) {
                    PDRectangle media = doc.getPage(i).getMediaBox();
                    pages.add(pageSpec(i + 1, media.getWidth(), media.getHeight()));
                }
                figures.addAll(extractFigures(doc));
            } catch (Exception e) {
                log.warn("pdf inspect failed: {}", e.getMessage());
            }
        }
        if (pages.isEmpty() && figures.isEmpty()) {
            Matcher meta = META.matcher(text);
            if (meta.find()) {
                float w = Float.parseFloat(meta.group(1));
                float h = Float.parseFloat(meta.group(2));
                int pageCount = Integer.parseInt(meta.group(3));
                for (int i = 1; i <= Math.max(1, pageCount); i++) {
                    pages.add(pageSpec(i, w, h));
                }
            }
        }
        String specLabel = specLabel(pages);
        List<ProgramIssue> issues = new ArrayList<>();
        if ("LETTER".equals(specLabel) && text.toUpperCase(Locale.ROOT).contains("ACL")) {
            issues.add(new ProgramIssue("PAGE_SPEC", "MEDIUM", "Page size is US Letter; ACL expects A4",
                    "Deterministic parse: media box is Letter (~612×792 pt), which does not match ACL A4. Vision was not invoked.", "page-size"));
        }
        if ("A4".equals(specLabel) && text.toUpperCase(Locale.ROOT).contains("NEURIPS")
                && text.toUpperCase(Locale.ROOT).contains("LETTER")) {
            issues.add(new ProgramIssue("PAGE_SPEC", "MEDIUM", "Page size is A4; NeurIPS expects US Letter",
                    "Deterministic parse: media box is A4, which does not match NeurIPS Letter. Vision was not invoked.", "page-size"));
        }
        int declaredFigures = declaredFigureCount(text);
        if ((declaredFigures == 0 && figures.isEmpty()) || text.contains("figures=0")) {
            issues.add(new ProgramIssue("NO_FIGURES", "LOW", "No embedded figures detected by PDF object scan",
                    "Deterministic parse found zero XObjects. Vision was not invoked.", "pdf"));
        }
        for (FigureMeta fig : figures) {
            issues.addAll(issuesOfFigure(fig));
        }
        if (text.toLowerCase(Locale.ROOT).contains("blurry figure")
                && figures.stream().noneMatch(FigureMeta::possiblyBlurry)) {
            issues.add(new ProgramIssue("BLUR_CANDIDATE", "MEDIUM", "Vision flag: possible blur / unreadable text",
                    "Manuscript text mentions a blurry figure. Vision runs only if a raster exists.", "figure-1"));
        }
        issues.addAll(numberingIssues(text));
        boolean needsVision = figures.stream().anyMatch(FigureMeta::needsVision)
                || issues.stream().anyMatch(i -> "BLUR_CANDIDATE".equals(i.code()) || "STRETCH".equals(i.code()));
        return new PdfInspection(specLabel, pages, figures, issues, needsVision);
    }

    public List<VisionTarget> visionTargets(String storagePath, PdfInspection inspection) {
        if (inspection == null || !inspection.needsVision() || !isPdfFile(storagePath)) {
            return List.of();
        }
        List<VisionTarget> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (FigureMeta fig : inspection.figures()) {
            if (!fig.needsVision()) {
                continue;
            }
            String key = fig.page() + ":" + fig.name();
            if (!seen.add(key)) {
                continue;
            }
            byte[] png = rasterFigure(storagePath, fig);
            if (png != null) {
                out.add(new VisionTarget(fig.page(), fig.name(), png,
                        fig.possiblyBlurry() ? "blur" : "stretch"));
            }
        }
        return out;
    }

    /** 单图程序检查，供多图 CompletableFuture 并行。 */
    public List<ProgramIssue> issuesOfFigure(FigureMeta fig) {
        if (fig == null) {
            return List.of();
        }
        List<ProgramIssue> issues = new ArrayList<>();
        if (fig.lowDpi()) {
            issues.add(new ProgramIssue("LOW_DPI", "MEDIUM",
                    "Figure on page " + fig.page() + " approx DPI " + Math.round(fig.approxDpi()),
                    "Deterministic parse: pixels " + fig.width() + "×" + fig.height() + ", approx DPI " + round1(fig.approxDpi())
                            + " (< 150). Vision was not invoked.",
                    fig.anchor()));
        }
        if (fig.tiny()) {
            issues.add(new ProgramIssue("TINY", "LOW",
                    "Figure on page " + fig.page() + " is too small (" + fig.width() + "×" + fig.height() + ")",
                    "Deterministic parse: edge too small. Vision was not invoked.", fig.anchor()));
        }
        if (fig.stretched()) {
            issues.add(new ProgramIssue("STRETCH", "MEDIUM",
                    "Figure on page " + fig.page() + " has extreme aspect ratio",
                    "Program flagged stretch distortion; Vision is invoked for this figure only.", fig.anchor()));
        }
        if (fig.possiblyBlurry()) {
            issues.add(new ProgramIssue("BLUR_CANDIDATE", "MEDIUM",
                    "Figure on page " + fig.page() + " looks blurry / unreadable",
                    "Laplacian variance is low; Vision is invoked for this figure only.", fig.anchor()));
        }
        return issues;
    }

    public record ParsedDocument(String filename, String text, List<FigureMeta> figures, String format) {
    }

    public record FigureMeta(int page, String name, int width, int height, double approxDpi,
                             boolean lowDpi, boolean tiny, boolean stretched, boolean possiblyBlurry) {
        public boolean needsVision() {
            return possiblyBlurry || stretched;
        }

        public String anchor() {
            return "p" + page + "-" + (name == null ? "img" : name);
        }

        public FigureMeta(int page, int width, int height, double approxDpi) {
            this(page, "img", width, height, approxDpi,
                    approxDpi > 0 && approxDpi < 150,
                    Math.min(width, height) < 48,
                    aspectExtreme(width, height),
                    false);
        }
    }

    public record PageSpec(int page, float widthPt, float heightPt, String spec) {
    }

    public record ProgramIssue(String code, String severity, String summary, String detail, String anchor) {
    }

    public record PdfInspection(String pageSpec, List<PageSpec> pages, List<FigureMeta> figures,
                                List<ProgramIssue> issues, boolean needsVision) {
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("pageSpec=").append(pageSpec)
                    .append(" pages=").append(pages.size())
                    .append(" figures=").append(figures.size())
                    .append(" needsVision=").append(needsVision).append('\n');
            for (FigureMeta fig : figures) {
                sb.append("- fig page=").append(fig.page())
                        .append(" name=").append(fig.name())
                        .append(" px=").append(fig.width()).append('x').append(fig.height())
                        .append(" dpi=").append(round1(fig.approxDpi()))
                        .append(" lowDpi=").append(fig.lowDpi())
                        .append(" tiny=").append(fig.tiny())
                        .append(" stretched=").append(fig.stretched())
                        .append(" blur=").append(fig.possiblyBlurry())
                        .append('\n');
            }
            for (ProgramIssue issue : issues) {
                sb.append("- issue ").append(issue.code()).append(": ").append(issue.summary()).append('\n');
            }
            return sb.toString();
        }
    }

    public record VisionTarget(int page, String name, byte[] png, String reason) {
    }

    public record CaptionRef(int number, String caption) {
    }

    /** 从正文抽 Figure / 图 N 题注，供对照面展示编号与题注。 */
    public static List<CaptionRef> captionsOf(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<CaptionRef> out = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        Matcher matcher = CAPTION.matcher(text);
        while (matcher.find()) {
            int number = Integer.parseInt(matcher.group(1));
            if (!seen.add(number)) {
                continue;
            }
            String caption = matcher.group(2).trim();
            if (caption.length() > 240) {
                caption = caption.substring(0, 240) + "…";
            }
            out.add(new CaptionRef(number, caption));
        }
        return out;
    }

    private byte[] rasterFigure(String storagePath, FigureMeta fig) {
        try (PDDocument doc = Loader.loadPDF(Path.of(storagePath).toFile())) {
            return rasterOf(doc, fig);
        } catch (Exception e) {
            log.warn("raster figure {} failed: {}", fig.anchor(), e.getMessage());
            return null;
        }
    }

    private static byte[] rasterOf(PDDocument doc, FigureMeta fig) throws Exception {
        int pageIndex = Math.max(0, fig.page() - 1);
        if (pageIndex >= doc.getNumberOfPages()) {
            return null;
        }
        PDPage page = doc.getPage(pageIndex);
        var resources = page.getResources();
        if (resources != null) {
            for (var name : resources.getXObjectNames()) {
                if (fig.name() != null && !fig.name().isBlank() && !name.getName().equals(fig.name())) {
                    continue;
                }
                var x = resources.getXObject(name);
                if (x instanceof PDImageXObject image) {
                    return toPng(image.getImage());
                }
            }
        }
        return toPng(new PDFRenderer(doc).renderImageWithDPI(pageIndex, 96));
    }

    private List<FigureMeta> extractFigures(PDDocument doc) {
        List<FigureMeta> figures = new ArrayList<>();
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            PDPage page = doc.getPage(i);
            PDRectangle media = page.getMediaBox();
            double pageWidthIn = Math.max(0.5, media.getWidth() / 72.0);
            var resources = page.getResources();
            if (resources == null) {
                continue;
            }
            for (var name : resources.getXObjectNames()) {
                try {
                    var x = resources.getXObject(name);
                    if (!(x instanceof PDImageXObject image)) {
                        continue;
                    }
                    int w = image.getWidth();
                    int h = image.getHeight();
                    double dpi = w / pageWidthIn;
                    boolean lowDpi = dpi > 0 && dpi < 150;
                    boolean tiny = Math.min(w, h) < 48;
                    boolean stretched = aspectExtreme(w, h);
                    boolean blurry = false;
                    try {
                        BufferedImage raster = image.getImage();
                        if (raster != null) {
                            blurry = laplacianVariance(raster) < BLUR_VARIANCE;
                        }
                    } catch (Exception e) {
                        log.debug("skip blur probe for {}: {}", name.getName(), e.getMessage());
                    }
                    figures.add(new FigureMeta(i + 1, name.getName(), w, h, dpi, lowDpi, tiny, stretched, blurry));
                } catch (Exception e) {
                    log.debug("skip xobject on page {}: {}", i + 1, e.getMessage());
                }
            }
        }
        return figures;
    }

    private static List<ProgramIssue> numberingIssues(String text) {
        List<ProgramIssue> issues = new ArrayList<>();
        List<Integer> figures = collectNumbers(FIGURE_NO, text);
        List<Integer> tables = collectNumbers(TABLE_NO, text);
        gapIssue(issues, "Figure", figures, "figures");
        gapIssue(issues, "Table", tables, "tables");
        if (text.toLowerCase(Locale.ROOT).contains("caption is missing")
                || text.toLowerCase(Locale.ROOT).contains("缺题注")) {
            issues.add(new ProgramIssue("MISSING_CAPTION", "LOW", "Caption missing for at least one figure",
                    "Deterministic parse: caption is marked missing. Vision was not invoked.", "caption"));
        }
        return issues;
    }

    private static void gapIssue(List<ProgramIssue> issues, String kind, List<Integer> nums, String anchor) {
        if (nums.isEmpty()) {
            return;
        }
        int max = nums.stream().mapToInt(Integer::intValue).max().orElse(0);
        Set<Integer> have = new LinkedHashSet<>(nums);
        List<Integer> missing = new ArrayList<>();
        for (int i = 1; i <= max; i++) {
            if (!have.contains(i)) {
                missing.add(i);
            }
        }
        boolean outOfOrder = false;
        int prev = 0;
        for (int n : nums) {
            if (n < prev) {
                outOfOrder = true;
                break;
            }
            prev = n;
        }
        if (!missing.isEmpty() || outOfOrder) {
            issues.add(new ProgramIssue("NUMBERING", "MEDIUM",
                    kind + " numbering gap or out of order: " + nums,
                    "Deterministic parse: " + kind + " numbers " + nums + (missing.isEmpty() ? "" : ", missing " + missing)
                            + (outOfOrder ? ", out of order." : ".") + " Vision was not invoked.",
                    anchor));
        }
    }

    private static List<Integer> collectNumbers(Pattern pattern, String text) {
        List<Integer> out = new ArrayList<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            out.add(Integer.parseInt(matcher.group(1)));
        }
        return out;
    }

    private static int declaredFigureCount(String text) {
        Matcher meta = META.matcher(text);
        if (meta.find()) {
            return Integer.parseInt(meta.group(4));
        }
        return -1;
    }

    private static PageSpec pageSpec(int page, float width, float height) {
        return new PageSpec(page, width, height, classify(width, height));
    }

    private static String specLabel(List<PageSpec> pages) {
        if (pages.isEmpty()) {
            return "UNKNOWN";
        }
        Set<String> specs = new LinkedHashSet<>();
        for (PageSpec page : pages) {
            specs.add(page.spec());
        }
        if (specs.size() > 1) {
            return "MIXED";
        }
        return specs.iterator().next();
    }

    private static String classify(float width, float height) {
        if (near(width, 595, 8) && near(height, 842, 8) || near(width, 842, 8) && near(height, 595, 8)) {
            return "A4";
        }
        if (near(width, 612, 8) && near(height, 792, 8) || near(width, 792, 8) && near(height, 612, 8)) {
            return "LETTER";
        }
        return "OTHER";
    }

    private static boolean near(float value, float target, float tol) {
        return Math.abs(value - target) <= tol;
    }

    private static boolean aspectExtreme(int width, int height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        double aspect = width / (double) height;
        return aspect > 8 || aspect < 0.125;
    }

    private static boolean isPdfFile(String storagePath) {
        return storagePath != null && !storagePath.isBlank()
                && Files.isRegularFile(Path.of(storagePath))
                && storagePath.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private static double laplacianVariance(BufferedImage src) {
        BufferedImage gray = scaleDown(src, 256);
        int w = gray.getWidth();
        int h = gray.getHeight();
        if (w < 3 || h < 3) {
            return 0;
        }
        double sum = 0;
        double sum2 = 0;
        int n = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int c = luminance(gray.getRGB(x, y));
                int v = luminance(gray.getRGB(x, y - 1)) + luminance(gray.getRGB(x, y + 1))
                        + luminance(gray.getRGB(x - 1, y)) + luminance(gray.getRGB(x + 1, y)) - 4 * c;
                sum += v;
                sum2 += (double) v * v;
                n++;
            }
        }
        if (n == 0) {
            return 0;
        }
        double mean = sum / n;
        return sum2 / n - mean * mean;
    }

    private static int luminance(int rgb) {
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private static byte[] toPng(BufferedImage src) throws Exception {
        if (src == null) {
            return null;
        }
        BufferedImage scaled = scaleDown(src, 1024);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(scaled, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage scaleDown(BufferedImage src, int maxEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= maxEdge && h <= maxEdge) {
            return src;
        }
        double s = Math.min(maxEdge / (double) w, maxEdge / (double) h);
        int nw = Math.max(1, (int) Math.round(w * s));
        int nh = Math.max(1, (int) Math.round(h * s));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
