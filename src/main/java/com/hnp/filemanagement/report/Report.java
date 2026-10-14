package com.hnp.filemanagement.report;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The reports (roadmap 12.9), each a page of its own at {@code /reports/{slug}} - so that a view counts
 * only what it shows. Its title is {@code report.{slug}.title}, its description {@code .description}.
 */
public enum Report {

    OVERVIEW,
    FOLDERS,
    GROWTH,
    KINDS,
    LARGEST,
    VERSIONS,
    UPLOADERS,
    DOWNLOADS,
    COLD,
    DUPLICATES,
    QUOTAS,
    COVERAGE,
    /** What should be zero - over the whole installation, so for a reader who may read every folder only. */
    INTEGRITY;

    public String slug() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String titleCode() {
        return "report." + slug() + ".title";
    }

    public String descriptionCode() {
        return "report." + slug() + ".description";
    }

    /** Whether the report covers a period of months ({@code months}). */
    public boolean takesMonths() {
        return this == GROWTH || this == UPLOADERS || this == DOWNLOADS;
    }

    public static Optional<Report> of(String slug) {
        return Arrays.stream(values()).filter(report -> report.slug().equals(slug)).findFirst();
    }
}
