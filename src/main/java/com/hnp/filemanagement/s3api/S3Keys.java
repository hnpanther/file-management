package com.hnp.filemanagement.s3api;

import java.util.ArrayList;
import java.util.List;

/**
 * Keys as S3 orders them, in Java: by the bytes of their UTF-8 - which is the order of their code
 * points, and PostgreSQL's {@code COLLATE "C"}. Not {@link String#compareTo}, which compares UTF-16
 * units and puts a character beyond U+FFFF (a surrogate pair, U+D800 on) before U+E000 - U+FFFF.
 */
final class S3Keys {

    private S3Keys() {
    }

    /** Negative, zero or positive as {@code a} sorts before, with or after {@code b} in S3's order. */
    static int compare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int x = a.codePointAt(i);
            int y = b.codePointAt(j);
            if (x != y) {
                return Integer.compare(x, y);
            }
            i += Character.charCount(x);
            j += Character.charCount(y);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    /**
     * The key paths of the folders a key could lie in: {@code ''} - the bucket's own - and each part
     * of it up to and with a {@code /}: {@code a/b/c.pdf} gives {@code ''}, {@code a/}, {@code a/b/}.
     */
    static List<String> ancestorsOf(String key) {
        List<String> paths = new ArrayList<>();
        paths.add("");
        for (int i = key.indexOf('/'); i >= 0; i = key.indexOf('/', i + 1)) {
            paths.add(key.substring(0, i + 1));
        }
        return paths;
    }
}
