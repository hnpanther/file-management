package com.hnp.filemanagement.content;

import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.metadata.MetadataDocument;
import com.hnp.filemanagement.shared.metadata.MetadataRules;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A metadata document asked of a search (roadmap 12.8) made into the conditions a query tests by
 * containment ({@code @>}, through the GIN index): one condition a key, every one required, each
 * satisfied by any of a few documents - so that what a person types on a form finds what an
 * integration stored:
 *
 * <ul>
 *   <li>a value typed {@code 1500} finds the number 1500 and the string "1500"; {@code true}, the
 *       boolean and the string;</li>
 *   <li>a key typed {@code party.name} finds the key of that name and the nested
 *       {@code {"party": {"name": ...}}};</li>
 *   <li>a value that is an object or an array is asked as it is, containment's own meaning - a part
 *       of an object, a member of an array.</li>
 * </ul>
 */
public final class MetadataConditions {

    /** The most keys one search asks of a document: each is a condition of the query. */
    public static final int MAX_KEYS = 10;

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private MetadataConditions() {
    }

    /** The conditions of a document, each a list of alternative documents as compact JSON. */
    public static List<List<String>> of(MetadataDocument document) {
        JsonNode tree = MetadataRules.treeOf(document);
        if (tree.size() > MAX_KEYS) {
            throw new InvalidDataException("a search asks at most " + MAX_KEYS + " keys of metadata",
                    "contentSearch.metadata.tooMany", MAX_KEYS);
        }
        List<List<String>> conditions = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : tree.properties()) {
            Set<String> alternatives = new LinkedHashSet<>();
            for (JsonNode value : values(entry.getValue())) {
                alternatives.add(MetadataRules.write(at(entry.getKey(), value, false)));
                if (entry.getKey().contains(".")) {
                    alternatives.add(MetadataRules.write(at(entry.getKey(), value, true)));
                }
            }
            conditions.add(List.copyOf(alternatives));
        }
        return conditions;
    }

    /** The keys a document asks, as typed. */
    public static List<String> keys(MetadataDocument document) {
        List<String> keys = new ArrayList<>();
        MetadataRules.treeOf(document).properties().forEach(entry -> keys.add(entry.getKey()));
        return keys;
    }

    /** A value and what else it may have been stored as. */
    private static List<JsonNode> values(JsonNode value) {
        List<JsonNode> values = new ArrayList<>();
        values.add(value);
        if (value.isString()) {
            String text = value.stringValue().trim();
            number(text).ifPresent(values::add);
            if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
                values.add(NODES.booleanNode(Boolean.parseBoolean(text)));
            }
        } else if (value.isNumber() || value.isBoolean()) {
            values.add(NODES.stringNode(value.asString()));
        }
        return values;
    }

    private static java.util.Optional<JsonNode> number(String text) {
        if (text.isEmpty() || text.length() > 40 || !text.matches("-?\\d+(\\.\\d+)?")) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(NODES.numberNode(new BigDecimal(text)));
    }

    /** {@code {key: value}}, or with {@code nested} a dotted key as nested objects. */
    private static ObjectNode at(String key, JsonNode value, boolean nested) {
        ObjectNode root = NODES.objectNode();
        if (!nested) {
            root.set(key, value);
            return root;
        }
        String[] parts = key.split("\\.", -1);
        ObjectNode current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            ObjectNode child = NODES.objectNode();
            current.set(parts[i], child);
            current = child;
        }
        current.set(parts[parts.length - 1], value);
        return root;
    }
}
