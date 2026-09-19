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
 * <p>These two predicates are the only thing standing between a caller-supplied string and a
 * path segment on disk. {@link FileStorageFileSystemService} contains every path it builds
 * ({@code within}), so traversal cannot escape the root even if a rule let it through - but a
 * rule that let {@code ..} through would still turn "a folder named this" into "the parent",
 * which is why the traversal cases below are the ones to keep whatever else is relaxed.
 *
 * <p>Since {@code V2.9} the rules are what a file system refuses, not what a taxonomy once
 * needed: spaces, dots inside a name and any script are fine; separators, control characters,
 * the characters Windows forbids, a trailing dot or space and the names Windows reserves are
 * not. The storage layer delegates to these same predicates, so there is one copy.
 */
class ValidationUtilTest {

    // ---------------------------------------------------------------- directory names

    @ParameterizedTest
    @ValueSource(strings = {"documents", "invoices2024", "a", "UPPER", "with-dash", "with_underscore",
            "with space", "has.dot", "گزارش ماهانه", "2024.03 report", "a  b"})
    @DisplayName("a directory name may hold spaces, dots and any script")
    void acceptsAPlainDirectoryName(String name) {
        assertThat(ValidationUtil.checkCorrectDirectoryName(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"has/separator", "has\\backslash", "..", ".", "a/../b", " ", "../escape",
            " leading", "trailing ", "trailing.", "a<b", "a>b", "a:b", "a\"b", "a|b", "a?b", "a*b",
            "tab\there", "CON", "con", "NUL.txt", "lpt1"})
    @DisplayName("a separator, a dot-name, a forbidden or control character, a trailing dot or space, or a reserved name is refused")
    void rejectsAnUnsafeDirectoryName(String name) {
        assertThat(ValidationUtil.checkCorrectDirectoryName(name)).isFalse();
    }

    @Test
    @DisplayName("the empty string is refused: a name that is nothing lands in the parent")
    void refusesTheEmptyString() {
        assertThat(ValidationUtil.checkCorrectDirectoryName("")).isFalse();
    }

    @Test
    @DisplayName("a null name throws rather than answering false")
    void nullIsNotAnAnswer() {
        assertThatThrownBy(() -> ValidationUtil.checkCorrectDirectoryName(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---------------------------------------------------------------- file names

    @ParameterizedTest
    @ValueSource(strings = {"report.pdf", "a.b", "invoice-2024.txt", "UPPER.TXT", "two.dots.pdf", "has space.pdf",
            "گزارش ماهانه.pdf", "v1.2 final.docx"})
    @DisplayName("a file name is a safe segment with an extension; spaces and inner dots are fine")
    void acceptsAPlainFileName(String name) {
        assertThat(ValidationUtil.checkCorrectFileName(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-extension", ".hidden", "trailing.", "dir/report.pdf", "..\\escape.pdf", "../escape.pdf",
            "bad.p df", "bad.pd/f", "a<b.pdf", "CON.pdf", "report.pdf "})
    @DisplayName("no extension, an extension that is not letters and digits, a separator, a forbidden character or a reserved name is refused")
    void rejectsAnUnsafeFileName(String name) {
        assertThat(ValidationUtil.checkCorrectFileName(name)).isFalse();
    }

    @Test
    @DisplayName("a traversal attempt cannot pass either rule")
    void refusesPathTraversal() {
        assertThat(ValidationUtil.checkCorrectDirectoryName("../../etc")).isFalse();
        assertThat(ValidationUtil.checkCorrectFileName("../../etc/passwd")).isFalse();
        assertThat(ValidationUtil.checkCorrectFileName("../../passwd.txt")).isFalse();
        assertThat(ValidationUtil.checkCorrectDirectoryName("..")).isFalse();
    }
}
