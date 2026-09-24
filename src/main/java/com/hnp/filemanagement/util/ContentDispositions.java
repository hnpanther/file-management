package com.hnp.filemanagement.util;

import org.springframework.http.ContentDisposition;

import java.nio.charset.StandardCharsets;

/**
 * The {@code Content-Disposition} of every download, built in one place.
 *
 * <p>A revision keeps the name it was uploaded with, and here that name is usually Persian.
 * Written straight into the header - {@code attachment; filename="گزارش.pdf"} - it is a header
 * Tomcat will not send: a response header may carry ISO-8859-1 only, so Tomcat logs
 * "has been removed from the response because it is invalid", drops the whole header and answers
 * without it, and the browser names the file after the last segment of the URL. Every such
 * download was saved as {@code download}, with no extension (issue 85).
 *
 * <p>What is built here is ASCII from end to end (RFC 6266): {@code filename*} carries the real
 * name as percent-encoded UTF-8 (RFC 8187), which every current browser prefers, and
 * {@code filename} a fallback with each non-ASCII character replaced by {@code _}, extension
 * intact, for a client that reads nothing else. A name that is ASCII already is unchanged in
 * {@code filename}, so an integration that reads that parameter sees what it always saw.
 *
 * <p>Never concatenate a file name into this header anywhere else.
 */
public final class ContentDispositions {

    private ContentDispositions() {
    }

    /** Save it, under its own name. */
    public static String attachment(String fileName) {
        return of(false, fileName);
    }

    /** Render it in the browser, or save it when {@code inline} is false - under its own name either way. */
    public static String of(boolean inline, String fileName) {
        ContentDisposition.Builder builder = inline ? ContentDisposition.inline() : ContentDisposition.attachment();
        return builder.filename(fileName, StandardCharsets.UTF_8).build().toString();
    }
}
