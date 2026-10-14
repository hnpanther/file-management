package com.hnp.filemanagement.report;

import com.hnp.filemanagement.shared.util.JalaliDate;
import com.hnp.filemanagement.shared.web.UiMessages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reports' parts that need no database (roadmap 12.9): the projection's slope, the bars, how a
 * value is shown and written, and the cache of the heavy reports.
 */
class ReportUnitTest {

    private static final ZoneId TEHRAN = ZoneId.of("Asia/Tehran");

    @Test
    @DisplayName("the slope through the night's counts: a straight line read exactly, a flat one is no growth")
    void slope() {
        List<ReportRepository.Stored> days = new ArrayList<>();
        LocalDate first = LocalDate.of(2026, 7, 1);
        for (int i = 0; i < 30; i++) {
            days.add(new ReportRepository.Stored(first.plusDays(i), 0, 0, 1_000_000L + 5_000L * i, 0));
        }
        assertThat(ReportService.slopePerDay(days)).isCloseTo(5_000.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(ReportService.slopePerDay(days.subList(0, 1))).isZero();
        List<ReportRepository.Stored> flat = days.stream().map(d -> new ReportRepository.Stored(d.day(), 0, 0, 42, 0)).toList();
        assertThat(ReportService.slopePerDay(flat)).isZero();
    }

    @Test
    @DisplayName("bars against the largest of their column; shares of nothing are nothing")
    void bars() {
        List<ReportView.Row> rows = ReportService.withBars(List.of(List.of("a", 50L), List.of("b", 200L), new ArrayList<>(
                java.util.Arrays.asList("c", null))), 1);
        assertThat(rows).extracting(ReportView.Row::bar).containsExactly(25, 100, 0);
        assertThat(ReportService.share(1, 0)).isZero();
        assertThat(ReportService.share(1, 4)).isEqualTo(25.0);
    }

    @Test
    @DisplayName("shown in Persian digits and units, a month by its name; written for a spreadsheet in Latin digits and bytes")
    void format() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        ReportFormat format = new ReportFormat(new UiMessages(source), new JalaliDate(Clock.system(TEHRAN)));

        assertThat(format.count(1234567L)).isEqualTo("۱٬۲۳۴٬۵۶۷");
        assertThat(format.bytes(512L)).isEqualTo("۵۱۲ بایت");
        assertThat(format.bytes(1536L)).isEqualTo("۱٫۵ کیلوبایت");
        assertThat(format.bytes(5L * 1024 * 1024 * 1024)).isEqualTo("۵ گیگابایت");
        assertThat(format.percent(45.26)).isEqualTo("۴۵٫۳٪");
        assertThat(format.month(new JalaliDate.JalaliMonth(1405, 7))).isEqualTo("مهر ۱۴۰۵");

        assertThat(format.written(ReportView.Kind.BYTES, 5L * 1024 * 1024 * 1024)).isEqualTo("5368709120");
        assertThat(format.written(ReportView.Kind.PERCENT, 45.26)).isEqualTo("45.3");
        assertThat(format.written(ReportView.Kind.MONTH, new JalaliDate.JalaliMonth(1405, 7))).isEqualTo("1405-07");
        assertThat(format.written(ReportView.Kind.DATE, Instant.parse("2026-10-13T08:00:00Z"))).isEqualTo("1405/07/21 11:30");
        assertThat(format.written(ReportView.Kind.FILE, new ReportView.FileCell(1, "a.pdf", "X / Y"))).isEqualTo("X / Y / a.pdf");
        assertThat(format.written(ReportView.Kind.LABEL, "report.uploaders.person")).isEqualTo("فرد");
    }

    @Test
    @DisplayName("every key built at run time is in the bundle: each unit, each Jalali month, each report's title and description")
    void builtKeys() throws java.io.IOException {
        java.util.Properties bundle = new java.util.Properties();
        try (var in = new java.io.InputStreamReader(ReportUnitTest.class.getResourceAsStream("/messages.properties"),
                java.nio.charset.StandardCharsets.UTF_8)) {
            bundle.load(in);
        }
        for (String unit : ReportFormat.UNITS) {
            assertThat(bundle.getProperty("report.unit." + unit)).as(unit).isNotBlank();
        }
        for (int month = 1; month <= 12; month++) {
            assertThat(bundle.getProperty("jalali.month." + month)).as("month " + month).isNotBlank();
        }
        for (Report report : Report.values()) {
            assertThat(bundle.getProperty(report.titleCode())).as(report.titleCode()).isNotBlank();
            assertThat(bundle.getProperty(report.descriptionCode())).as(report.descriptionCode()).isNotBlank();
        }
        for (String channel : List.of("PAGE", "PREVIEW", "PUBLIC", "SHARE_LINK", "API_V1", "API_V2", "S3")) {
            assertThat(bundle.getProperty("fileDownloads.channel." + channel)).as(channel).isNotBlank();
        }
        for (String state : List.of("PENDING", "READING", "DONE", "EMPTY", "SKIPPED", "FAILED")) {
            assertThat(bundle.getProperty("contentExtraction.state." + state)).as(state).isNotBlank();
        }
    }

    @Test
    @DisplayName("the cache: kept for its minutes for the same key, counted again after; bounded; off at zero")
    void cache() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-13T08:00:00Z"));
        ReportCache cache = new ReportCache(Duration.ofMinutes(5), clock);
        AtomicInteger counted = new AtomicInteger();
        ReportView first = cache.get("all|overview", () -> view(counted));
        assertThat(cache.get("all|overview", () -> view(counted))).isSameAs(first);
        assertThat(cache.get("u7k0|overview", () -> view(counted))).as("another reader's").isNotSameAs(first);
        clock.now = clock.now.plus(Duration.ofMinutes(5));
        assertThat(cache.get("all|overview", () -> view(counted))).isNotSameAs(first);
        assertThat(counted).hasValue(3);

        for (int i = 0; i < ReportCache.MOST + 50; i++) {
            cache.get("k" + i, () -> view(counted));
        }
        assertThat(counted.get()).isEqualTo(3 + ReportCache.MOST + 50);
        ReportCache off = new ReportCache(Duration.ZERO, clock);
        off.get("x", () -> view(counted));
        off.get("x", () -> view(counted));
        assertThat(counted.get()).isEqualTo(5 + ReportCache.MOST + 50);
    }

    private static ReportView view(AtomicInteger counted) {
        counted.incrementAndGet();
        return new ReportView(Report.OVERVIEW, List.of(), List.of(), List.of(), Instant.EPOCH);
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
