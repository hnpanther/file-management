package com.hnp.filemanagement.shared.util;

/**
 * How a search box turns into a query parameter.
 *
 * <p>The list pages accept an optional search term, and their repository queries are written as
 * {@code :search = '' OR ... LIKE ...} so that an absent term matches everything without a second
 * query. "Absent" therefore reaches the query as the <em>empty string</em>, never as {@code null}
 * ({@link #blankToEmpty}): a {@code null} bound into {@code LIKE CONCAT('%', :search, '%')} has no
 * type Hibernate can infer, and PostgreSQL refuses an untyped parameter there - every list page
 * with an empty box failed on it (issue 87). MySQL never minded, which is why nothing showed it.
 * The queries answer a {@code null} with no rows at all, so a caller that passes one is caught by
 * the tests rather than by an error page.
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

    /**
     * The term, or the empty string when the box was empty or held only whitespace - what a
     * {@code :search = '' OR ...} query takes, where the empty string means "everything".
     */
    public static String blankToEmpty(String search) {
        return (search == null || search.isBlank()) ? "" : search.trim();
    }

    /**
     * The term read as a file id, or {@code null} when it is not one.
     *
     * <p>A search box that also accepts an id is the only way to find a file whose label is shared
     * with another branch ({@code docs/issues.md}, issue 73), so "does this look like an id?" is
     * asked in more than one place and must be answered the same way in all of them.
     *
     * <p>Two things make it less obvious than it looks. {@code Character.isDigit} accepts
     * Persian-Indic digits — which this interface's users type — and {@code Integer.valueOf} cannot
     * parse them; and a run of ASCII digits can still be too long for an {@code int}. Both are "not
     * an id", not a search that fails: the term is then matched as text, which is what someone
     * typing a long number in a name almost certainly meant.
     */
    public static Integer asFileId(String term) {
        if (term == null || term.isBlank() || !term.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        try {
            return Integer.valueOf(term);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
