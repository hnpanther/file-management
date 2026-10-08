package com.hnp.filemanagement.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a person typed into "search in contents", as a search asks it (roadmap 11, "Searching: what a
 * query matches"): folded as the pages' text is folded ({@link ContentFolding}), each word a prefix -
 * {@code کتاب} finds {@code کتاب‌ها} and {@code کتابخانه} - every word required, a part in double
 * quotes a phrase, its words next to each other in that order.
 *
 * <p>The query PostgreSQL is given ({@link #tsquery()}) is built here from folded words alone -
 * letters and digits, each in quotes - so nothing typed reaches it as syntax. A word of one character
 * is matched whole, never as a prefix: {@code و:*} would be half of every Persian page.
 *
 * @param tsquery the text of a {@code to_tsquery('simple', ...)}
 * @param terms   the words, folded, for marking them in a snippet ({@link Snippets})
 */
public record ContentQuery(String tsquery, List<Term> terms) {

    /** The longest search accepted, in characters; the rest is not read. */
    public static final int MAX_LENGTH = 200;
    /** The most words a search asks for; the rest are not asked. */
    public static final int MAX_WORDS = 12;

    private static final Pattern PHRASE = Pattern.compile("\"([^\"]*)\"");

    /** A word asked for: its folded form, and whether a word beginning with it counts. */
    public record Term(String folded, boolean prefix) {
    }

    /** The query of what was typed; empty when nothing in it is a word. */
    public static Optional<ContentQuery> of(String typed) {
        if (typed == null || typed.isBlank()) {
            return Optional.empty();
        }
        String text = typed.length() > MAX_LENGTH ? typed.substring(0, MAX_LENGTH) : typed;
        List<List<String>> groups = new ArrayList<>();
        Matcher phrases = PHRASE.matcher(text);
        StringBuilder rest = new StringBuilder();
        int last = 0;
        while (phrases.find()) {
            rest.append(text, last, phrases.start()).append(' ');
            last = phrases.end();
            List<String> words = words(phrases.group(1));
            if (!words.isEmpty()) {
                groups.add(words);
            }
        }
        rest.append(text.substring(last).replace('"', ' '));
        for (String word : words(rest.toString())) {
            groups.add(List.of(word));
        }

        List<String> parts = new ArrayList<>();
        List<Term> terms = new ArrayList<>();
        int count = 0;
        for (List<String> group : groups) {
            List<String> lexemes = new ArrayList<>();
            for (String word : group) {
                if (count++ >= MAX_WORDS) {
                    break;
                }
                boolean prefix = word.codePointCount(0, word.length()) > 1;
                lexemes.add("'" + word + "'" + (prefix ? ":*" : ""));
                terms.add(new Term(word, prefix));
            }
            if (!lexemes.isEmpty()) {
                parts.add(lexemes.size() == 1 ? lexemes.getFirst() : "(" + String.join(" <-> ", lexemes) + ")");
            }
        }
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ContentQuery(String.join(" & ", parts), List.copyOf(terms)));
    }

    /** The folded words of a part of the query - letters and digits only, so no quote or operator among them. */
    private static List<String> words(String part) {
        String folded = ContentFolding.fold(part);
        return folded.isEmpty() ? List.of() : List.of(folded.split(" "));
    }
}
