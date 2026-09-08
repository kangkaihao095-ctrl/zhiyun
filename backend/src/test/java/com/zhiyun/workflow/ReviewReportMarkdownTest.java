package com.zhiyun.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyun.domain.Artifact;
import com.zhiyun.domain.Codes;
import com.zhiyun.domain.ReviewTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewReportMarkdownTest {
    private final ReviewReport report = new ReviewReport(null, null, null, null, new ObjectMapper());

    @Test
    void readsEnvelopeBodyAndKeepsNotVerified() {
        ReviewTask task = new ReviewTask();
        task.setId(9L);
        task.setManuscriptId(3L);
        task.setWorkflow(Codes.CITATION_ONLY);
        task.setStatus(Codes.DONE);
        task.setSourceVersion(1);
        task.setCheckpointAgent("CITATION_INTEGRITY");

        Artifact issues = artifact("CITATION_INTEGRITY", "ReviewIssue", """
                {"schemaVersion":1,"body":[{"issueId":"iss-1","severity":"HIGH","category":"CITATION","summary":"DOI 10.0000/ghost.doi not found"}]}
                """);
        Artifact evidence = artifact("CITATION_INTEGRITY", "Evidence", """
                {"body":[{"evidenceId":"ev-1","status":"NOT_VERIFIED","claim":"ghost paper","excerpt":"missing in Crossref"}]}
                """);
        Artifact empty = artifact("ACADEMIC_STYLE", "Bundle", "");
        empty.setPayload(null);

        String md = report.markdown(task, "评测稿", "引用核验", List.of(issues, evidence, empty));
        assertThat(md).contains("审校结果汇总");
        assertThat(md).contains("10.0000/ghost.doi");
        assertThat(md).contains("NOT_VERIFIED");
        assertThat(md).contains("ghost paper");
        assertThat(md).contains("不是单独的报告服务");
    }

    @Test
    void emptyArtifactsStillProduceAFile() {
        ReviewTask task = new ReviewTask();
        task.setId(2L);
        task.setManuscriptId(1L);
        task.setWorkflow(Codes.QUICK_REVIEW);
        task.setStatus(Codes.FAILED);
        task.setErrorMessage("structured output failed after retry");
        String md = report.markdown(task, "空稿", "快速审读", List.of());
        assertThat(md).contains("没有 ReviewIssue");
        assertThat(md).contains("模型输出格式校验失败，请重试。");
        assertThat(md).doesNotContain("structured output failed after retry");
        assertThat(md).contains("没有 RevisionPatch");
    }

    private static Artifact artifact(String agent, String type, String payload) {
        Artifact a = new Artifact();
        a.setAgent(agent);
        a.setArtifactType(type);
        a.setPayload(payload);
        a.setFencingToken(1L);
        return a;
    }
}
