package com.zhiyun.harness;

import java.util.List;

public final class AgentIds {
    private AgentIds() {
    }

    public static final String CITATION = "CITATION_INTEGRITY";
    public static final String FIGURE = "FIGURE_PDF";
    public static final String REVIEWER = "ACADEMIC_REVIEWER";
    public static final String STYLE = "ACADEMIC_STYLE";
    public static final String PLANNING = "REVISION_PLANNING";
    public static final String EXECUTION = "REVISION_EXECUTION";
    public static final String VERIFICATION = "FINAL_VERIFICATION";
    public static final String CS = "CUSTOMER_SERVICE";

    public static List<String> ofWorkflow(String workflow) {
        if ("QUICK_REVIEW".equals(workflow)) {
            return List.of(CITATION, STYLE);
        }
        if ("FULL_REVIEW".equals(workflow)) {
            return List.of(CITATION, FIGURE, REVIEWER, STYLE, PLANNING, EXECUTION, VERIFICATION);
        }
        if ("CITATION_ONLY".equals(workflow)) {
            return List.of(CITATION);
        }
        return List.of();
    }

    public static String displayName(String agentId) {
        return switch (agentId == null ? "" : agentId) {
            case CITATION -> "引用核验";
            case FIGURE -> "图表检查";
            case REVIEWER -> "学术审稿";
            case STYLE -> "语言润色";
            case PLANNING -> "改稿计划";
            case EXECUTION -> "修改执行";
            case VERIFICATION -> "结果复核";
            case CS -> "云笺";
            default -> agentId == null ? "" : agentId;
        };
    }

    public static String liteflowName(String agentId) {
        return switch (agentId) {
            case CITATION -> "citation";
            case FIGURE -> "figurePdf";
            case REVIEWER -> "reviewer";
            case STYLE -> "style";
            case PLANNING -> "planning";
            case EXECUTION -> "execution";
            case VERIFICATION -> "verification";
            default -> agentId;
        };
    }

    public static String fromLiteflow(String nodeId) {
        return switch (nodeId) {
            case "citation" -> CITATION;
            case "figurePdf" -> FIGURE;
            case "reviewer" -> REVIEWER;
            case "style" -> STYLE;
            case "planning" -> PLANNING;
            case "execution" -> EXECUTION;
            case "verification" -> VERIFICATION;
            default -> nodeId;
        };
    }
}
