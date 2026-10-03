package com.hnp.filemanagement.storage.copy;

import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.storage.CopyableStore;
import com.hnp.filemanagement.storage.CopyableStore.ObjectFacts;
import com.hnp.filemanagement.storage.FilesystemBlobStore;
import com.hnp.filemanagement.storage.StorageKey;
import com.hnp.filemanagement.storage.copy.StorageCopySettings.Mode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Copies the bytes of every revision from one store to the other, verifying each against the
 * SHA-256 its row records (roadmap 4.4) - the move to the object store, and the way back.
 *
 * <p><b>What it reads and what it writes.</b> The rows - {@code file_details}, and the journal of
 * writes in flight - only ever read, never written. The source store only read. The target only
 * written by {@link CopyableStore#copyIn}, which leaves an object at a key only once the bytes have
 * hashed to the row's checksum, and deleted only by {@link Mode#PRUNE} with
 * {@code confirm}.
 *
 * <p><b>Each revision</b>, in id order and {@code threads} at a time:
 *
 * <ul>
 *   <li><b>in the target already</b>: checked, never replaced. An object store's own checksum of the
 *       whole object, or - for one written in parts, whose checksum is only a composite - the
 *       SHA-256 this copy verified it against before making it visible; otherwise, or with
 *       {@code deep-verify}, it is read back. On the filesystem, {@code copy} takes an equal size
 *       (a file this copy wrote was renamed into place whole and verified; the directory is
 *       otherwise the service's own) and {@code verify} reads every one.</li>
 *   <li><b>not in the target</b> ({@code copy}): read from the source, hashed on the way, and
 *       written only if the hash is the row's - a source whose bytes have changed is reported, not
 *       copied.</li>
 *   <li><b>gone meanwhile</b> - the row deleted while the service runs: nothing to do.</li>
 * </ul>
 *
 * <p><b>Resumable</b>: everything verified in the target is skipped, so a second run copies only
 * what the first did not - which is the window's second pass. <b>Never silent</b>: every revision
 * it could not copy or verify is a line in the report (a CSV beside the log) and makes the run
 * fail; a revision without a recorded checksum is verified against the source's own bytes and
 * listed as a warning.
 */
public class StorageCopy {

    private static final Logger logger = LoggerFactory.getLogger(StorageCopy.class);

    /** Rows read, and handed to the threads, at a time. */
    static final int PAGE = 200;

    /** The run will not start, or will not delete: said, and nothing done. */
    public static final class Refused extends RuntimeException {
        public Refused(String message) {
            super(message);
        }
    }

    /** What happened to one revision, or - in prune - to one object. */
    public enum Outcome {
        /** Copied and verified. */
        COPIED(false),
        /** In the target, and verified against the row's checksum. */
        VERIFIED(false),
        /** In the target on the filesystem with the source's size - copy's check (see the class comment). */
        SAME_SIZE(false),
        /** The row was deleted while the copy ran. */
        GONE(false),
        /** Verify: the target does not hold it. */
        MISSING_AT_TARGET(true),
        /** The source does not hold it - nothing to copy from. */
        MISSING_AT_SOURCE(true),
        /** The source's bytes do not hash to the row's checksum; not copied. */
        SOURCE_CORRUPT(true),
        /** The target holds something else at the key; left as it is. */
        MISMATCH_AT_TARGET(true),
        /** A store or the database failed; see the log. */
        FAILED(true),
        /** Prune without confirm: would be deleted. */
        WOULD_DELETE(false),
        /** Prune: deleted. */
        DELETED(false),
        /** Prune: no row names it, but it was written too recently to be sure; kept. */
        KEPT_RECENT(false),
        /** Prune: could not be deleted. */
        DELETE_FAILED(true);

        private final boolean problem;

        Outcome(boolean problem) {
            this.problem = problem;
        }

        public boolean problem() {
            return problem;
        }
    }

    /** One row of {@code file_details}, as much as the copy needs. */
    record Revision(int id, String storageKey, long fileSize, String checksumSha256) {
    }

    /** A line of the report: an outcome or a warning, with the revision or object it is about. */
    public record Line(String what, Integer fileDetailsId, String storageKey, String detail) {
    }

    /**
     * What a run did.
     *
     * @param orphans objects in the target that no row names - counted by verify, -1 when not counted
     */
    public record Report(StorageCopySettings.Direction direction, Mode mode, Map<Outcome, Long> counts,
                         long bytesCopied, int warnings, long orphans, List<Line> lines, boolean stopped,
                         Duration took, Path reportFile) {

        public long count(Outcome outcome) {
            return counts.getOrDefault(outcome, 0L);
        }

        public long problems() {
            return counts.entrySet().stream().filter(entry -> entry.getKey().problem())
                    .mapToLong(Map.Entry::getValue).sum();
        }

        /** Nothing failed, nothing is missing, and the run was not stopped half-way. */
        public boolean succeeded() {
            return problems() == 0 && !stopped;
        }
    }

    private final JdbcTemplate jdbc;
    private final CopyableStore source;
    private final CopyableStore target;
    private final StorageCopySettings settings;
    private final Clock clock;
    private final boolean targetIsFilesystem;

    private final Map<Outcome, LongAdder> counts = new EnumMap<>(Outcome.class);
    private final List<Line> lines = Collections.synchronizedList(new ArrayList<>());
    private final LongAdder bytesCopied = new LongAdder();
    private final LongAdder warnings = new LongAdder();
    private volatile boolean stopping;

    public StorageCopy(JdbcTemplate jdbc, CopyableStore source, CopyableStore target, StorageCopySettings settings,
                       Clock clock) {
        this.jdbc = jdbc;
        this.source = source;
        this.target = target;
        this.settings = settings;
        this.clock = clock;
        this.targetIsFilesystem = target instanceof FilesystemBlobStore;
        for (Outcome outcome : Outcome.values()) {
            counts.put(outcome, new LongAdder());
        }
    }

    /** Asks a run to stop after the revisions in hand; what it did stays done, and the next run resumes. */
    public void stop() {
        stopping = true;
    }

    public Report run() {
        Instant started = clock.instant();
        long orphans = -1;
        switch (settings.mode()) {
            case COPY, VERIFY -> orphans = eachRevision();
            case PRUNE -> prune();
        }
        Map<Outcome, Long> totals = new EnumMap<>(Outcome.class);
        counts.forEach((outcome, adder) -> {
            if (adder.sum() > 0) {
                totals.put(outcome, adder.sum());
            }
        });
        List<Line> ordered;
        synchronized (lines) {
            ordered = new ArrayList<>(lines);
        }
        ordered.sort((a, b) -> Integer.compare(a.fileDetailsId() == null ? 0 : a.fileDetailsId(),
                b.fileDetailsId() == null ? 0 : b.fileDetailsId()));
        Duration took = Duration.between(started, clock.instant());
        Report report = new Report(settings.direction(), settings.mode(), totals, bytesCopied.sum(),
                warnings.intValue(), orphans, List.copyOf(ordered), stopping, took, null);
        Path file = writeReport(report);
        return new Report(report.direction(), report.mode(), report.counts(), report.bytesCopied(), report.warnings(),
                report.orphans(), report.lines(), report.stopped(), report.took(), file);
    }

    // ---------------------------------------------------------------- copy and verify

    /** Every revision after {@code afterId}; in verify, also the objects no row names - or -1. */
    private long eachRevision() {
        boolean countOrphans = settings.mode() == Mode.VERIFY && settings.afterId() == 0;
        Set<String> referenced = countOrphans ? new HashSet<>() : null;
        ExecutorService threads = Executors.newFixedThreadPool(settings.threads(), runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("storage-copy-" + thread.threadId());
            return thread;
        });
        AtomicLong done = new AtomicLong();
        Instant lastProgress = clock.instant();
        try {
            int afterId = settings.afterId();
            while (!stopping) {
                List<Revision> page = page(afterId);
                if (page.isEmpty()) {
                    break;
                }
                List<Future<?>> inFlight = new ArrayList<>(page.size());
                for (Revision revision : page) {
                    if (stopping) {
                        break;
                    }
                    if (referenced != null) {
                        referenced.add(revision.storageKey());
                    }
                    inFlight.add(threads.submit(() -> {
                        // Asked to stop: what is queued is left for the next run, what is in hand finishes.
                        if (stopping) {
                            return;
                        }
                        count(handle(revision));
                        done.incrementAndGet();
                    }));
                }
                for (Future<?> future : inFlight) {
                    await(future);
                }
                afterId = page.getLast().id();
                if (Duration.between(lastProgress, clock.instant()).toSeconds() >= 30) {
                    lastProgress = clock.instant();
                    logger.info("storage copy: {} revisions done, up to file_details id={}; {} copied ({}), {} problem(s)",
                            done.get(), afterId, counts.get(Outcome.COPIED).sum(), bytes(bytesCopied.sum()), problems());
                }
            }
        } finally {
            threads.shutdown();
        }
        if (referenced == null || stopping) {
            return -1;
        }
        referenced.addAll(journalKeys());
        LongAdder orphans = new LongAdder();
        target.forEachObject(object -> {
            if (!referenced.contains(object.key().value())) {
                orphans.increment();
                lines.add(new Line("ORPHAN_IN_TARGET", null, object.key().value(),
                        object.sizeBytes() + " bytes, written " + object.lastModified()));
            }
        });
        return orphans.sum();
    }

    private Outcome handle(Revision revision) {
        StorageKey key;
        try {
            key = StorageKey.of(revision.storageKey());
        } catch (RuntimeException e) {
            return problem(Outcome.FAILED, revision, "not a storage key: " + e.getMessage());
        }
        try {
            ObjectFacts inTarget = target.facts(key).orElse(null);
            if (inTarget == null) {
                if (settings.mode() == Mode.VERIFY) {
                    return stillThere(revision) ? problem(Outcome.MISSING_AT_TARGET, revision, null) : Outcome.GONE;
                }
                return copy(revision, key);
            }
            return check(revision, key, inTarget);
        } catch (RuntimeException e) {
            if (!stillThere(revision)) {
                return Outcome.GONE;
            }
            logger.error("storage copy: file_details id=" + revision.id() + " key=" + revision.storageKey() + " failed", e);
            return problem(Outcome.FAILED, revision, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Not in the target: copied, if the source holds it and its bytes are the row's. */
    private Outcome copy(Revision revision, StorageKey key) {
        if (source.facts(key).isEmpty()) {
            return stillThere(revision) ? problem(Outcome.MISSING_AT_SOURCE, revision, null) : Outcome.GONE;
        }
        String expected = expectedChecksum(revision, key);
        try (InputStream in = source.open(key).getInputStream()) {
            CopyableStore.CopiedObject copied = target.copyIn(key, in, expected);
            bytesCopied.add(copied.sizeBytes());
            if (copied.sizeBytes() != revision.fileSize()) {
                warn("SIZE_DIFFERS_FROM_ROW", revision, copied.sizeBytes() + " bytes, file_size says " + revision.fileSize());
            }
            return Outcome.COPIED;
        } catch (CopyableStore.ChecksumMismatchException e) {
            return problem(Outcome.SOURCE_CORRUPT, revision, "the source holds " + e.actualSize() + " bytes hashing to "
                    + e.actualSha256() + ", the row records " + expected);
        } catch (DuplicateResourceException e) {
            // Written meanwhile by another run: checked like any object already there.
            return target.facts(key).map(facts -> check(revision, key, facts))
                    .orElseGet(() -> problem(Outcome.FAILED, revision, "refused as a duplicate, then not found"));
        } catch (ResourceNotFoundException e) {
            return stillThere(revision) ? problem(Outcome.MISSING_AT_SOURCE, revision, null) : Outcome.GONE;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** In the target already: is it the row's revision? */
    private Outcome check(Revision revision, StorageKey key, ObjectFacts inTarget) {
        String expected = expectedChecksum(revision, key);
        boolean deep = settings.deepVerify() || (targetIsFilesystem && settings.mode() == Mode.VERIFY);
        if (!deep) {
            if (inTarget.sha256() != null) {
                return verdict(revision, inTarget.sha256(), expected, "the store's checksum");
            }
            if (inTarget.compositeChecksum() != null && inTarget.recordedSha256() != null) {
                return verdict(revision, inTarget.recordedSha256(), expected, "the checksum it was copied against");
            }
            if (targetIsFilesystem) {
                long sourceSize = source.facts(key).map(ObjectFacts::sizeBytes).orElse(revision.fileSize());
                return inTarget.sizeBytes() == sourceSize ? Outcome.SAME_SIZE
                        : problem(Outcome.MISMATCH_AT_TARGET, revision,
                        "the target holds " + inTarget.sizeBytes() + " bytes, the source " + sourceSize);
            }
        }
        Hashed read = hash(target, key);
        return verdict(revision, read.sha256(), expected, "its bytes read back (" + read.size() + ")");
    }

    private Outcome verdict(Revision revision, String found, String expected, String how) {
        return found.equals(expected) ? Outcome.VERIFIED
                : problem(Outcome.MISMATCH_AT_TARGET, revision, "the target holds " + found + " by " + how
                + ", the row records " + expected);
    }

    /** The row's checksum; for a row without one, the source's own bytes - with a warning. */
    private String expectedChecksum(Revision revision, StorageKey key) {
        if (revision.checksumSha256() != null && !revision.checksumSha256().isBlank()) {
            return revision.checksumSha256();
        }
        String ofSource = hash(source, key).sha256();
        warn("NO_RECORDED_CHECKSUM", revision, "verified against the source's own bytes, " + ofSource);
        return ofSource;
    }

    // ---------------------------------------------------------------- prune

    /**
     * Deletes the objects in the target that no row names. The listing comes first and the rows
     * after it, the journal of writes in flight before the rows: an object an upload is writing
     * has its journal entry before its bytes and its row before the entry is cleared, so whatever
     * the listing found is named by one or the other - and anything written within
     * {@code quiet-minutes} is kept regardless.
     */
    private void prune() {
        List<CopyableStore.ListedObject> listed = new ArrayList<>();
        target.forEachObject(listed::add);
        Set<String> referenced = new HashSet<>(journalKeys());
        List<String> rowKeys = jdbc.queryForList("SELECT storage_key FROM file_details", String.class);
        if (rowKeys.isEmpty()) {
            throw new Refused("file_details has no rows: pruning against an empty database would delete everything;"
                    + " check spring.datasource.url");
        }
        referenced.addAll(rowKeys);

        Instant quietSince = clock.instant().minus(Duration.ofMinutes(settings.quietMinutes()));
        List<CopyableStore.ListedObject> candidates = new ArrayList<>();
        for (CopyableStore.ListedObject object : listed) {
            if (referenced.contains(object.key().value())) {
                continue;
            }
            if (object.lastModified() != null && object.lastModified().isAfter(quietSince)) {
                count(Outcome.KEPT_RECENT);
                lines.add(new Line(Outcome.KEPT_RECENT.name(), null, object.key().value(),
                        "written " + object.lastModified()));
                continue;
            }
            candidates.add(object);
        }
        logger.info("storage copy prune: {} object(s) in the target, {} named by a row or a write in flight, {} to delete",
                listed.size(), listed.size() - candidates.size() - counts.get(Outcome.KEPT_RECENT).sum(), candidates.size());
        if (candidates.size() > settings.maxPrune()) {
            throw new Refused(candidates.size() + " objects would be deleted, more than filemanagement.storage-copy.max-prune ("
                    + settings.maxPrune() + "); run without confirm to see them, and raise the limit if that is intended");
        }
        for (CopyableStore.ListedObject object : candidates) {
            String detail = object.sizeBytes() + " bytes, written " + object.lastModified();
            if (!settings.confirm()) {
                count(Outcome.WOULD_DELETE);
                lines.add(new Line(Outcome.WOULD_DELETE.name(), null, object.key().value(), detail));
                continue;
            }
            try {
                target.delete(object.key());
                count(Outcome.DELETED);
                lines.add(new Line(Outcome.DELETED.name(), null, object.key().value(), detail));
            } catch (RuntimeException e) {
                logger.error("storage copy prune: could not delete " + object.key(), e);
                count(Outcome.DELETE_FAILED);
                lines.add(new Line(Outcome.DELETE_FAILED.name(), null, object.key().value(), e.getMessage()));
            }
        }
    }

    // ---------------------------------------------------------------- the rows

    private List<Revision> page(int afterId) {
        return jdbc.query("""
                        SELECT id, storage_key, file_size, checksum_sha256 FROM file_details
                        WHERE id > ? ORDER BY id LIMIT ?""",
                (rs, n) -> new Revision(rs.getInt("id"), rs.getString("storage_key"), rs.getLong("file_size"),
                        rs.getString("checksum_sha256")),
                afterId, PAGE);
    }

    private boolean stillThere(Revision revision) {
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM file_details WHERE id = ? AND storage_key = ?",
                Integer.class, revision.id(), revision.storageKey());
        return rows != null && rows > 0;
    }

    private List<String> journalKeys() {
        return jdbc.queryForList("SELECT storage_key FROM file_storage_write", String.class);
    }

    // ---------------------------------------------------------------- helpers

    private record Hashed(String sha256, long size) {
    }

    private static Hashed hash(CopyableStore store, StorageKey key) {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[256 * 1024];
        long size = 0;
        try (InputStream in = store.open(key).getInputStream()) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                size += read;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + key + " failed", e);
        }
        return new Hashed(HexFormat.of().formatHex(digest.digest()), size);
    }

    private Outcome problem(Outcome outcome, Revision revision, String detail) {
        lines.add(new Line(outcome.name(), revision.id(), revision.storageKey(), detail));
        return outcome;
    }

    private void warn(String what, Revision revision, String detail) {
        warnings.increment();
        lines.add(new Line(what, revision.id(), revision.storageKey(), detail));
    }

    private void count(Outcome outcome) {
        counts.get(outcome).increment();
    }

    private long problems() {
        return counts.entrySet().stream().filter(entry -> entry.getKey().problem())
                .mapToLong(entry -> entry.getValue().sum()).sum();
    }

    private static void await(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("a revision's task failed unexpectedly", e.getCause());
        }
    }

    /** The report beside the log: a summary in comment lines, then one CSV line per entry. */
    private Path writeReport(Report report) {
        if (settings.reportDir() == null) {
            return null;
        }
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(clock.instant());
        Path file = settings.reportDir().resolve("storage-copy-" + report.direction() + "-" + report.mode() + "-"
                + stamp + "Z.csv");
        List<String> out = new ArrayList<>();
        out.add("# storage copy " + report.direction() + ", " + report.mode() + (report.stopped() ? ", STOPPED" : "")
                + ", took " + report.took());
        report.counts().forEach((outcome, n) -> out.add("# " + outcome + ": " + n));
        out.add("# bytes copied: " + report.bytesCopied() + ", warnings: " + report.warnings()
                + (report.orphans() >= 0 ? ", objects no row names: " + report.orphans() : ""));
        out.add("what,file_details_id,storage_key,detail");
        for (Line line : report.lines()) {
            out.add(csv(line.what()) + "," + (line.fileDetailsId() == null ? "" : line.fileDetailsId()) + ","
                    + csv(line.storageKey()) + "," + csv(line.detail()));
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, out, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            logger.error("storage copy: the report could not be written to " + file, e);
            return null;
        }
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    static String bytes(long bytes) {
        if (bytes < 1024 * 1024) {
            return bytes + " bytes";
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
