package com.hnp.filemanagement.content;

import com.hnp.filemanagement.content.FileContentRepository.Outcome;
import com.hnp.filemanagement.content.FileContentRepository.PageRow;
import com.hnp.filemanagement.content.TikaClient.Lane;
import com.hnp.filemanagement.content.TikaReading.Kind;
import com.hnp.filemanagement.content.TikaReading.Unit;
import com.hnp.filemanagement.content.TextQuality.Score;
import com.hnp.filemanagement.content.TextQuality.Verdict;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Reads one revision (roadmap 11.2): which container, what Tika answers, which of its pages can be
 * trusted, and the rows to keep. Holds nothing between revisions and touches no database - the worker
 * claims and writes; this decides.
 *
 * <ul>
 *   <li><b>A PDF or an image goes to the OCR container</b>, once ({@code auto}: a text page read as
 *       text, a scanned one recognised) - a PDF of both kinds of page is one request and one answer
 *       (decided 2026-10-08). With OCR off, a PDF goes to the text container and an image is skipped.</li>
 *   <li><b>Office documents and text go to the text container.</b> Anything else - video, audio,
 *       archives - is skipped, said so.</li>
 *   <li><b>A PDF's text pages are judged</b> ({@link TextQuality}): one stored reversed is put right;
 *       one of garbage sends the document back to the OCR container with {@code every-page}, and that
 *       page keeps both readings, the recognised one first.</li>
 *   <li><b>A PDF that OCR read nothing from is {@code FAILED}</b>, never {@code EMPTY}: Tika answers an
 *       image it could not decode with no text and no error (the JPEG 2000 scans of 2026-10-08). An
 *       image with no text is {@code EMPTY} - a photograph may have none.</li>
 * </ul>
 */
public class ContentReader {

    /** A unit's text is stored in parts of at most this many characters: a tsvector stays far below 1 MB. */
    static final int PART_CHARACTERS = 100_000;

