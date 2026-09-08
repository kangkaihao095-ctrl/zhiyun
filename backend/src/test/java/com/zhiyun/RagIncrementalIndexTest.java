package com.zhiyun;

import com.zhiyun.rag.RagService;
import com.zhiyun.rag.SemanticChunker;
import com.zhiyun.repo.ChunkHashRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RagIncrementalIndexTest {
    @Autowired
    RagService ragService;
    @Autowired
    SemanticChunker chunker;
    @Autowired
    ChunkHashRepo chunkHashRepo;

    @Test
    void skipsUnchangedChunksByContentSha256() {
        long tenant = 88001L;
        long manuscript = 88002L;
        String body = """
                # Introduction
                Unchanged paragraph stays exactly the same across versions for incremental embed skip.
                
                # Method
                Another stable section about retrieval augmented review without extra noise.
                """;
        List<SemanticChunker.Chunk> chunks = chunker.split(body);
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);

        RagService.IndexResult first = ragService.indexManuscript(tenant, 1, manuscript, 1, chunks, "PRIVATE");
        assertThat(first.embedded()).isEqualTo(chunks.size());
        assertThat(first.skipped()).isZero();

        RagService.IndexResult again = ragService.indexManuscript(tenant, 1, manuscript, 1, chunks, "PRIVATE");
        assertThat(again.embedded()).isZero();
        assertThat(again.skipped()).isEqualTo(chunks.size());

        RagService.IndexResult nextVersion = ragService.indexManuscript(tenant, 1, manuscript, 2, chunks, "PRIVATE");
        assertThat(nextVersion.embedded()).isZero();
        assertThat(nextVersion.skipped()).isEqualTo(chunks.size());
        assertThat(chunkHashRepo.findByManuscriptIdAndVersionNoAndTenantId(manuscript, 2, tenant)).hasSize(chunks.size());

        List<SemanticChunker.Chunk> mutated = chunker.split(body + "\n\n# Conclusion\nA brand new closing paragraph that must be embedded.\n");
        RagService.IndexResult mixed = ragService.indexManuscript(tenant, 1, manuscript, 3, mutated, "PRIVATE");
        assertThat(mixed.embedded()).isGreaterThan(0);
        assertThat(mixed.skipped()).isGreaterThan(0);
        assertThat(mixed.embedded() + mixed.skipped()).isEqualTo(mutated.size());
    }
}
