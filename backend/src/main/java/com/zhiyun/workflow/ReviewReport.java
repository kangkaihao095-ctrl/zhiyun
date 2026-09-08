package com.zhiyun.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zhiyun.common.PublicError;
import com.zhiyun.domain.Artifact;
import com.zhiyun.domain.Manuscript;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.harness.ArtifactStore;
import com.zhiyun.repo.ManuscriptRepo;
import com.zhiyun.security.TenantContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把当前任务已有 Artifact 汇总成 Markdown，不另建报告服务。
 */
@Service
public class ReviewReport {
    private static final List<String> SEV_ORDER = List.of(
            "CRITICAL", "HIGH", "NOT_VERIFIED", "MEDIUM", "LOW");
    private static final Map<String, String> SEV_LABEL = Map.of(
            "CRITICAL", "必须处理",
            "HIGH", "高",
            "NOT_VERIFIED", "未能核实",
            "MEDIUM", "中",
            "LOW", "低");
    private static final Map<String, String> KIND_LABEL = Map.of(
            "HUMAN_REQUIRED", "需要人工处理",
            "HYBRID", "人机协作",
            "AI_AUTOMATABLE", "可由系统改");
    private static final Map<String, String> STATUS_LABEL = Map.of(
            "PENDING", "排队中",
            "RUNNING", "正在审校",
            "WAITING_ACCEPT", "等你确认修改",
            "DONE", "已完成",
            "FAILED", "没能完成");

    private final ReviewService reviewService;
    private final ArtifactStore artifactStore;
    private final ManuscriptRepo manuscriptRepo;
    private final WorkflowCatalog workflowCatalog;
    private final ObjectMapper objectMapper;

    public ReviewReport(ReviewService reviewService, ArtifactStore artifactStore,
                        ManuscriptRepo manuscriptRepo, WorkflowCatalog workflowCatalog,
                        ObjectMapper objectMapper) {
        this.reviewService = reviewService;
        this.artifactStore = artifactStore;
        this.manuscriptRepo = manuscriptRepo;
        this.workflowCatalog = workflowCatalog;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> export(String key) {
        ReviewTask task = reviewService.get(key);
        return export(task);
    }

    public Map<String, Object> export(long taskId) {
        return export(reviewService.get(taskId));
    }

    private Map<String, Object> export(ReviewTask task) {
        String title = manuscriptRepo.findByIdAndTenantId(task.getManuscriptId(), TenantContext.tenantId())
                .map(Manuscript::getTitle)
                .orElse("已删除的论文");
        List<Artifact> artifacts = artifactStore.list(task.getId(), TenantContext.tenantId());
        String markdown = markdown(task, title, workflowCatalog.nameOf(task.getWorkflow()), artifacts);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.publicId());
        out.put("filename", "zhiyun-review-" + task.publicId() + ".md");
        out.put("markdown", markdown);
        out.put("artifactsFilename", "zhiyun-artifacts-" + task.publicId() + ".json");
        out.put("artifacts", artifacts.stream().map(reviewService::toPublicArtifact).toList());
        return out;
    }