    private static final String EVERY_PAGE = "every-page";
    private static final Set<String> IMAGES = Set.of("image/png", "image/jpeg", "image/gif", "image/tiff", "image/bmp",
            "image/webp", "image/x-ms-bmp");
    private static final List<String> TEXT_PREFIXES = List.of(
            "text/",
            "application/vnd.openxmlformats-officedocument.",
            "application/vnd.oasis.opendocument.",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.ms-word",
            "application/vnd.ms-visio",
            "application/vnd.visio",
            "application/msword",
            "application/rtf",
            "application/xml",
            "application/json",
            "application/xhtml+xml",
            "message/rfc822",
            "application/vnd.ms-outlook");
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "csv", "tsv", "md", "rtf", "xml", "json", "html",
            "htm", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "vsd", "vsdx", "eml", "msg");

    /** What reading one revision came to: the row's outcome and its pages. */
    public record Result(Outcome outcome, List<PageRow> pages) {
    }

    private final TikaClient tika;
    private final ContentSearchProperties.Extraction settings;

    public ContentReader(TikaClient tika, ContentSearchProperties.Extraction settings) {
        this.tika = tika;
        this.settings = settings;
    }

    /** The container a kind goes to, or empty for a kind nothing here reads. */
    Lane laneOf(String contentType, String extension) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        if ("application/pdf".equals(type) || "pdf".equals(ext)) {
            return settings.ocrEnabled() ? Lane.OCR : Lane.TEXT;
        }
        if (IMAGES.contains(type)) {
            return settings.ocrEnabled() ? Lane.OCR : null;
        }
        if (TEXT_PREFIXES.stream().anyMatch(type::startsWith) || TEXT_EXTENSIONS.contains(ext)) {
            return Lane.TEXT;
        }
        return null;
    }

    /**
     * Reads one revision.
     *
     * @param bytes opens its bytes - once for each request this makes (two for a PDF read again by OCR)
     * @throws TikaClient.Unavailable Tika could not be reached: nothing is decided, the reading is put back
     * @throws TikaClient.Refused     Tika could not parse it: a {@code FAILED} the caller writes at once
     * @throws IOException            anything else - one attempt spent
     */
    public Result read(FileContentRepository.Target target, Supplier<InputStream> bytes)
            throws IOException, InterruptedException {
        String type = target.contentType();
        Lane lane = laneOf(type, target.extension());
        if (lane == null) {
            boolean image = type != null && type.toLowerCase(Locale.ROOT).startsWith("image/");
            return skipped(image ? "an image, and OCR is switched off" : "a kind nothing here reads: " + type);
        }
        if (target.size() > settings.maxFileBytes()) {
            return skipped("larger than filemanagement.content-search.extraction.max-file-mb (" + settings.maxFileMb() + " MB)");
        }

        long maxCharacters = settings.maxTextCharacters();
        TikaReading reading = tika.read(lane, null, bytes, target.size(), type, target.extension(), maxCharacters);
        boolean pdf = "application/pdf".equalsIgnoreCase(type) || "pdf".equalsIgnoreCase(target.extension());

        Map<Integer, Judged> judged = new HashMap<>();
        boolean garbage = false;
        if (pdf) {
            for (Unit unit : reading.units()) {
                if (unit.kind() == Kind.PAGE && !unit.layer().isBlank()) {
                    Judged verdict = judge(unit.layer());
                    judged.put(unit.number(), verdict);
                    garbage |= verdict.verdict() == Verdict.GARBAGE;
                }
            }
        }

        TikaReading recognised = null;
        if (garbage && lane == Lane.OCR) {
            recognised = tika.read(Lane.OCR, EVERY_PAGE, bytes, target.size(), type, target.extension(), maxCharacters);
        }

        List<PageRow> rows = new ArrayList<>();
        long characters = 0;
        for (Unit unit : reading.units()) {
            Judged verdict = judged.get(unit.kind() == Kind.PAGE ? unit.number() : -1);
            String text;
            String source;
            Integer score = verdict == null ? null : verdict.score().perMille();
            if (verdict != null && verdict.verdict() == Verdict.GARBAGE) {
                String ocr = recognised == null ? "" : ocrOf(recognised, unit.number());
                // Both: the recognised text first, for the snippet; the layer kept, in case it was right.
                text = ocr.isBlank() ? unit.layer() : ocr + "\n" + unit.layer();
                source = ocr.isBlank() ? "TEXT" : "BOTH";
            } else if (verdict != null && verdict.verdict() == Verdict.REVERSED) {
                text = verdict.text() + (unit.ocr().isBlank() ? "" : "\n" + unit.ocr());
                source = "TEXT_REVERSED";
            } else if (!unit.layer().isBlank() && !unit.ocr().isBlank()) {
                text = unit.ocr() + "\n" + unit.layer();
                source = "BOTH";
            } else if (!unit.ocr().isBlank()) {
                text = unit.ocr();
                source = "OCR";
            } else {
                text = unit.layer();
                source = "TEXT";
            }
            if (text.isBlank()) {
                continue;
            }
            characters += text.length();
            addParts(rows, unit, text, source, score);
        }

        boolean ocrRan = reading.ocrBlocks() > 0 || (recognised != null && recognised.ocrBlocks() > 0);
        int ocrPages = Math.max(reading.ocrBlocks(), recognised == null ? 0 : recognised.ocrBlocks());
        boolean partial = reading.partial() || (recognised != null && recognised.partial());
        String detected = reading.contentType();
        Integer pages = reading.pages() > 0 ? reading.pages() : null;
        String laneName = lane.name();

        if (rows.isEmpty()) {
            if (pdf && lane == Lane.OCR && ocrRan) {
                // A scan has something on it: no text at all is a reading that went wrong (an image the
                // container could not decode answers 200 with nothing), not an empty document.
                return new Result(new Outcome("FAILED", laneName, detected,
                        "OCR read nothing from " + ocrPages + " page(s) - an image the container could not decode?",
                        false, pages, ocrPages, 0), List.of());
            }
            String why = ocrRan ? "OCR found no text" : (pdf && lane == Lane.TEXT ? "no text layer, and OCR is switched off" : null);
            return new Result(new Outcome("EMPTY", laneName, detected, why, false, pages, ocrRan ? ocrPages : null, 0), List.of());
        }
        String reason = partial ? "the first " + settings.maxTextMb() + " MB of text kept (max-text-mb)" : null;
        long repaired = rows.stream().filter(row -> "TEXT_REVERSED".equals(row.source()) && row.part() == 0).count();
        long both = rows.stream().filter(row -> "BOTH".equals(row.source()) && row.part() == 0).count();
        if (repaired > 0 || both > 0) {
            String notes = (repaired > 0 ? repaired + " page(s) stored reversed, put right" : "")
                    + (repaired > 0 && both > 0 ? "; " : "")
                    + (both > 0 ? both + " page(s) with an untrustworthy text layer, read again by OCR" : "");
            reason = reason == null ? notes : reason + "; " + notes;
        }
        return new Result(new Outcome("DONE", laneName, detected, reason, partial, pages, ocrRan ? ocrPages : null, characters),
                rows);
    }

    private record Judged(Verdict verdict, Score score, String text) {
    }

    /** A text page judged; a reversed one put right and judged again - still wrong, it is garbage. */
    private static Judged judge(String layer) {
        Score score = TextQuality.score(layer);
        if (score.verdict() != Verdict.REVERSED) {
            return new Judged(score.verdict(), score, layer);
        }
        String repaired = TextQuality.repaired(layer);
        Score again = TextQuality.score(repaired);
        return again.verdict() == Verdict.SOUND
                ? new Judged(Verdict.REVERSED, again, repaired)
                : new Judged(Verdict.GARBAGE, score, layer);
    }

    private static String ocrOf(TikaReading reading, int pageNumber) {
        return reading.units().stream()
                .filter(unit -> unit.kind() == Kind.PAGE && unit.number() == pageNumber)
                .map(Unit::ocr).findFirst().orElse("");
    }

    /** A unit's text in parts of at most {@link #PART_CHARACTERS}, each cut at a space where it can be. */
    private static void addParts(List<PageRow> rows, Unit unit, String text, String source, Integer score) {
        String unitName = unit.kind().name();
        int part = 0;
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + PART_CHARACTERS);
            if (end < text.length()) {
                int space = text.lastIndexOf(' ', end);
                int newline = text.lastIndexOf('\n', end);
                int cut = Math.max(space, newline);
                if (cut > start + PART_CHARACTERS / 2) {
                    end = cut;
                } else if (Character.isHighSurrogate(text.charAt(end - 1))) {
                    end--;
                }
            }
            String piece = text.substring(start, end).strip();
            if (!piece.isEmpty()) {
                rows.add(new PageRow(unit.number(), part++, unitName, unit.label(), source, score, piece,
                        ContentFolding.fold(piece)));
            }
            start = end;
        }
    }

    private static Result skipped(String reason) {
        return new Result(new Outcome("SKIPPED", null, null, reason, false, null, null, 0), List.of());
    }
}
