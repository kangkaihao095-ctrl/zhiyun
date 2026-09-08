package com.zhiyun.workflow;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 按 RevisionPatch.location 的 offset 落补丁；offset 无效或不匹配时，对 originalText 做一次 indexOf。
 * 不用 String.replace 全量替换。多处补丁先在原文上解析，再从后往前写入，避免位移。
 */
public final class PatchApplier {
    private PatchApplier() {
    }

    public record Spec(int startOffset, int endOffset, String original, String proposed) {
        public static Spec of(String original, String proposed) {
            return new Spec(-1, -1, original == null ? "" : original, proposed == null ? "" : proposed);
        }
    }

    public record Result(String text, int applied, List<String> skipped) {
    }

    public static String applyOnce(String text, Spec spec) {
        Result result = applyAll(text, List.of(spec));
        return result.applied() == 0 ? null : result.text();
    }

    public static Result applyAll(String text, List<Spec> specs) {
        String src = text == null ? "" : text;
        if (specs == null || specs.isEmpty()) {
            return new Result(src, 0, List.of());
        }
        List<Resolved> resolved = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Spec spec : specs) {
            Resolved hit = resolve(src, spec);
            if (hit == null) {
                skipped.add(spec.original() == null ? "" : spec.original());
                continue;
            }
            resolved.add(hit);
        }
        resolved.sort(Comparator.comparingInt(Resolved::start).reversed());
        String out = src;
        int applied = 0;
        for (Resolved hit : resolved) {
            if (hit.start() < 0 || hit.end() > out.length() || hit.start() > hit.end()) {
                skipped.add(hit.original());
                continue;
            }
            out = out.substring(0, hit.start()) + hit.proposed() + out.substring(hit.end());
            applied++;
        }
        return new Result(out, applied, skipped);
    }

    public static Spec fromJson(JsonNode patch) {
        String original = firstText(patch, "originalText", "original_text", "original", "oldText", "old_text", "before");
        String proposed = firstText(patch, "proposedText", "proposed_text", "proposed", "newText", "new_text", "after");
        int start = -1;
        int end = -1;
        JsonNode loc = patch == null ? null : patch.get("location");
        if (loc != null && loc.isObject()) {
            start = intOf(loc, "startOffset", "start_offset");
            end = intOf(loc, "endOffset", "end_offset");
        }
        return new Spec(start, end, original, proposed);
    }

    static Resolved resolve(String text, Spec spec) {
        String original = spec.original() == null ? "" : spec.original();
        String proposed = spec.proposed() == null ? "" : spec.proposed();
        int start = spec.startOffset();
        int end = spec.endOffset();
        if (start >= 0 && end >= start && end <= text.length()) {
            String slice = text.substring(start, end);
            if (original.isBlank() || slice.equals(original)) {
                return new Resolved(start, end, original, proposed);
            }
        }
        if (original.isBlank()) {
            return null;
        }
        int i = text.indexOf(original);
        if (i < 0) {
            return null;
        }
        return new Resolved(i, i + original.length(), original, proposed);
    }

    record Resolved(int start, int end, String original, String proposed) {
    }

    private static int intOf(JsonNode loc, String... keys) {
        for (String key : keys) {
            JsonNode node = loc.get(key);
            if (node != null && node.isNumber()) {
                return node.asInt(-1);
            }
        }
        return -1;
    }

    static String firstText(JsonNode node, String... keys) {
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
}
