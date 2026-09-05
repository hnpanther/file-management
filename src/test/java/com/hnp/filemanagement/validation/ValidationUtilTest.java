package com.hnp.filemanagement.validation;

import com.hnp.filemanagement.service.FileStorageFileSystemService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The naming rules, as pure unit tests — no Spring, no database, no file system.
 *
 * <p>These two predicates are the only thing standing between a caller-supplied string and a path
 * on disk. {@link FileStorageFileSystemService} builds its paths by concatenation and never
 * resolves or normalises them, so a name that reaches storage containing {@code /} or {@code ..}
 * escapes the storage root entirely. That makes these rules a security boundary, not a formatting
 * preference, and the traversal cases below are the ones to keep when the rules are relaxed.
 *
 * <p>The same rules are re-implemented inside {@code FileStorageFileSystemService}. They must agree;
 * {@code FileStorageFileSystemServiceTest} checks the other copy against the same inputs.
 */
class ValidationUtilTest {

    // ---------------------------------------------------------------- directory names

    @ParameterizedTest
    @ValueSource(strings = {"documents", "invoices2024", "a", "UPPER", "with-dash", "with_underscore"})
    @DisplayName("a directory name may be anything without a dot, a space or a separator")
    void acceptsAPlainDirectoryName(String name) {
        assertThat(ValidationUtil.checkCorrectDirectoryName(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "has.dot", "has/separator", "..", ".", "a/../b", " ", "../escape"})
    @DisplayName("a directory name with a dot, a space or a separator is refused")
    void rejectsAnUnsafeDirectoryName(String name) {
        assertThat(ValidationUtil.checkCorrectDirectoryName(name)).isFalse();
    }

    @Test
    @DisplayName("the empty string is accepted, which callers must not rely on")
    void acceptsTheEmptyString() {
        // Documented rather than endorsed: the predicate counts forbidden characters, and an empty
        // string has none. Every caller checks for emptiness through bean validation first, so this
        // has never been reachable - but a new caller that skips that would create a directory
        // named "" and land back in the parent.
        assertThat(ValidationUtil.checkCorrectDirectoryName("")).isTrue();
    }

    @Test
    @DisplayName("a null name throws rather than answering false")
    void nullIsNotAnAnswer() {
        assertThatThrownBy(() -> ValidationUtil.checkCorrectDirectoryName(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---------------------------------------------------------------- file names

    @ParameterizedTest
    @ValueSource(strings = {"report.pdf", "a.b", "invoice-2024.txt", "UPPER.TXT"})
    @DisplayName("a file name must have exactly one dot and no space or separator")
    void acceptsAPlainFileName(String name) {
        assertThat(ValidationUtil.checkCorrectFileName(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-extension", "two.dots.pdf", "has space.pdf", "dir/report.pdf", "../escape.pdf"})
    @DisplayName("a file name without exactly one dot, or with a space or a separator, is refused")
    void rejectsAnUnsafeFileName(String name) {
        assertThat(ValidationUtil.checkCorrectFileName(name)).isFalse();
    }

    @Test
    @DisplayName("a traversal attempt cannot pass either rule")
    void refusesPathTraversal() {
        assertThat(ValidationUtil.checkCorrectDirectoryName("../../etc")).isFalse();
        assertThat(ValidationUtil.checkCorrectFileName("../../etc/passwd")).isFalse();
        assertThat(ValidationUtil.checkCorrectFileName("../../passwd.txt")).isFalse();
    }
}
