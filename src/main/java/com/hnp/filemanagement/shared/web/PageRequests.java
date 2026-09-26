package com.hnp.filemanagement.shared.web;

/**
 * The page a list screen was asked for, made safe to hand to a query.
 *
 * <p>{@code page-size} and {@code page-number} come from the URL, and anybody can edit a URL.
 * Spring's {@code PageRequest.of} throws on a size below one or a negative number - a 500 for a
 * typo - and would otherwise run a query for as many rows as the URL names. So a missing or
 * non-positive size is the screen's default, a larger one than {@link #MAX_PAGE_SIZE} is clamped
 * to it, and a missing or negative page number is the first page.
 */
public final class PageRequests {

    /** The most rows one page of any list may hold; a larger size is clamped, not refused. */
    public static final int MAX_PAGE_SIZE = 200;

    private PageRequests() {
    }

    /** The rows per page to ask for: {@code fallback} when missing or below one, never above the cap. */
    public static int size(Integer requested, int fallback) {
        int size = requested == null || requested < 1 ? fallback : requested;
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /** The zero-based page to ask for: the first when missing or negative. */
    public static int number(Integer requested) {
        return requested == null ? 0 : Math.max(requested, 0);
    }
}
