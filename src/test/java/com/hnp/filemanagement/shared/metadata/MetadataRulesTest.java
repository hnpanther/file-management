package com.hnp.filemanagement.shared.metadata;

import com.hnp.filemanagement.shared.exception.InvalidDataException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every rule of a metadata document (roadmap 12.2), at its limit and one past it. */
class MetadataRulesTest {

    private final MetadataRules rules = new MetadataRules(MetadataProperties.defaults());

    private String refusal(String sent) {
        try {
            rules.parse(sent);
        } catch (InvalidDataException e) {
            assertThat(e.getMessage()).as("the content stays out of the message").doesNotContain("SECRET");
            return e.getMessageCode().orElseThrow();
        }
        throw new AssertionError("accepted: " + sent);
    }

    @Test
    @DisplayName("an object is a document, written compact; nothing, blank and {} are none")
    void documents() {
        assertThat(rules.parse(" { \"contractNo\" : \"C-5678\", \"party\": {\"code\": \"P-1\"}, \"n\": [1, 2] } "))
                .map(MetadataDocument::json).contains("{\"contractNo\":\"C-5678\",\"party\":{\"code\":\"P-1\"},\"n\":[1,2]}");
        assertThat(rules.parse(null)).isEmpty();
        assertThat(rules.parse("  ")).isEmpty();
        assertThat(rules.parse("{}")).isEmpty();
        assertThat(rules.parse("{ }")).isEmpty();
        assertThat(rules.parse("{\"نام\":\"علی رضایی\",\"کد\":\"۱۲۳\"}")).map(MetadataDocument::json)
                .contains("{\"نام\":\"علی رضایی\",\"کد\":\"۱۲۳\"}");
        assertThat(rules.parse("{\"emoji\":\"😀\",\"null\":null,\"t\":true}")).isPresent();
    }

    @Test
    @DisplayName("a number is kept as written - never rounded through a double, its zeros kept")
    void numbers() {
        assertThat(rules.parse("{\"a\":12345678901234567890123,\"b\":0.1000,\"c\":3.141592653589793238462643383279,\"d\":-0}"))
                .map(MetadataDocument::json)
                .contains("{\"a\":12345678901234567890123,\"b\":0.1000,\"c\":3.141592653589793238462643383279,\"d\":0}");
    }

    @Test
    @DisplayName("a number is refused when written out it is past 1000 characters - an exponent cannot smuggle a megabyte past the size")
    void hugeNumbers() {
        assertThat(refusal("{\"a\":1e1000000}")).isEqualTo("metadata.invalid.number");
        assertThat(refusal("{\"a\":1e999999999}")).isEqualTo("metadata.invalid.number");
        assertThat(refusal("{\"a\":1e-1000000}")).isEqualTo("metadata.invalid.number");
        assertThat(refusal("{\"a\":[1,{\"b\":-5e2000}]}")).isEqualTo("metadata.invalid.number");
        assertThat(rules.parse("{\"a\":1e2,\"b\":1.5e-3,\"c\":-2E+3}")).map(MetadataDocument::json)
                .as("written out as jsonb writes it").contains("{\"a\":100,\"b\":0.0015,\"c\":-2000}");
        assertThat(rules.parse("{\"a\":1e900}")).isPresent();
    }

    @Test
    @DisplayName("what is not an object is refused: an array, a string, a number, null, two documents, broken JSON")
    void notAnObject() {
        assertThat(refusal("[1,2]")).isEqualTo("metadata.invalid.notObject");
        assertThat(refusal("\"SECRET\"")).isEqualTo("metadata.invalid.notObject");
        assertThat(refusal("12")).isEqualTo("metadata.invalid.notObject");
        assertThat(refusal("null")).isEqualTo("metadata.invalid.notObject");
        assertThat(refusal("{\"a\":\"SECRET\"} {\"b\":1}")).isEqualTo("metadata.invalid.json");
        assertThat(refusal("{\"a\":\"SECRET\"")).isEqualTo("metadata.invalid.json");
        assertThat(refusal("{a:'SECRET'}")).isEqualTo("metadata.invalid.json");
        assertThat(refusal("{\"a\":NaN}")).isEqualTo("metadata.invalid.json");
    }

    @Test
    @DisplayName("a key given twice is refused - at the top and below - rather than one silently kept")
    void duplicateKeys() {
        assertThat(refusal("{\"a\":\"SECRET\",\"a\":2}")).isEqualTo("metadata.invalid.json");
        assertThat(refusal("{\"p\":{\"a\":1,\"a\":\"SECRET\"}}")).isEqualTo("metadata.invalid.json");
    }

    @Test
    @DisplayName("16 KB is accepted, a byte more is not - counted as UTF-8, so Persian counts twice")
    void size() {
        String exact = "{\"k\":\"" + "x".repeat(16_384 - 8) + "\"}";
        assertThat(exact.length()).isEqualTo(16_384);
        assertThat(rules.parse(exact)).isPresent();
        assertThat(refusal("{\"k\":\"" + "x".repeat(16_384 - 7) + "\"}")).isEqualTo("metadata.invalid.tooLarge");
        assertThat(refusal("{\"k\":\"" + "ب".repeat(8_190) + "\"}")).as("two bytes a letter").isEqualTo("metadata.invalid.tooLarge");
        assertThat(rules.parse("{\"k\":\"" + "ب".repeat(8_180) + "\"}")).isPresent();
        assertThat(refusal(" ".repeat(70_000) + "{}")).as("not even parsed").isEqualTo("metadata.invalid.tooLarge");
        // Spaces are not stored: a sent document larger than the limit only by its spacing is kept.
        assertThat(rules.parse("{\"k\" :    \"" + "x".repeat(16_384 - 8) + "\"   }")).isPresent();
    }