    String markdown(ReviewTask task, String title, String workflowName, List<Artifact> artifacts) {
        List<JsonNode> issues = collectTyped(artifacts, "ReviewIssue");
        if (issues.isEmpty()) {
            issues = collectBundleField(artifacts, "issues");
        }
        List<JsonNode> evidence = collectTyped(artifacts, "Evidence");
        if (evidence.isEmpty()) {
            evidence = collectBundleField(artifacts, "evidence");
        }
        List<JsonNode> tasks = collectTyped(artifacts, "RevisionTask");
        if (tasks.isEmpty()) {
            tasks = collectBundleField(artifacts, "revisionTasks");
        }
        List<JsonNode> patches = collectTyped(artifacts, "RevisionPatch");
        if (patches.isEmpty()) {
            patches = collectBundleField(artifacts, "patches");
        }
        List<JsonNode> verifications = collectTyped(artifacts, "VerificationResult");
        if (verifications.isEmpty()) {
            verifications = collectBundleField(artifacts, "verification");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 审校结果汇总\n\n");
        sb.append("## 任务\n\n");
        line(sb, "任务 ID", task.publicId() == null ? "—" : task.publicId());
        line(sb, "稿件", title);
        line(sb, "稿件 ID", String.valueOf(task.getManuscriptId()));
        line(sb, "检查范围", workflowName + " (`" + nullToDash(task.getWorkflow()) + "`)");
        line(sb, "状态", statusLabel(task.getStatus()) + " (`" + nullToDash(task.getStatus()) + "`)");
        line(sb, "源版本", task.getSourceVersion() == null ? "—" : String.valueOf(task.getSourceVersion()));
        line(sb, "候选版本", task.getCandidateVersion() == null ? "—" : String.valueOf(task.getCandidateVersion()));
        line(sb, "检查点", nullToDash(task.getCheckpointAgent()));
        line(sb, "创建时间", task.getCreatedAt() == null ? "—" : task.getCreatedAt().toString());
        if (task.getErrorMessage() != null && !task.getErrorMessage().isBlank()) {
            line(sb, "错误", PublicError.message(task.getErrorMessage()));
        }
        sb.append('\n');

        appendIssues(sb, issues);
        appendUnverifiedCitations(sb, issues, evidence);
        appendHumanRequired(sb, tasks);
        appendPatches(sb, patches);
        appendVerification(sb, verifications);
        sb.append("_本文件由当前任务已落库的 Artifact 汇总，不是单独的报告服务。_\n");
        return sb.toString();
    }

    private void appendIssues(StringBuilder sb, List<JsonNode> issues) {
        sb.append("## 问题（按严重度）\n\n");
        if (issues.isEmpty()) {
            sb.append("没有 ReviewIssue。\n\n");
            return;
        }
        Map<String, List<JsonNode>> grouped = new LinkedHashMap<>();
        for (String sev : SEV_ORDER) {
            grouped.put(sev, new ArrayList<>());
        }
        grouped.put("OTHER", new ArrayList<>());
        for (JsonNode issue : issues) {
            String sev = issue.path("severity").asText("");
            grouped.computeIfAbsent(SEV_ORDER.contains(sev) ? sev : "OTHER", k -> new ArrayList<>()).add(issue);
        }
        for (Map.Entry<String, List<JsonNode>> e : grouped.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            String label = SEV_LABEL.getOrDefault(e.getKey(), e.getKey().equals("OTHER") ? "其他" : e.getKey());
            sb.append("### ").append(label).append("\n\n");
            int i = 0;
            for (JsonNode issue : e.getValue()) {
                i++;
                sb.append(i).append(". ");
                String id = firstText(issue, "issueId", "id");
                if (!id.isBlank()) {
                    sb.append('`').append(id).append("` ");
                }
                sb.append(firstText(issue, "summary", "detail", "message"));
                String category = firstText(issue, "category");
                if (!category.isBlank()) {
                    sb.append(" · ").append(category);
                }
                sb.append('\n');
                String detail = firstText(issue, "detail");
                String summary = firstText(issue, "summary");
                if (!detail.isBlank() && !detail.equals(summary)) {
                    sb.append("   - ").append(clip(detail, 240)).append('\n');
                }
            }
            sb.append('\n');
        }
    }

    private void appendUnverifiedCitations(StringBuilder sb, List<JsonNode> issues, List<JsonNode> evidence) {
        List<String> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode ev : evidence) {
            if (!"NOT_VERIFIED".equals(ev.path("status").asText())) {
                continue;
            }
            String id = firstText(ev, "evidenceId", "id");
            String claim = firstText(ev, "claim", "excerpt");
            String key = id + "|" + claim;
            if (!seen.add(key)) {
                continue;
            }
            rows.add(formatUnverified(id, claim, firstText(ev, "excerpt")));
        }
        for (JsonNode issue : issues) {
            String sev = issue.path("severity").asText("");
            String blob = (firstText(issue, "summary", "detail") + " " + issue.path("category").asText()).toUpperCase();
            boolean flagged = "NOT_VERIFIED".equals(sev)
                    || blob.contains("NOT_VERIFIED")
                    || "CITATION".equals(issue.path("category").asText()) && blob.contains("NOT FOUND");
            if (!flagged) {
                continue;
            }
            String id = firstText(issue, "issueId", "id");
            String summary = firstText(issue, "summary", "detail");
            String key = id + "|" + summary;
            if (!seen.add(key)) {
                continue;
            }
            rows.add(formatUnverified(id, summary, firstText(issue, "detail", "excerpt")));
        }
        sb.append("## 引用未能核实（NOT_VERIFIED）\n\n");
        if (rows.isEmpty()) {
            sb.append("没有标记为 NOT_VERIFIED 的引用。\n\n");
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            sb.append(i + 1).append(". ").append(rows.get(i)).append('\n');
        }
        sb.append('\n');
    }

