package com.zhiyun.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyun.common.ApiException;
import com.zhiyun.domain.Artifact;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.repo.ArtifactRepo;
import com.zhiyun.repo.ReviewTaskRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class ArtifactStore {
    private static final Logger log = LoggerFactory.getLogger(ArtifactStore.class);

    private final ArtifactRepo artifactRepo;
    private final ReviewTaskRepo reviewTaskRepo;
    private final ObjectMapper objectMapper;
    private final HarnessMeters harnessMeters;

    public ArtifactStore(ArtifactRepo artifactRepo, ReviewTaskRepo reviewTaskRepo, ObjectMapper objectMapper) {
        this(artifactRepo, reviewTaskRepo, objectMapper, null);
    }

    @Autowired
    public ArtifactStore(ArtifactRepo artifactRepo, ReviewTaskRepo reviewTaskRepo, ObjectMapper objectMapper,
                         HarnessMeters harnessMeters) {
        this.artifactRepo = artifactRepo;
        this.reviewTaskRepo = reviewTaskRepo;
        this.objectMapper = objectMapper;
        this.harnessMeters = harnessMeters;
    }

    @Transactional
    public void save(ReviewTask task, long fencingToken, String agent, String artifactType, JsonNode payload) {
        ReviewTask fresh = reviewTaskRepo.findById(task.getId()).orElseThrow();
        long current = fresh.getFencingToken() == null ? 0L : fresh.getFencingToken();
        if (fencingToken < current) {
            log.warn("stale fencing token rejected taskId={} write={} current={}",
                    task.publicId(), fencingToken, current);
            if (harnessMeters != null) {
                harnessMeters.recordFencingRejected();
            }
            throw new ApiException(HttpStatus.CONFLICT, "stale fencing token rejected");
        }
        Optional<Artifact> existing = artifactRepo.findByTaskIdAndAgentAndArtifactType(task.getId(), agent, artifactType);
        if (existing.isPresent()) {
            return;
        }
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("schemaVersion", 1);
        envelope.put("agent", agent);
        envelope.put("producedAt", Instant.now().toString());
        envelope.put("fencingToken", fencingToken);
        envelope.set("body", payload);
        Artifact artifact = new Artifact();
        artifact.setTenantId(task.getTenantId());
        artifact.setTaskId(task.getId());
        artifact.setAgent(agent);
        artifact.setArtifactType(artifactType);
        artifact.setFencingToken(fencingToken);
        artifact.setPayload(envelope.toString());
        artifactRepo.save(artifact);
        if (reviewTaskRepo.casCheckpoint(task.getId(), agent, fencingToken) == 0) {
            ReviewTask after = reviewTaskRepo.findById(task.getId()).orElseThrow();
            long now = after.getFencingToken() == null ? 0L : after.getFencingToken();
            if (fencingToken < now) {
                log.warn("stale fencing token rejected taskId={} write={} current={}",
                        task.publicId(), fencingToken, now);
                if (harnessMeters != null) {
                    harnessMeters.recordFencingRejected();
                }
                throw new ApiException(HttpStatus.CONFLICT, "stale fencing token rejected");
            }
        }
    }

    public boolean completed(long taskId, String agent) {
        return artifactRepo.existsByTaskIdAndAgent(taskId, agent);
    }

    public List<Artifact> list(long taskId, long tenantId) {
        return artifactRepo.findByTaskIdAndTenantIdOrderByIdAsc(taskId, tenantId);
    }

    public JsonNode body(long taskId, String agent, String type) {
        return artifactRepo.findByTaskIdAndAgentAndArtifactType(taskId, agent, type)
                .map(a -> {
                    try {
                        return objectMapper.readTree(a.getPayload()).path("body");
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .orElse(objectMapper.missingNode());
    }
}
