package com.hnp.filemanagement.shared.metadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * A metadata document (roadmap 12.2, 12.3): a JSON object with at least one key - never {@code {}},
 * which is no document - as checked by {@link MetadataRules} or as the database stored it.
 *
 * <p>Its {@code toString} says how large it is and never what it holds: metadata may be personal
 * (a name, a national code), and a document in a log line would put it there.
 *
 * @param json the document's text - compact JSON as checked, or {@code jsonb}'s own form as read
 */
public record MetadataDocument(String json) {

    public MetadataDocument {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("a metadata document has text");
        }
    }

    /** What a column holds: a document, or none for {@code null}. */
    public static Optional<MetadataDocument> ofStored(String json) {
        return json == null ? Optional.empty() : Optional.of(new MetadataDocument(json));
    }

    /** The column's value for an optional document: its text, or {@code null}. */
    public static String columnOf(Optional<MetadataDocument> document) {
        return document.map(MetadataDocument::json).orElse(null);
    }

    /**
     * The entity tag of a document, for {@code If-Match}: the first 32 hex digits of the SHA-256 of
     * its canonical form, quoted - keys sorted, no space ({@link MetadataRules#canonical}) - so one
     * document has one tag whether its text is the one sent or the one {@code jsonb} gives back, and
     * a change of any key or value changes it.
     */
    public String etag() {
        return etagOf(MetadataRules.canonical(this));
    }

    /** The entity tag of a document, or of none ({@code "none"} quoted - no document's tag is that). */
    public static String etagOf(Optional<MetadataDocument> document) {
        return document.map(MetadataDocument::etag).orElse("\"none\"");
    }

    private static String etagOf(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return "\"" + HexFormat.of().formatHex(digest).substring(0, 32) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    /** Its size as UTF-8 - what a log line may say of it. */
    public int bytes() {
        return json.getBytes(StandardCharsets.UTF_8).length;
    }

    @Override
    public String toString() {
        return "MetadataDocument[" + bytes() + " bytes]";
    }
}