    private void appendHumanRequired(StringBuilder sb, List<JsonNode> tasks) {
        sb.append("## 需人工处理（HUMAN_REQUIRED）\n\n");
        List<JsonNode> human = new ArrayList<>();
        List<JsonNode> others = new ArrayList<>();
        for (JsonNode task : tasks) {
            if ("HUMAN_REQUIRED".equals(task.path("kind").asText())) {
                human.add(task);
            } else if (!firstText(task, "instruction", "kind").isBlank()) {
                others.add(task);
            }
        }
        if (human.isEmpty() && others.isEmpty()) {
            sb.append("没有 RevisionTask。\n\n");
            return;
        }
        if (human.isEmpty()) {
            sb.append("没有 HUMAN_REQUIRED 项。\n\n");
        } else {
            int i = 0;
            for (JsonNode task : human) {
                i++;
                sb.append(i).append(". ");
                String id = firstText(task, "taskId", "id");
                if (!id.isBlank()) {
                    sb.append('`').append(id).append("` ");
                }
                sb.append(firstText(task, "instruction"));
                String issueId = firstText(task, "issueId");
                if (!issueId.isBlank()) {
                    sb.append(" · 问题 `").append(issueId).append('`');
                }
                sb.append('\n');
            }
            sb.append('\n');
        }
        if (!others.isEmpty()) {
            sb.append("其他改稿计划：\n\n");
            for (JsonNode task : others) {
                String kind = task.path("kind").asText("");
                sb.append("- ").append(KIND_LABEL.getOrDefault(kind, kind)).append("：")
                        .append(firstText(task, "instruction")).append('\n');
            }
            sb.append('\n');
        }
    }

    private void appendPatches(StringBuilder sb, List<JsonNode> patches) {
        sb.append("## 建议修改（RevisionPatch）\n\n");
        List<JsonNode> usable = new ArrayList<>();
        for (JsonNode p : patches) {
            String original = firstText(p, "originalText", "original_text", "original");
            String proposed = firstText(p, "proposedText", "proposed_text", "proposed");
            if (original.isBlank() && proposed.isBlank()) {
                continue;
            }
            if (original.equals(proposed)) {
                continue;
            }
            usable.add(p);
        }
        if (usable.isEmpty()) {
            sb.append("没有 RevisionPatch。\n\n");
            return;
        }
        int i = 0;
        for (JsonNode p : usable) {
            i++;
            String id = firstText(p, "patchId", "id");
            sb.append(i).append(". ");
            if (!id.isBlank()) {
                sb.append('`').append(id).append("` ");
            }
            sb.append('\n');
            sb.append("   - 原文：").append(clip(firstText(p, "originalText", "original_text", "original"), 180)).append('\n');
            sb.append("   - 建议：").append(clip(firstText(p, "proposedText", "proposed_text", "proposed"), 180)).append('\n');
            String reason = firstText(p, "reason", "rationale");
            if (!reason.isBlank()) {
                sb.append("   - 原因：").append(clip(reason, 180)).append('\n');
            }
        }
        sb.append('\n');
    }

