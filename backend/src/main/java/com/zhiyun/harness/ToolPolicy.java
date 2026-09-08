package com.zhiyun.harness;

import java.util.Set;

/**
 * 某 Agent 实际被允许调用的 Tool 白名单。无需要时不授予高风险或无关工具。
 */
public final class ToolPolicy {
    private ToolPolicy() {
    }

    public static final String CITATION_PARSER = "CitationParser";
    public static final String ACADEMIC_SEARCH = "AcademicSearch";
    public static final String METADATA_VERIFIER = "MetadataVerifier";
    public static final String WEB_SEARCH = "WebSearch";
    public static final String MANUSCRIPT_RETRIEVAL = "ManuscriptRetrieval";
    public static final String PDF_PARSE = "PDFParse";
    public static final String PDF_RENDER = "PDFRender";
    public static final String FIGURE_EXTRACT = "FigureExtract";
    public static final String FIGURE_METADATA = "FigureMetadata";
    public static final String VISION = "Vision";
    public static final String DOCUMENT_READ = "DocumentRead";
    public static final String DOCUMENT_PATCH = "DocumentPatch";
    public static final String DOCX = "DocxTool";
    public static final String DIFF = "Diff";
    public static final String KNOWLEDGE_RETRIEVAL = "KnowledgeRetrieval";
    public static final String TASK_STATUS = "TaskStatus";
    public static final String TASK_LIST = "TaskList";
    public static final String PAPER_LOOKUP = "PaperLookup";
    public static final String MANUSCRIPT_LIST = "ManuscriptList";
    public static final String MANUSCRIPT_GET = "ManuscriptGet";
    public static final String USAGE_QUERY = "UsageQuery";
    public static final String LEDGER_QUERY = "LedgerQuery";
    public static final String ORDER_QUERY = "OrderQuery";
    public static final String PLAN_LIST = "PlanList";
    public static final String INBOX_UNREAD = "InboxUnread";
    public static final String ACCOUNT_PROFILE = "AccountProfile";
    public static final String MODEL_CONFIG = "ModelConfig";
    public static final String CITATION_RESULT = "CitationResult";

    public static Set<String> allowed(String agentId) {
        return switch (agentId) {
            case AgentIds.CITATION -> Set.of(
                    CITATION_PARSER, ACADEMIC_SEARCH, METADATA_VERIFIER, WEB_SEARCH, MANUSCRIPT_RETRIEVAL);
            case AgentIds.FIGURE -> Set.of(
                    PDF_PARSE, PDF_RENDER, FIGURE_EXTRACT, FIGURE_METADATA, VISION);
            case AgentIds.REVIEWER -> Set.of(MANUSCRIPT_RETRIEVAL, ACADEMIC_SEARCH);
            case AgentIds.STYLE -> Set.of(DOCUMENT_READ);
            case AgentIds.PLANNING -> Set.of();
            case AgentIds.EXECUTION -> Set.of(DOCUMENT_READ, DOCUMENT_PATCH, DOCX);
            case AgentIds.VERIFICATION -> Set.of(
                    MANUSCRIPT_RETRIEVAL, ACADEMIC_SEARCH, METADATA_VERIFIER, PDF_PARSE, FIGURE_METADATA, DIFF);
            case AgentIds.CS -> Set.of(
                    KNOWLEDGE_RETRIEVAL, TASK_STATUS, TASK_LIST, PAPER_LOOKUP,
                    MANUSCRIPT_LIST, MANUSCRIPT_GET,
                    USAGE_QUERY, LEDGER_QUERY, ORDER_QUERY, PLAN_LIST,
                    INBOX_UNREAD, ACCOUNT_PROFILE, MODEL_CONFIG, CITATION_RESULT);
            default -> Set.of();
        };
    }

    public static void assertAllowed(String agentId, String tool) {
        if (!allowed(agentId).contains(tool)) {
            throw new IllegalArgumentException(agentId + " is not allowed to use " + tool);
        }
    }
}
