package com.hnp.filemanagement.content;

import java.util.ArrayList;
import java.util.List;

/**
 * A few lines of a page around the words a search found, those words marked (roadmap 11, "Where in
 * the file"). The words are found in the page's folded text ({@link ContentFolding}) - so {@code 1403}
 * marks {@code ۱۴۰۳} and {@code کتاب} marks {@code كتاب‌ها} - and the snippet is cut from the text as
 * it was read, through the folded text's map back to it.
 *
 * <p>A snippet is segments, each marked or not, never HTML: the page writes each with {@code th:text},
 * so a document's text can never become markup on it.
 */
public final class Snippets {

    /** Characters shown before the first word found, and in all. */
    static final int BEFORE = 60;
    static final int LENGTH = 240;

    private Snippets() {
    }

    /** A piece of a snippet, and whether it is a word the search found. */
    public record Segment(String text, boolean match) {
    }

    /** A snippet: its segments, and whether the text goes on before and after it. */
    public record Snippet(List<Segment> segments, boolean before, boolean after) {

        /** Its text, unmarked. */
        public String text() {
            StringBuilder text = new StringBuilder();
            segments.forEach(segment -> text.append(segment.text()));
            return text.toString();
        }
    }

    /** The snippet of a page for these words: around the first one found, or the page's beginning if none is. */
    public static Snippet of(String text, List<ContentQuery.Term> terms) {
        if (text == null || text.isEmpty()) {
            return new Snippet(List.of(), false, false);
        }
        ContentFolding.Folded folded = ContentFolding.foldMapped(text);
        List<int[]> matches = matches(text, folded, terms);

        int start = matches.isEmpty() ? 0 : Math.max(0, matches.getFirst()[0] - BEFORE);
        start = wordStart(text, start);
        int end = Math.min(text.length(), start + LENGTH);
        end = wordEnd(text, end);

        List<Segment> segments = new ArrayList<>();
        int at = start;
        for (int[] match : matches) {
            if (match[0] < at || match[1] > end) {
                continue;
            }
            if (match[0] > at) {
                segments.add(new Segment(flat(text.substring(at, match[0])), false));
            }
            segments.add(new Segment(flat(text.substring(match[0], match[1])), true));
            at = match[1];
        }
        if (at < end) {
            segments.add(new Segment(flat(text.substring(at, end)), false));
        }
        return new Snippet(segments, start > 0, end < text.length());
    }

    /**
     * Where in {@code text} the words are, as [start, end) of the original: a folded word that is a
     * term, or begins with one that is a prefix, marked whole.
     */
    private static List<int[]> matches(String text, ContentFolding.Folded folded, List<ContentQuery.Term> terms) {
        List<int[]> found = new ArrayList<>();
        String f = folded.text();
        int[] origin = folded.origin();
        int i = 0;
        while (i < f.length()) {
            int end = f.indexOf(' ', i);
            if (end < 0) {
                end = f.length();
            }
            String word = f.substring(i, end);
            for (ContentQuery.Term term : terms) {
                if (term.prefix() ? word.startsWith(term.folded()) : word.equals(term.folded())) {
                    int from = origin[i];
                    int to = end < f.length() ? endOfOriginal(text, origin[end - 1]) : endOfOriginal(text, origin[f.length() - 1]);
                    found.add(new int[]{from, to});
                    break;
                }
            }
            i = end + 1;
        }
        return found;
    }

    /** The end of the character of the original at {@code index}, and of what joins it (marks, the half-space). */
    private static int endOfOriginal(String text, int index) {
        int end = index + Character.charCount(text.codePointAt(index));
        while (end < text.length()) {
            int next = text.codePointAt(end);
            int type = Character.getType(next);
            if (type != Character.NON_SPACING_MARK && type != Character.ENCLOSING_MARK && type != Character.FORMAT) {
                break;
            }
            end += Character.charCount(next);
        }
        return end;
    }

    /** Back to the start of the word {@code index} is in, so a snippet never begins mid-word. */
    private static int wordStart(String text, int index) {
        int i = index;
        while (i > 0 && i > index - 20 && !Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        if (i > 0 && Character.isLowSurrogate(text.charAt(i))) {
            i--;
        }
        return i;
    }

    /** On to the end of the word {@code index} is in. */
    private static int wordEnd(String text, int index) {
        int i = index;
        while (i < text.length() && i < index + 20 && !Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i < text.length() && i > 0 && Character.isHighSurrogate(text.charAt(i - 1))) {
            i++;
        }
        return i;
    }

    /** Line breaks as spaces: a snippet is one line. */
    private static String flat(String text) {
        return text.replace('\n', ' ');
    }
}