    private void appendVerification(StringBuilder sb, List<JsonNode> verifications) {
        sb.append("## 复核（VerificationResult）\n\n");
        if (verifications.isEmpty()) {
            sb.append("没有 VerificationResult。\n\n");
            return;
        }
        int i = 0;
        for (JsonNode vr : verifications) {
            i++;
            sb.append(i).append(". ");
            String id = firstText(vr, "resultId", "id");
            if (!id.isBlank()) {
                sb.append('`').append(id).append("` ");
            }
            sb.append(vr.path("resolved").asBoolean(false) ? "已解决" : "未解决");
            if (vr.path("stillHumanRequired").asBoolean(false)) {
                sb.append(" · 仍需人工");
            }
            String issueId = firstText(vr, "issueId");
            if (!issueId.isBlank()) {
                sb.append(" · 问题 `").append(issueId).append('`');
            }
            sb.append('\n');
            String notes = firstText(vr, "notes", "note");
            if (!notes.isBlank()) {
                sb.append("   - ").append(clip(notes, 240)).append('\n');
            }
            JsonNode np = vr.get("newProblems");
            if (np != null && np.isArray() && np.size() > 0) {
                sb.append("   - 新问题：");
                List<String> items = new ArrayList<>();
                np.forEach(n -> items.add(n.asText()));
                sb.append(String.join("；", items)).append('\n');
            }
            sb.append("   - basedOnExecutionSelfReport=").append(vr.path("basedOnExecutionSelfReport").asBoolean(false)).append('\n');
        }
        sb.append('\n');
    }

    private static String formatUnverified(String id, String claim, String extra) {
        StringBuilder row = new StringBuilder();
        if (id != null && !id.isBlank()) {
            row.append('`').append(id).append("` ");
        }
        row.append(claim == null || claim.isBlank() ? "未能核实" : claim);
        row.append(" · **NOT_VERIFIED**");
        if (extra != null && !extra.isBlank() && !extra.equals(claim)) {
            row.append('\n').append("   - ").append(clip(extra, 200));
        }
        return row.toString();
    }

    private static void line(StringBuilder sb, String k, String v) {
        sb.append("- ").append(k).append("：").append(v).append('\n');
    }

    private static String statusLabel(String status) {
        return STATUS_LABEL.getOrDefault(status == null ? "" : status, status == null ? "—" : status);
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private List<JsonNode> collectTyped(List<Artifact> artifacts, String type) {
        List<JsonNode> out = new ArrayList<>();
        for (Artifact a : artifacts) {
            if (!type.equals(a.getArtifactType())) {
                continue;
            }
            out.addAll(asArray(unwrapBody(a.getPayload())));
        }
        return out;
    }

    private List<JsonNode> collectBundleField(List<Artifact> artifacts, String field) {
        List<JsonNode> out = new ArrayList<>();
        for (Artifact a : artifacts) {
            if (!"Bundle".equals(a.getArtifactType())) {
                continue;
            }
            JsonNode body = unwrapBody(a.getPayload());
            if (body != null && body.has(field)) {
                out.addAll(asArray(body.get(field)));
            }
        }
        return out;
    }

    private JsonNode unwrapBody(String payload) {
        if (payload == null || payload.isBlank()) {
            return objectMapper.missingNode();
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root != null && root.has("body")) {
                return root.get("body");
            }
            return root == null ? objectMapper.missingNode() : root;
        } catch (Exception e) {
            return objectMapper.missingNode();
        }
    }

    private static List<JsonNode> asArray(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<JsonNode> out = new ArrayList<>();
            node.forEach(out::add);
            return out;
        }
        if (node.isObject()) {
            return List.of(node);
        }
        return List.of();
    }

    private static String firstText(JsonNode node, String... keys) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull() || value.isMissingNode() || !value.isTextual()) {
                continue;
            }
            String text = value.asText("").trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return "";
    }

    private static String clip(String s, int n) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        if (t.length() <= n) {
            return t;
        }
        return t.substring(0, n) + "…";
    }
}
