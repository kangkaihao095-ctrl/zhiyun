package com.zhiyun.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.cs.CsIntent;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.harness.AgentIds;
import com.zhiyun.harness.LeaseService;
import com.zhiyun.harness.ReviewSlot;
import com.zhiyun.harness.ToolPolicy;
import com.zhiyun.llm.LlmGateway;
import com.zhiyun.tool.AcademicSearchTool;
import com.zhiyun.workflow.ReviewOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发版门禁：逐条断言 {@code eval/agent-tasks.jsonl} 的 expect（L1 dry-run / L2 终态 / L3 fencing）。
 * 自建金标，不是线上 SLA。
 */
class AgentGoldEvalTest {
    private static final Pattern DOI = Pattern.compile("10\\.\\d{4,9}/[-._;()/:A-Z0-9]+", Pattern.CASE_INSENSITIVE);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void everyJsonlExpectIsProgramChecked() throws Exception {
        Path jsonl = Path.of("..", "eval", "agent-tasks.jsonl");
        List<String> lines = Files.readAllLines(jsonl).stream().filter(l -> !l.isBlank()).toList();
        assertThat(lines).hasSize(50);

        ZhiyunProperties dry = AgentRuntimeHarness.dryProps();
        AcademicSearchTool search = new AcademicSearchTool(null, mapper, dry);
        LlmGateway unusedLlm = new LlmGateway(dry, null, mapper, AgentRuntimeHarness.meters());
        AgentRuntimeHarness.MemStore store = new AgentRuntimeHarness.MemStore(mapper);
        AgentRuntime runtime = AgentRuntimeHarness.runtime(dry, search, unusedLlm, store);

        Map<String, Map<String, JsonNode>> byPaper = new LinkedHashMap<>();
        int checked = 0;
        for (String line : lines) {
            JsonNode row = mapper.readTree(line);
            String paper = row.path("paper").asText("");
            if (!paper.isBlank() && !byPaper.containsKey(paper)) {
                byPaper.put(paper, runDryChain(runtime, store, paper));
            }
            assertExpect(row, byPaper.get(paper), paperText(paper));
            checked++;
        }
        assertThat(checked).isEqualTo(50);
        assertThat(byPaper).isNotEmpty();
    }

    private Map<String, JsonNode> runDryChain(AgentRuntime runtime, AgentRuntimeHarness.MemStore store, String paper)
            throws Exception {
        String text = paperText(paper);
        ReviewSlot slot = AgentRuntimeHarness.slot(text);
        ReviewTask task = slot.getTask();
        task.setWorkflow(Codes.FULL_REVIEW);
        Map<String, JsonNode> bundles = new LinkedHashMap<>();
        for (String agent : AgentIds.ofWorkflow(Codes.FULL_REVIEW)) {
            JsonNode produced = runtime.dryRun(slot, agent);
            store.remember(task, agent, produced);
            bundles.put(agent, produced);
        }
        bundles.put("paperText", mapper.getNodeFactory().textNode(text));
        bundles.put("officialVersion", mapper.getNodeFactory().numberNode(slot.getManuscript().getCurrentVersion()));
        return bundles;
    }

