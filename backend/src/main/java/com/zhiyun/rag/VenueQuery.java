package com.zhiyun.rag;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公共刊规范 RAG 检索词：用户选的投稿期刊优先，否则从正文/标题抽 venue。
 * 抽不到或解析失败时回退调用方的固定检索词，不阻断审校。
 */
public final class VenueQuery {
    private static final int FRONT_CHARS = 6000;

    /** 正文里明确写出的投稿目标（不是库表字段）。 */
    private static final Pattern LABELED = Pattern.compile(
            "(?i)(?:intended for|submitted to|under review(?: at)?|camera[- ]ready for|to appear in"
                    + "|target (?:venue|journal|conference)"
                    + "|投稿[至往到]|拟投|投往|目标(?:期刊|会议))[:：\\s]+([^\\n,;。，；]{2,80})");

    /** LaTeX 文档类 / 宏包，对应 classpath:knowledge 刊规范专章。 */
    private static final Pattern LATEX_CLASS = Pattern.compile(
            "\\\\(?:documentclass|usepackage)(?:\\[[^\\]]*])?\\{([^}]+)}");

    private static final Pattern REFERENCES = Pattern.compile(
            "(?im)^(?:#+\\s*)?(?:references|bibliography|参考文献)\\b");

    /** 需上下文才认的短名，避免 IEEE 754、the nature of 误伤。 */
    private static final Pattern LOOSE_CONTEXT = Pattern.compile(
            "(?i)(conference|journal|workshop|proceedings|template|camera-ready"
                    + "|transactions|symposium|投稿|会议|期刊|大会|专刊|汇刊)");

    private VenueQuery() {
    }

    /**
     * 公共 RAG 检索词：有 venue 则前置规范化刊名，否则原样返回 fallback。
     */
    public static String publicQuery(String title, String content, String fallback) {
        return publicQuery(null, title, content, fallback);
    }

    /**
     * 用户选定的投稿期刊优先于正文抽取。未指定或无法规范化时再抽正文。
     */
    public static String publicQuery(String selectedVenue, String title, String content, String fallback) {
        String base = fallback == null ? "" : fallback;
        try {
            String chosen = VenueCatalog.canonical(selectedVenue);
            if (chosen != null && !chosen.isBlank()) {
                return chosen + " " + base;
            }
            String venue = extract(title, content);
            if (venue == null || venue.isBlank()) {
                return base;
            }
            return venue + " " + base;
        } catch (RuntimeException ignored) {
            return base;
        }
    }

    /** 规范化刊名（IEEE / ACL / …）；抽不到返回 null。 */
    public static String extract(String title, String content) {
        String head = frontMatter(title, content);
        if (head.isBlank()) {
            return null;
        }
        String labeled = fromLabeled(head);
        if (labeled != null) {
            return labeled;
        }
        String tex = fromLatex(head);
        if (tex != null) {
            return tex;
        }
        String titled = fromAliases(safe(title), true);
        if (titled != null) {
            return titled;
        }
        return fromAliases(head, false);
    }

    private static String frontMatter(String title, String content) {
        String body = safe(content);
        Matcher refs = REFERENCES.matcher(body);
        if (refs.find()) {
            body = body.substring(0, refs.start());
        }
        if (body.length() > FRONT_CHARS) {
            body = body.substring(0, FRONT_CHARS);
        }
        String t = safe(title);
        return t.isBlank() ? body : t + "\n" + body;
    }

    private static String fromLabeled(String text) {
        Matcher m = LABELED.matcher(text);
        while (m.find()) {
            String raw = clean(m.group(1));
            if (raw.isBlank()) {
                continue;
            }
            String known = matchAlias(raw);
            if (known != null) {
                return known;
            }
            if (looksLikeVenueName(raw)) {
                return raw;
            }
        }
        return null;
    }

    private static String fromLatex(String text) {
        Matcher m = LATEX_CLASS.matcher(text);
        while (m.find()) {
            String known = matchAlias(m.group(1));
            if (known != null) {
                return known;
            }
        }
        return null;
    }

