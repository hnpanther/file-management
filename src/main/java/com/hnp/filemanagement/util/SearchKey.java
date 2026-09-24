package com.hnp.filemanagement.util;

import java.text.Normalizer;
import java.util.Locale;

/**
 * The folded form of a name or a description that searching and name uniqueness compare, the
 * same way on MySQL and on PostgreSQL (issue 86, roadmap step 4).
 *
 * <p>MySQL's {@code utf8mb4_unicode_ci} collation treats {@code ۱۴۰۳} and {@code 1403} as one
 * string, and a word written with the half-space (U+200C, zero-width non-joiner) as the same word
 * without it. PostgreSQL does neither, so a person who types {@code 1403} would stop finding
 * {@code گزارش ۱۴۰۳} the day the database changed. Rather than depend on a collation, each searched
 * column has a folded copy beside it ({@code search_name}, {@code search_description},
 * {@code search_display_name}), written by the entity whenever the original is, and filled for the
 * rows that existed before by {@code V2_17__Fill_Search_Keys_And_External_Ids}. A search folds the term with
 * {@link #forSearch} and compares it with the folded column - plain text on both databases.
 *
 * <p>What the fold does, in order:
 * <ol>
 *   <li>compatibility decomposition (NFKD): Arabic presentation forms and full-width letters become
 *       the letters they draw, and an accented letter becomes the letter and its accent;</li>
 *   <li>drops the marks that decomposition separated (é is e; the Arabic short vowels, tanween,
 *       shadda and hamza above are dropped, so {@code مُحَمَّد} is {@code محمد}), the format
 *       characters (the half-space and its joiner, direction marks, a byte-order mark), the Arabic
 *       tatweel and control characters;</li>
 *   <li>every decimal digit - Persian, Arabic-Indic, or any other script's - becomes its ASCII digit;</li>
 *   <li>the Arabic {@code ي}, {@code ى} and {@code ك} become the Persian {@code ی} and {@code ک},
 *       which a person on an Arabic keyboard types without noticing; MySQL never folded these;</li>
 *   <li>whitespace of any kind becomes one space, and the ends are trimmed;</li>
 *   <li>upper case, in the root locale.</li>
 * </ol>
 *
 * <p>So {@code گزارش‌های ۱۴۰۳} and {@code گزارشهاي 1403} fold to the same key. A space stays a
 * space in the key, because a name's key is also what two names are compared by: {@code گزارش‌ها}
 * and {@code گزارشها} are one name, as they were on MySQL, but {@code report 1} and {@code report1}
 * remain two. Search is looser - {@link #forSearch} and the queries both drop the spaces, so
 * {@code گزارش ها} typed with a plain space finds the half-spaced name too.
 *
 * <p><b>Changing the fold changes every stored key.</b> A change here needs a migration that
 * recomputes the columns, as {@code V2_17} computed them; otherwise stored keys and folded terms
 * disagree and a search misses what it should find. {@code PersianNameFoldingTest} fails when a
 * stored key is not what this class computes.
 */
public final class SearchKey {

    /** Width of a {@code search_name} column: twice the 100 characters a name may have. */
    public static final int NAME_LENGTH = 200;

    /** Width of {@code folder.search_display_name}: twice the 200 of a label. */
    public static final int LABEL_LENGTH = 400;

    /** Width of a {@code search_description} column: twice the 1000 of a description. */
    public static final int DESCRIPTION_LENGTH = 2000;

    /**
     * A search term no stored key contains - a control character, which the fold drops - for a
     * query that must match on its id alone: an empty term would match every row through
     * {@code LIKE '%%'}. U+0001 rather than U+0000, which PostgreSQL refuses in text.
     */
    public static final String MATCHES_NOTHING = "\u0001";

    private static final char SPACE = ' ';
    private static final int ARABIC_TATWEEL = 0x0640;

    private SearchKey() {
    }

    /**
     * The key of a stored value, cut to {@code maxLength} characters - the width of its column.
     * Decomposition can lengthen a string (one ligature decomposes into up to eighteen letters),
     * and a key that does not fit would fail the write of the value it describes; losing the tail
     * of a pathological key costs a search at most, never an upload. Null for null.
     */
    public static String of(String value, int maxLength) {
        String key = of(value);
        if (key == null || key.length() <= maxLength) {
            return key;
        }
        // Never between the two halves of a surrogate pair. Counting UTF-16 units is the safe side
        // of the column's count of characters: the cut key has at most as many.
        int end = Character.isHighSurrogate(key.charAt(maxLength - 1)) ? maxLength - 1 : maxLength;
        return key.substring(0, end).stripTrailing();
    }

    /** The key of a value, uncut; null for null. */
    public static String of(String value) {
        if (value == null) {
            return null;
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD);
        StringBuilder folded = new StringBuilder(decomposed.length());
        boolean pendingSpace = false;
        for (int i = 0; i < decomposed.length(); ) {
            int codePoint = decomposed.codePointAt(i);
            i += Character.charCount(codePoint);

            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = folded.length() > 0;
                continue;
            }
            if (isDropped(codePoint)) {
                continue;
            }
            if (pendingSpace) {
                folded.append(SPACE);
                pendingSpace = false;
            }
            folded.appendCodePoint(mapped(codePoint));
        }
        return folded.toString().toUpperCase(Locale.ROOT);
    }

    /**
     * A search term as the queries compare it: folded, and without spaces - the queries drop the
     * spaces of the stored key too, so the half-space, a plain space and no space all meet. The
     * empty string, never null, for a term that folds to nothing: {@code :term = ''} is how the
     * queries say "everything" (issue 87).
     */
    public static String forSearch(String term) {
        String key = of(term);
        return key == null ? "" : key.replace(String.valueOf(SPACE), "");
    }

    private static boolean isDropped(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT, Character.CONTROL -> true;
            default -> codePoint == ARABIC_TATWEEL;
        };
    }

    private static int mapped(int codePoint) {
        if (Character.getType(codePoint) == Character.DECIMAL_DIGIT_NUMBER) {
            return '0' + Character.digit(codePoint, 10);
        }
        return switch (codePoint) {
            case 0x064A, 0x0649 -> 0x06CC; // Arabic yeh and alef maksura -> Persian yeh
            case 0x0643 -> 0x06A9;         // Arabic kaf -> Persian keheh
            default -> codePoint;
        };
    }
}
