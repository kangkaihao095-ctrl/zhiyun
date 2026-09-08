package com.zhiyun.manuscript;

import com.zhiyun.common.ApiException;
import com.zhiyun.common.Pages;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.DocumentVersion;
import com.zhiyun.domain.Manuscript;
import com.zhiyun.domain.ResearchProject;
import com.zhiyun.rag.DocumentParser;
import com.zhiyun.rag.RagService;
import com.zhiyun.rag.SemanticChunker;
import com.zhiyun.repo.DocumentVersionRepo;
import com.zhiyun.repo.ManuscriptRepo;
import com.zhiyun.repo.ProjectRepo;
import com.zhiyun.security.TenantContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class ManuscriptService {
    private final ProjectRepo projectRepo;
    private final ManuscriptRepo manuscriptRepo;
    private final DocumentVersionRepo documentVersionRepo;
    private final DocumentParser documentParser;
    private final SemanticChunker chunker;
    private final RagService ragService;
    private final Path storageDir;

    public ManuscriptService(ProjectRepo projectRepo, ManuscriptRepo manuscriptRepo,
                             DocumentVersionRepo documentVersionRepo, DocumentParser documentParser,
                             SemanticChunker chunker, RagService ragService,
                             com.zhiyun.config.ZhiyunProperties properties) {
        this.projectRepo = projectRepo;
        this.manuscriptRepo = manuscriptRepo;
        this.documentVersionRepo = documentVersionRepo;
        this.documentParser = documentParser;
        this.chunker = chunker;
        this.ragService = ragService;
        this.storageDir = Path.of(properties.getStorage().getDir());
    }

    public List<ResearchProject> projects() {
        return projectRepo.findByTenantIdOrderByIdDesc(TenantContext.tenantId());
    }

    @Transactional
    public ResearchProject createProject(String name) {
        ResearchProject project = new ResearchProject();
        project.setTenantId(TenantContext.tenantId());
        project.setName(name);
        return projectRepo.save(project);
    }

    public List<Manuscript> list() {
        return manuscriptRepo.findByTenantIdOrderByIdDesc(TenantContext.tenantId());
    }

    /**
     * 论文列表。{@code projectId} 空 = 本租户全部；有值则必须是本租户课题，否则 404（他租户同号也 404）。
     */
    public Map<String, Object> list(String q, Integer page, Integer size, Long projectId) {
        int p = Pages.page(page);
        int s = Pages.size(size);
        long tenantId = TenantContext.tenantId();
        boolean projectBlank = projectId == null;
        if (!projectBlank) {
            projectRepo.findByIdAndTenantId(projectId, tenantId)
                    .orElseThrow(() -> ApiException.notFound("project not found"));
        }
        var result = manuscriptRepo.search(
                tenantId,
                Pages.flag(projectBlank),
                projectBlank ? 0L : projectId,
                Pages.flag(Pages.blank(q)),
                Pages.needle(q),
                Pages.of(p, s));
        return Pages.wrap(result.getContent(), result.getTotalElements(), p, s);
    }

    public Map<String, Object> detail(long id) {
        Manuscript ms = requireManuscript(id);
        List<DocumentVersion> versions = documentVersionRepo
                .findByManuscriptIdAndTenantIdOrderByVersionNoAsc(id, TenantContext.tenantId());
        List<Map<String, Object>> views = new ArrayList<>();
        for (DocumentVersion version : versions) {
            views.add(toView(version, ms.getCurrentVersion()));
        }
        return Map.of("manuscript", ms, "versions", views);
    }

    public Map<String, Object> versionPreview(long manuscriptId, int versionNo) {
        Manuscript ms = requireManuscript(manuscriptId);
        DocumentVersion version = requireVersion(manuscriptId, versionNo);
        return toView(version, ms.getCurrentVersion());
    }

    public FilePayload fileOf(long manuscriptId, int versionNo) {
        requireManuscript(manuscriptId);
        DocumentVersion version = requireVersion(manuscriptId, versionNo);
        Path path = resolveStoredFile(version.getStoragePath());
        if (path == null) {
            throw ApiException.notFound("原件不在磁盘上");
        }
        String filename = DocumentParser.originalFilename(path.getFileName().toString());
        return new FilePayload(new FileSystemResource(path), mediaTypeOf(filename), filename);
    }

    /**
     * PDF 页规格 / 图表程序检查摘要。非 PDF 只回 format，不假装有页预览。
     */
    public Map<String, Object> inspect(long manuscriptId, int versionNo) {
        requireManuscript(manuscriptId);
        DocumentVersion version = requireVersion(manuscriptId, versionNo);
        Path file = resolveStoredFile(version.getStoragePath());
        String filename = file != null
                ? DocumentParser.originalFilename(file.getFileName().toString())
                : DocumentParser.originalFilename(version.getStoragePath());
        String format = formatOf(filename);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("manuscriptId", manuscriptId);
        out.put("versionNo", versionNo);
        out.put("format", format);
        out.put("filename", filename);
        out.put("hasFile", file != null);
        boolean pdf = "PDF".equals(format);
        out.put("pagePreview", pdf);
        if (!pdf) {
            return out;
        }
        String text = resolveText(version);
        DocumentParser.PdfInspection inspection = documentParser.inspect(version.getStoragePath(), text);
        List<DocumentParser.CaptionRef> captions = DocumentParser.captionsOf(text);
        Map<Integer, String> captionByNo = new LinkedHashMap<>();
        for (DocumentParser.CaptionRef cap : captions) {
            captionByNo.putIfAbsent(cap.number(), cap.caption());
        }
        List<Map<String, Object>> pages = new ArrayList<>();
        for (DocumentParser.PageSpec page : inspection.pages()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("page", page.page());
            row.put("widthPt", page.widthPt());
            row.put("heightPt", page.heightPt());
            row.put("spec", page.spec());
            pages.add(row);
        }
        List<Map<String, Object>> figures = new ArrayList<>();
        int index = 0;
        for (DocumentParser.FigureMeta fig : inspection.figures()) {
            index++;
            int number = index;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("page", fig.page());
            row.put("name", fig.name());
            row.put("number", number);
            row.put("width", fig.width());
            row.put("height", fig.height());
            row.put("approxDpi", Math.round(fig.approxDpi() * 10.0) / 10.0);
            row.put("caption", captionByNo.getOrDefault(number, ""));
            row.put("needsVision", fig.needsVision());
            row.put("lowDpi", fig.lowDpi());
            row.put("tiny", fig.tiny());
            row.put("stretched", fig.stretched());
            row.put("possiblyBlurry", fig.possiblyBlurry());
            row.put("anchor", fig.anchor());
            figures.add(row);
        }
        List<Map<String, Object>> captionRows = new ArrayList<>();
        for (DocumentParser.CaptionRef cap : captions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("number", cap.number());
            row.put("caption", cap.caption());
            captionRows.add(row);
        }
        List<Map<String, Object>> issues = new ArrayList<>();
        for (DocumentParser.ProgramIssue item : inspection.issues()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", item.code());
            row.put("severity", item.severity());
            row.put("summary", item.summary());
            row.put("detail", item.detail());
            row.put("anchor", item.anchor());
            issues.add(row);
        }
        out.put("pageSpec", inspection.pageSpec());
        out.put("pageCount", inspection.pages().size());
        out.put("needsVision", inspection.needsVision());
        out.put("pages", pages);
        out.put("figures", figures);
        out.put("captions", captionRows);
        out.put("issues", issues);
        return out;
    }

    /** 正文为空时从 storage_path 再抽一次，并写回库。 */
    @Transactional
    public String resolveText(DocumentVersion version) {
        if (version == null) {
            return "";
        }
        String text = version.getContentText();
        if (text != null && !text.isBlank()) {
            return text;
        }
        String filled = documentParser.textFromStorage(version.getStoragePath());
        if (filled == null || filled.isBlank()) {
            return text == null ? "" : text;
        }
        version.setContentText(filled);
        version.setContentSha256(SemanticChunker.sha(filled));
        documentVersionRepo.save(version);
        return filled;
    }

    @Transactional
    public Manuscript upload(long projectId, MultipartFile file) throws Exception {
        ResearchProject project = projectRepo.findByIdAndTenantId(projectId, TenantContext.tenantId())
                .orElseThrow(() -> ApiException.notFound("project not found"));
        DocumentParser.ParsedDocument parsed = documentParser.parse(file);
        Files.createDirectories(storageDir);
        Path dest = storageDir.resolve(UUID.randomUUID() + "-" + parsed.filename());
        file.transferTo(dest);

        Manuscript ms = new Manuscript();
        ms.setTenantId(TenantContext.tenantId());
        ms.setProjectId(project.getId());
        ms.setTitle(parsed.filename());
        ms.setCurrentVersion(1);
        ms = manuscriptRepo.save(ms);

        saveVersion(ms, 1, Codes.OFFICIAL, dest.toString(), parsed.text());
        ragService.indexManuscript(ms.getTenantId(), ms.getProjectId(), ms.getId(), 1,
                chunker.split(parsed.text()), "PRIVATE");
        return ms;
    }

    public DocumentVersion saveVersion(Manuscript ms, int versionNo, String status, String path, String text) {
        DocumentVersion version = new DocumentVersion();
        version.setTenantId(ms.getTenantId());
        version.setManuscriptId(ms.getId());
        version.setVersionNo(versionNo);
        version.setStatus(status);
        version.setStoragePath(path);
        version.setContentText(text);
        version.setContentSha256(SemanticChunker.sha(text));
        return documentVersionRepo.save(version);
    }

    public Manuscript requireManuscript(long id) {
        return manuscriptRepo.findByIdAndTenantId(id, TenantContext.tenantId())
                .orElseThrow(() -> ApiException.notFound("manuscript not found"));
    }

    public DocumentVersion requireVersion(long manuscriptId, int versionNo) {
        return documentVersionRepo.findByManuscriptIdAndVersionNoAndTenantId(manuscriptId, versionNo, TenantContext.tenantId())
                .orElseThrow(() -> ApiException.notFound("document version not found"));
    }

    private Map<String, Object> toView(DocumentVersion version, Integer currentVersion) {
        String text = resolveText(version);
        Path file = resolveStoredFile(version.getStoragePath());
        String filename = file != null
                ? DocumentParser.originalFilename(file.getFileName().toString())
                : DocumentParser.originalFilename(version.getStoragePath());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", version.getId());
        out.put("versionNo", version.getVersionNo());
        out.put("status", version.getStatus());
        out.put("contentText", text);
        out.put("createdAt", version.getCreatedAt());
        out.put("hasFile", file != null);
        out.put("filename", filename);
        out.put("format", formatOf(filename));
        out.put("current", currentVersion != null && currentVersion.equals(version.getVersionNo())
                && Codes.OFFICIAL.equals(version.getStatus()));
        return out;
    }

    private Path resolveStoredFile(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return null;
        }
        Path path = Path.of(storagePath).toAbsolutePath().normalize();
        Path root = storageDir.toAbsolutePath().normalize();
        if (!path.startsWith(root)) {
            return null;
        }
        return Files.isRegularFile(path) ? path : null;
    }

    private static String formatOf(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return "PDF";
        }
        if (lower.endsWith(".docx")) {
            return "DOCX";
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "MD";
        }
        if (lower.endsWith(".tex")) {
            return "TEX";
        }
        if (lower.endsWith(".txt")) {
            return "TXT";
        }
        return "FILE";
    }

    private static MediaType mediaTypeOf(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        }
        if (lower.endsWith(".docx")) {
            return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return MediaType.parseMediaType("text/markdown");
        }
        if (lower.endsWith(".txt") || lower.endsWith(".tex")) {
            return MediaType.TEXT_PLAIN;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    public record FilePayload(Resource resource, MediaType mediaType, String filename) {
    }
}