    private static String fromAliases(String text, boolean titleScope) {
        if (text.isBlank()) {
            return null;
        }
        int bestAt = Integer.MAX_VALUE;
        String best = null;
        for (Alias alias : ALIASES) {
            Matcher m = alias.pattern.matcher(text);
            if (!m.find()) {
                continue;
            }
            if (!titleScope && alias.needsContext && !nearContext(text, m.start(), m.end())) {
                continue;
            }
            if (m.start() < bestAt) {
                bestAt = m.start();
                best = alias.canonical;
            }
        }
        return best;
    }

    /** 把自由文本规范成目录刊名；认不出返回 null。 */
    public static String matchKnown(String raw) {
        return matchAlias(raw);
    }

    private static String matchAlias(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (Alias alias : ALIASES) {
            if (alias.pattern.matcher(raw).find()) {
                return alias.canonical;
            }
        }
        return null;
    }

    private static boolean nearContext(String text, int start, int end) {
        int from = Math.max(0, start - 80);
        int to = Math.min(text.length(), end + 80);
        return LOOSE_CONTEXT.matcher(text.substring(from, to)).find();
    }

    private static boolean looksLikeVenueName(String raw) {
        if (raw.length() < 3 || raw.length() > 80) {
            return false;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.matches("the|a|an|this|paper|draft|conference|journal|workshop")) {
            return false;
        }
        return raw.chars().anyMatch(Character::isLetter);
    }

    private static String clean(String raw) {
        String s = raw == null ? "" : raw.replaceAll("\\s+", " ").strip();
        s = s.replaceAll("(?i)\\s+(?:conference|workshop|proceedings)\\s*$", "").strip();
        s = s.replaceAll("\\s+\\d{4}$", "").strip();
        if (s.startsWith("the ") || s.startsWith("The ")) {
            s = s.substring(4).strip();
        }
        return s;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private record Alias(String canonical, Pattern pattern, boolean needsContext) {
    }

    /** 与 VenueCatalog / knowledge 专章对齐；正文已有的投稿目标可额外保留原文名。 */
    private static final Alias[] ALIASES = {
            new Alias("IEEE", Pattern.compile("(?i)\\bIEEEtran\\b|\\bIEEEXplore\\b|\\bIEEE\\b"), true),
            new Alias("ACM", Pattern.compile("(?i)\\bacmart\\b|\\bSIGCHI\\b|\\bSIGCOMM\\b|\\bACM\\b"), true),
            new Alias("EMNLP", Pattern.compile("(?i)\\bEMNLP\\b"), false),
            new Alias("NAACL", Pattern.compile("(?i)\\bNAACL\\b"), false),
            new Alias("ACL", Pattern.compile("(?i)\\b(?:EACL|AACL|\\*?ACL)\\b"), false),
            new Alias("NeurIPS", Pattern.compile("(?i)\\b(?:NeurIPS|NIPS)\\b"), false),
            new Alias("ICML", Pattern.compile("(?i)\\bICML\\b"), false),
            new Alias("ICLR", Pattern.compile("(?i)\\bICLR\\b"), false),
            new Alias("AAAI", Pattern.compile("(?i)\\bAAAI\\b"), false),
            new Alias("COLM", Pattern.compile("(?i)\\bCOLM\\b"), false),
            new Alias("OSDI", Pattern.compile("(?i)\\bOSDI\\b"), false),
            new Alias("SOSP", Pattern.compile("(?i)\\bSOSP\\b"), false),
            new Alias("NSDI", Pattern.compile("(?i)\\bNSDI\\b"), false),
            new Alias("ASPLOS", Pattern.compile("(?i)\\bASPLOS\\b"), false),
            new Alias("Elsevier", Pattern.compile("(?i)\\belsarticle\\b|\\bels-cas\\b|\\bElsevier\\b"), false),
            new Alias("Springer", Pattern.compile("(?i)\\bsn-(?:jnl|article)\\b|\\bSpringer\\b"), false),
            new Alias("Nature", Pattern.compile(
                    "(?i)\\bNature Portfolio\\b|\\bNature Communications\\b|\\bScientific Reports\\b"), false)
    };
}
