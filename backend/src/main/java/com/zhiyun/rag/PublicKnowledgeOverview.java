package com.zhiyun.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.Instant;

/**
 * 运营页只读概述：标题 / 短摘要 / 专章名，不把 md 或 ES 原文塞给前端。
 */
public final class PublicKnowledgeOverview {
    static final int MAX_SUMMARY = 140;
    static final int MAX_SECTIONS = 10;

    private PublicKnowledgeOverview() {
    }

    public static String categoryOf(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.startsWith("00-") || name.startsWith("01-") || name.startsWith("33-") || name.startsWith("34-")) {
            return "source";
        }
        if (name.startsWith("02-") || name.startsWith("03-") || name.startsWith("04-")
                || name.startsWith("05-") || name.startsWith("06-") || name.startsWith("07-")
                || name.startsWith("08-") || name.startsWith("14-") || name.startsWith("15-")
                || name.startsWith("16-") || name.startsWith("17-") || name.startsWith("18-")
                || name.contains("systems") || name.contains("venue")) {
            return "venue";
        }
        return "skill";
    }

    public static String titleOf(String filename, String content) {
        if (content != null) {
            for (String raw : content.split("\n")) {
                String line = raw.trim();
                if (line.startsWith("# ") && !line.startsWith("##")) {
                    return line.substring(2).trim();
                }
            }
        }
        return filename == null || filename.isBlank() ? "未命名" : filename;
    }

    public static String summaryOf(String filename, String content) {
        if (filename != null) {
            for (VenueCatalog.Venue venue : VenueCatalog.all()) {
                if (filename.equals(venue.knowledgeFile()) && venue.summary() != null && !venue.summary().isBlank()) {
                    return clip(venue.summary());
                }
            }
        }
        if (content == null || content.isBlank()) {
            return "";
        }
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("来源") || line.startsWith("http")) {
                continue;
            }
            if (line.startsWith("- ")) {
                line = line.substring(2).trim();
            }
            return clip(line);
        }
        return "";
    }

    public static List<String> sectionsOf(String content) {
        List<String> out = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        for (String raw : content.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("## ") && !line.startsWith("###")) {
                out.add(line.substring(3).trim());
                if (out.size() >= MAX_SECTIONS) {
                    break;
                }
            }
        }
        return out;
    }

    public static Map<String, Object> row(String source, String filename, String content, Long id, Instant updatedAt) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (id != null) {
            row.put("id", id);
        }
        row.put("source", source);
        row.put("filename", filename);
        row.put("title", titleOf(filename, content));
        row.put("summary", summaryOf(filename, content));
        row.put("sections", sectionsOf(content));
        row.put("category", "ops".equals(source) ? "ops" : categoryOf(filename));
        row.put("chars", content == null ? 0 : content.length());
        if (updatedAt != null) {
            row.put("updatedAt", updatedAt);
        }
        return row;
    }

    public static List<Map<String, Object>> groups(List<Map<String, Object>> bundled, int uploaded, int chunks) {
        int venueFiles = 0;
        int skillFiles = 0;
        int sourceFiles = 0;
        LinkedHashSet<String> venueNames = new LinkedHashSet<>();
        List<Map<String, Object>> docs = bundled == null ? List.of() : bundled;
        for (Map<String, Object> row : docs) {
            String filename = String.valueOf(row.getOrDefault("filename", ""));
            String cat = String.valueOf(row.getOrDefault("category", categoryOf(filename)));
            if ("venue".equals(cat)) {
                venueFiles++;
                for (VenueCatalog.Venue venue : VenueCatalog.all()) {
                    if (filename.equals(venue.knowledgeFile())) {
                        venueNames.add(venue.id());
                    }
                }
            } else if ("skill".equals(cat)) {
                skillFiles++;
            } else {
                sourceFiles++;
            }
        }
        String venueSummary = venueNames.isEmpty()
                ? "IEEE、ACM、ACL 等投稿模板、页规格与实验门槛。"
                : String.join("、", take(venueNames, 6)) + " 等投稿模板、页规格与实验门槛。";
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(group("venue", "期刊规范", venueSummary,
                List.of("排版与页规格", "公式与字体", "实验 vs 仿真"), venueFiles, new ArrayList<>(venueNames)));
        out.add(group("skill", "写作 skill", "英文润色、中文润色、中译英与 LaTeX 审查。",
                List.of("英文润色", "中文润色", "中译英", "LaTeX 审查"), skillFiles, List.of()));
        out.add(group("source", "来源与索引", "全局一份 PUBLIC ES，不是一租户一份库。",
                List.of("仓库内置 " + docs.size() + " 篇", "公共切片 " + chunks + " 条", "运营上传 " + uploaded + " 份"),
                sourceFiles, List.of()));
        return out;
    }

    private static Map<String, Object> group(String id, String title, String summary,
                                             List<String> highlights, int fileCount, List<String> items) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("title", title);
        row.put("summary", summary);
        row.put("highlights", highlights);
        row.put("fileCount", fileCount);
        row.put("items", items);
        return row;
    }

    private static List<String> take(LinkedHashSet<String> names, int n) {
        List<String> out = new ArrayList<>();
        for (String name : names) {
            out.add(name);
            if (out.size() >= n) {
                break;
            }
        }
        return out;
    }

    static String clip(String text) {
        String t = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (t.length() <= MAX_SUMMARY) {
            return t;
        }
        return t.substring(0, MAX_SUMMARY - 1) + "…";
    }
}
