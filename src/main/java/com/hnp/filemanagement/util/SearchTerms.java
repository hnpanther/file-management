package com.hnp.filemanagement.util;

/**
 * How a search box turns into a query parameter.
 *
 * <p>Every list page in the application accepts an optional search term, and every one of the
 * repository queries is written as {@code (:search) IS NULL OR ... LIKE ...} so that an absent term
 * matches everything without a second query. That only works if "absent" reaches the query as
 * {@code null} — an empty or all-whitespace string would be matched literally.
 *
 * <p>Each service used to do this inline, and they did not agree: some checked
 * {@code isEmpty() || isBlank()}, some only {@code isEmpty()}, and the user list page checked one
 * way for its rows and another for its count, so the pager could contradict the list it paged.
 */
public final class SearchTerms {

    private SearchTerms() {
    }

    /** The term, or {@code null} when the box was empty or held only whitespace. */
    public static String blankToNull(String search) {
        return (search == null || search.isBlank()) ? null : search.trim();
    }
}
