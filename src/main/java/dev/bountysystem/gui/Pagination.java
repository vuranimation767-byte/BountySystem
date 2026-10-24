package dev.bountysystem.gui;

import java.util.List;

/** Pure pagination math — unit-testable without any Bukkit runtime. */
public final class Pagination {

    private Pagination() {
    }

    /** The slice for {@code page} (1-based, clamped into range). */
    public static <T> List<T> page(List<T> items, int page, int pageSize) {
        if (items.isEmpty() || pageSize <= 0) {
            return List.of();
        }
        int pages = pageCount(items.size(), pageSize);
        int safe = clampPage(page, items.size(), pageSize);
        int from = (safe - 1) * pageSize;
        int to = Math.min(items.size(), from + pageSize);
        return items.subList(from, to);
    }

    /** Number of pages for the given total (an empty list still shows page 1). */
    public static int pageCount(int total, int pageSize) {
        if (total <= 0 || pageSize <= 0) {
            return 1;
        }
        return (total + pageSize - 1) / pageSize;
    }

    /** 1-based page clamped to 1…pageCount. */
    public static int clampPage(int page, int total, int pageSize) {
        return Math.max(1, Math.min(page, pageCount(total, pageSize)));
    }
}
