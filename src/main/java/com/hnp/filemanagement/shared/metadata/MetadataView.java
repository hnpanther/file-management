package com.hnp.filemanagement.shared.metadata;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A metadata document as the pages show it ({@code ${@metadataView...}} in a template): its keys
 * and values as rows - a nested value as indented JSON - and the document as text for the editor.
 * Thymeleaf escapes every value written with {@code th:text}, as every other value on a page.
 */
@Component("metadataView")
public class MetadataView {

    private static final JsonMapper PRETTY = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    /**
     * One key of a document.
     *
     * @param structured whether the value is an object or an array, shown as JSON
     */
    public record Row(String key, String value, boolean structured) {
    }

    /** The document's keys in its order, or none for no document. */
    public List<Row> rows(JsonNode document) {
        List<Row> rows = new ArrayList<>();
        if (document == null || !document.isObject()) {
            return rows;
        }
        for (Map.Entry<String, JsonNode> property : document.properties()) {
            JsonNode value = property.getValue();
            boolean structured = value.isObject() || value.isArray();
            String text = structured ? PRETTY.writeValueAsString(value)
                    : value.isNull() ? "null" : value.isString() ? value.stringValue() : value.asString();
            rows.add(new Row(property.getKey(), text, structured));
        }
        return rows;
    }

    /** The document as indented JSON - what the editor starts from; empty for none. */
    public String json(JsonNode document) {
        return document == null ? "" : PRETTY.writeValueAsString(document);
    }

    /**
     * {@link #json(JsonNode)} for a stored document's text, or empty for none - a name of its own:
     * an overload would leave a template's {@code null} (no document before a first change) to chance.
     */
    public String jsonOfStored(String stored) {
        return stored == null ? "" : json(MetadataRules.treeOf(new MetadataDocument(stored)));
    }
}
