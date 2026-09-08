package com.zhiyun.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 平台期刊/会议目录，与 classpath:knowledge 刊规范专章对齐。
 * 下拉与校验都走这里，不要在前端写死三两个刊名。
 */
public final class VenueCatalog {

    public record Venue(String id, String name, String family, String summary, String knowledgeFile) {
    }

    private static final List<Venue> ALL = List.of(
            new Venue("IEEE", "IEEE", "会议/期刊", "IEEEtran 双栏 Times，camera-ready 走 PDF eXpress。", "02-ieee.md"),
            new Venue("ACM", "ACM", "会议/期刊", "acmart / TAPS，CCS 概念；旧 Word 模板已停用。", "03-acm.md"),
            new Venue("Nature", "Nature / Nature Portfolio", "期刊", "字数紧、图终稿 ≥300 dpi；仅仿真通常不够。", "04-nature.md"),
            new Venue("Elsevier", "Elsevier", "期刊", "elsarticle / CAS；多数刊 Your Paper Your Way。", "05-elsevier.md"),
            new Venue("ACL", "ACL / *ACL", "会议", "A4 双栏 Times，Limitations 强制；EMNLP/NAACL 同族。", "06-acl.md"),
            new Venue("EMNLP", "EMNLP", "会议", "使用 ACL 官方样式文件，A4 双栏。", "06-acl.md"),
            new Venue("NAACL", "NAACL", "会议", "使用 ACL 官方样式文件，A4 双栏。", "06-acl.md"),
            new Venue("NeurIPS", "NeurIPS", "会议", "US Letter 单栏，仅官方 LaTeX 生成 PDF。", "07-neurips.md"),
            new Venue("Springer", "Springer Nature", "期刊", "sn-jnl / sn-article；实验要求以目标刊为准。", "08-springer.md"),
            new Venue("ICML", "ICML", "会议", "主文 8 页、Times 10pt、Type-1 字体，Broader Impact。", "14-icml.md"),
            new Venue("ICLR", "ICLR", "会议", "主文 9 页；LLM 实质性写作须披露。", "15-iclr.md"),
            new Venue("AAAI", "AAAI", "会议", "US Letter；正文禁止 Computer Modern。", "16-aaai.md"),
            new Venue("COLM", "COLM", "会议", "类 ICLR 模板，面向语言模型。", "17-colm.md"),
            new Venue("OSDI", "OSDI", "系统会议", "必须有可运行实现；纯仿真通常不够。", "23-skill-systems.md"),
            new Venue("SOSP", "SOSP", "系统会议", "真实负载与实现优先于思想实验。", "23-skill-systems.md"),
            new Venue("NSDI", "NSDI", "系统会议", "网络系统，强调端到端与真实工作负载。", "23-skill-systems.md"),
            new Venue("ASPLOS", "ASPLOS", "系统会议", "体系结构与系统交叉，需实现与评测。", "23-skill-systems.md")
    );

    private VenueCatalog() {
    }

    public static List<Venue> all() {
        return ALL;
    }

    public static List<Venue> search(String q) {
        String needle = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return ALL;
        }
        List<Venue> out = new ArrayList<>();
        for (Venue v : ALL) {
            String blob = (v.id() + " " + v.name() + " " + v.family() + " " + v.summary()).toLowerCase(Locale.ROOT);
            if (blob.contains(needle)) {
                out.add(v);
            }
        }
        return out;
    }

    /** 目录 id 或别名 → 规范刊名；未指定返回 null。 */
    public static String canonical(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.isEmpty() || "UNSPECIFIED".equalsIgnoreCase(s) || "未指定".equals(s)) {
            return null;
        }
        for (Venue v : ALL) {
            if (v.id().equalsIgnoreCase(s) || v.name().equalsIgnoreCase(s)) {
                return v.id();
            }
        }
        return VenueQuery.matchKnown(s);
    }

    public static boolean known(String raw) {
        return canonical(raw) != null;
    }
}
