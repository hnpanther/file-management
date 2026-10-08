package com.hnp.filemanagement.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Whether a page's text layer can be trusted (roadmap 11, "Measured again on fifteen real files",
 * 2026-10-08): the share of its words that are common function words - {@code از}, {@code در},
 * {@code به}, {@code the}, {@code of} ... - counted as stored and with each Persian word reversed.
 *
 * <table>
 *   <tr><th>Page</th><th>as stored</th><th>reversed</th></tr>
 *   <tr><td>sound prose</td><td>14-27%</td><td>0%</td></tr>
 *   <tr><td>a sound invoice, mostly numbers</td><td>3%</td><td>0%</td></tr>
 *   <tr><td>a scanner's garbage text layer</td><td>0%</td><td>0%</td></tr>
 *   <tr><td>a letter stored reversed</td><td>0-1%</td><td>7-13%</td></tr>
 * </table>
 *
 * <p>Only words with a letter count - a number is neither. So: <b>reversed</b> when the reversed share is at least 3% and three times the stored one - put
 * right by reversing each Persian word and each line's order ({@link #repaired}); <b>garbage</b> when
 * a page of at least 50 words has under 1% either way - read again by OCR. Anything else is sound,
 * a page of tables and codes among them. Palindromes ({@code و}, {@code اا}) count in neither share:
 * they read the same both ways and would make a reversed page look sound.
 */
public final class TextQuality {

    /** What a page's text layer is judged to be. */
    public enum Verdict { SOUND, REVERSED, GARBAGE }

    /** A page's verdict, its words and the two shares, in thousandths. */
    public record Score(Verdict verdict, int words, int perMille, int reversedPerMille) {
    }

    static final int MIN_WORDS_FOR_GARBAGE = 50;
    static final int MIN_WORDS_FOR_REVERSED = 10;
    private static final int GARBAGE_BELOW_PER_MILLE = 10;
    private static final int REVERSED_AT_LEAST_PER_MILLE = 30;
    private static final int REVERSED_TIMES = 3;

    private static final Set<String> PERSIAN = folded(Stream.of(
            "از", "در", "به", "که", "را", "این", "با", "است", "برای", "آن", "یک", "تا", "می", "شود", "ها", "های",
            "بر", "هم", "نیز", "یا", "اگر", "باید", "شده", "شد", "کرد", "کند", "دارد", "بود", "ای", "پس", "هر",
            "بین", "روی", "آنها", "خود", "سال", "شرکت", "شماره", "تاریخ", "مورد", "جهت", "طبق", "اساس", "گردد",
            "نمود", "باشد", "کنید", "بوده", "توسط", "بنابراین", "ولی", "اما", "چون"));
    private static final Set<String> ENGLISH = folded(Stream.of(
            "the", "of", "and", "to", "in", "for", "is", "on", "with", "by", "as", "at", "from", "this", "that", "be",
            "are", "or", "an", "it", "was", "will", "not", "has"));

    private TextQuality() {
    }

    /** The verdict on one page's text layer. */
    public static Score score(String text) {
        String folded = ContentFolding.fold(text);
        if (folded.isEmpty()) {
            return new Score(Verdict.SOUND, 0, 0, 0);
        }
        // Words with a letter in them, as measured: a number is neither a function word nor garbage, and a
        // page of tables would otherwise look like one with no words at all.
        String[] words = java.util.Arrays.stream(folded.split(" "))
                .filter(word -> word.codePoints().anyMatch(Character::isLetter)).toArray(String[]::new);
        if (words.length == 0) {
            return new Score(Verdict.SOUND, 0, 0, 0);
        }
        int known = 0;
        int reversed = 0;
        for (String word : words) {
            String backwards = reverse(word);
            if (backwards.equals(word)) {
                continue;
            }
            if (PERSIAN.contains(word) || ENGLISH.contains(word)) {
                known++;
            }
            if (isPersian(word) && PERSIAN.contains(backwards)) {
                reversed++;
            }
        }
        int perMille = known * 1000 / words.length;
        int reversedPerMille = reversed * 1000 / words.length;
        Verdict verdict;
        if (words.length >= MIN_WORDS_FOR_REVERSED && reversedPerMille >= REVERSED_AT_LEAST_PER_MILLE
                && reversedPerMille >= REVERSED_TIMES * perMille) {
            verdict = Verdict.REVERSED;
        } else if (words.length >= MIN_WORDS_FOR_GARBAGE && perMille < GARBAGE_BELOW_PER_MILLE
                && reversedPerMille < GARBAGE_BELOW_PER_MILLE) {
            verdict = Verdict.GARBAGE;
        } else {
            verdict = Verdict.SOUND;
        }
        return new Score(verdict, words.length, perMille, reversedPerMille);
    }

    /**
     * A text layer stored reversed, put right: on each line the words in the other order, and each
     * word with a right-to-left letter in it reversed - a number or a Latin code kept as it is, as it
     * was the right way round already. (The {@code لا} ligature, stored as one glyph, comes back as
     * {@code ال}: a word or two a page, which OCR would not do better.)
     */
    public static String repaired(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            List<String> words = new ArrayList<>(List.of(line.strip().split("\\s+")));
            Collections.reverse(words);
            lines.add(words.stream().map(word -> hasRightToLeft(word) ? reverse(word) : word).collect(Collectors.joining(" ")));
        }
        return String.join("\n", lines);
    }

    private static boolean isPersian(String word) {
        return word.codePoints().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.ARABIC);
    }

    private static boolean hasRightToLeft(String word) {
        return word.codePoints().anyMatch(c -> {
            byte direction = Character.getDirectionality(c);
            return direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC;
        });
    }

    private static String reverse(String word) {
        return new StringBuilder(word).reverse().toString();
    }

    private static Set<String> folded(Stream<String> words) {
        return words.map(ContentFolding::fold).collect(Collectors.toUnmodifiableSet());
    }
}
