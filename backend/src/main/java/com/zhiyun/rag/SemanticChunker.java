package com.zhiyun.rag;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class SemanticChunker {
    private static final Pattern HEADING = Pattern.compile("(?m)^(#{1,3}\\s+.+|[A-Z][A-Za-z ]{3,40}\\n)");

    public List<Chunk> split(String text) {
        String normalized = text.replace("\r\n", "\n");
        String[] parts = normalized.split("(?m)(?=^#{1,3} )|(?=^(Abstract|Introduction|Related Work|Method|Methods|Experiments|Results|Discussion|Conclusion|References)\\s*$)");
        List<Chunk> chunks = new ArrayList<>();
        int idx = 0;
        for (String part : parts) {
            String block = part.trim();
            if (block.isEmpty()) {
                continue;
            }
            String section = detectSection(block);
            if (block.length() > 2400) {
                for (String para : block.split("\\n\\n+")) {
                    if (para.trim().length() < 40) {
                        continue;
                    }
                    chunks.add(new Chunk("c" + (++idx), section, para.trim(), sha(para.trim())));
                }
            } else {
                chunks.add(new Chunk("c" + (++idx), section, block, sha(block)));
            }
        }
        if (chunks.isEmpty()) {
            chunks.add(new Chunk("c1", "body", normalized, sha(normalized)));
        }
        return chunks;
    }

    private String detectSection(String block) {
        String first = block.lines().findFirst().orElse("body").replace("#", "").trim();
        String key = first.toLowerCase(Locale.ROOT);
        if (key.contains("abstract")) return "abstract";
        if (key.contains("intro")) return "introduction";
        if (key.contains("method")) return "method";
        if (key.contains("experiment") || key.contains("result")) return "experiments";
        if (key.contains("conclu")) return "conclusion";
        if (key.contains("reference")) return "references";
        if (key.contains("figure") || key.contains("table")) return "figures";
        return first.length() > 48 ? "body" : first;
    }

    public static String sha(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public record Chunk(String chunkId, String section, String content, String sha256) {
    }
}
