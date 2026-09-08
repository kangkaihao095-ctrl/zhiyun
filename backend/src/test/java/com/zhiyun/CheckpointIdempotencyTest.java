package com.zhiyun;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyun.harness.AgentIds;
import com.zhiyun.harness.ArtifactStore;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.repo.ArtifactRepo;
import com.zhiyun.repo.ReviewTaskRepo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CheckpointIdempotencyTest {
    @Autowired
    ArtifactStore artifactStore;
    @Autowired
    ReviewTaskRepo reviewTaskRepo;
    @Autowired
    ArtifactRepo artifactRepo;
    @Autowired
    ObjectMapper mapper;

    @Test
    void duplicateArtifactWriteIsIdempotent() {
        ReviewTask task = new ReviewTask();
        task.setTenantId(9L);
        task.setUserId(9L);
        task.setManuscriptId(9L);
        task.setWorkflow(Codes.CITATION_ONLY);
        task.setStatus(Codes.RUNNING);
        task.setSourceVersion(1);
        task.setFencingToken(1L);
        task.setIdempotencyKey("idem-" + System.nanoTime());
        task = reviewTaskRepo.saveAndFlush(task);
        ObjectNode body = mapper.createObjectNode().put("ok", true);
        artifactStore.save(task, 1L, AgentIds.CITATION, "Bundle", body);
        artifactStore.save(task, 1L, AgentIds.CITATION, "Bundle", body);
        assertThat(artifactRepo.findByTaskIdAndAgentAndArtifactType(task.getId(), AgentIds.CITATION, "Bundle")).isPresent();
        assertThat(artifactStore.completed(task.getId(), AgentIds.CITATION)).isTrue();
    }
}
