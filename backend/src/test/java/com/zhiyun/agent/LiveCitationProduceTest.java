package com.zhiyun.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.zhiyun.config.ZhiyunProperties;
import com.zhiyun.harness.AgentIds;
import com.zhiyun.harness.ReviewSlot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LiveCitationProduceTest {

    @Test
    void liveLooksUpDoiBeforeLlmAndDropsInventedDoi() throws Exception {
        ZhiyunProperties properties = AgentRuntimeHarness.liveProps();
        AgentRuntimeHarness.RecordingSearch search = new AgentRuntimeHarness.RecordingSearch(properties);
        AgentRuntimeHarness.CapturingLlm llm = new AgentRuntimeHarness.CapturingLlm(properties);
        llm.reply = """
                {"evidence":[
                  {"claim":"hallucinated","excerpt":"10.9999/invented","status":"VERIFIED","supportsClaim":true,
                   "paper":{"doi":"10.9999/invented","title":"fake"}},
                  {"claim":"ghost ok","excerpt":"10.0000/ghost.doi","status":"VERIFIED","supportsClaim":true,
                   "paper":{"doi":"10.0000/ghost.doi"}},
                  {"claim":"example","excerpt":"10.1145/example.2019","status":"VERIFIED","supportsClaim":true}
                ]}
                """;
        AgentRuntime runtime = AgentRuntimeHarness.runtime(properties, search, llm,
                new AgentRuntimeHarness.MemStore(new com.fasterxml.jackson.databind.ObjectMapper()));
        String text = """
                Smith 10.1145/example.2019 and a fabricated 10.0000/ghost.doi
                """;
        JsonNode out = runtime.produce(AgentRuntimeHarness.slot(text), AgentIds.CITATION, 0);

        assertThat(search.dois).contains("10.1145/example.2019", "10.0000/ghost.doi");
        assertThat(llm.completeCalls).isEqualTo(1);
        assertThat(llm.lastUser).contains("AcademicSearchTool.lookupDoi");
        assertThat(llm.lastUser).contains("Java metadata");
        assertThat(llm.lastUser).contains("Do not invent");
        assertThat(llm.lastSystem).contains("lookupDoi");
        assertThat(out.toString()).doesNotContain("10.9999/invented");
        JsonNode ghost = evidenceFor(out, "10.0000/ghost.doi");
        assertThat(ghost.path("status").asText()).isEqualTo("NOT_VERIFIED");
        assertThat(ghost.path("supportsClaim").asBoolean()).isFalse();
        assertThat(ghost.path("paper").isNull()).isTrue();
        JsonNode real = evidenceFor(out, "10.1145/example.2019");
        assertThat(real.path("paper").path("title").asText()).contains("Java Crossref");
        assertThat(real.path("source").asText()).isEqualTo("CROSSREF");
        assertThat(real.path("supportsClaim").asBoolean()).isTrue();
        assertThat(real.path("status").asText()).isEqualTo("VERIFIED");
    }

    @Test
    void liveDoesNotTreatCrossrefHitAsClaimSupportWhenLlmOmitsDoi() throws Exception {
        ZhiyunProperties properties = AgentRuntimeHarness.liveProps();
        AgentRuntimeHarness.RecordingSearch search = new AgentRuntimeHarness.RecordingSearch(properties);
        AgentRuntimeHarness.CapturingLlm llm = new AgentRuntimeHarness.CapturingLlm(properties);
        llm.reply = """
                {"evidence":[
                  {"claim":"hallucinated","excerpt":"10.9999/invented","status":"VERIFIED","supportsClaim":true}
                ]}
                """;
        AgentRuntime runtime = AgentRuntimeHarness.runtime(properties, search, llm,
                new AgentRuntimeHarness.MemStore(new com.fasterxml.jackson.databind.ObjectMapper()));
        JsonNode out = runtime.produce(AgentRuntimeHarness.slot("See 10.1145/example.2019"), AgentIds.CITATION, 0);

        JsonNode real = evidenceFor(out, "10.1145/example.2019");
        assertThat(real.path("paper").isNull()).isFalse();
        assertThat(real.path("supportsClaim").asBoolean()).isFalse();
        assertThat(real.path("status").asText()).isEqualTo("NOT_VERIFIED");
        assertThat(out.toString()).doesNotContain("10.9999/invented");
    }

    @Test
    void liveCitationCallsWebSearchForHttpUrls() throws Exception {
        ZhiyunProperties properties = AgentRuntimeHarness.liveProps();
        AgentRuntimeHarness.RecordingSearch search = new AgentRuntimeHarness.RecordingSearch(properties);
        AgentRuntimeHarness.RecordingWebSearch web = new AgentRuntimeHarness.RecordingWebSearch(properties);
        AgentRuntimeHarness.CapturingLlm llm = new AgentRuntimeHarness.CapturingLlm(properties);
        llm.reply = "{\"evidence\":[]}";
        AgentRuntime runtime = AgentRuntimeHarness.runtime(properties, search, llm,
                new AgentRuntimeHarness.MemStore(new com.fasterxml.jackson.databind.ObjectMapper()), web);
        String text = """
                Publisher page https://doi.org/10.1145/example.2019 and 10.1145/example.2019
                """;
        JsonNode out = runtime.produce(AgentRuntimeHarness.slot(text), AgentIds.CITATION, 0);

        assertThat(web.queries).anyMatch(q -> q.contains("https://doi.org/10.1145/example.2019"));
        assertThat(out.toString()).contains("WEB");
        JsonNode webEv = null;
        for (JsonNode ev : out.path("evidence")) {
            if ("WEB".equals(ev.path("source").asText())) {
                webEv = ev;
                break;
            }
        }
        assertThat(webEv).isNotNull();
        assertThat(webEv.path("supportsClaim").asBoolean()).isFalse();
        assertThat(webEv.path("status").asText()).isEqualTo("NOT_VERIFIED");
    }

    @Test
    void liveExecutionCallsDocxWriteCandidate() throws Exception {
        ZhiyunProperties properties = AgentRuntimeHarness.liveProps();
        AgentRuntimeHarness.RecordingSearch search = new AgentRuntimeHarness.RecordingSearch(properties);
        AgentRuntimeHarness.CapturingLlm llm = new AgentRuntimeHarness.CapturingLlm(properties);
        llm.reply = "{\"patches\":[]}";
        AgentRuntime runtime = AgentRuntimeHarness.runtime(properties, search, llm,
                new AgentRuntimeHarness.MemStore(new com.fasterxml.jackson.databind.ObjectMapper()));
        var slot = AgentRuntimeHarness.slot("Firstly, we propose a novel method.");
        slot.getSource().setStoragePath("paper.docx");
        JsonNode out = runtime.produce(slot, AgentIds.EXECUTION, 0);

        assertThat(out.path("docxTool").asText()).isEqualTo("candidate-only");
        assertThat(out.path("docxCandidateBytes").asInt()).isGreaterThan(0);
    }

    @Test
    void dryRunExecutionCallsDocxWriteCandidate() throws Exception {
        ZhiyunProperties properties = AgentRuntimeHarness.dryProps();
        AgentRuntimeHarness.RecordingSearch search = new AgentRuntimeHarness.RecordingSearch(properties);
        AgentRuntimeHarness.CapturingLlm llm = new AgentRuntimeHarness.CapturingLlm(properties);
        AgentRuntime runtime = AgentRuntimeHarness.runtime(properties, search, llm,
                new AgentRuntimeHarness.MemStore(new com.fasterxml.jackson.databind.ObjectMapper()));
        var slot = AgentRuntimeHarness.slot("Firstly, we propose a novel method.");
        slot.getSource().setStoragePath("paper.docx");
        JsonNode out = runtime.dryRun(slot, AgentIds.EXECUTION);
        assertThat(out.path("docxTool").asText()).isEqualTo("candidate-only");
        assertThat(out.path("docxCandidateBytes").asInt()).isGreaterThan(0);
    }

    @Test
    void liveWithoutDoiIsNotVerifiedAndSkipsLookup() throws Exception {
        ZhiyunProperties properties = AgentRuntimeHarness.liveProps();
        AgentRuntimeHarness.RecordingSearch search = new AgentRuntimeHarness.RecordingSearch(properties);
        AgentRuntimeHarness.CapturingLlm llm = new AgentRuntimeHarness.CapturingLlm(properties);
        llm.reply = "{\"evidence\":[{\"status\":\"VERIFIED\",\"paper\":{\"doi\":\"10.9999/invented\"}}]}";
        AgentRuntime runtime = AgentRuntimeHarness.runtime(properties, search, llm,
                new AgentRuntimeHarness.MemStore(new com.fasterxml.jackson.databind.ObjectMapper()));
        ReviewSlot slot = AgentRuntimeHarness.slot("No citations appear in this paragraph.");
        JsonNode out = runtime.produce(slot, AgentIds.CITATION, 0);

        assertThat(search.dois).isEmpty();
        assertThat(llm.completeCalls).isEqualTo(0);
        assertThat(out.toString()).contains("NOT_VERIFIED");
        assertThat(out.toString()).doesNotContain("10.9999/invented");
        assertThat(out.path("evidence").get(0).path("status").asText()).isEqualTo("NOT_VERIFIED");
    }

    private static JsonNode evidenceFor(JsonNode root, String doi) {
        for (JsonNode ev : root.path("evidence")) {
            if (ev.toString().contains(doi)) {
                return ev;
            }
        }
        throw new AssertionError("missing evidence for " + doi + " in " + root);
    }
}
