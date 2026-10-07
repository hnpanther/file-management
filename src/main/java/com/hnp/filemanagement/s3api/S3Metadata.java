package com.hnp.filemanagement.s3api;

import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.metadata.MetadataDocument;
import com.hnp.filemanagement.shared.metadata.MetadataRules;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An object's metadata on the S3 surface (roadmap 12.2 step 4, 2.13.0) - S3's user metadata, kept as
 * the revision's document:
 *
 * <ul>
 *   <li><b>Sent</b> as {@code x-amz-meta-{name}: value} headers, one key each, the name lower-cased as
 *       S3 keeps it: {@code x-amz-meta-contract-no: C-5678} is {@code {"contract-no": "C-5678"}}. A
 *       value that is not ASCII is sent as RFC 2047 says - {@code =?UTF-8?B?...?=} or
 *       {@code =?UTF-8?Q?...?=} - as S3 asks of it, and stored decoded. At most 2 KB of names and
 *       values together, S3's own limit ({@code MetadataTooLarge}). For a document S3's flat strings
 *       cannot say - nested, typed - one header of this server's own: {@code x-fm-metadata}, the JSON
 *       base64-encoded; not with {@code x-amz-meta-*} beside it.</li>
 *   <li><b>Answered</b> on {@code GET} and {@code HEAD}: each key whose value is a string, a number or
 *       a boolean and whose name an HTTP header can carry, as {@code x-amz-meta-*}, up to 2 KB of header lines and
 *       {@value #MAX_ANSWERED_KEYS} keys, a
 *       value not ASCII in RFC 2047's {@code B} form; how many keys were left out, in S3's own
 *       {@code x-amz-missing-meta}; and then the whole document in {@code x-fm-metadata} too, when it
 *       fits in 4 KB - beyond that it is read through v1.</li>
 * </ul>
 */
final class S3Metadata {

    static final String PREFIX = "x-amz-meta-";
    static final String DOCUMENT_HEADER = "x-fm-metadata";
    /** S3's limit on the names and values of the user metadata of one object, as UTF-8. */
    static final int MAX_HEADER_BYTES = 2048;
    /** The largest {@code x-fm-metadata} answered - response headers are not without bound. */
    static final int MAX_DOCUMENT_HEADER = 4096;
    /**
     * The most {@code x-amz-meta-*} headers one answer carries. A document set through v1 may hold
     * hundreds of short keys - within S3's 2 KB of names and values, but as many header lines as an
     * HTTP client takes (the AWS SDK refuses an answer of a hundred or more) and more bytes than the
     * server sends (8 KB of headers). What is left out is counted in {@code x-amz-missing-meta}.
     */
    static final int MAX_ANSWERED_KEYS = 50;

    private static final Pattern ENCODED_WORD = Pattern.compile("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=");
    private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+");

    /** Thrown for user metadata above S3's limit - S3's {@code MetadataTooLarge}. */
    static final class TooLarge extends RuntimeException {
        TooLarge(String message) {
            super(message);
        }
    }

    private S3Metadata() {
    }

    /**
     * The document a {@code PUT} carries, checked as every document is ({@link MetadataRules}), as
     * compact JSON; empty for none.
     */
    static Optional<String> ofRequest(HttpServletRequest request, MetadataRules rules) {
        Map<String, String> values = new LinkedHashMap<>();
        int bytes = 0;
        for (String header : Collections.list(request.getHeaderNames())) {
            if (!header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
                continue;
            }
            String name = header.substring(PREFIX.length()).toLowerCase(Locale.ROOT);
            String value = String.join(",", Collections.list(request.getHeaders(header)).stream()
                    .map(S3Metadata::decoded).toList());
            if (name.isEmpty()) {
                throw new InvalidDataException("an x-amz-meta- header without a name");
            }
            bytes += name.getBytes(StandardCharsets.UTF_8).length + value.getBytes(StandardCharsets.UTF_8).length;
            values.merge(name, value, (a, b) -> a + "," + b);
        }
        if (bytes > MAX_HEADER_BYTES) {
            throw new TooLarge("user metadata of " + bytes + " bytes, above S3's " + MAX_HEADER_BYTES);
        }
        String document = request.getHeader(DOCUMENT_HEADER);
        if (document != null) {
            if (!values.isEmpty()) {
                throw new InvalidDataException(DOCUMENT_HEADER + " and x-amz-meta-* together: one or the other");
            }
            String json;
            try {
                json = new String(Base64.getDecoder().decode(document.trim()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new InvalidDataException(DOCUMENT_HEADER + " is not base64");
            }
            return rules.parse(json).map(MetadataDocument::json);
        }
        return rules.ofStrings(values).map(MetadataDocument::json);
    }

    /** {@code x-amz-meta-*}, {@code x-amz-missing-meta} and {@code x-fm-metadata} for a stored document. */
    static void answer(ResponseEntity.HeadersBuilder<?> response, String stored) {
        if (stored == null) {
            return;
        }
        JsonNode tree = MetadataRules.treeOf(new MetadataDocument(stored));
        List<String> sent = new ArrayList<>();
        int bytes = 0;
        int missing = 0;
        for (Map.Entry<String, JsonNode> entry : tree.properties()) {
            String name = entry.getKey();
            JsonNode value = entry.getValue();
            String text = value.isString() ? value.stringValue()
                    : value.isNumber() || value.isBoolean() ? value.asString() : null;
            String lower = name.toLowerCase(Locale.ROOT);
            if (text == null || !TOKEN.matcher(name).matches() || sent.contains(lower)) {
                missing++;
                continue;
            }
            // The whole line as it goes out - the prefix, the name, the value as encoded, ": " and CRLF.
            String line = encoded(text);
            int size = PREFIX.length() + name.length() + line.length() + 4;
            if (sent.size() >= MAX_ANSWERED_KEYS || bytes + size > MAX_HEADER_BYTES) {
                missing++;
                continue;
            }
            bytes += size;
            sent.add(lower);
            response.header(PREFIX + name, line);
        }
        if (missing > 0) {
            response.header("x-amz-missing-meta", String.valueOf(missing));
            String whole = Base64.getEncoder().encodeToString(stored.getBytes(StandardCharsets.UTF_8));
            if (whole.length() <= MAX_DOCUMENT_HEADER) {
                response.header(DOCUMENT_HEADER, whole);
            }
        }
    }

    /**
     * A header's value as sent: an RFC 2047 encoded word decoded; anything else as it is. A value
     * beyond ASCII sent raw never gets here: the AWS SDKs send it as {@code ?}s, which its signature
     * does not match, and UTF-8 on the wire is refused by the request firewall - as S3 says, such a
     * value is RFC 2047, or the document goes whole in {@code x-fm-metadata}.
     */
    static String decoded(String value) {
        String trimmed = value.trim();
        Matcher word = ENCODED_WORD.matcher(trimmed);
        if (!word.matches() || !word.group(1).equalsIgnoreCase("UTF-8")) {
            return trimmed;
        }
        try {
            byte[] bytes = word.group(2).equalsIgnoreCase("B")
                    ? Base64.getDecoder().decode(word.group(3))
                    : quotedPrintable(word.group(3));
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new InvalidDataException("an x-amz-meta- value is not RFC 2047 as it says");
        }
    }

    /** A value for a header: as it is when ASCII, as an RFC 2047 {@code B} word otherwise - as S3 answers it. */
    static String encoded(String value) {
        boolean ascii = value.chars().allMatch(c -> c >= 0x20 && c < 0x7F);
        return ascii ? value
                : "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static byte[] quotedPrintable(String text) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '_') {
                out.write(' ');
            } else if (c == '=') {
                if (i + 2 >= text.length()) {
                    throw new IllegalArgumentException("a cut =XX");
                }
                out.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.write(c);
            }
        }
        return out.toByteArray();
    }
}
