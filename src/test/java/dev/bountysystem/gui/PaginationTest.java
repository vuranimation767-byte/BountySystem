package dev.bountysystem.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Pagination math used by the 28-slot content areas of all list menus. */
class PaginationTest {

    private static final int PAGE_SIZE = 28;

    @Test
    void slicesPagesCorrectly() {
        List<Integer> items = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            items.add(i); // 60 items → 3 full pages (28 + 28 + 4)
        }
        assertEquals(3, Pagination.pageCount(60, PAGE_SIZE));
        assertEquals(items.subList(0, 28), Pagination.page(items, 1, PAGE_SIZE));
        assertEquals(items.subList(28, 56), Pagination.page(items, 2, PAGE_SIZE));
        assertEquals(items.subList(56, 60), Pagination.page(items, 3, PAGE_SIZE));
    }

    @Test
    void clampsOutOfRangePages() {
        List<Integer> items = List.of(1, 2, 3);
        assertEquals(1, Pagination.pageCount(3, PAGE_SIZE));
        assertEquals(items, Pagination.page(items, 0, PAGE_SIZE));    // underflow → first
        assertEquals(items, Pagination.page(items, 99, PAGE_SIZE));   // overflow → last valid
        assertEquals(List.of(1), Pagination.page(List.of(1), 0, PAGE_SIZE));
    }

    @Test
    void emptyListsRenderAsAnEmptyFirstPage() {
        assertEquals(1, Pagination.pageCount(0, PAGE_SIZE));
        assertEquals(List.of(), Pagination.page(List.of(), 1, PAGE_SIZE));
        assertEquals(List.of(), Pagination.page(List.of(), 7, PAGE_SIZE));
    }

    @Test
    void exactPageMultiplesHaveNoSpillPage() {
        assertEquals(2, Pagination.pageCount(56, PAGE_SIZE));
        assertEquals(1, Pagination.pageCount(28, PAGE_SIZE));
    }

    @Test
    void degeneratePageSizeIsSafe() {
        assertEquals(1, Pagination.pageCount(10, 0));
        assertEquals(List.of(), Pagination.page(List.of(1, 2, 3), 1, 0));
    }
}
