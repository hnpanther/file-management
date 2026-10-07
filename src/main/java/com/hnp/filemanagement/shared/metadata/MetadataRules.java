package com.hnp.filemanagement.shared.metadata;

import com.hnp.filemanagement.shared.exception.InvalidDataException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * What a metadata document must be (roadmap 12.2) - asked of every one before it is stored, from
 * every route: an upload's, v1's, the file and folder pages', the S3 surface's headers.
 *
 * <ul>
 *   <li>a JSON <b>object</b> at the top - not an array, a string or a number; {@code null}, blank
 *       and {@code {}} are no document at all;</li>
 *   <li>at most {@code filemanagement.metadata.max-bytes} as compact UTF-8, and at most
 *       {@code max-depth} levels of objects and arrays, the document itself the first;</li>
 *   <li>every key, at any depth, 1 to 100 characters and no control character; a key given twice
 *       refused rather than one of them silently kept;</li>
 *   <li>no character PostgreSQL's {@code jsonb} cannot hold - U+0000 - and no half of a surrogate
 *       pair, in a key or a value;</li>
 *   <li>values anything JSON has; a number kept as written, never rounded through a double.</li>
 * </ul>
 *
 * <p>A refusal names the rule and never the content - a key or a value may be personal - as an
 * {@link InvalidDataException} with a {@code metadata.invalid.*} message code.
 */
@Component
public class MetadataRules {

    /** The longest key, in characters. */
    public static final int MAX_KEY_LENGTH = 100;

    /**
     * The longest number, written out in full as {@code jsonb} writes it. An exponent is short on the
     * way in - {@code 1e999999999} is eleven characters - and a billion digits on the way out: past
     * this a number is refused before it is ever written out, or it would cost the memory to write it
     * and the column the room to hold it.
     */
    public static final int MAX_NUMBER_LENGTH = 1000;

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            // Written out as jsonb writes a number (1e2 is 100): the size checked is the size stored.
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private final int maxBytes;
    private final int maxDepth;

    public MetadataRules(MetadataProperties properties) {
        this.maxBytes = properties.maxBytes();
        this.maxDepth = properties.maxDepth();
    }

    public int maxBytes() {
        return maxBytes;
    }

    public int maxDepth() {
        return maxDepth;
    }

    /**
     * A document as sent: checked, and written back compact. Empty for none - {@code null}, blank,
     * or an object with no key, which is how a client clears a document.
     */
    public Optional<MetadataDocument> parse(String sent) {
        if (sent == null || sent.isBlank()) {
            return Optional.empty();
        }
        // Not parsed at all past four times the limit: no document that size can shrink under it.
        if (sent.length() > (long) maxBytes * 4) {
            throw tooLarge();
        }
        JsonNode tree;
        try {
            tree = JSON.readTree(sent);
        } catch (JacksonException e) {
            // Jackson's message quotes the text it stopped at; the content stays out of it.
            throw new InvalidDataException("metadata is not JSON", "metadata.invalid.json");
        }
        if (tree == null || !tree.isObject()) {
            throw new InvalidDataException("metadata is not a JSON object", "metadata.invalid.notObject");
        }
        return checked(tree);
    }

