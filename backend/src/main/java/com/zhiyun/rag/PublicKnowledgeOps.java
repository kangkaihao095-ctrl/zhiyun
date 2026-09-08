package com.zhiyun.rag;

import com.zhiyun.common.ApiException;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.domain.PublicKnowledgeDoc;
import com.zhiyun.repo.ChunkHashRepo;
import com.zhiyun.repo.PublicKnowledgeDocRepo;
import com.zhiyun.repo.UserRepo;
import com.zhiyun.security.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 公共投稿规范运营：全局一份 PUBLIC 索引，可上传/重建。私有检索仍带 tenant+version。
 */
@Service
public class PublicKnowledgeOps {
    private static final Logger log = LoggerFactory.getLogger(PublicKnowledgeOps.class);

    private final SemanticChunker chunker;
    private final RagService ragService;
    private final ChunkHashRepo chunkHashRepo;
    private final PublicKnowledgeDocRepo docRepo;
    private final UserRepo userRepo;
    private final ZhiyunProperties properties;

    public PublicKnowledgeOps(SemanticChunker chunker, RagService ragService, ChunkHashRepo chunkHashRepo,
                              PublicKnowledgeDocRepo docRepo, UserRepo userRepo, ZhiyunProperties properties) {
        this.chunker = chunker;
        this.ragService = ragService;
        this.chunkHashRepo = chunkHashRepo;
        this.docRepo = docRepo;
        this.userRepo = userRepo;
        this.properties = properties;
    }

    public boolean isOperator() {
        String email = userRepo.findById(TenantContext.userId()).map(u -> u.getEmail()).orElse("");
        return properties.getOps().isOperator(email);
    }

    public void requireOperator() {
        if (!isOperator()) {
            throw ApiException.forbidden("只有演示账号或运营邮箱可以更新公共投稿规范");
        }
    }

    public Map<String, Object> catalog() {
        requireOperator();
        List<Map<String, Object>> bundled = new ArrayList<>();
        for (ClasspathDoc doc : classpathDocs()) {
            bundled.add(PublicKnowledgeOverview.row("classpath", doc.filename(), doc.content(), null, null));
        }
        List<Map<String, Object>> uploaded = new ArrayList<>();
        for (PublicKnowledgeDoc doc : docRepo.findAll()) {
            uploaded.add(PublicKnowledgeOverview.row(
                    "ops",
                    doc.getFilename(),
                    doc.getContent(),
                    doc.getId(),
                    doc.getUpdatedAt()
            ));
        }
        int publicChunks = chunkHashRepo.findPublicChunks().size();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bundled", bundled);
        out.put("uploaded", uploaded);
        out.put("publicChunks", publicChunks);
        out.put("groups", PublicKnowledgeOverview.groups(bundled, uploaded.size(), publicChunks));
        out.put("note", "公共知识全局一份（scope=PUBLIC）。私有检索仍带当前租户与稿件版本。");
        return out;
    }

    @Transactional
    public Map<String, Object> upload(MultipartFile file) throws Exception {
        requireOperator();
        if (file == null || file.isEmpty()) {
            throw ApiException.bad("请上传 Markdown 文件");
        }
        String filename = sanitize(file.getOriginalFilename());
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        if (content.isBlank()) {
            throw ApiException.bad("文件是空的");
        }
        if (content.length() > 400_000) {
            throw ApiException.bad("单份规范请控制在 400KB 以内");
        }
        PublicKnowledgeDoc row = docRepo.findByFilename(filename).orElseGet(PublicKnowledgeDoc::new);
        row.setFilename(filename);
        row.setContent(content);
        row.setUploadedByUserId(TenantContext.userId());
        row.setUpdatedAt(Instant.now());
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(Instant.now());
        }
        docRepo.save(row);
        rebuild();
        return catalog();
    }

    @Transactional
    public Map<String, Object> delete(long id) {
        requireOperator();
        PublicKnowledgeDoc row = docRepo.findById(id).orElseThrow(() -> ApiException.notFound("规范不存在"));
        docRepo.delete(row);
        rebuild();
        return catalog();
    }

    @Transactional
    public Map<String, Object> reindex() {
        requireOperator();
        RagService.IndexResult result = rebuild();
        Map<String, Object> out = catalog();
        out.put("indexed", result.total());
        out.put("embedded", result.embedded());
        return out;
    }

    @Transactional
    public RagService.IndexResult rebuild() {
        ragService.deletePublicIndex();
        List<SemanticChunker.Chunk> all = new ArrayList<>();
        for (ClasspathDoc doc : classpathDocs()) {
            all.addAll(prefixChunks(doc.filename().replace(".md", ""), chunker.split(doc.content())));
        }
        for (PublicKnowledgeDoc doc : docRepo.findAll()) {
            String prefix = "ops-" + doc.getId();
            all.addAll(prefixChunks(prefix, chunker.split(doc.getContent() == null ? "" : doc.getContent())));
        }
        if (all.isEmpty()) {
            log.warn("public knowledge rebuild produced no chunks");
            return new RagService.IndexResult(0, 0, 0);
        }
        RagService.IndexResult result = ragService.indexManuscript(0, 0, 0, 0, all, "PUBLIC");
        log.info("public RAG rebuilt: {} chunks, embedded={}", result.total(), result.embedded());
        return result;
    }

    private List<ClasspathDoc> classpathDocs() {
        List<ClasspathDoc> out = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:knowledge/*.md");
            Arrays.sort(resources, Comparator.comparing(Resource::getFilename, Comparator.nullsLast(String::compareTo)));
            for (Resource resource : resources) {
                if (resource.getFilename() == null) {
                    continue;
                }
                String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                out.add(new ClasspathDoc(resource.getFilename(), text));
            }
        } catch (Exception e) {
            log.warn("read classpath knowledge failed: {}", e.getMessage());
        }
        return out;
    }

    private static List<SemanticChunker.Chunk> prefixChunks(String prefix, List<SemanticChunker.Chunk> chunks) {
        List<SemanticChunker.Chunk> out = new ArrayList<>();
        for (SemanticChunker.Chunk chunk : chunks) {
            out.add(new SemanticChunker.Chunk(
                    prefix + "-" + chunk.chunkId(),
                    chunk.section(),
                    chunk.content(),
                    chunk.sha256()
            ));
        }
        return out;
    }

    private static String sanitize(String raw) {
        String name = raw == null ? "note.md" : raw.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isBlank()) {
            name = "note.md";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".md") && !lower.endsWith(".markdown") && !lower.endsWith(".txt")) {
            throw ApiException.bad("请上传 .md / .markdown / .txt");
        }
        return name.replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]", "_");
    }

    private record ClasspathDoc(String filename, String content) {
    }
}