    @Test
    @DisplayName("five levels are accepted, six are not - arrays count as levels")
    void depth() {
        assertThat(rules.parse("{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":1}}}}}")).isPresent();
        assertThat(rules.parse("{\"a\":[[[{\"e\":1}]]]}")).isPresent();
        assertThat(refusal("{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":1}}}}}}")).isEqualTo("metadata.invalid.tooDeep");
        assertThat(refusal("{\"a\":[[[[[\"SECRET\"]]]]]}")).isEqualTo("metadata.invalid.tooDeep");
        assertThat(rules.parse("{\"a\":[[[[]]]]}")).as("an empty array at the fifth level").isPresent();
    }

    @Test
    @DisplayName("a key is 1 to 100 characters, no control character, at any depth")
    void keys() {
        assertThat(rules.parse("{\"" + "k".repeat(100) + "\":1}")).isPresent();
        assertThat(rules.parse("{\"" + "😀".repeat(100) + "\":1}")).as("characters, not UTF-16 units").isPresent();
        assertThat(refusal("{\"" + "k".repeat(101) + "\":1}")).isEqualTo("metadata.invalid.keyLength");
        assertThat(refusal("{\"\":\"SECRET\"}")).isEqualTo("metadata.invalid.keyLength");
        assertThat(refusal("{\"   \":\"SECRET\"}")).isEqualTo("metadata.invalid.keyLength");
        assertThat(refusal("{\"a\\nb\":\"SECRET\"}")).isEqualTo("metadata.invalid.keyCharacter");
        assertThat(refusal("{\"p\":{\"a\\u0007\":1}}")).isEqualTo("metadata.invalid.keyCharacter");
        assertThat(refusal("{\"p\":[{\"" + "k".repeat(101) + "\":1}]}")).isEqualTo("metadata.invalid.keyLength");
    }

    @Test
    @DisplayName("nothing jsonb cannot hold: U+0000, half a surrogate pair - in a value at any depth")
    void characters() {
        assertThat(refusal("{\"a\":\"SECRET\\u0000\"}")).isEqualTo("metadata.invalid.character");
        assertThat(refusal("{\"a\":[\"\\ud83d\"]}")).isEqualTo("metadata.invalid.character");
        assertThat(refusal("{\"a\":{\"b\":\"x\\ude00\"}}")).isEqualTo("metadata.invalid.character");
        assertThat(rules.parse("{\"a\":\"line\\nbreak\\ttab\"}")).as("a control character in a value is a value").isPresent();
    }

    @Test
    @DisplayName("flat strings - the S3 headers - make a document under the same rules")
    void strings() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("contract-no", "C-5678");
        values.put("party", "علی");
        assertThat(rules.ofStrings(values)).map(MetadataDocument::json).contains("{\"contract-no\":\"C-5678\",\"party\":\"علی\"}");
        assertThat(rules.ofStrings(Map.of())).isEmpty();
        assertThatThrownBy(() -> rules.ofStrings(Map.of("k", "x".repeat(20_000)))).isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("the limits are settings")
    void settings() {
        MetadataRules small = new MetadataRules(new MetadataProperties(64, 2));
        assertThat(small.parse("{\"a\":{\"b\":1}}")).isPresent();
        assertThatThrownBy(() -> small.parse("{\"a\":{\"b\":[1]}}")).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> small.parse("{\"a\":\"" + "x".repeat(60) + "\"}")).isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("a document's tag follows its text; none has a tag of its own; its toString holds no content")
    void tagsAndPrinting() {
        MetadataDocument one = rules.parse("{\"a\":\"SECRET\"}").orElseThrow();
        assertThat(one.etag()).matches("\"[0-9a-f]{32}\"").isEqualTo(new MetadataDocument("{\"a\":\"SECRET\"}").etag());
        assertThat(one.etag()).isNotEqualTo(new MetadataDocument("{\"a\":\"SECRET2\"}").etag());
        assertThat(MetadataDocument.etagOf(Optional.empty())).isEqualTo("\"none\"");
        assertThat(one.toString()).doesNotContain("SECRET").contains("bytes");
        assertThat(new MetadataDocument("{\"b\":[1,{\"d\":2,\"c\":1.50}],\"a\":\"x\"}").etag())
                .as("one document, as sent and as jsonb gives it back")
                .isEqualTo(new MetadataDocument("{\"a\": \"x\", \"b\": [1, {\"c\": 1.50, \"d\": 2}]}").etag());
        assertThat(new MetadataDocument("{\"b\":[2,1]}").etag()).as("an array's order counts")
                .isNotEqualTo(new MetadataDocument("{\"b\":[1,2]}").etag());
    }
}