    /**
     * A document made of flat string values - the S3 surface's {@code x-amz-meta-*} headers, the
     * name lower-cased as S3 keeps it. Empty for none.
     */
    public Optional<MetadataDocument> ofStrings(Map<String, String> values) {
        if (values.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode object = JSON.createObjectNode();
        values.forEach(object::put);
        return checked(object);
    }

    /** The document's tree, for a page or an answer to render. */
    public static JsonNode treeOf(MetadataDocument document) {
        try {
            return JSON.readTree(document.json());
        } catch (JacksonException e) {
            throw new IllegalStateException("a stored metadata document is not JSON", e);
        }
    }

    /** A tree written as compact JSON. */
    public static String write(JsonNode tree) {
        return JSON.writeValueAsString(tree);
    }

    /**
     * A document in one form whatever wrote it - keys sorted at every level, no space, numbers written
     * out: the compact text this class writes and {@code jsonb}'s own text of one document are two
     * texts and one canonical form. What its entity tag is taken of ({@link MetadataDocument#etag}).
     */
    static String canonical(MetadataDocument document) {
        return JSON.writeValueAsString(sorted(treeOf(document)));
    }

    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            java.util.TreeMap<String, JsonNode> byKey = new java.util.TreeMap<>();
            node.properties().forEach(property -> byKey.put(property.getKey(), property.getValue()));
            byKey.forEach((key, value) -> sorted.set(key, sorted(value)));
            return sorted;
        }
        if (node.isArray()) {
            tools.jackson.databind.node.ArrayNode sorted = JSON.createArrayNode();
            node.values().forEach(element -> sorted.add(sorted(element)));
            return sorted;
        }
        return node;
    }

    private Optional<MetadataDocument> checked(JsonNode tree) {
        if (tree.isEmpty()) {
            return Optional.empty();
        }
        check(tree, 1);
        String json = JSON.writeValueAsString(tree);
        if (json.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw tooLarge();
        }
        return Optional.of(new MetadataDocument(json));
    }

    private void check(JsonNode node, int depth) {
        if (node.isObject() || node.isArray()) {
            if (depth > maxDepth) {
                throw new InvalidDataException("metadata nests deeper than " + maxDepth, "metadata.invalid.tooDeep", maxDepth);
            }
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                checkKey(property.getKey());
                check(property.getValue(), depth + 1);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node.values()) {
                check(element, depth + 1);
            }
        } else if (node.isString()) {
            checkText(node.stringValue());
        } else if (node.isNumber()) {
            checkNumber(node);
        }
    }

    /**
     * Refuses a number longer than {@link #MAX_NUMBER_LENGTH} written out - asked of its precision
     * and scale, which say so without writing it.
     */
    private static void checkNumber(JsonNode node) {
        if (!node.isBigDecimal() && !node.isBigInteger()) {
            return;
        }
        java.math.BigDecimal value = node.isBigDecimal() ? node.decimalValue() : new java.math.BigDecimal(node.bigIntegerValue());
        long integerDigits = (long) value.precision() - value.scale();
        long fractionDigits = Math.max(0, value.scale());
        long written = Math.max(1, integerDigits) + (fractionDigits > 0 ? fractionDigits + 1 : 0) + 1;
        if (written > MAX_NUMBER_LENGTH) {
            throw new InvalidDataException("a metadata number is longer than " + MAX_NUMBER_LENGTH + " characters written out",
                    "metadata.invalid.number", MAX_NUMBER_LENGTH);
        }
    }

    private static void checkKey(String key) {
        int length = key.codePointCount(0, key.length());
        if (length < 1 || length > MAX_KEY_LENGTH || key.isBlank()) {
            throw new InvalidDataException("a metadata key is not 1 to " + MAX_KEY_LENGTH + " characters",
                    "metadata.invalid.keyLength", MAX_KEY_LENGTH);
        }
        if (key.codePoints().anyMatch(Character::isISOControl)) {
            throw new InvalidDataException("a metadata key holds a control character", "metadata.invalid.keyCharacter");
        }
        checkText(key);
    }

    private static void checkText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u0000') {
                throw new InvalidDataException("metadata holds U+0000", "metadata.invalid.character");
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                    i++;
                    continue;
                }
                throw new InvalidDataException("metadata holds half a surrogate pair", "metadata.invalid.character");
            }
            if (Character.isLowSurrogate(c)) {
                throw new InvalidDataException("metadata holds half a surrogate pair", "metadata.invalid.character");
            }
        }
    }

    private InvalidDataException tooLarge() {
        return new InvalidDataException("metadata is larger than " + maxBytes + " bytes", "metadata.invalid.tooLarge", maxBytes);
    }
}
