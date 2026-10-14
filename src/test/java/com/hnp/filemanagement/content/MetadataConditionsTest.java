package com.hnp.filemanagement.content;

import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.metadata.MetadataDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A metadata document asked of a search made into containment conditions (roadmap 12.8): one per key,
 * each satisfied by what a form's text could have been stored as.
 */
class MetadataConditionsTest {

    @Test
    @DisplayName("a key a condition; a text value alone when it can only be text")
    void plain() {
        List<List<String>> conditions = MetadataConditions.of(new MetadataDocument("{\"contractNo\":\"C-5678\",\"city\":\"تهران\"}"));
        assertThat(conditions).containsExactly(List.of("{\"contractNo\":\"C-5678\"}"), List.of("{\"city\":\"تهران\"}"));
        assertThat(MetadataConditions.keys(new MetadataDocument("{\"a\":\"1\",\"b\":\"2\"}"))).containsExactly("a", "b");
    }

    @Test
    @DisplayName("a number typed as text finds the number too, and a number the text; true and false the boolean")
    void numbersAndBooleans() {
        assertThat(MetadataConditions.of(new MetadataDocument("{\"amount\":\"1500\"}")))
                .containsExactly(List.of("{\"amount\":\"1500\"}", "{\"amount\":1500}"));
        assertThat(MetadataConditions.of(new MetadataDocument("{\"amount\":1500.50}")).getFirst())
                .contains("{\"amount\":1500.50}", "{\"amount\":\"1500.50\"}");
        assertThat(MetadataConditions.of(new MetadataDocument("{\"signed\":\"true\"}")).getFirst())
                .contains("{\"signed\":\"true\"}", "{\"signed\":true}");
        assertThat(MetadataConditions.of(new MetadataDocument("{\"code\":\"007a\"}")).getFirst()).hasSize(1);
    }

    @Test
    @DisplayName("a dotted key finds the key of that name and the nested path; an object or an array is asked as it is")
    void nested() {
        assertThat(MetadataConditions.of(new MetadataDocument("{\"party.name\":\"علی\"}")).getFirst())
                .containsExactly("{\"party.name\":\"علی\"}", "{\"party\":{\"name\":\"علی\"}}");
        assertThat(MetadataConditions.of(new MetadataDocument("{\"party\":{\"code\":\"P-1\"},\"tags\":[\"signed\"]}")))
                .containsExactly(List.of("{\"party\":{\"code\":\"P-1\"}}"), List.of("{\"tags\":[\"signed\"]}"));
    }

    @Test
    @DisplayName("at most ten keys a search")
    void bounded() {
        StringBuilder doc = new StringBuilder("{");
        for (int i = 0; i < 11; i++) {
            doc.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":\"v\"");
        }
        assertThatThrownBy(() -> MetadataConditions.of(new MetadataDocument(doc.append("}").toString())))
                .isInstanceOf(InvalidDataException.class);
    }
}
