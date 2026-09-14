package com.zhiyun.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.domain.DocumentVersion;
import com.zhiyun.domain.Manuscript;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.harness.AgentTraceService;
import com.zhiyun.harness.ArtifactStore;
import com.zhiyun.harness.HarnessMeters;
import com.zhiyun.harness.LeaseService;
import com.zhiyun.harness.ReviewSlot;
import com.zhiyun.harness.ToolPolicy;
import com.zhiyun.llm.AgentModelRouter;
import com.zhiyun.llm.LlmGateway;
import com.zhiyun.llm.UserLlmOverride;
import com.zhiyun.rag.DocumentParser;
import com.zhiyun.rag.RagService;
import com.zhiyun.tool.AcademicSearchTool;
import com.zhiyun.tool.DocxTool;
import com.zhiyun.tool.WebSearchTool;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Java 26 下手写 stub，不 mock 具体类。
 */
final class AgentRuntimeHarness {
    private AgentRuntimeHarness() {
    }

    static ZhiyunProperties liveProps() {
        ZhiyunProperties properties = new ZhiyunProperties();
        properties.getLlm().setMode("live");
        properties.getLlm().setApiKey("test-not-used");
        properties.getElasticsearch().setEnabled(false);
        return properties;
    }

    static ZhiyunProperties dryProps() {
        ZhiyunProperties properties = new ZhiyunProperties();
        properties.getLlm().setMode("dry-run");
        properties.getElasticsearch().setEnabled(false);
        return properties;
    }

    static HarnessMeters meters() {
        return new HarnessMeters(new SimpleMeterRegistry());
    }

    static AgentRuntime runtime(ZhiyunProperties properties, AcademicSearchTool search, LlmGateway llm,
                                ArtifactStore store) {
        return runtime(properties, search, llm, store, null);
    }

    static AgentRuntime runtime(ZhiyunProperties properties, AcademicSearchTool search, LlmGateway llm,
                                ArtifactStore store, WebSearchTool webSearch) {
        ObjectMapper mapper = new ObjectMapper();
        Executor sync = Runnable::run;
        DocxTool docx = new DocxTool();
        WebSearchTool web = webSearch == null ? new WebSearchTool(null, mapper, properties) : webSearch;
        return new AgentRuntime(
                store,
                search,
                web,
                new EmptyRag(properties),
                new DocumentParser(docx),
                llm,
                mapper,
                properties,
                new SkillRegistry(),
                new AgentModelRouter(properties, null),
                new ParallelFanout(sync, sync),
                new NoopTrace(),
                new NoopLease(properties),
                docx,
                meters()
        );
    }

    static ReviewSlot slot(String text) {
        ReviewTask task = new ReviewTask();
        task.setId(1L);
        task.setTenantId(1L);
        task.setUserId(1L);
        task.setManuscriptId(1L);
        task.setWorkflow("CITATION_ONLY");
        task.setSourceVersion(1);
        task.setFencingToken(1L);
        Manuscript manuscript = new Manuscript();
        manuscript.setId(1L);
        manuscript.setTenantId(1L);
        manuscript.setTitle("eval");
        manuscript.setCurrentVersion(1);
        DocumentVersion source = new DocumentVersion();
        source.setVersionNo(1);
        source.setContentText(text);
        source.setStoragePath("eval.md");
        ReviewSlot slot = new ReviewSlot();
        slot.setTask(task);
        slot.setManuscript(manuscript);
        slot.setSource(source);
        slot.setFencingToken(1L);
        return slot;
    }

    static final class RecordingWebSearch extends WebSearchTool {
        final List<String> queries = new ArrayList<>();

        RecordingWebSearch(ZhiyunProperties properties) {
            super(null, new ObjectMapper(), properties);
        }

        @Override
        public ArrayNode search(String agentId, String query) {
            queries.add(query);
            ToolPolicy.assertAllowed(agentId, ToolPolicy.WEB_SEARCH);
            ArrayNode results = new ObjectMapper().createArrayNode();
            ObjectNode hit = results.addObject();
            hit.put("source", "WEB");
            hit.put("url", query);
            hit.put("title", "stub page");
            hit.put("excerpt", "WebSearchTool live stub excerpt for " + query);
            return results;
        }
    }

