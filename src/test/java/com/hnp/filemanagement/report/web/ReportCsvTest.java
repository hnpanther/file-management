package com.hnp.filemanagement.report.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A CSV's cells (roadmap 12.9): quotes doubled, a name that begins as a formula defused, a number left alone. */
class ReportCsvTest {

    @Test
    @DisplayName("quotes doubled; = + - @ tab and return at the start of a name made text; a number untouched")
    void cells() {
        assertThat(ReportController.quoted("a\"b", true)).isEqualTo("\"a\"\"b\"");
        assertThat(ReportController.quoted(null, true)).isEqualTo("\"\"");
        for (String formula : List.of("=1+1", "+1", "-1", "@SUM(A1)", "\tx", "\rx")) {
            assertThat(ReportController.quoted(formula, true)).as(formula).startsWith("\"'");
        }
        assertThat(ReportController.quoted("-5", false)).isEqualTo("\"-5\"");
        assertThat(ReportController.quoted("گزارش", true)).isEqualTo("\"گزارش\"");
    }
}
