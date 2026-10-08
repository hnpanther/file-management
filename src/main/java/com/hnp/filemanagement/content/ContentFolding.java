package com.hnp.filemanagement.content;

import com.hnp.filemanagement.shared.util.SearchKey;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;

/**
 * The text of a file as its search compares it (roadmap 11): folded as {@link SearchKey} folds a
 * name - compatibility decomposition, the marks and format characters dropped (the half-space among
 * them, so {@code می‌روم} is {@code میروم}), every digit to ASCII, Arabic yeh and kaf to Persian, upper
 * case - and then reduced to its words: every character that is neither a letter nor a digit becomes
 * a space. So {@code PMP-1201} is two words, {@code PMP 1201}, on the page and in a query alike, and
 * PostgreSQL's parser, whatever the database's locale, meets only letters, digits and single spaces.
 *
 * <p>Folded a code point at a time, each folded character remembering the character of the text it
 * came from ({@link Folded#origin}), so that a match found in the folded text is marked in the text as
 * it was read ({@link Snippets}). A string of letters, digits and spaces folds to exactly what
 * {@link SearchKey#of} makes of it - {@code ContentFoldingTest} holds the two to that.
 */
public final class ContentFolding {

    private static final int ARABIC_TATWEEL = 0x0640;

    private ContentFolding() {
    }

    /**
     * Folded text, and for each of its characters the index in the original of the character it
     * came from.
     */
    public record Folded(String text, int[] origin) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Folded that && text.equals(that.text) && Arrays.equals(origin, that.origin);
        }

        @Override
        public int hashCode() {
            return text.hashCode();
        }

        @Override
        public String toString() {
            return "Folded[" + text.length() + " characters]";
        }
    }

    /** The folded words of {@code text}, single spaces between them, none at the ends; "" for null. */
    public static String fold(String text) {
        return foldWithOrigin(text, false).text();
    }

    /** {@link #fold}, with where each folded character came from. */
    public static Folded foldMapped(String text) {
        return foldWithOrigin(text, true);
    }

    private static Folded foldWithOrigin(String text, boolean mapped) {
        if (text == null || text.isEmpty()) {
            return new Folded("", new int[0]);
        }
        StringBuilder folded = new StringBuilder(text.length());
        int[] origin = mapped ? new int[Math.max(16, text.length())] : null;
        boolean pendingSpace = false;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int at = i;
            i += Character.charCount(codePoint);
            String decomposed = Normalizer.normalize(new String(Character.toChars(codePoint)), Normalizer.Form.NFKD);
            for (int j = 0; j < decomposed.length(); ) {
                int part = decomposed.codePointAt(j);
                j += Character.charCount(part);
                // Whitespace before what is dropped, as SearchKey asks: a line break and a tab are
                // control characters, and dropping them would join a line's last word to the next's first.
                if (Character.isWhitespace(part) || Character.isSpaceChar(part)) {
                    pendingSpace = folded.length() > 0;
                    continue;
                }
                if (isDropped(part)) {
                    continue;
                }
                int mappedPart = mapped(part);
                if (!Character.isLetterOrDigit(mappedPart)) {
                    pendingSpace = folded.length() > 0;
                    continue;
                }
                if (pendingSpace) {
                    origin = append(folded, origin, ' ', at, mapped);
                    pendingSpace = false;
                }
                String upper = new String(Character.toChars(mappedPart)).toUpperCase(Locale.ROOT);
                for (int k = 0; k < upper.length(); k++) {
                    origin = append(folded, origin, upper.charAt(k), at, mapped);
                }
            }
        }
        return new Folded(folded.toString(), mapped ? Arrays.copyOf(origin, folded.length()) : new int[0]);
    }

    private static int[] append(StringBuilder folded, int[] origin, char c, int at, boolean mapped) {
        if (mapped) {
            if (folded.length() == origin.length) {
                origin = Arrays.copyOf(origin, origin.length * 2);
            }
            origin[folded.length()] = at;
        }
        folded.append(c);
        return origin;
    }

    /** What {@link SearchKey} drops: marks, format characters (the half-space), controls, the tatweel. */
    private static boolean isDropped(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT, Character.CONTROL -> true;
            default -> codePoint == ARABIC_TATWEEL;
        };
    }

    /** What {@link SearchKey} maps: every digit to ASCII, Arabic yeh, alef maksura and kaf to Persian. */
    private static int mapped(int codePoint) {
        if (Character.getType(codePoint) == Character.DECIMAL_DIGIT_NUMBER) {
            return '0' + Character.digit(codePoint, 10);
        }
        return switch (codePoint) {
            case 0x064A, 0x0649 -> 0x06CC;
            case 0x0643 -> 0x06A9;
            default -> codePoint;
        };
    }
}