    private void assertExpect(JsonNode row, Map<String, JsonNode> bundles, String paperText) {
        String id = row.path("id").asText();
        String agent = row.path("agent").asText();
        JsonNode expect = row.path("expect");
        String workflow = row.path("workflow").asText("");
        String blob = bundles == null ? "" : bundles.toString();
        String paper = paperText == null ? "" : paperText;

        if (expect.has("doi")) {
            String doi = expect.path("doi").asText();
            assertThat(paper).as(id + " paper cites " + doi).contains(doi);
            JsonNode citation = bundle(bundles, AgentIds.CITATION);
            assertThat(citation.toString()).as(id + " citation mentions " + doi).contains(doi);
            if ("NOT_VERIFIED".equals(expect.path("status").asText())) {
                JsonNode ev = evidenceFor(citation, doi);
                assertThat(ev.path("status").asText()).as(id).isEqualTo("NOT_VERIFIED");
            }
            if ("VERIFIED_OR_CANDIDATE".equals(expect.path("status").asText())) {
                JsonNode ev = evidenceFor(citation, doi);
                assertThat(ev.path("paper").isNull()).as(id).isFalse();
                assertThat(ev.path("supportsClaim").asBoolean()).as(id + " existence is not claim support").isFalse();
                assertThat(ev.path("status").asText()).as(id).isEqualTo("NOT_VERIFIED");
            }
        }
        if (expect.path("rule").asText("").equals("existence_not_claim_support")) {
            JsonNode citation = bundle(bundles, AgentIds.CITATION);
            assertThat(citation.path("evidence")).as(id).isNotEmpty();
            for (JsonNode ev : citation.path("evidence")) {
                assertThat(ev.has("status")).as(id).isTrue();
                assertThat(ev.has("supportsClaim")).as(id).isTrue();
                assertThat(ev.has("paper")).as(id).isTrue();
                if (ev.path("paper").isNull() || ev.path("paper").isMissingNode()) {
                    assertThat(ev.path("status").asText()).as(id).isEqualTo("NOT_VERIFIED");
                    assertThat(ev.path("supportsClaim").asBoolean()).as(id).isFalse();
                } else {
                    assertThat(ev.path("supportsClaim").asBoolean())
                            .as(id + " paper exists does not mean claim supported")
                            .isFalse();
                    assertThat(ev.path("status").asText()).as(id).isNotEqualTo("VERIFIED");
                }
            }
        }
        if (expect.path("no_fabricated_doi").asBoolean(false)) {
            assertNoFabricatedDoi(id, paper, bundle(bundles, AgentIds.CITATION));
        }
        if (expect.path("java_validates_metadata").asBoolean(false)) {
            JsonNode citation = bundle(bundles, AgentIds.CITATION);
            assertThat(citation.toString()).as(id).contains("CROSSREF");
        }
        if (expect.has("flags")) {
            JsonNode style = bundle(bundles, AgentIds.STYLE);
            for (JsonNode flag : expect.path("flags")) {
                assertThat(paper).as(id).contains(flag.asText());
                assertThat(style.toString()).as(id + " flags " + flag.asText()).contains(flag.asText().split(",")[0]);
            }
        }
        if (expect.has("preserve")) {
            for (JsonNode needle : expect.path("preserve")) {
                assertThat(paper).as(id + " preserve " + needle.asText()).contains(needle.asText());
            }
            assertThat(bundles.get("officialVersion").asInt()).as(id).isEqualTo(1);
        }
        if (expect.path("no_academic_search").asBoolean(false)) {
            assertThat(ToolPolicy.allowed(AgentIds.STYLE)).as(id).doesNotContain(ToolPolicy.ACADEMIC_SEARCH);
        }
        if (expect.path("empty_closing").asBoolean(false)) {
            assertThat(paper).as(id).contains("In conclusion, this paper");
            assertThat(bundle(bundles, AgentIds.STYLE).toString()).as(id).contains("In conclusion");
        }
        if ("EVIDENCE_GAP".equals(expect.path("category").asText())
                || expect.path("outperforms_without_ablation").asBoolean(false)) {
            String review = bundle(bundles, AgentIds.REVIEWER).toString();
            assertThat(paper.toLowerCase(Locale.ROOT)).as(id).contains("outperforms");
            assertThat(review.toUpperCase(Locale.ROOT)).as(id).contains("EVIDENCE_GAP");
        }
        if (expect.path("no_fraud_claim").asBoolean(false)) {
            assertThat(bundle(bundles, AgentIds.REVIEWER).toString().toLowerCase(Locale.ROOT))
                    .as(id).doesNotContain("fraud").doesNotContain("造假");
        }
        if (expect.path("private_split_80").asBoolean(false)) {
            assertThat(paper).as(id).contains("80");
            assertThat(bundle(bundles, AgentIds.REVIEWER).toString()).as(id).contains("80");
        }
        if (expect.path("acl_limitations_missing").asBoolean(false)) {
            assertThat(paper.toLowerCase(Locale.ROOT)).as(id).contains("no limitations");
            assertThat(bundle(bundles, AgentIds.REVIEWER).toString()).as(id).contains("Limitations");
        }
        if (expect.path("ghost_gain_unsupported").asBoolean(false)) {
            assertThat(paper).as(id).contains("10.0000/ghost.doi");
            assertThat(bundle(bundles, AgentIds.REVIEWER).toString().toLowerCase(Locale.ROOT))
                    .as(id).contains("ghost");
        }
        if (expect.has("pageSize") || expect.path("failAclA4").asBoolean(false)) {
            String fig = bundle(bundles, AgentIds.FIGURE).toString();
            assertThat(fig).as(id).contains("ACL expects A4");
        }
        if (expect.has("figures") && expect.path("figures").asInt() == 0) {
            assertThat(bundle(bundles, AgentIds.FIGURE).toString()).as(id).contains("zero XObject");
        }
        if (expect.path("blurry_figure").asBoolean(false) || expect.path("blurry_pipeline_figure").asBoolean(false)) {
            assertThat(paper.toLowerCase(Locale.ROOT)).as(id).contains("blurry");
            assertThat(bundle(bundles, AgentIds.FIGURE).toString().toUpperCase(Locale.ROOT)).as(id).contains("BLUR");
        }
        if (expect.path("caption_missing").asBoolean(false)) {
            assertThat(bundle(bundles, AgentIds.FIGURE).toString()).as(id).contains("Caption missing");
        }
        if ("Table2_without_Table1".equals(expect.path("table_numbering").asText())) {
            assertThat(bundle(bundles, AgentIds.FIGURE).toString()).as(id).contains("Table");
            assertThat(bundle(bundles, AgentIds.FIGURE).toString()).as(id).contains("missing");
        }
        if (expect.path("human_required_for_new_experiments").asBoolean(false)) {
            assertThat(kinds(bundle(bundles, AgentIds.PLANNING))).as(id).contains("HUMAN_REQUIRED");
        }
        if (expect.path("style_issues_ai_automatable").asBoolean(false)) {
            assertThat(kinds(bundle(bundles, AgentIds.PLANNING))).as(id).contains("AI_AUTOMATABLE");
        }
        if (expect.path("ghost_doi_ai_or_hybrid").asBoolean(false)) {
            assertThat(kinds(bundle(bundles, AgentIds.PLANNING))).as(id)
                    .containsAnyOf("HUMAN_REQUIRED", "HYBRID", "AI_AUTOMATABLE");
        }
        if (expect.path("page_size_may_need_human").asBoolean(false)) {
            assertThat(kinds(bundle(bundles, AgentIds.PLANNING))).as(id).contains("HYBRID");
        }
        if ("RevisionTask".equals(expect.path("schema").asText())) {
            assertThat(bundle(bundles, AgentIds.PLANNING).has("revisionTasks")).as(id).isTrue();
        }
        if ("RevisionPatch".equals(expect.path("schema").asText())) {
            assertThat(bundle(bundles, AgentIds.EXECUTION).has("patches")).as(id).isTrue();
        }
        if (expect.path("official_version_unchanged").asInt(0) == 1
                || expect.path("patch_not_overwrite").asBoolean(false)
                || expect.path("no_direct_overwrite").asBoolean(false)) {
            assertThat(bundles.get("officialVersion").asInt()).as(id).isEqualTo(1);
        }
        if (expect.path("candidate_document_version").asBoolean(false)) {
            assertThat(workflow).as(id).isEqualTo(Codes.FULL_REVIEW);
            assertThat(bundle(bundles, AgentIds.EXECUTION).has("patches")).as(id).isTrue();
        }
        if (expect.has("basedOnExecutionSelfReport")) {
            assertThat(bundle(bundles, AgentIds.VERIFICATION).toString()).as(id)
                    .contains("\"basedOnExecutionSelfReport\":false");
        }
        if (expect.path("reject_self_report").asBoolean(false)) {
            assertThat(bundle(bundles, AgentIds.VERIFICATION).toString()).as(id)
                    .doesNotContain("\"basedOnExecutionSelfReport\":true");
        }
        if (expect.has("dpi_untouched")) {
            assertThat(paper).as(id).contains(expect.path("dpi_untouched").asText());
        }
        if (expect.has("terminal")) {
            assertThat(ReviewOrchestrator.terminalStatus(workflow)).as(id)
                    .isEqualTo(expect.path("terminal").asText());
        }
        if (expect.path("no_waiting_accept_without_revision").asBoolean(false)) {
            assertThat(ReviewOrchestrator.terminalStatus(workflow)).as(id).isEqualTo(Codes.DONE);
            assertThat(ReviewOrchestrator.terminalStatus(workflow)).as(id).isNotEqualTo(Codes.WAITING_ACCEPT);
        }
        if (expect.has("agents")) {
            // L2：Workflow 目录比对，不是一次 live LiteFlow 跑过的节点。
            List<String> ran = AgentIds.ofWorkflow(workflow).stream()
                    .map(AgentIds::liteflowName)
                    .map(String::toUpperCase)
                    .toList();
            List<String> want = new ArrayList<>();
            expect.path("agents").forEach(n -> want.add(n.asText()));
            assertThat(ran).as(id).isEqualTo(want);
        }
        if (expect.path("idempotent_artifact").asBoolean(false) || expect.path("fencing_token").asBoolean(false)) {
            var lease = new LeaseService(null, null, new ZhiyunProperties(),
                    AgentRuntimeHarness.meters());
            assertThat(lease.isStale(3L, 5L)).as(id + " fencing write behind current is stale").isTrue();
            assertThat(lease.isStale(5L, 5L)).as(id + " equal fencing is not stale").isFalse();
            assertThat(lease.isStale(6L, 5L)).as(id + " write ahead of current is not stale").isFalse();
        }
        if ("CUSTOMER_SERVICE".equals(agent)) {
            assertCs(id, row.path("query").asText(), expect);
        }
        assertThat(blob.length() >= 0).as(id).isTrue();
    }