    static final class RecordingSearch extends AcademicSearchTool {
        final List<String> dois = new ArrayList<>();

        RecordingSearch(ZhiyunProperties properties) {
            super(null, new ObjectMapper(), properties);
        }

        @Override
        public ObjectNode lookupDoi(String doi) {
            dois.add(doi);
            if (doi == null || doi.toLowerCase().contains("0000/ghost")) {
                return null;
            }
            ObjectNode paper = new ObjectMapper().createObjectNode();
            paper.put("doi", doi);
            paper.put("title", "Java Crossref candidate " + doi);
            paper.putArray("authors").add("Stub");
            paper.put("year", 2019);
            paper.put("venue", "Stub Venue");
            paper.put("abstractText", "Metadata from handwritten AcademicSearchTool stub.");
            return paper;
        }
    }

    static final class CapturingLlm extends LlmGateway {
        String lastUser;
        String lastSystem;
        int completeCalls;
        String reply = "{}";

        CapturingLlm(ZhiyunProperties properties) {
            super(properties, null, new ObjectMapper(), meters());
        }

        @Override
        public String complete(String model, double temperature, String system, String user, UserLlmOverride override) {
            completeCalls++;
            lastSystem = system;
            lastUser = user;
            return reply;
        }
    }

    static final class EmptyRag extends RagService {
        EmptyRag(ZhiyunProperties properties) {
            super(properties, null, null, meters());
        }

        @Override
        public List<Retrieved> retrievePrivate(long tenantId, long manuscriptId, int version, String query, String section) {
            return List.of();
        }

        @Override
        public List<Retrieved> retrievePublic(String query) {
            return List.of();
        }
    }

    static final class MemStore extends ArtifactStore {
        private final Map<String, JsonNode> data = new ConcurrentHashMap<>();
        private final ObjectMapper mapper;

        MemStore(ObjectMapper mapper) {
            super(null, null, mapper);
            this.mapper = mapper;
        }

        @Override
        public void save(ReviewTask task, long fencingToken, String agent, String artifactType, JsonNode payload) {
            data.put(key(task.getId(), agent, artifactType), payload);
        }

        @Override
        public JsonNode body(long taskId, String agent, String type) {
            JsonNode node = data.get(key(taskId, agent, type));
            return node == null ? mapper.missingNode() : node;
        }

        @Override
        public boolean completed(long taskId, String agent) {
            String prefix = taskId + ":" + agent + ":";
            return data.keySet().stream().anyMatch(k -> k.startsWith(prefix));
        }

        void remember(ReviewTask task, String agent, JsonNode produced) {
            if (produced.has("issues")) {
                save(task, 1L, agent, "ReviewIssue", produced.get("issues"));
            }
            if (produced.has("evidence")) {
                save(task, 1L, agent, "Evidence", produced.get("evidence"));
            }
            if (produced.has("verification")) {
                save(task, 1L, agent, "VerificationResult", produced.get("verification"));
            }
            if (produced.has("revisionTasks")) {
                save(task, 1L, agent, "RevisionTask", produced.get("revisionTasks"));
            }
            if (produced.has("patches")) {
                save(task, 1L, agent, "RevisionPatch", produced.get("patches"));
            }
            save(task, 1L, agent, "Bundle", produced);
        }

        private static String key(long taskId, String agent, String type) {
            return taskId + ":" + agent + ":" + type;
        }
    }

    static final class NoopLease extends LeaseService {
        NoopLease(ZhiyunProperties properties) {
            super(null, null, properties, meters());
        }

        @Override
        public void assertWritable(long taskId, long writeToken) {
        }

        @Override
        public boolean holds(long taskId, long writeToken) {
            return true;
        }
    }

    static final class NoopTrace extends AgentTraceService {
        NoopTrace() {
            super(null, null, null, new ObjectMapper(), new SkillRegistry());
        }

        @Override
        public void begin(ReviewTask task, String agent, long fencingToken) {
        }

        @Override
        public void skip(ReviewTask task, String agent, long fencingToken) {
        }

        @Override
        public void complete(ReviewTask task, String agent, int tokens, long fencingToken) {
        }

        @Override
        public void fail(ReviewTask task, String agent, String error, long fencingToken) {
        }
    }
}
