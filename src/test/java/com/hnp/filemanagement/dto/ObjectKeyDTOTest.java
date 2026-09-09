package com.hnp.filemanagement.dto;

import com.hnp.filemanagement.exception.InvalidDataException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How an object key is taken apart (roadmap 9.3) — no Spring and no database, because a key is a
 * string and every rule about it is a rule about that string.
 *
 * <p>The two shapes are told apart <em>structurally</em>: a key whose second-to-last segment reads
 * {@code v}<i>n</i> names a stored version, and one that does not is a write asking for the next
 * version. That is the whole contract, and it is the kind of thing that is quietly wrong for one
 * shape while the other keeps working.
 */
class ObjectKeyDTOTest {

    @Test
    @DisplayName("a key with a version names a stored object")
    void theStoredShape() {
        ObjectKeyDTO key = ObjectKeyDTO.parse("IMS_Document_System/HSED/report/v3/report.pdf");

        assertThat(key.folders()).containsExactly("IMS_Document_System", "HSED");
        assertThat(key.fileName()).isEqualTo("report");
        assertThat(key.version()).isEqualTo(3);
        assertThat(key.objectName()).isEqualTo("report.pdf");
        assertThat(key.isVersionless()).isFalse();
        assertThat(key.extension()).isEqualTo("pdf");
    }

    @Test
    @DisplayName("a key without a version is a write, and says which version it is not naming")
    void theWriteShape() {
        ObjectKeyDTO key = ObjectKeyDTO.parse("IMS_Document_System/HSED/report/report.pdf");

        assertThat(key.folders()).containsExactly("IMS_Document_System", "HSED");
        assertThat(key.fileName()).isEqualTo("report");
        assertThat(key.version()).isNull();
        assertThat(key.isVersionless()).isTrue();
    }

    /**
     * The folder part is a list rather than a fixed pair, so that Phase 7 — which makes the tree any
     * depth — needs no change here. Both ends of the range are exercised because "always two" is
     * exactly the assumption that would pass today and break then.
     */
    @Test
    @DisplayName("any number of folders, including none")
    void theFolderPartIsNotAFixedDepth() {
        assertThat(ObjectKeyDTO.parse("report/v1/report.pdf").folders()).isEmpty();
        assertThat(ObjectKeyDTO.parse("a/report/v1/report.pdf").folders()).containsExactly("a");
        assertThat(ObjectKeyDTO.parse("a/b/c/d/report/v1/report.pdf").folders())
                .containsExactly("a", "b", "c", "d");
    }

    @Test
    @DisplayName("only v followed by digits is a version; anything else is a folder name")
    void whatCountsAsAVersionSegment() {
        assertThat(ObjectKeyDTO.parse("a/report/v12/report.pdf").version()).isEqualTo(12);

        // These look like versions and are not: the shape has to be exact, or a folder called
        // "version" or "v" would silently change what the key means.
        assertThat(ObjectKeyDTO.parse("a/report/version/report.pdf").version()).isNull();
        assertThat(ObjectKeyDTO.parse("a/report/v/report.pdf").version()).isNull();
        assertThat(ObjectKeyDTO.parse("a/report/v1x/report.pdf").version()).isNull();
        assertThat(ObjectKeyDTO.parse("a/report/V1/report.pdf").version()).isNull();
    }

    @Test
    @DisplayName("repeated and trailing separators are ignored, as they are in a path")
    void separatorsAreForgiving() {
        ObjectKeyDTO key = ObjectKeyDTO.parse("//a//report//v2//report.pdf");

        assertThat(key.folders()).containsExactly("a");
        assertThat(key.version()).isEqualTo(2);
    }

    @Test
    @DisplayName("a key that fits neither shape is refused, not guessed at")
    void malformedKeysAreRefused() {
        assertThatThrownBy(() -> ObjectKeyDTO.parse(null)).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> ObjectKeyDTO.parse("")).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> ObjectKeyDTO.parse("   ")).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> ObjectKeyDTO.parse("report.pdf"))
                .as("a name with no file folder around it")
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> ObjectKeyDTO.parse("v1/report.pdf"))
                .as("a version with no file")
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("an object name with no extension is refused, because the extension is stored")
    void theExtensionIsRequired() {
        assertThatThrownBy(() -> ObjectKeyDTO.parse("a/report/v1/report").extension())
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> ObjectKeyDTO.parse("a/report/v1/report.").extension())
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("a rendered key parses back to what rendered it")
    void renderingAndParsingAgree() {
        String rendered = ObjectKeyDTO.of(List.of("a", "b"), "report", 4, "report.pdf");

        assertThat(rendered).isEqualTo("a/b/report/v4/report.pdf");
        assertThat(ObjectKeyDTO.parse(rendered))
                .isEqualTo(new ObjectKeyDTO(List.of("a", "b"), "report", 4, "report.pdf"));

        String noFolders = ObjectKeyDTO.of(List.of(), "report", 1, "report.pdf");
        assertThat(noFolders).isEqualTo("report/v1/report.pdf");
        assertThat(ObjectKeyDTO.parse(noFolders).folders()).isEmpty();
    }
}
