package com.zhiyun.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.KnnSearch;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.domain.ChunkHash;
import com.zhiyun.llm.LlmGateway;
import com.zhiyun.repo.ChunkHashRepo;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RagService {
    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final ZhiyunProperties properties;
    private final ChunkHashRepo chunkHashRepo;
    private final LlmGateway llmGateway;
    private RestClient lowLevel;
    private ElasticsearchClient es;

    public RagService(ZhiyunProperties properties, ChunkHashRepo chunkHashRepo, LlmGateway llmGateway) {
        this.properties = properties;
        this.chunkHashRepo = chunkHashRepo;
        this.llmGateway = llmGateway;
    }

    @PostConstruct
    public void init() {
        if (!properties.getElasticsearch().isEnabled()) {
            return;
        }
        try {
            lowLevel = RestClient.builder(HttpHost.create(properties.getElasticsearch().getUrl())).build();
            es = new ElasticsearchClient(new RestClientTransport(lowLevel, new JacksonJsonpMapper()));
            ensureIndex();
        } catch (Exception e) {
            log.warn("Elasticsearch unavailable, falling back to lexical RAG: {}", e.getMessage());
            es = null;
        }
    }

    @PreDestroy
    public void close() throws Exception {
        if (lowLevel != null) {
            lowLevel.close();
        }
    }

    public IndexResult indexManuscript(long tenantId, long projectId, long manuscriptId, int version,
                                List<SemanticChunker.Chunk> chunks, String scope) {
        if (chunks == null || chunks.isEmpty()) {
            return new IndexResult(0, 0, 0);
        }
        List<ChunkHash> prior = chunkHashRepo.findByManuscriptIdAndTenantId(manuscriptId, tenantId);
        Set<String> knownSha = new HashSet<>();
        for (ChunkHash row : prior) {
            if (row.getContentSha256() != null && !row.getContentSha256().isBlank()) {
                knownSha.add(row.getContentSha256());
            }
        }
        Map<String, ChunkHash> thisVersion = new HashMap<>();
        for (ChunkHash row : chunkHashRepo.findByManuscriptIdAndVersionNoAndTenantId(manuscriptId, version, tenantId)) {
            thisVersion.put(row.getChunkId(), row);
        }
        List<String> toEmbed = new ArrayList<>();
        List<Integer> embedAt = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            SemanticChunker.Chunk chunk = chunks.get(i);
            if (!knownSha.contains(chunk.sha256())) {
                embedAt.add(i);
                toEmbed.add(chunk.content());
            }
        }
        List<float[]> newVectors = toEmbed.isEmpty() ? List.of() : llmGateway.embedBatch(toEmbed);
        Map<Integer, float[]> byIndex = new HashMap<>();
        for (int k = 0; k < embedAt.size(); k++) {
            byIndex.put(embedAt.get(k), k < newVectors.size() ? newVectors.get(k) : llmGateway.embed(chunks.get(embedAt.get(k)).content()));
        }
        int embedded = 0;
        int skipped = 0;
        for (int i = 0; i < chunks.size(); i++) {
            SemanticChunker.Chunk chunk = chunks.get(i);
            ChunkHash row = thisVersion.getOrDefault(chunk.chunkId(), new ChunkHash());
            row.setTenantId(tenantId);
            row.setManuscriptId(manuscriptId);
            row.setVersionNo(version);
            row.setChunkId(chunk.chunkId());
            row.setSection(chunk.section());
            row.setContent(chunk.content());
            row.setContentSha256(chunk.sha256());
            chunkHashRepo.save(row);
            float[] vec = byIndex.get(i);
            if (vec != null) {
                embedded++;
                indexEs(tenantId, projectId, manuscriptId, version, chunk, scope, vec);
            } else {
                skipped++;
                float[] reused = existingVector(chunk.sha256());
                if (reused != null) {
                    indexEs(tenantId, projectId, manuscriptId, version, chunk, scope, reused);
                }
            }
        }
        return new IndexResult(chunks.size(), embedded, skipped);
    }

    public record IndexResult(int total, int embedded, int skipped) {
    }

    /** 重建公共知识前清空 MySQL 切片与 ES PUBLIC 文档。 */
    public void deletePublicIndex() {
        chunkHashRepo.deletePublicChunks();
        if (es == null) {
            return;
        }
        try {
            es.deleteByQuery(d -> d
                    .index(properties.getElasticsearch().getIndex())
                    .query(q -> q.term(t -> t.field("scope").value("PUBLIC"))));
        } catch (Exception e) {
            log.warn("delete public ES docs skipped: {}", e.getMessage());
        }
    }

    public List<Retrieved> retrievePrivate(long tenantId, long manuscriptId, int version, String query, String section) {
        List<Retrieved> hits = knn(tenantId, manuscriptId, version, "PRIVATE", query, section);
        if (hits.isEmpty()) {
            hits = lexical(chunkHashRepo.findByManuscriptIdAndVersionNoAndTenantId(manuscriptId, version, tenantId), query);
        }
        return rerank(query, hits);
    }

    public List<Retrieved> retrievePublic(String query) {
        List<Retrieved> hits = knn(0, 0, 0, "PUBLIC", query, null);
        if (hits.isEmpty()) {
            hits = lexical(chunkHashRepo.findPublicChunks(), query);
        }
        return rerank(query, hits);
    }

    private void ensureIndex() throws Exception {
        String index = properties.getElasticsearch().getIndex();
        int dims = properties.getLlm().getEmbeddingDims();
        boolean exists = es.indices().exists(e -> e.index(index)).value();
        if (exists) {
            try {
                var mapping = es.indices().getMapping(m -> m.index(index));
                var props = mapping.get(index).mappings().properties();
                var emb = props == null ? null : props.get("embedding");
                Integer have = null;
                if (emb != null && emb.isDenseVector()) {
                    have = emb.denseVector().dims();
                }
                if (have != null && have != dims) {
                    log.warn("recreate ES index {} (dims {} → {})", index, have, dims);
                    es.indices().delete(d -> d.index(index));
                    exists = false;
                }
            } catch (Exception e) {
                log.warn("could not inspect ES mapping: {}", e.getMessage());
            }
        }
        if (exists) {
            return;
        }
        es.indices().create(c -> c.index(index).mappings(m -> m
                .properties("tenantId", p -> p.long_(l -> l))
                .properties("researchProjectId", p -> p.long_(l -> l))
                .properties("manuscriptId", p -> p.long_(l -> l))
                .properties("documentVersion", p -> p.integer(i -> i))
                .properties("section", p -> p.keyword(k -> k))
                .properties("chunkId", p -> p.keyword(k -> k))
                .properties("scope", p -> p.keyword(k -> k))
                .properties("content", p -> p.text(t -> t))
                .properties("contentSha256", p -> p.keyword(k -> k))
                .properties("embedding", p -> p.denseVector(d -> d.dims(dims).index(true).similarity("cosine")))
        ));
    }

    private void indexEs(long tenantId, long projectId, long manuscriptId, int version,
                         SemanticChunker.Chunk chunk, String scope, float[] vec) {
        if (es == null) {
            return;
        }
        try {
            Map<String, Object> doc = new HashMap<>();
            doc.put("tenantId", tenantId);
            doc.put("researchProjectId", projectId);
            doc.put("manuscriptId", manuscriptId);
            doc.put("documentVersion", version);
            doc.put("section", chunk.section());
            doc.put("chunkId", chunk.chunkId());
            doc.put("scope", scope);
            doc.put("content", chunk.content());
            doc.put("contentSha256", chunk.sha256());
            List<Float> embedding = new ArrayList<>();
            for (float v : vec) {
                embedding.add(v);
            }
            doc.put("embedding", embedding);
            String id = tenantId + "-" + manuscriptId + "-" + version + "-" + chunk.chunkId();
            es.index(i -> i.index(properties.getElasticsearch().getIndex()).id(id).document(doc));
        } catch (Exception e) {
            log.warn("skip ES index for {}: {}", chunk.chunkId(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private float[] existingVector(String sha256) {
        if (es == null || sha256 == null || sha256.isBlank()) {
            return null;
        }
        try {
            SearchResponse<Map> resp = es.search(s -> s
                    .index(properties.getElasticsearch().getIndex())
                    .query(q -> q.term(t -> t.field("contentSha256").value(sha256)))
                    .size(1), Map.class);
            if (resp.hits().hits().isEmpty()) {
                return null;
            }
            Map src = resp.hits().hits().get(0).source();
            if (src == null) {
                return null;
            }
            Object embedding = src.get("embedding");
            if (!(embedding instanceof List<?> list) || list.isEmpty()) {
                return null;
            }
            float[] vec = new float[list.size()];
            for (int i = 0; i < list.size(); i++) {
                vec[i] = ((Number) list.get(i)).floatValue();
            }
            return vec;
        } catch (Exception e) {
            log.debug("reuse embedding for {} failed: {}", sha256, e.getMessage());
            return null;
        }
    }

    private List<Retrieved> knn(long tenantId, long manuscriptId, int version, String scope, String query, String section) {
        if (es == null) {
            return List.of();
        }
        try {
            float[] vec = llmGateway.embed(query);
            List<Float> q = new ArrayList<>();
            for (float v : vec) {
                q.add(v);
            }
            SearchResponse<Map> resp = es.search(s -> s
                    .index(properties.getElasticsearch().getIndex())
                    .knn(k -> k
                            .field("embedding")
                            .queryVector(q)
                            .k(20L)
                            .numCandidates(60L)
                            .filter(f -> f.bool(b -> {
                                b.filter(ff -> ff.term(t -> t.field("scope").value(scope)));
                                if (!"PUBLIC".equals(scope)) {
                                    b.filter(ff -> ff.term(t -> t.field("tenantId").value(tenantId)));
                                    b.filter(ff -> ff.term(t -> t.field("manuscriptId").value(manuscriptId)));
                                    b.filter(ff -> ff.term(t -> t.field("documentVersion").value(version)));
                                } else {
                                    b.filter(ff -> ff.term(t -> t.field("tenantId").value(0)));
                                }
                                if (section != null && !section.isBlank()) {
                                    b.filter(ff -> ff.term(t -> t.field("section").value(section)));
                                }
                                return b;
                            })))
                    .size(20), Map.class);
            List<Retrieved> out = new ArrayList<>();
            for (Hit<Map> hit : resp.hits().hits()) {
                Map src = hit.source();
                if (src == null) {
                    continue;
                }
                out.add(new Retrieved(String.valueOf(src.get("chunkId")),
                        String.valueOf(src.get("section")),
                        String.valueOf(src.get("content")),
                        hit.score() == null ? 0 : hit.score()));
            }
            return out;
        } catch (Exception e) {
            log.warn("knn failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<Retrieved> lexical(List<ChunkHash> rows, String query) {
        String q = query.toLowerCase(Locale.ROOT);
        String[] terms = q.split("\\W+");
        List<Retrieved> out = new ArrayList<>();
        for (ChunkHash row : rows) {
            String c = row.getContent().toLowerCase(Locale.ROOT);
            double score = 0;
            for (String t : terms) {
                if (t.length() > 2 && c.contains(t)) {
                    score += 1;
                }
            }
            if (score > 0) {
                out.add(new Retrieved(row.getChunkId(), row.getSection(), row.getContent(), score));
            }
        }
        out.sort(Comparator.comparingDouble(Retrieved::score).reversed());
        return out.size() > 20 ? out.subList(0, 20) : out;
    }

    /** 召回约 20 后用网关 rerank 到 Top-5。无 Key 时保持原序截断。 */
    private List<Retrieved> rerank(String query, List<Retrieved> hits) {
        if (hits.isEmpty()) {
            return hits;
        }
        List<String> docs = hits.stream().map(Retrieved::content).toList();
        List<Integer> order = llmGateway.rerank(query, docs, Math.min(5, hits.size()));
        List<Retrieved> out = new ArrayList<>();
        for (Integer idx : order) {
            if (idx != null && idx >= 0 && idx < hits.size()) {
                out.add(hits.get(idx));
            }
        }
        if (out.isEmpty()) {
            return hits.size() > 5 ? hits.subList(0, 5) : hits;
        }
        return out.size() > 5 ? out.subList(0, 5) : out;
    }

    public record Retrieved(String chunkId, String section, String content, double score) {
    }
}
