package com.zhiyun;

import com.zhiyun.domain.AgentSpan;
import com.zhiyun.domain.Artifact;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.DocumentVersion;
import com.zhiyun.domain.Plan;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.harness.LeaseService;
import com.zhiyun.repo.AgentSpanRepo;
import com.zhiyun.repo.ArtifactRepo;
import com.zhiyun.repo.DocumentVersionRepo;
import com.zhiyun.repo.OrderRepo;
import com.zhiyun.repo.PlanRepo;
import com.zhiyun.repo.QuotaLedgerRepo;
import com.zhiyun.repo.ReviewTaskRepo;
import com.zhiyun.repo.TaskLeaseRepo;
import com.zhiyun.workflow.ReviewOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReviewPipelineTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    PlanRepo planRepo;
    @Autowired
    OrderRepo orderRepo;
    @Autowired
    ReviewTaskRepo reviewTaskRepo;
    @Autowired
    AgentSpanRepo agentSpanRepo;
    @Autowired
    ArtifactRepo artifactRepo;
    @Autowired
    DocumentVersionRepo documentVersionRepo;
    @Autowired
    LeaseService leaseService;
    @Autowired
    TaskLeaseRepo taskLeaseRepo;
    @Autowired
    QuotaLedgerRepo quotaLedgerRepo;
    @Autowired
    ReviewOrchestrator orchestrator;

    @Test
    void citationOnlyProducesNotVerifiedForGhostDoi() throws Exception {
        seedPlan();
        String token = register("alice@zhiyun.dev");
        long projectId = mapper.readTree(mvc.perform(get("/api/projects").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get(0).get("id").asLong();

        String paper = """
                # Introduction
                Firstly, we propose a novel method that outperforms all prior work.
                Smith et al. claimed superiority using DOI 10.1145/example.2019.
                A fabricated paper 10.0000/ghost.doi is also cited.
                # Method
                We retrieve evidence with metadata scope.
                # References
                10.1145/example.2019
                10.0000/ghost.doi
                """;
        MockMultipartFile file = new MockMultipartFile("file", "paper.md", "text/markdown", paper.getBytes());
        MvcResult up = mvc.perform(multipart("/api/manuscripts").file(file).param("projectId", String.valueOf(projectId))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn();
        long msId = mapper.readTree(up.getResponse().getContentAsString()).get("id").asLong();

        MvcResult started = mvc.perform(post("/api/manuscripts/" + msId + "/reviews")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflow\":\"CITATION_ONLY\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode task = mapper.readTree(started.getResponse().getContentAsString());
        assertThat(task.get("id").asText()).startsWith("ZYT");
        assertThat(task.get("id").asText()).doesNotMatch("^\\d+$");
        assertThat(task.get("status").asText()).isEqualTo(Codes.DONE);
        assertThat(task.get("workflow").asText()).isEqualTo(Codes.CITATION_ONLY);
        assertThat(task.get("fencingToken").asLong()).isGreaterThan(0L);

        String arts = mvc.perform(get("/api/reviews/" + task.get("id").asText() + "/artifacts")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(arts).contains("NOT_VERIFIED");
        assertThat(arts).contains("CITATION_INTEGRITY");

        JsonNode report = mapper.readTree(mvc.perform(get("/api/reviews/" + task.get("id").asText() + "/report")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(report.get("filename").asText()).isEqualTo("zhiyun-review-" + task.get("id").asText() + ".md");
        String md = report.get("markdown").asText();
        assertThat(md).contains("审校结果汇总");
        assertThat(md).contains("NOT_VERIFIED");
        assertThat(md).contains("10.0000/ghost.doi");
        assertThat(md).contains("引用核验");
        assertThat(report.get("artifactsFilename").asText()).isEqualTo("zhiyun-artifacts-" + task.get("id").asText() + ".json");
        assertThat(report.get("artifacts").isArray()).isTrue();
        assertThat(report.get("artifacts").size()).isGreaterThan(0);
        int anonReport = mvc.perform(get("/api/reviews/" + task.get("id").asText() + "/report")).andReturn().getResponse().getStatus();
        assertThat(anonReport).isIn(401, 403);

        JsonNode trace = mapper.readTree(mvc.perform(get("/api/reviews/" + task.get("id").asText() + "/trace")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(trace.get("nodes").size()).isEqualTo(1);
        assertThat(trace.get("nodes").get(0).get("agent").asText()).isEqualTo("CITATION_INTEGRITY");
        assertThat(trace.get("nodes").get(0).get("status").asText()).isEqualTo(Codes.DONE);
        assertThat(trace.get("nodes").get(0).get("checkpoint").asBoolean()).isTrue();
        assertThat(trace.get("fencingToken").asLong()).isGreaterThan(0L);
        assertThat(trace.get("nodes").get(0).has("fencingToken")).isTrue();
        assertThat(trace.get("nodes").get(0).get("skillVersion").asText()).isEqualTo("1");
        assertThat(trace.get("nodes").get(0).get("promptVersion").asText()).isEqualTo("1");
        assertThat(trace.get("nodes").get(0).has("errorCode")).isTrue();
        assertThat(trace.get("nodes").get(0).get("skipped").asBoolean()).isFalse();
        assertThat(trace.get("nodes").get(0).has("startedAt")).isTrue();
        assertThat(trace.get("nodes").get(0).has("endedAt")).isTrue();
        assertThat(trace.get("nodes").get(0).has("durationMs")).isTrue();

        int anonObs = mvc.perform(get("/api/ops/observability")).andReturn().getResponse().getStatus();
        assertThat(anonObs).isIn(401, 403);
        mvc.perform(get("/api/observability").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/ops/observability").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());

        JsonNode inbox = mapper.readTree(mvc.perform(get("/api/inbox").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(inbox.get("unreadCount").asInt()).isGreaterThan(0);
        assertThat(inbox.get("items").get(0).get("refId").asText()).isEqualTo("task-" + task.get("id").asText());

        String bob = register("trace-bob-" + System.nanoTime() + "@zhiyun.dev");
        JsonNode usage = mapper.readTree(mvc.perform(get("/api/reviews/" + task.get("id").asText() + "/usage")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(usage.get("nodes").size()).isEqualTo(1);
        assertThat(usage.get("nodes").get(0).get("name").asText()).isEqualTo("引用核验");
        assertThat(usage.get("nodes").get(0).has("durationMs")).isTrue();
        assertThat(usage.get("nodes").get(0).has("tokens")).isTrue();
        assertThat(usage.get("nodes").get(0).has("quota")).isTrue();
        assertThat(usage.has("fencingToken")).isFalse();
        assertThat(usage.has("lease")).isFalse();
        assertThat(usage.has("waterfall")).isFalse();
        assertThat(usage.get("nodes").get(0).has("toolName")).isFalse();
        assertThat(usage.get("nodes").get(0).has("skillVersion")).isFalse();
        assertThat(usage.get("nodes").get(0).has("errorCode")).isFalse();
        assertThat(usage.get("nodes").get(0).has("fencingToken")).isFalse();
        assertThat(usage.get("settled").asBoolean()).isTrue();
        ReviewTask stored = reviewTaskRepo.findByTaskNo(task.get("id").asText()).orElseThrow();
        int ledgerPoints = quotaLedgerRepo.findByTenantIdAndUserIdAndRefIdOrderByIdDesc(
                        stored.getTenantId(), stored.getUserId(), "task-" + task.get("id").asText())
                .stream()
                .filter(row -> "REVIEW_USAGE".equals(row.getReason()) || "SKILL_FEE".equals(row.getReason()))
                .mapToInt(row -> row.getDelta() == null ? 0 : -row.getDelta())
                .sum();
        assertThat(usage.get("quota").asInt()).isEqualTo(ledgerPoints);

        mvc.perform(get("/api/reviews/" + task.get("id").asText() + "/trace").header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/reviews/" + task.get("id").asText() + "/usage").header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/ops/observability").header("Authorization", bearer(bob)))
                .andExpect(status().isForbidden());
    }

    @Test
    void opsDashboardIsGlobalAndRejectsClientJwt() throws Exception {
        String demoClient = loginOrRegister("demo@zhiyun.dev");
        JsonNode clientMe = mapper.readTree(mvc.perform(get("/api/me").header("Authorization", bearer(demoClient)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(clientMe.get("operator").asBoolean()).isTrue();
        assertThat(clientMe.get("ops").asBoolean()).isFalse();
        mvc.perform(get("/api/ops/observability").header("Authorization", bearer(demoClient)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/observability").header("Authorization", bearer(demoClient)))
                .andExpect(status().isForbidden());

        String stranger = register("obs-stranger-" + System.nanoTime() + "@zhiyun.dev");
        JsonNode strangerMe = mapper.readTree(mvc.perform(get("/api/me").header("Authorization", bearer(stranger)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(get("/api/ops/observability").header("Authorization", bearer(stranger)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/ops/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + strangerMe.get("email").asText() + "\",\"password\":\"demo123456\"}"))
                .andExpect(status().isForbidden());

        ReviewTask demoTask = new ReviewTask();
        demoTask.setTenantId(clientMe.get("tenantId").asLong());
        demoTask.setUserId(clientMe.get("userId").asLong());
        demoTask.setManuscriptId(1L);
        demoTask.setWorkflow(Codes.CITATION_ONLY);
        demoTask.setStatus(Codes.DONE);
        demoTask.setSourceVersion(1);
        demoTask.setFencingToken(1L);
        demoTask.setIdempotencyKey("ops-demo-" + System.nanoTime());
        demoTask = reviewTaskRepo.saveAndFlush(demoTask);
        AgentSpan demoSpan = new AgentSpan();
        demoSpan.setTenantId(demoTask.getTenantId());
        demoSpan.setTaskId(demoTask.getId());
        demoSpan.setAgent("CITATION_INTEGRITY");
        demoSpan.setStatus(Codes.DONE);
        demoSpan.setTokens(120);
        demoSpan.setDurationMs(80L);
        agentSpanRepo.saveAndFlush(demoSpan);

        ReviewTask otherTask = new ReviewTask();
        otherTask.setTenantId(strangerMe.get("tenantId").asLong());
        otherTask.setUserId(strangerMe.get("userId").asLong());
        otherTask.setManuscriptId(1L);
        otherTask.setWorkflow(Codes.QUICK_REVIEW);
        otherTask.setStatus(Codes.FAILED);
        otherTask.setErrorMessage("timeout while waiting for model");
        otherTask.setSourceVersion(1);
        otherTask.setFencingToken(1L);
        otherTask.setIdempotencyKey("ops-other-" + System.nanoTime());
        otherTask = reviewTaskRepo.saveAndFlush(otherTask);
        AgentSpan otherSpan = new AgentSpan();
        otherSpan.setTenantId(otherTask.getTenantId());
        otherSpan.setTaskId(otherTask.getId());
        otherSpan.setAgent("CITATION_INTEGRITY");
        otherSpan.setStatus(Codes.FAILED);
        otherSpan.setTokens(40);
        otherSpan.setDurationMs(30L);
        otherSpan.setErrorCode("timeout");
        agentSpanRepo.saveAndFlush(otherSpan);

        String ops = mapper.readTree(mvc.perform(post("/api/auth/ops/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"demo@zhiyun.dev\",\"password\":\"demo123456\"}"))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("token").asText();
        JsonNode opsMe = mapper.readTree(mvc.perform(get("/api/me").header("Authorization", bearer(ops)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(opsMe.get("ops").asBoolean()).isTrue();
        assertThat(opsMe.get("operator").asBoolean()).isTrue();

        JsonNode obs = mapper.readTree(mvc.perform(get("/api/ops/observability").param("range", "24h")
                        .header("Authorization", bearer(ops)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(obs.get("caption").asText()).contains("非 SLA");
        assertThat(obs.get("caption").asText()).doesNotContain("SLA 达标");
        assertThat(obs.get("scope").asText()).isEqualTo("all");
        assertThat(obs.get("window").get("range").asText()).isEqualTo("24h");
        assertThat(obs.get("kpis").has("tasks")).isTrue();
        assertThat(obs.get("kpis").has("successRatePct")).isTrue();
        assertThat(obs.get("kpis").has("tokens")).isTrue();
        assertThat(obs.get("kpis").has("leasesHeld")).isTrue();
        assertThat(obs.get("kpis").has("checkpointSkipped")).isTrue();
        assertThat(obs.get("trend").isArray()).isTrue();
        assertThat(obs.get("trend").size()).isEqualTo(24);

        JsonNode halfYear = mapper.readTree(mvc.perform(get("/api/ops/observability").param("range", "6m")
                        .header("Authorization", bearer(ops)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(halfYear.get("window").get("range").asText()).isEqualTo("6m");
        assertThat(halfYear.get("trend").size()).isEqualTo(6);
        assertThat(halfYear.get("trend").get(0).get("bucket").asText()).matches("\\d{4}-\\d{2}");

        JsonNode allTime = mapper.readTree(mvc.perform(get("/api/ops/observability").param("range", "all")
                        .header("Authorization", bearer(ops)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(allTime.get("window").get("range").asText()).isEqualTo("all");
        assertThat(allTime.get("trend").get(0).get("bucket").asText()).matches("\\d{4}-\\d{2}");
        assertThat(obs.get("recent").isArray()).isTrue();
        assertThat(obs.get("tenants").isArray()).isTrue();
        assertThat(obs.get("tenants").size()).isGreaterThanOrEqualTo(2);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (JsonNode row : obs.get("tenants")) {
            seen.add(row.get("tenantId").asLong());
            assertThat(row.has("tasks")).isTrue();
            assertThat(row.has("successRatePct")).isTrue();
            assertThat(row.has("failed")).isTrue();
            assertThat(row.has("leasesHeld")).isTrue();
            assertThat(row.has("tokens")).isTrue();
            assertThat(row.has("errorCodes")).isTrue();
        }
        assertThat(seen).contains(clientMe.get("tenantId").asLong(), strangerMe.get("tenantId").asLong());
        assertThat(obs.get("kpis").get("tokens").asInt()).isGreaterThanOrEqualTo(160);
        assertThat(obs.get("llm").has("avgDurationMs")).isTrue();
        assertThat(obs.get("llm").get("caption").asText()).contains("首 token");
        assertThat(obs.get("llm").get("caption").asText()).doesNotContain("无 TTFT");
        assertThat(obs.get("llm").has("firstTokenP50Ms")).isTrue();
        assertThat(obs.has("meters")).isFalse();
        assertThat(obs.get("tools").has("failed")).isTrue();
        assertThat(obs.get("harness").has("fencingRejected")).isTrue();
        assertThat(obs.get("rag").has("hasDuration")).isTrue();

        JsonNode drilled = mapper.readTree(mvc.perform(get("/api/ops/observability")
                        .param("range", "24h")
                        .param("tenantId", strangerMe.get("tenantId").asText())
                        .header("Authorization", bearer(ops)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(drilled.get("scope").asText()).isEqualTo("tenant");
        assertThat(drilled.get("tenantId").asLong()).isEqualTo(strangerMe.get("tenantId").asLong());
        assertThat(drilled.get("kpis").get("failed").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(drilled.get("tenants").size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void fullReviewWaitsForAcceptAndDoesNotOverwriteOfficial() throws Exception {
        seedPlan();
        String token = register("full@zhiyun.dev");
        long projectId = mapper.readTree(mvc.perform(get("/api/projects").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asLong();
        String paper = """
                # Introduction
                Firstly, we propose a novel method that outperforms all prior work.
                DOI 10.1145/example.2019
                # Experiments
                Results are reported without ablation.
                # Conclusion
                In conclusion, this paper has demonstrated the effectiveness of our approach.
                """;
        MockMultipartFile file = new MockMultipartFile("file", "full.md", "text/markdown", paper.getBytes());
        long msId = mapper.readTree(mvc.perform(multipart("/api/manuscripts").file(file)
                        .param("projectId", String.valueOf(projectId)).header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        JsonNode task = mapper.readTree(mvc.perform(post("/api/manuscripts/" + msId + "/reviews")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflow\":\"FULL_REVIEW\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(task.get("status").asText()).isEqualTo(Codes.WAITING_ACCEPT);
        JsonNode detail = mapper.readTree(mvc.perform(get("/api/manuscripts/" + msId)
                        .header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString());
        assertThat(detail.get("manuscript").get("currentVersion").asInt()).isEqualTo(1);
        JsonNode waitingInbox = mapper.readTree(mvc.perform(get("/api/inbox").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(waitingInbox.get("unreadCount").asInt()).isGreaterThan(0);
        mvc.perform(post("/api/reviews/" + task.get("id").asText() + "/accept")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        JsonNode after = mapper.readTree(mvc.perform(get("/api/manuscripts/" + msId)
                        .header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString());
        assertThat(after.get("manuscript").get("currentVersion").asInt()).isEqualTo(2);
        JsonNode afterInbox = mapper.readTree(mvc.perform(get("/api/inbox").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(afterInbox.get("unreadCount").asInt()).isEqualTo(0);
        JsonNode me = mapper.readTree(mvc.perform(get("/api/me").header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(me.get("unreadInbox").asInt()).isEqualTo(0);
    }

    @Test
    void acceptPartialAppliesOnlySelectedPatches() throws Exception {
        seedPlan();
        JsonNode registered = mapper.readTree(mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"partial@zhiyun.dev\",\"password\":\"demo123456\",\"displayName\":\"P\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String token = registered.get("token").asText();
        long tenantId = registered.get("tenantId").asLong();
        long userId = registered.get("userId").asLong();
        long projectId = mapper.readTree(mvc.perform(get("/api/projects").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asLong();
        String paper = "Alpha sentence one remains unique here.\nBeta sentence two stays put for now.\n";
        MockMultipartFile file = new MockMultipartFile("file", "partial.md", "text/markdown", paper.getBytes());
        long msId = mapper.readTree(mvc.perform(multipart("/api/manuscripts").file(file)
                        .param("projectId", String.valueOf(projectId)).header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        DocumentVersion candidate = new DocumentVersion();
        candidate.setTenantId(tenantId);
        candidate.setManuscriptId(msId);
        candidate.setVersionNo(2);
        candidate.setStatus(Codes.CANDIDATE);
        candidate.setStoragePath("partial-candidate");
        candidate.setContentText("THIS FULL CANDIDATE MUST NOT BE USED");
        candidate.setContentSha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        documentVersionRepo.save(candidate);

        ReviewTask waiting = new ReviewTask();
        waiting.setTenantId(tenantId);
        waiting.setUserId(userId);
        waiting.setManuscriptId(msId);
        waiting.setWorkflow(Codes.FULL_REVIEW);
        waiting.setStatus(Codes.WAITING_ACCEPT);
        waiting.setSourceVersion(1);
        waiting.setCandidateVersion(2);
        waiting.setIdempotencyKey("partial-accept-" + msId);
        waiting = reviewTaskRepo.saveAndFlush(waiting);

        Artifact artifact = new Artifact();
        artifact.setTenantId(tenantId);
        artifact.setTaskId(waiting.getId());
        artifact.setAgent("REVISION_EXECUTION");
        artifact.setArtifactType("RevisionPatch");
        artifact.setFencingToken(1L);
        artifact.setPayload("""
                {"schemaVersion":1,"agent":"REVISION_EXECUTION","producedAt":"2026-09-04T00:00:00Z","fencingToken":1,"body":[{"patchId":"rp-alpha","issueId":"iss-a","originalText":"Alpha sentence one remains unique here.","proposedText":"Alpha rewritten now.","reason":"style"},{"patchId":"rp-beta","issueId":"iss-b","originalText":"Beta sentence two stays put for now.","proposedText":"Beta rewritten now.","reason":"style"}]}
                """);
        artifactRepo.save(artifact);

        int quotaBefore = mapper.readTree(mvc.perform(get("/api/me").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("quota").asInt();
        String bob = register("partial-bob@zhiyun.dev");
        mvc.perform(post("/api/reviews/" + waiting.getId() + "/accept-partial")
                        .header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patchIds\":[\"rp-alpha\"]}"))
                .andExpect(status().isNotFound());
        JsonNode result = mapper.readTree(mvc.perform(post("/api/reviews/" + waiting.getId() + "/accept-partial")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patchIds\":[\"rp-alpha\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.get("status").asText()).isEqualTo(Codes.DONE);
        assertThat(result.get("applied").asInt()).isEqualTo(1);
        assertThat(result.get("skipped").isArray()).isTrue();
        assertThat(result.get("skipped").size()).isEqualTo(0);

        int quotaAfter = mapper.readTree(mvc.perform(get("/api/me").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("quota").asInt();
        assertThat(quotaAfter).isEqualTo(quotaBefore);

        JsonNode after = mapper.readTree(mvc.perform(get("/api/manuscripts/" + msId)
                        .header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString());
        int current = after.get("manuscript").get("currentVersion").asInt();
        String official = "";
        for (JsonNode version : after.get("versions")) {
            if (version.get("versionNo").asInt() == current) {
                official = version.get("contentText").asText();
                assertThat(version.get("status").asText()).isEqualTo(Codes.OFFICIAL);
            }
        }
        assertThat(official).contains("Alpha rewritten now.");
        assertThat(official).contains("Beta sentence two stays put for now.");
        assertThat(official).doesNotContain("Beta rewritten now.");
        assertThat(official).doesNotContain("THIS FULL CANDIDATE MUST NOT BE USED");
        assertThat(official).doesNotContain("Alpha sentence one remains unique here.");
    }

    @Test
    void acceptPartialPrefersLocationOffsetOverFirstStringMatch() throws Exception {
        seedPlan();
        JsonNode registered = mapper.readTree(mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"offset@zhiyun.dev\",\"password\":\"demo123456\",\"displayName\":\"O\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String token = registered.get("token").asText();
        long tenantId = registered.get("tenantId").asLong();
        long userId = registered.get("userId").asLong();
        long projectId = mapper.readTree(mvc.perform(get("/api/projects").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asLong();
        String paper = "foo bar foo\n";
        MockMultipartFile file = new MockMultipartFile("file", "offset.md", "text/markdown", paper.getBytes());
        long msId = mapper.readTree(mvc.perform(multipart("/api/manuscripts").file(file)
                        .param("projectId", String.valueOf(projectId)).header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        ReviewTask waiting = new ReviewTask();
        waiting.setTenantId(tenantId);
        waiting.setUserId(userId);
        waiting.setManuscriptId(msId);
        waiting.setWorkflow(Codes.FULL_REVIEW);
        waiting.setStatus(Codes.WAITING_ACCEPT);
        waiting.setSourceVersion(1);
        waiting.setCandidateVersion(null);
        waiting.setIdempotencyKey("offset-accept-" + msId);
        waiting = reviewTaskRepo.saveAndFlush(waiting);

        Artifact artifact = new Artifact();
        artifact.setTenantId(tenantId);
        artifact.setTaskId(waiting.getId());
        artifact.setAgent("REVISION_EXECUTION");
        artifact.setArtifactType("RevisionPatch");
        artifact.setFencingToken(1L);
        artifact.setPayload("""
                {"schemaVersion":1,"agent":"REVISION_EXECUTION","producedAt":"2026-09-07T00:00:00Z","fencingToken":1,"body":[{"patchId":"rp-second-foo","issueId":"iss-o","location":{"startOffset":8,"endOffset":11,"anchor":"dup"},"originalText":"foo","proposedText":"baz","reason":"offset"}]}
                """);
        artifactRepo.save(artifact);

        JsonNode result = mapper.readTree(mvc.perform(post("/api/reviews/" + waiting.getId() + "/accept-partial")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"patchIds\":[\"rp-second-foo\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.get("applied").asInt()).isEqualTo(1);

        JsonNode after = mapper.readTree(mvc.perform(get("/api/manuscripts/" + msId)
                        .header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString());
        int current = after.get("manuscript").get("currentVersion").asInt();
        String official = "";
        for (JsonNode version : after.get("versions")) {
            if (version.get("versionNo").asInt() == current) {
                official = version.get("contentText").asText();
            }
        }
        assertThat(official).isEqualTo("foo bar baz\n");
    }

    @Test
    void figureAgentUsesProgrammaticChecksFromPdfMeta() throws Exception {
        seedPlan();
        String token = register("figure-meta@zhiyun.dev");
        long projectId = mapper.readTree(mvc.perform(get("/api/projects").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asLong();
        String paper = """
                %%PDF_META pageSize=612x792 pages=2 figures=0
                ACL requires A4. Figure 1 is a blurry figure. Figure 3 is out of order.
                Table 2 is referenced. Caption is missing.
                """;
        MockMultipartFile file = new MockMultipartFile("file", "fig.md", "text/markdown", paper.getBytes());
        long msId = mapper.readTree(mvc.perform(multipart("/api/manuscripts").file(file)
                        .param("projectId", String.valueOf(projectId)).header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        JsonNode task = mapper.readTree(mvc.perform(post("/api/manuscripts/" + msId + "/reviews")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflow\":\"FULL_REVIEW\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String arts = mvc.perform(get("/api/reviews/" + task.get("id").asText() + "/artifacts")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(arts).contains("FIGURE_PDF");
        assertThat(arts).contains("ACL expects A4");
        assertThat(arts).contains("Vision was not invoked");
        assertThat(arts).doesNotContain("firstRaster");
    }

    @Test
    void pdfInspectShowsPageSpecAndFiguresAndIsTenantIsolated() throws Exception {
        seedPlan();
        String token = register("pdf-inspect@zhiyun.dev");
        String bob = register("pdf-inspect-bob@zhiyun.dev");
        long projectId = mapper.readTree(mvc.perform(get("/api/projects").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asLong();
        MockMultipartFile file = new MockMultipartFile("file", "camera-ready.pdf", "application/pdf", sampleLetterPdf());
        long msId = mapper.readTree(mvc.perform(multipart("/api/manuscripts").file(file)
                        .param("projectId", String.valueOf(projectId)).header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        JsonNode inspect = mapper.readTree(mvc.perform(get("/api/manuscripts/" + msId + "/versions/1/inspect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(inspect.get("format").asText()).isEqualTo("PDF");
        assertThat(inspect.get("pagePreview").asBoolean()).isTrue();
        assertThat(inspect.get("pageSpec").asText()).isEqualTo("LETTER");
        assertThat(inspect.get("pageCount").asInt()).isEqualTo(1);
        assertThat(inspect.get("pages").isArray()).isTrue();
        assertThat(inspect.get("pages").get(0).get("page").asInt()).isEqualTo(1);
        assertThat(inspect.get("figures").size()).isGreaterThan(0);
        assertThat(inspect.get("figures").get(0).get("page").asInt()).isEqualTo(1);
        assertThat(inspect.get("figures").get(0).has("approxDpi")).isTrue();
        assertThat(inspect.get("figures").get(0).has("needsVision")).isTrue();
        assertThat(inspect.get("captions").toString()).contains("Blurry architecture");
        mvc.perform(get("/api/manuscripts/" + msId + "/versions/1/inspect").header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());

        JsonNode started = mapper.readTree(mvc.perform(post("/api/manuscripts/" + msId + "/reviews")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflow\":\"CITATION_ONLY\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(get("/api/reviews/" + started.get("id").asText() + "/report").header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
    }

    @Test
    void evalApiIsOffByDefaultEvenWhenLoggedIn() throws Exception {
        String token = register("eval-user@zhiyun.dev");
        int anon = mvc.perform(get("/api/eval")).andReturn().getResponse().getStatus();
        assertThat(anon).isIn(401, 403);
        mvc.perform(get("/api/eval").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/eval/agents").header("Authorization", bearer(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    void manuscriptPreviewShowsTextAndFileAndMergePreviewScores() throws Exception {
        seedPlan();
        String token = register("preview@zhiyun.dev");
        long projectId = mapper.readTree(mvc.perform(get("/api/projects").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asLong();
        String paper = "# Abstract\nPreview body must be visible in the manuscript page.\n";
        MockMultipartFile file = new MockMultipartFile("file", "show.md", "text/markdown", paper.getBytes());
        long msId = mapper.readTree(mvc.perform(multipart("/api/manuscripts").file(file)
                        .param("projectId", String.valueOf(projectId)).header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        JsonNode detail = mapper.readTree(mvc.perform(get("/api/manuscripts/" + msId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(detail.get("versions").size()).isGreaterThan(0);
        JsonNode v1 = detail.get("versions").get(0);
        assertThat(v1.get("contentText").asText()).contains("Preview body must be visible");
        assertThat(v1.get("hasFile").asBoolean()).isTrue();
        assertThat(v1.get("format").asText()).isEqualTo("MD");
        mvc.perform(get("/api/manuscripts/" + msId + "/versions/1/file").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        JsonNode mdInspect = mapper.readTree(mvc.perform(get("/api/manuscripts/" + msId + "/versions/1/inspect")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(mdInspect.get("format").asText()).isEqualTo("MD");
        assertThat(mdInspect.get("pagePreview").asBoolean()).isFalse();
        assertThat(mdInspect.has("pages")).isFalse();

        long tenantId = mapper.readTree(mvc.perform(get("/api/me").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("tenantId").asLong();
        DocumentVersion stored = documentVersionRepo.findByManuscriptIdAndVersionNoAndTenantId(msId, 1, tenantId).orElseThrow();
        stored.setContentText("");
        documentVersionRepo.save(stored);
        JsonNode again = mapper.readTree(mvc.perform(get("/api/manuscripts/" + msId)
                        .header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString());
        assertThat(again.get("versions").get(0).get("contentText").asText()).contains("Preview body must be visible");

        JsonNode task = mapper.readTree(mvc.perform(post("/api/manuscripts/" + msId + "/reviews")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflow\":\"FULL_REVIEW\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode preview = mapper.readTree(mvc.perform(get("/api/reviews/" + task.get("id").asText() + "/merge-preview")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(preview.get("official").asText()).contains("Preview body");
        assertThat(preview.get("score").asInt()).isBetween(0, 96);
        assertThat(preview.get("grade").asText()).isIn("A", "B", "C", "D");
        assertThat(preview.get("points").isArray()).isTrue();
        assertThat(preview.get("score").asInt()).isLessThan(100);
    }

    @Test
    void failedRetryResumesFromCheckpointWithoutDoubleCharge() throws Exception {
        seedPlan();
        JsonNode registered = mapper.readTree(mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"retry-alice@zhiyun.dev\",\"password\":\"demo123456\",\"displayName\":\"A\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String token = registered.get("token").asText();
        long tenantId = registered.get("tenantId").asLong();
        long projectId = mapper.readTree(mvc.perform(get("/api/projects").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asLong();
        MockMultipartFile file = new MockMultipartFile("file", "retry.md", "text/markdown",
                "# Intro\nFirstly, we cite 10.1145/example.2019 and 10.0000/ghost.doi.\n".getBytes());
        long msId = mapper.readTree(mvc.perform(multipart("/api/manuscripts").file(file)
                        .param("projectId", String.valueOf(projectId)).header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        JsonNode started = mapper.readTree(mvc.perform(post("/api/manuscripts/" + msId + "/reviews")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflow\":\"CITATION_ONLY\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String taskKey = started.get("id").asText();
        assertThat(taskKey).startsWith("ZYT");
        ReviewTask persisted = reviewTaskRepo.findByTaskNo(taskKey).orElseThrow();
        long taskId = persisted.getId();
        assertThat(started.get("status").asText()).isEqualTo(Codes.DONE);
        assertThat(started.get("checkpointAgent").asText()).isEqualTo("CITATION_INTEGRITY");

        int quotaBefore = mapper.readTree(mvc.perform(get("/api/me").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("quota").asInt();
        int artifactsBefore = artifactRepo.findByTaskIdAndTenantIdOrderByIdAsc(taskId, tenantId).size();
        assertThat(artifactsBefore).isGreaterThan(0);

        ReviewTask task = reviewTaskRepo.findById(taskId).orElseThrow();
        task.setStatus(Codes.RUNNING);
        reviewTaskRepo.saveAndFlush(task);
        mvc.perform(post("/api/reviews/" + taskId + "/retry").header("Authorization", bearer(token)))
                .andExpect(status().isConflict());

        task.setStatus(Codes.FAILED);
        task.setErrorMessage("structured output failed after retry: injected");
        reviewTaskRepo.saveAndFlush(task);

        String bob = register("retry-bob@zhiyun.dev");
        mvc.perform(post("/api/reviews/" + taskId + "/retry").header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/reviews/" + taskId).header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());

        JsonNode retried = mapper.readTree(mvc.perform(post("/api/reviews/" + taskId + "/retry")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(retried.get("status").asText()).isEqualTo(Codes.DONE);
        assertThat(retried.get("checkpointAgent").asText()).isEqualTo("CITATION_INTEGRITY");
        assertThat(retried.get("id").asText()).isEqualTo(taskKey);

        int quotaAfter = mapper.readTree(mvc.perform(get("/api/me").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("quota").asInt();
        assertThat(quotaAfter).isEqualTo(quotaBefore);
        assertThat(artifactRepo.findByTaskIdAndTenantIdOrderByIdAsc(taskId, tenantId).size())
                .isEqualTo(artifactsBefore);

        mvc.perform(post("/api/reviews/" + taskId + "/retry").header("Authorization", bearer(token)))
                .andExpect(status().isConflict());
    }

    @Test
    void tenantIsolationHidesForeignManuscript() throws Exception {
        seedPlan();
        String alice = register("iso-a@zhiyun.dev");
        String bob = register("iso-b@zhiyun.dev");
        long projectId = mapper.readTree(mvc.perform(get("/api/projects").header("Authorization", bearer(alice)))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asLong();
        MockMultipartFile file = new MockMultipartFile("file", "a.md", "text/markdown", "# Abstract\nsecret claim".getBytes());
        long msId = mapper.readTree(mvc.perform(multipart("/api/manuscripts").file(file)
                        .param("projectId", String.valueOf(projectId)).header("Authorization", bearer(alice)))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(get("/api/manuscripts/" + msId).header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/manuscripts/" + msId + "/versions/1/inspect").header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
    }

    @Test
    void manuscriptsFilterByProjectIsTenantScoped() throws Exception {
        seedPlan();
        JsonNode aliceReg = mapper.readTree(mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"filter-alice@zhiyun.dev\",\"password\":\"demo123456\",\"displayName\":\"A\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String alice = aliceReg.get("token").asText();
        String bob = register("filter-bob@zhiyun.dev");
        JsonNode projects = mapper.readTree(mvc.perform(get("/api/projects").header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long projectA = projects.get(0).get("id").asLong();
        long projectB = mapper.readTree(mvc.perform(post("/api/projects")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ACL 投稿\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asLong();

        MockMultipartFile fileA = new MockMultipartFile("file", "alpha.md", "text/markdown", "# A\n".getBytes());
        MockMultipartFile fileB = new MockMultipartFile("file", "beta.md", "text/markdown", "# B\n".getBytes());
        mvc.perform(multipart("/api/manuscripts").file(fileA).param("projectId", String.valueOf(projectA))
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk());
        mvc.perform(multipart("/api/manuscripts").file(fileB).param("projectId", String.valueOf(projectB))
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk());

        JsonNode all = mapper.readTree(mvc.perform(get("/api/manuscripts")
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(all.get("total").asInt()).isGreaterThanOrEqualTo(2);

        JsonNode onlyA = mapper.readTree(mvc.perform(get("/api/manuscripts").param("projectId", String.valueOf(projectA))
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(onlyA.get("total").asInt()).isEqualTo(1);
        assertThat(onlyA.get("items").get(0).get("title").asText()).contains("alpha");

        JsonNode onlyB = mapper.readTree(mvc.perform(get("/api/manuscripts").param("projectId", String.valueOf(projectB))
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(onlyB.get("total").asInt()).isEqualTo(1);
        assertThat(onlyB.get("items").get(0).get("title").asText()).contains("beta");

        mvc.perform(get("/api/manuscripts").param("projectId", String.valueOf(projectA))
                        .header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelRunningReleasesLeaseAndRetryStillWorks() throws Exception {
        seedPlan();
        JsonNode registered = mapper.readTree(mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"cancel-alice@zhiyun.dev\",\"password\":\"demo123456\",\"displayName\":\"A\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String token = registered.get("token").asText();
        long tenantId = registered.get("tenantId").asLong();
        long userId = registered.get("userId").asLong();
        long projectId = mapper.readTree(mvc.perform(get("/api/projects").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asLong();
        MockMultipartFile file = new MockMultipartFile("file", "cancel.md", "text/markdown",
                "# Intro\nFirstly, we cite 10.1145/example.2019.\n".getBytes());
        long msId = mapper.readTree(mvc.perform(multipart("/api/manuscripts").file(file)
                        .param("projectId", String.valueOf(projectId)).header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();

        ReviewTask running = new ReviewTask();
        running.setTenantId(tenantId);
        running.setUserId(userId);
        running.setManuscriptId(msId);
        running.setWorkflow(Codes.CITATION_ONLY);
        running.setStatus(Codes.RUNNING);
        running.setSourceVersion(1);
        running.setFencingToken(1L);
        running.setIdempotencyKey("cancel-run-" + msId);
        running = reviewTaskRepo.saveAndFlush(running);
        String owner = leaseService.newOwner();
        leaseService.acquire(running, owner);
        assertThat(taskLeaseRepo.findById(running.getId())).isPresent();
        long fencingBefore = reviewTaskRepo.findById(running.getId()).orElseThrow().getFencingToken();

        String bob = register("cancel-bob@zhiyun.dev");
        mvc.perform(post("/api/reviews/" + running.getId() + "/cancel").header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());

        JsonNode cancelled = mapper.readTree(mvc.perform(post("/api/reviews/" + running.getId() + "/cancel")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(cancelled.get("status").asText()).isEqualTo(Codes.FAILED);
        assertThat(cancelled.get("errorMessage").asText()).isEqualTo("已取消");
        assertThat(taskLeaseRepo.findById(running.getId())).isEmpty();
        assertThat(reviewTaskRepo.findById(running.getId()).orElseThrow().getFencingToken())
                .isGreaterThan(fencingBefore);

        int ledgerBefore = quotaLedgerRepo.findByTenantIdAndUserIdAndRefIdOrderByIdDesc(
                tenantId, userId, "task-" + running.getId()).size();
        assertThat(ledgerBefore).isEqualTo(0);

        mvc.perform(post("/api/reviews/" + running.getId() + "/cancel").header("Authorization", bearer(token)))
                .andExpect(status().isConflict());

        JsonNode retried = mapper.readTree(mvc.perform(post("/api/reviews/" + running.getId() + "/retry")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(retried.get("status").asText()).isEqualTo(Codes.DONE);
        assertThat(retried.get("id").asText()).isEqualTo(running.publicId());
        int ledgerAfter = quotaLedgerRepo.findByTenantIdAndUserIdAndRefIdOrderByIdDesc(
                tenantId, userId, "task-" + running.publicId()).size();
        assertThat(ledgerAfter).isEqualTo(1);

        JsonNode done = mapper.readTree(mvc.perform(post("/api/manuscripts/" + msId + "/reviews")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflow\":\"CITATION_ONLY\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/reviews/" + done.get("id").asText() + "/cancel")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isConflict());

        ReviewTask pending = new ReviewTask();
        pending.setTenantId(tenantId);
        pending.setUserId(userId);
        pending.setManuscriptId(msId);
        pending.setWorkflow(Codes.CITATION_ONLY);
        pending.setStatus(Codes.PENDING);
        pending.setSourceVersion(1);
        pending.setFencingToken(0L);
        pending.setIdempotencyKey("cancel-pending-" + msId);
        pending = reviewTaskRepo.saveAndFlush(pending);
        JsonNode pendingCancelled = mapper.readTree(mvc.perform(post("/api/reviews/" + pending.getId() + "/cancel")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(pendingCancelled.get("status").asText()).isEqualTo(Codes.FAILED);
        orchestrator.execute(pending.getId());
        assertThat(reviewTaskRepo.findById(pending.getId()).orElseThrow().getStatus()).isEqualTo(Codes.FAILED);
        assertThat(quotaLedgerRepo.findByTenantIdAndUserIdAndRefIdOrderByIdDesc(
                tenantId, userId, "task-" + pending.getId())).isEmpty();
    }

    @Test
    void insufficientQuotaConflicts() throws Exception {
        seedPlan();
        String token = register("quota@zhiyun.dev");
        // consume the 3 signup credits
        long projectId = mapper.readTree(mvc.perform(get("/api/projects").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get(0).get("id").asLong();
        MockMultipartFile file = new MockMultipartFile("file", "q.md", "text/markdown", "# Intro\nFirstly, hello.\nDOI 10.1145/example.2019".getBytes());
        long msId = mapper.readTree(mvc.perform(multipart("/api/manuscripts").file(file)
                        .param("projectId", String.valueOf(projectId)).header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("id").asLong();
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/manuscripts/" + msId + "/reviews")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"workflow\":\"CITATION_ONLY\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/manuscripts/" + msId + "/reviews")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflow\":\"CITATION_ONLY\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void mockPayIncreasesQuota() throws Exception {
        seedPlan();
        String token = register("pay@zhiyun.dev");
        long planId = planRepo.findAll().get(0).getId();
        String orderId = publicOrderId(mapper.readTree(mvc.perform(post("/api/orders")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":" + planId + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()));
        mvc.perform(post("/api/orders/" + orderId + "/mock-pay").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        JsonNode paid = mapper.readTree(mvc.perform(get("/api/orders/" + orderId).header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(paid.get("payChannel").asText()).isEqualTo("mock");
        int quota = mapper.readTree(mvc.perform(get("/api/me").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("quota").asInt();
        assertThat(quota).isGreaterThan(3);
    }

    @Test
    void customRechargeRejectsBelowTenYuan() throws Exception {
        String token = register("custom@zhiyun.dev");
        mvc.perform(post("/api/orders")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountYuan\":9}"))
                .andExpect(status().isBadRequest());
        JsonNode created = mapper.readTree(mvc.perform(post("/api/orders")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountYuan\":10}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(created.get("quotaAmount").asInt()).isEqualTo(10);
        assertThat(created.get("amountCents").asInt()).isEqualTo(1000);
        String orderId = publicOrderId(created);
        mvc.perform(post("/api/orders/" + orderId + "/mock-pay")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        int quota = mapper.readTree(mvc.perform(get("/api/me").header("Authorization", bearer(token)))
                .andReturn().getResponse().getContentAsString()).get("quota").asInt();
        assertThat(quota).isEqualTo(13);
    }

    @Test
    void orderDetailIsTenantScopedAndIncludesLedger() throws Exception {
        seedPlan();
        String alice = register("order-alice@zhiyun.dev");
        String bob = register("order-bob@zhiyun.dev");
        long planId = planRepo.findAll().get(0).getId();
        JsonNode created = mapper.readTree(mvc.perform(post("/api/orders")
                        .header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planId\":" + planId + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String orderId = publicOrderId(created);
        mvc.perform(post("/api/orders/" + orderId + "/mock-pay").header("Authorization", bearer(alice)))
                .andExpect(status().isOk());
        JsonNode detail = mapper.readTree(mvc.perform(get("/api/orders/" + orderId)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(detail.get("id").asText()).isEqualTo(orderId);
        assertThat(detail.get("status").asText()).isEqualTo(Codes.ORDER_PAID);
        assertThat(detail.get("paidAt").asText()).isNotBlank();
        assertThat(detail.get("ledger").isArray()).isTrue();
        assertThat(detail.get("ledger").size()).isGreaterThan(0);
        assertThat(detail.get("ledger").get(0).get("refId").asText()).isEqualTo("order-" + orderId);
        assertThat(detail.has("fencingToken")).isFalse();
        assertThat(detail.has("token")).isFalse();
        mvc.perform(get("/api/orders/" + orderId).header("Authorization", bearer(bob)))
                .andExpect(status().isNotFound());
        long pk = orderRepo.findAll().stream()
                .filter(o -> orderId.equals(o.publicId()))
                .findFirst().orElseThrow().getId();
        JsonNode byPk = mapper.readTree(mvc.perform(get("/api/orders/" + pk)
                        .header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(byPk.get("id").asText()).isEqualTo(orderId);
    }

    private static String publicOrderId(JsonNode node) {
        String id = node.get("id").asText();
        assertThat(id).startsWith("ZY");
        assertThat(id).doesNotMatch("^\\d{1,8}$");
        assertThat(id.length()).isGreaterThan(10);
        return id;
    }

    private void seedPlan() {
        if (planRepo.count() > 0) {
            return;
        }
        Plan plan = new Plan();
        plan.setCode("starter");
        plan.setName("Starter");
        plan.setQuotaAmount(10);
        plan.setPriceCents(0);
        plan.setDescription("test");
        planRepo.save(plan);
    }

    private String loginOrRegister(String email) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"demo123456\",\"displayName\":\"Demo\"}";
        var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        if (login.getResponse().getStatus() == 200) {
            return mapper.readTree(login.getResponse().getContentAsString()).get("token").asText();
        }
        return register(email);
    }

    private String register(String email) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"demo123456\",\"displayName\":\"U\"}";
        return mapper.readTree(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private static byte[] sampleLetterPdf() throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.LETTER);
            doc.addPage(page);
            java.awt.image.BufferedImage blur = new java.awt.image.BufferedImage(80, 80, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var img = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, blur);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.drawImage(img, 40, 400, 80, 80);
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(40, 720);
                cs.showText("Figure 1. Blurry architecture diagram.");
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