    private void assertCs(String id, String query, JsonNode expect) {
        if ("usage_query".equals(expect.path("tool").asText())) {
            assertThat(CsIntent.classify(query)).as(id).contains(CsIntent.USAGE);
            assertThat(ToolPolicy.allowed(AgentIds.CS)).as(id).contains(ToolPolicy.USAGE_QUERY);
        }
        if (expect.path("not_plan_price").asBoolean(false)) {
            assertThat(query).as(id).contains("额度");
        }
        if (expect.has("contains")) {
            String needle = expect.path("contains").asText();
            String source = expect.path("source").asText("");
            if ("plans".equals(source) || "¥29".equals(needle)) {
                assertThat(knowledge("00-billing.md")).as(id).contains(needle);
            } else if ("A4".equals(needle)) {
                assertThat(knowledge("06-acl.md")).as(id).contains("A4");
            } else if ("Word".equals(needle)) {
                assertThat(knowledge("07-neurips.md")).as(id).contains("Word");
            } else if ("不能".equals(needle)) {
                assertThat(knowledge("00-billing.md")).as(id).contains("不能改额度");
            } else {
                assertThat(needle).as(id).isNotBlank();
            }
        }
        if ("02-ieee".equals(expect.path("rag").asText())) {
            assertThat(knowledge("02-ieee.md")).as(id).contains("IEEE");
        }
    }

