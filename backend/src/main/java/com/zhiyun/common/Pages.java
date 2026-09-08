package com.zhiyun.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 列表分页与包含匹配（前模糊 + 后模糊）。 */
public final class Pages {
    public static final int DEFAULT_SIZE = 5;
    public static final int MAX_SIZE = 100;

    private Pages() {
    }

    public static int page(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    public static int size(Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    public static String q(String raw) {
        return raw == null ? "" : raw.trim();
    }

    public static boolean blank(String q) {
        return q(q).isEmpty();
    }

    /** JPQL 里用 0/1，避免 MySQL 把 `:flag = true` 判死。 */
    public static int flag(boolean value) {
        return value ? 1 : 0;
    }

    /** 给 LOCATE 用的小写关键词，不含 % 通配符。 */
    public static String needle(String raw) {
        return q(raw).toLowerCase(Locale.ROOT);
    }

    /** 大小写不敏感的包含匹配，等价于 %keyword%。 */
    public static String like(String raw) {
        String q = needle(raw);
        String escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    public static Pageable of(int page, int size) {
        return PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id"));
    }

    public static Map<String, Object> wrap(List<?> items, long total, int page, int size) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", total);
        out.put("page", page);
        out.put("size", size);
        return out;
    }

    /** Hibernate 对空 IN 列表不友好，无匹配码时塞一个不会命中的占位。 */
    public static List<String> orDummy(List<String> items) {
        return items == null || items.isEmpty() ? List.of("__none__") : items;
    }
}
