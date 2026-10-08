package com.hnp.filemanagement.content;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Optional;

/**
 * Searching the contents of files (roadmap Phase 11, 2.15.0) - two switches that do not depend on
 * each other, and where Tika is.
 *
 * <p><b>Nothing here stops the application from starting</b> (the owner's requirement, 2026-10-08):
 * a number out of range is brought into it, and a missing or malformed Tika URL leaves reading off
 * with the reason logged and shown on the status page - never a failed start. The one exception is
 * {@code engine}, which only {@code postgres} may be: a typo there is not a state to run in.
 *
 * @param enabled    the "search in contents" page, its menu entry and its API; off, they answer 404
 * @param engine     where the search runs: {@code postgres} (an OpenSearch engine is roadmap 11.6)
 * @param extraction the reading of contents by Tika
 * @param tika       where the two Tika containers are
 */
@ConfigurationProperties(prefix = "filemanagement.content-search")
public record ContentSearchProperties(Boolean enabled, String engine, Extraction extraction, Tika tika) {

    public ContentSearchProperties {
        enabled = Boolean.TRUE.equals(enabled);
        engine = engine == null || engine.isBlank() ? "postgres" : engine.trim();
        extraction = extraction == null ? new Extraction(null, null, null, null, null, null, null, null, null, null) : extraction;
        tika = tika == null ? new Tika(null, null, null, null, null) : tika;
    }

    /**
     * @param enabled             the worker that gives files to Tika; off, nothing is read and Tika is never called
     * @param backfill            read the revisions stored before 2.15.0 too, not only those uploaded since -
     *                            queued in batches, behind every new upload
     * @param ocrEnabled          PDFs and images to the OCR container ({@code auto}: a text page read as text, a
     *                            scanned one recognised); off, they are read for their text layer alone, and an
     *                            image is skipped
     * @param concurrency         files at Tika at once - 1 (the owner's choice); at most 4
     * @param maxFileMb           a larger revision is not sent to Tika: {@code SKIPPED}, said so
     * @param maxTextMb           the text kept of one revision; beyond it the reading is {@code partial}
     * @param maxAttempts         failed readings of one revision before it is left {@code FAILED}
     * @param retryMaxWaitMinutes Tika unreachable: the worker waits {@code retryFirstWaitSeconds}, doubling up to this
     * @param backfillBatch       revisions the backfill queues at a time
     * @param retryFirstWaitSeconds the first wait once Tika is lost - 30
     */
    public record Extraction(Boolean enabled, Boolean backfill, Boolean ocrEnabled, Integer concurrency,
                             Integer maxFileMb, Integer maxTextMb, Integer maxAttempts, Integer retryMaxWaitMinutes,
                             Integer backfillBatch, Integer retryFirstWaitSeconds) {

        public Extraction {
            enabled = Boolean.TRUE.equals(enabled);
            backfill = Boolean.TRUE.equals(backfill);
            ocrEnabled = ocrEnabled == null || ocrEnabled;
            concurrency = clamp(concurrency, 1, 1, 4);
            maxFileMb = clamp(maxFileMb, 200, 1, 10_000);
            maxTextMb = clamp(maxTextMb, 10, 1, 100);
            maxAttempts = clamp(maxAttempts, 3, 1, 20);
            retryMaxWaitMinutes = clamp(retryMaxWaitMinutes, 10, 1, 240);
            backfillBatch = clamp(backfillBatch, 500, 10, 10_000);
            retryFirstWaitSeconds = clamp(retryFirstWaitSeconds, 30, 1, 600);
        }

        public long maxFileBytes() {
            return maxFileMb * 1024L * 1024L;
        }

        /** The characters kept of one revision: about half as many as its megabytes' bytes, Persian taking two each. */
        public long maxTextCharacters() {
            return maxTextMb * 1024L * 1024L / 2;
        }
    }

    /**
     * @param textUrl               the text container ({@code tika-text}): Office documents, text
     * @param ocrUrl                the OCR container ({@code tika-ocr}): PDFs and images
     * @param connectTimeoutSeconds a connection not made in this is Tika unreachable
     * @param textTimeoutMinutes    one document read by the text container - a little above its own limit
     *                              (deploy/tika/config/text.json: 5 minutes)
     * @param ocrTimeoutMinutes     one document read by the OCR container - a little above its own limit
     *                              (deploy/tika/config/ocr.json: 30 minutes)
     */
    public record Tika(String textUrl, String ocrUrl, Integer connectTimeoutSeconds, Integer textTimeoutMinutes,
                       Integer ocrTimeoutMinutes) {

        public Tika {
            textUrl = textUrl == null ? "" : textUrl.trim();
            ocrUrl = ocrUrl == null ? "" : ocrUrl.trim();
            connectTimeoutSeconds = clamp(connectTimeoutSeconds, 5, 1, 120);
            textTimeoutMinutes = clamp(textTimeoutMinutes, 6, 1, 240);
            ocrTimeoutMinutes = clamp(ocrTimeoutMinutes, 35, 1, 600);
        }

        /** The text container's base URL, if it is one - an {@code http(s)} URL with a host. */
        public Optional<URI> textUri() {
            return uriOf(textUrl);
        }

        /** The OCR container's base URL, if it is one. */
        public Optional<URI> ocrUri() {
            return uriOf(ocrUrl);
        }

        private static Optional<URI> uriOf(String value) {
            if (value.isEmpty()) {
                return Optional.empty();
            }
            try {
                URI uri = URI.create(value.endsWith("/") ? value.substring(0, value.length() - 1) : value);
                boolean web = "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
                return web && uri.getHost() != null ? Optional.of(uri) : Optional.empty();
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    /**
     * Why reading cannot run with these settings, or empty when it can: what the status page and the
     * log say instead of refusing to start.
     */
    public Optional<String> whyReadingCannotRun() {
        if (!extraction.enabled()) {
            return Optional.of("reading is switched off (filemanagement.content-search.extraction.enabled=false)");
        }
        if (tika.textUri().isEmpty()) {
            return Optional.of("filemanagement.content-search.tika.text-url is not an http(s) URL: '" + tika.textUrl() + "'");
        }
        if (extraction.ocrEnabled() && tika.ocrUri().isEmpty()) {
            return Optional.of("filemanagement.content-search.tika.ocr-url is not an http(s) URL: '" + tika.ocrUrl()
                    + "' (or switch OCR off: filemanagement.content-search.extraction.ocr-enabled=false)");
        }
        return Optional.empty();
    }

    private static int clamp(Integer value, int fallback, int min, int max) {
        if (value == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