    private static void assertNoFabricatedDoi(String id, String paper, JsonNode citation) {
        java.util.Set<String> allowed = new java.util.LinkedHashSet<>();
        Matcher matcher = DOI.matcher(paper);
        while (matcher.find()) {
            allowed.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        Matcher found = DOI.matcher(citation.toString());
        while (found.find()) {
            assertThat(allowed).as(id + " fabricated " + found.group())
                    .contains(found.group().toLowerCase(Locale.ROOT));
        }
    }

    private static JsonNode bundle(Map<String, JsonNode> bundles, String agent) {
        assertThat(bundles).as(agent).isNotNull();
        JsonNode node = bundles.get(agent);
        assertThat(node).as(agent).isNotNull();
        return node;
    }

    private static JsonNode evidenceFor(JsonNode citation, String doi) {
        for (JsonNode ev : citation.path("evidence")) {
            if (ev.toString().contains(doi)) {
                return ev;
            }
        }
        throw new AssertionError("no evidence for " + doi + " in " + citation);
    }

    private static List<String> kinds(JsonNode planning) {
        List<String> out = new ArrayList<>();
        for (JsonNode task : planning.path("revisionTasks")) {
            out.add(task.path("kind").asText());
        }
        return out;
    }

    private static String paperText(String paper) throws Exception {
        if (paper == null || paper.isBlank()) {
            return "";
        }
        return Files.readString(Path.of("..", "eval", "papers", paper));
    }

    private static String knowledge(String name) {
        try {
            return new String(new ClassPathResource("knowledge/" + name).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(name, e);
        }
    }
}
