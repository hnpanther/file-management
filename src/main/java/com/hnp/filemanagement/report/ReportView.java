package com.hnp.filemanagement.report;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A report as the page shows it and the CSV writes it (roadmap 12.9): its headline figures, its tables
 * and what the reader should know about them. Every report is this shape, so one template renders
 * them all and one writer exports any table - and a value's kind, not its report, decides how it is
 * shown: a size as a size, a month as a Jalali month, a file as a link to its page.
 *
 * @param report    which report
 * @param stats     the headline figures, above the tables
 * @param tables    the tables, each exportable
 * @param notes     what the figures do not say by themselves (where history starts, a period capped)
 * @param countedAt when the figures were counted - earlier than now when the report was kept
 */
public record ReportView(Report report, List<Stat> stats, List<Table> tables, List<Note> notes, Instant countedAt) {

    /** How a value is shown, and written to a CSV. */
    public enum Kind {
        /** As it is. */
        TEXT,
        /** A message code, shown as its text. */
        LABEL,
        /** A count. */
        COUNT,
        /** A number of bytes, shown in the largest unit that keeps it above one. */
        BYTES,
        /** A share, 0 to 100. */
        PERCENT,
        /** A {@code JalaliDate.JalaliMonth}. */
        MONTH,
        /** An {@code Instant}, shown in the Jalali calendar. */
        DATE,
        /** A {@link FolderCell}: a link into the folders report when it has an id. */
        FOLDER,
        /** A {@link FileCell}: a link to the file's page when it has an id. */
        FILE
    }

    /** A headline figure; {@code hint} a note under it, or null. */
    public record Stat(String code, Kind kind, Object value, Note hint) {
    }

    public record Column(String code, Kind kind) {
    }

    /** A row: its cells, column by column (null for none), and its bar's width in percent of the largest. */
    public record Row(List<Object> cells, int bar) {
    }

    /**
     * @param id        the table's name in its CSV's link and file name
     * @param titleCode its heading
     * @param columns   its columns
     * @param rows      its rows
     * @param barColumn the column a bar is drawn beside, or -1
     * @param emptyCode what is said when it has no row
     */
    public record Table(String id, String titleCode, List<Column> columns, List<Row> rows, int barColumn, String emptyCode) {
    }

    /** A sentence from {@code messages.properties} and its arguments, already formatted for the page. */
    public record Note(String code, List<Object> arguments) {
        public static Note of(String code, Object... arguments) {
            return new Note(code, List.of(arguments));
        }
    }

    /** A folder in a table: its trail; {@code id} null for a row that is not one folder ("in this folder itself"). */
    public record FolderCell(Integer id, String label) {
    }

    /** A file in a table: its name and its folder's trail; {@code fileId} null for one since deleted, or out of sight. */
    public record FileCell(Integer fileId, String name, String trail) {
    }

    public Optional<Table> table(String id) {
        return tables.stream().filter(table -> table.id().equals(id)).findFirst();
    }
}
