package com.hnp.filemanagement.report;

import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderAccess;
import com.hnp.filemanagement.folder.domain.FolderAccessService;
import com.hnp.filemanagement.folder.domain.FolderKind;
import com.hnp.filemanagement.folder.domain.FolderReadScope;
import com.hnp.filemanagement.folder.domain.FolderTrails;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.report.ReportView.Column;
import com.hnp.filemanagement.report.ReportView.FileCell;
import com.hnp.filemanagement.report.ReportView.FolderCell;
import com.hnp.filemanagement.report.ReportView.Kind;
import com.hnp.filemanagement.report.ReportView.Note;
import com.hnp.filemanagement.report.ReportView.Row;
import com.hnp.filemanagement.report.ReportView.Stat;
import com.hnp.filemanagement.report.ReportView.Table;
import com.hnp.filemanagement.shared.config.FileManagementProperties;
import com.hnp.filemanagement.shared.util.JalaliDate;
import com.hnp.filemanagement.shared.util.JalaliDate.JalaliMonth;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The reports on the files (roadmap 12.9): each one counted over the folders its reader may read -
 * an administrator's over everything - and made into a {@link ReportView}. Months are Jalali months in
 * the installation's zone; a period is the last {@code months} of them, the current one included.
 */
@Service
public class ReportService {

    /** The periods a report of months may cover. */
    public static final List<Integer> MONTHS = List.of(6, 12, 24, 36);
    public static final int DEFAULT_MONTHS = 12;
    /** How long a file may go undownloaded before it is cold. */
    public static final List<Integer> COLD_DAYS = List.of(90, 180, 365, 730);
    public static final int DEFAULT_COLD_DAYS = 365;
    /** A quota this full is said to be nearly full. */
    static final double NEARLY_FULL = 0.9;
    /** A write still unsettled after this long is one the sweeper should have settled. */
    static final Duration UNSETTLED = Duration.ofHours(1);
    /** Days of the night's counts the projection is drawn through, and the fewest it needs. */
    static final int PROJECTION_DAYS = 90;
    static final int FEWEST_SNAPSHOTS = 14;

    static final int TOP_FOLDERS = 100;
    static final int LIST_ROWS = 50;

    /** Counted afresh on every view: one level of folders, a few thousand rows at most. */
    private static final Set<Report> UNCACHED = Set.of(Report.FOLDERS);
    private static final List<String> CHANNELS = List.of("PAGE", "PREVIEW", "PUBLIC", "SHARE_LINK", "API_V1", "API_V2", "S3");

    private final ReportRepository repository;
    private final FolderAccessService folderAccessService;
    private final FolderRepository folderRepository;
    private final FolderTrails folderTrails;
    private final ReportProperties properties;
    private final FileManagementProperties.Downloads downloads;
    private final JalaliDate jalali;
    private final ReportFormat format;
    private final Clock clock;
    private final ReportCache cache;

    public ReportService(ReportRepository repository, FolderAccessService folderAccessService, FolderRepository folderRepository,
                         FolderTrails folderTrails, ReportProperties properties, FileManagementProperties fileManagementProperties,
                         JalaliDate jalali, ReportFormat format, Clock clock) {
        this.repository = repository;
        this.folderAccessService = folderAccessService;
        this.folderRepository = folderRepository;
        this.folderTrails = folderTrails;
        this.properties = properties;
        this.downloads = fileManagementProperties.downloads();
        this.jalali = jalali;
        this.format = format;
        this.clock = clock;
        this.cache = new ReportCache(Duration.ofMinutes(properties.cacheMinutes()), clock);
    }

    /**
     * What a report is asked: {@code folder} for the folders report (null: the top level), {@code months}
     * for one over a period, {@code days} for the cold files - each brought to the nearest it may be.
     */
    public record Request(Report report, Integer folder, Integer months, Integer days) {

        public Request {
            months = months != null && MONTHS.contains(months) ? months : DEFAULT_MONTHS;
            days = days != null && COLD_DAYS.contains(days) ? days : DEFAULT_COLD_DAYS;
            folder = report == Report.FOLDERS ? folder : null;
        }

        String key() {
            return report.slug() + "|" + folder + "|" + (report.takesMonths() ? months : "") + "|" + (report == Report.COLD ? days : "");
        }
    }

    @Transactional(readOnly = true)
    public ReportView view(Request request, int principalId) {
        FolderAccess access = folderAccessService.accessFor(principalId);
        FolderReadScope scope = folderAccessService.readScope(access, principalId);
        if (scope.nothing()) {
            return new ReportView(request.report(), List.of(), List.of(), List.of(Note.of("report.nothingReadable")),
                    Instant.now(clock));
        }
        if (UNCACHED.contains(request.report())) {
            return count(request, scope, access);
        }
        String reader = scope.unrestricted() ? "all" : "u" + scope.userId() + "k" + scope.apiKeyId();
        return cache.get(reader + "|" + request.key(), () -> count(request, scope, access));
    }

    /** The folders report's folder as its trail of links, root first left out - only if the reader may walk into it. */
    @Transactional(readOnly = true)
    public List<FolderCell> trailOf(Integer folderId, int principalId) {
        if (folderId == null) {
            return List.of();
        }
        Optional<Folder> folder = folderRepository.findById(folderId);
        if (folder.isEmpty() || folder.get().getKind() == FolderKind.ROOT
                || !folderAccessService.accessFor(principalId).visible(folder.get().getPath())) {
            return List.of();
        }
        FolderTrails.Names names = folderTrails.of(List.of(folder.get().getPath()));
        List<FolderCell> trail = new ArrayList<>();
        for (Integer id : FolderTrails.idsOf(folder.get().getPath())) {
            String name = names.byId().get(id);
            if (name != null) {
                trail.add(new FolderCell(id, name));
            }
        }
        return trail;
    }

    private ReportView count(Request request, FolderReadScope scope, FolderAccess access) {
        Instant countedAt = Instant.now(clock);
        repository.planFor(scope);
        Parts parts = new Parts();
        switch (request.report()) {
            case OVERVIEW -> overview(scope, parts);
            case FOLDERS -> folders(scope, access, request.folder(), parts);
            case GROWTH -> growth(scope, request.months(), parts);
            case KINDS -> kinds(scope, parts);
            case LARGEST -> largest(scope, parts);
            case VERSIONS -> versions(scope, parts);
            case UPLOADERS -> uploaders(scope, request.months(), parts);
            case DOWNLOADS -> downloadsReport(scope, request.months(), parts);
            case COLD -> cold(scope, request.days(), parts);
            case DUPLICATES -> duplicates(scope, parts);
            case QUOTAS -> quotas(scope, parts);
            case COVERAGE -> coverage(scope, parts);
            case INTEGRITY -> integrity(scope, parts);
        }
        return new ReportView(request.report(), List.copyOf(parts.stats), List.copyOf(parts.tables), List.copyOf(parts.notes),
                countedAt);
    }

    /** A report's parts, as they are counted. */
    private static final class Parts {
        final List<Stat> stats = new ArrayList<>();
        final List<Table> tables = new ArrayList<>();
        final List<Note> notes = new ArrayList<>();

        void stat(String code, Kind kind, Object value) {
            stats.add(new Stat(code, kind, value, null));
        }

        void stat(String code, Kind kind, Object value, Note hint) {
            stats.add(new Stat(code, kind, value, hint));
        }
    }

    // ------------------------------------------------------------------ overview and folders

    private void overview(FolderReadScope scope, Parts parts) {
        ReportRepository.Totals totals = repository.totals(scope);
        totalsStats(totals, parts);
        parts.tables.add(folderTable("folders", "report.overview.byTopFolder", scope, rootPath(), totals.bytes(), parts));
        if (!scope.unrestricted()) {
            parts.notes.add(Note.of("report.scoped"));
        }
    }

    private void totalsStats(ReportRepository.Totals totals, Parts parts) {
        parts.stat("report.stat.files", Kind.COUNT, totals.files());
        parts.stat("report.stat.revisions", Kind.COUNT, totals.revisions());
        parts.stat("report.stat.bytes", Kind.BYTES, totals.bytes());
        parts.stat("report.stat.latestBytes", Kind.BYTES, totals.latestBytes(), Note.of("report.stat.latestBytes.hint"));
        parts.stat("report.stat.olderBytes", Kind.BYTES, totals.olderBytes(),
                Note.of("report.stat.olderBytes.hint", format.percent(share(totals.olderBytes(), totals.bytes()))));
        parts.stat("report.stat.folders", Kind.COUNT, totals.folders());
    }

    private void folders(FolderReadScope scope, FolderAccess access, Integer folderId, Parts parts) {
        String path = rootPath();
        if (folderId != null) {
            Optional<Folder> folder = folderRepository.findById(folderId);
            if (folder.isEmpty() || !access.visible(folder.get().getPath())) {
                // Not there, or not the reader's to see: the same answer, so that neither is told apart.
                parts.notes.add(Note.of("report.folders.notFound"));
                return;
            }
            path = folder.get().getPath();
        }
        if (path == null) {
            return;
        }
        parts.tables.add(folderTable("folders", "report.folders.table", scope, path, -1, parts));
    }

    /**
     * A folder's children by what is beneath each, the largest first; the rest of them, past the
     * first {@link #TOP_FOLDERS}, summed in one row. {@code whole} is what the shares are of (-1: the folder's own).
     */
    private Table folderTable(String id, String titleCode, FolderReadScope scope, String parentPath, long whole, Parts parts) {
        List<Column> columns = List.of(new Column("report.column.folder", Kind.FOLDER), new Column("report.column.files", Kind.COUNT),
                new Column("report.column.revisions", Kind.COUNT), new Column("report.column.bytes", Kind.BYTES),
                new Column("report.column.latestBytes", Kind.BYTES), new Column("report.column.share", Kind.PERCENT));
        if (parentPath == null) {
            return new Table(id, titleCode, columns, List.of(), 3, "report.empty");
        }
        List<ReportRepository.FolderRow> found = repository.below(scope, parentPath, TOP_FOLDERS);
        long of = whole >= 0 ? whole : found.isEmpty() ? 0 : found.getFirst().allBytes();
        List<List<Object>> rows = new ArrayList<>();
        long shownFiles = 0;
        long shownBytes = 0;
        for (ReportRepository.FolderRow row : found) {
            FolderCell folder = row.childId() == null ? new FolderCell(null, format.label("report.folders.here"))
                    : new FolderCell(row.childId(), row.name());
            rows.add(Arrays.asList(folder, row.files(), row.revisions(), row.bytes(), row.latestBytes(), share(row.bytes(), of)));
            shownFiles += row.files();
            shownBytes += row.bytes();
        }
        if (!found.isEmpty() && found.getFirst().children() > found.size()) {
            ReportRepository.FolderRow first = found.getFirst();
            long restBytes = first.allBytes() - shownBytes;
            rows.add(Arrays.asList(new FolderCell(null, format.label("report.folders.others",
                            format.count(first.children() - found.size()))),
                    first.allFiles() - shownFiles, null, restBytes, null, share(restBytes, of)));
            parts.notes.add(Note.of("report.folders.capped", format.count(TOP_FOLDERS), format.count(first.children())));
        }
        return new Table(id, titleCode, columns, withBars(rows, 3), 3, "report.empty");
    }

    // ------------------------------------------------------------------ growth

    private void growth(FolderReadScope scope, int months, Parts parts) {
        List<JalaliMonth> period = period(months);
        List<Instant> bounds = bounds(period);

        Map<Integer, ReportRepository.Arrivals> arrivals = new HashMap<>();
        repository.arrivals(scope, bounds).forEach(a -> arrivals.put(a.bucket(), a));
        Map<Integer, Long> newFiles = new HashMap<>();
        repository.newFiles(scope, bounds).forEach(f -> newFiles.put((int) f[0], f[1]));
        Map<Integer, ReportRepository.Deletions> deletions = new HashMap<>();
        repository.deletions(scope, bounds).forEach(d -> deletions.put(d.bucket(), d));
        LocalDate firstDay = LocalDate.ofInstant(bounds.getFirst(), clock.getZone());
        List<ReportRepository.Stored> stored = repository.stored(scope, firstDay.minusDays(PROJECTION_DAYS));

        // Stored at each month's end: the last night counted in it.
        Map<JalaliMonth, ReportRepository.Stored> storedByMonth = new HashMap<>();
        for (ReportRepository.Stored day : stored) {
            storedByMonth.put(jalali.monthOf(day.day().atStartOfDay(clock.getZone()).toInstant()), day);
        }

        long cumulative = arrivals.containsKey(0) ? arrivals.get(0).bytes() : 0;
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < period.size(); i++) {
            int bucket = i + 1;
            ReportRepository.Arrivals arrived = arrivals.get(bucket);
            ReportRepository.Deletions deleted = deletions.get(bucket);
            long bytes = arrived == null ? 0 : arrived.bytes();
            cumulative += bytes;
            ReportRepository.Stored atEnd = storedByMonth.get(period.get(i));
            rows.add(Arrays.asList(period.get(i), newFiles.getOrDefault(bucket, 0L), arrived == null ? 0L : arrived.revisions(),
                    bytes, cumulative, deleted == null ? 0L : deleted.files(), deleted == null ? 0L : deleted.revisions(),
                    atEnd == null ? null : atEnd.bytes()));
        }
        parts.tables.add(new Table("months", "report.growth.table", List.of(
                new Column("report.column.month", Kind.MONTH), new Column("report.column.newFiles", Kind.COUNT),
                new Column("report.column.revisions", Kind.COUNT), new Column("report.column.bytesArrived", Kind.BYTES),
                new Column("report.column.keptSoFar", Kind.BYTES), new Column("report.column.filesDeleted", Kind.COUNT),
                new Column("report.column.revisionsDeleted", Kind.COUNT), new Column("report.column.storedAtEnd", Kind.BYTES)),
                withBars(rows, 3), 3, "report.empty"));

        // The rate: from the nights counted when there are enough of them, else from what arrived.
        double perDay;
        if (stored.size() >= FEWEST_SNAPSHOTS) {
            perDay = slopePerDay(stored.subList(Math.max(0, stored.size() - PROJECTION_DAYS), stored.size()));
            parts.notes.add(Note.of("report.growth.rateFromSnapshots", format.count(Math.min(stored.size(), PROJECTION_DAYS))));
        } else {
            perDay = arrivedPerDay(rows, period);
            parts.notes.add(Note.of("report.growth.rateFromArrivals"));
        }
        long now = cumulative;
        parts.stat("report.stat.bytes", Kind.BYTES, now);
        parts.stat("report.growth.perMonth", Kind.BYTES, Math.round(perDay * 30.4375));
        long capacity = properties.capacityBytes();
        if (scope.unrestricted() && capacity > 0) {
            parts.stat("report.growth.capacity", Kind.BYTES, capacity, Note.of("report.growth.capacity.hint",
                    format.percent(share(now, capacity))));
            if (now >= capacity) {
                parts.stat("report.growth.full", Kind.TEXT, format.label("report.growth.fullAlready"));
            } else if (perDay > 0) {
                long days = (long) Math.ceil((capacity - now) / perDay);
                LocalDate full = LocalDate.now(clock).plusDays(Math.min(days, 365L * 100));
                parts.stat("report.growth.full", Kind.DATE, full.atStartOfDay(clock.getZone()).toInstant(),
                        Note.of("report.growth.full.hint", format.count(days)));
            } else {
                parts.stat("report.growth.full", Kind.TEXT, format.label("report.growth.notGrowing"));
            }
        } else if (scope.unrestricted()) {
            parts.notes.add(Note.of("report.growth.noCapacity"));
        }
        if (stored.isEmpty()) {
            parts.notes.add(Note.of("report.growth.noSnapshots"));
        } else {
            parts.notes.add(Note.of("report.growth.snapshotsSince", jalali.format(stored.getFirst().day())));
        }
        parts.notes.add(Note.of("report.growth.arrivalsOfKept"));
        if (!scope.unrestricted()) {
            parts.notes.add(Note.of("report.growth.scoped"));
        }
    }

    /** The least-squares slope of the bytes stored, per day. */
    static double slopePerDay(List<ReportRepository.Stored> days) {
        if (days.size() < 2) {
            return 0;
        }
        LocalDate origin = days.getFirst().day();
        double n = days.size();
        double sumX = 0;
        double sumY = 0;
        double sumXY = 0;
        double sumXX = 0;
        for (ReportRepository.Stored day : days) {
            double x = ChronoUnit.DAYS.between(origin, day.day());
            double y = day.bytes();
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumXX += x * x;
        }
        double denominator = n * sumXX - sumX * sumX;
        return denominator == 0 ? 0 : (n * sumXY - sumX * sumY) / denominator;
    }

    /** What arrived per day over the last six whole months (the current one left out: it is not over). */
    private double arrivedPerDay(List<List<Object>> rows, List<JalaliMonth> period) {
        int whole = Math.min(6, period.size() - 1);
        if (whole <= 0) {
            return 0;
        }
        long bytes = 0;
        for (int i = period.size() - 1 - whole; i < period.size() - 1; i++) {
            bytes += (Long) rows.get(i).get(3);
        }
        long days = Duration.between(jalali.startOf(period.get(period.size() - 1 - whole)),
                jalali.startOf(period.getLast())).toDays();
        return days <= 0 ? 0 : (double) bytes / days;
    }

    // ------------------------------------------------------------------ kinds, largest, versions

    private void kinds(FolderReadScope scope, Parts parts) {
        ReportRepository.Totals totals = repository.totals(scope);
        List<ReportRepository.KindRow> found = repository.kinds(scope, TOP_FOLDERS);
        List<List<Object>> rows = new ArrayList<>();
        for (ReportRepository.KindRow row : found) {
            rows.add(Arrays.asList(row.kind(), row.latest(), row.revisions(), row.bytes(), share(row.bytes(), totals.bytes())));
        }
        parts.stat("report.kinds.count", Kind.COUNT, found.isEmpty() ? 0L : found.getFirst().kinds());
        parts.tables.add(new Table("kinds", "report.kinds.table", List.of(new Column("report.column.kind", Kind.TEXT),
                new Column("report.column.latestRevisions", Kind.COUNT), new Column("report.column.revisions", Kind.COUNT),
                new Column("report.column.bytes", Kind.BYTES), new Column("report.column.share", Kind.PERCENT)),
                withBars(rows, 3), 3, "report.empty"));
    }

    private void largest(FolderReadScope scope, Parts parts) {
        List<ReportRepository.RevisionRow> revisions = repository.largestRevisions(scope, LIST_ROWS);
        List<ReportRepository.OwnFilesRow> folders = repository.fullestFolders(scope, LIST_ROWS);
        FolderTrails.Names names = folderTrails.of(Stream.concat(revisions.stream().map(ReportRepository.RevisionRow::folderPath),
                folders.stream().map(ReportRepository.OwnFilesRow::folderPath)).toList());
        List<List<Object>> revisionRows = new ArrayList<>();
        for (ReportRepository.RevisionRow row : revisions) {
            revisionRows.add(Arrays.asList(file(row.fileId(), row.fileName(), row.folderPath(), names), (long) row.version(),
                    row.extension(), row.bytes(), row.createdAt()));
        }
        parts.tables.add(new Table("revisions", "report.largest.revisions", List.of(new Column("report.column.file", Kind.FILE),
                new Column("report.column.version", Kind.COUNT), new Column("report.column.kind", Kind.TEXT),
                new Column("report.column.bytes", Kind.BYTES), new Column("report.column.uploadedAt", Kind.DATE)),
                withBars(revisionRows, 3), 3, "report.empty"));
        List<List<Object>> folderRows = new ArrayList<>();
        for (ReportRepository.OwnFilesRow row : folders) {
            folderRows.add(Arrays.asList(new FolderCell(row.folderId(), String.join(" / ", names.trail(row.folderPath()))),
                    row.files(), row.bytes()));
        }
        parts.tables.add(new Table("folders", "report.largest.folders", List.of(new Column("report.column.folder", Kind.FOLDER),
                new Column("report.column.filesInIt", Kind.COUNT), new Column("report.column.bytes", Kind.BYTES)),
                withBars(folderRows, 2), 2, "report.empty"));
    }

    private void versions(FolderReadScope scope, Parts parts) {
        ReportRepository.Totals totals = repository.totals(scope);
        List<ReportRepository.VersionedRow> found = repository.versioned(scope, LIST_ROWS);
        parts.stat("report.stat.olderBytes", Kind.BYTES, totals.olderBytes(),
                Note.of("report.stat.olderBytes.hint", format.percent(share(totals.olderBytes(), totals.bytes()))));
        parts.stat("report.versions.files", Kind.COUNT, found.isEmpty() ? 0L : found.getFirst().versionedFiles(),
                Note.of("report.versions.files.hint", format.percent(share(found.isEmpty() ? 0 : found.getFirst().versionedFiles(),
                        totals.files()))));
        FolderTrails.Names names = folderTrails.of(found.stream().map(ReportRepository.VersionedRow::folderPath).toList());
        List<List<Object>> rows = new ArrayList<>();
        for (ReportRepository.VersionedRow row : found) {
            rows.add(Arrays.asList(file(row.fileId(), row.fileName(), row.folderPath(), names), row.versions(), row.revisions(),
                    row.bytes(), row.olderBytes()));
        }
        parts.tables.add(new Table("files", "report.versions.table", List.of(new Column("report.column.file", Kind.FILE),
                new Column("report.column.versions", Kind.COUNT), new Column("report.column.revisions", Kind.COUNT),
                new Column("report.column.bytes", Kind.BYTES), new Column("report.column.olderBytes", Kind.BYTES)),
                withBars(rows, 4), 4, "report.versions.none"));
    }

    // ------------------------------------------------------------------ uploaders and downloads

    private void uploaders(FolderReadScope scope, int months, Parts parts) {
        List<JalaliMonth> period = period(months);
        List<Instant> bounds = bounds(period);
        List<ReportRepository.UploaderRow> found = repository.uploaders(scope, bounds.getFirst(), TOP_FOLDERS);
        long allBytes = found.stream().mapToLong(ReportRepository.UploaderRow::bytes).sum();
        List<List<Object>> rows = new ArrayList<>();
        for (ReportRepository.UploaderRow row : found) {
            String person = personOf(row);
            String who = row.apiKeyId() == null ? person : format.label("report.uploaders.key", row.keyTitle(), person);
            rows.add(Arrays.asList(who, row.apiKeyId() == null ? "report.uploaders.person" : "report.uploaders.apiKey",
                    row.files(), row.revisions(), row.bytes(), share(row.bytes(), allBytes)));
        }
        parts.tables.add(new Table("uploaders", "report.uploaders.table", List.of(new Column("report.column.uploader", Kind.TEXT),
                new Column("report.column.uploaderKind", Kind.LABEL), new Column("report.column.newFiles", Kind.COUNT),
                new Column("report.column.revisions", Kind.COUNT), new Column("report.column.bytes", Kind.BYTES),
                new Column("report.column.share", Kind.PERCENT)), withBars(rows, 4), 4, "report.empty"));
        if (found.size() >= TOP_FOLDERS) {
            parts.notes.add(Note.of("report.uploaders.capped", format.count(TOP_FOLDERS)));
        }

        Map<Integer, long[]> byMonth = new HashMap<>();
        for (ReportRepository.ShareRow row : repository.uploadShares(scope, bounds)) {
            long[] cells = byMonth.computeIfAbsent(row.bucket(), b -> new long[4]);
            int at = row.byKey() ? 2 : 0;
            cells[at] += row.revisions();
            cells[at + 1] += row.bytes();
        }
        List<List<Object>> monthRows = new ArrayList<>();
        for (int i = 0; i < period.size(); i++) {
            long[] cells = byMonth.getOrDefault(i + 1, new long[4]);
            monthRows.add(Arrays.asList(period.get(i), cells[0], cells[1], cells[2], cells[3], share(cells[3], cells[1] + cells[3])));
        }
        parts.tables.add(new Table("months", "report.uploaders.months", List.of(new Column("report.column.month", Kind.MONTH),
                new Column("report.column.peopleRevisions", Kind.COUNT), new Column("report.column.peopleBytes", Kind.BYTES),
                new Column("report.column.keysRevisions", Kind.COUNT), new Column("report.column.keysBytes", Kind.BYTES),
                new Column("report.column.keysShare", Kind.PERCENT)), withBars(monthRows, 2), 2, "report.empty"));
    }

    private static String personOf(ReportRepository.UploaderRow row) {
        String name = Stream.of(row.firstName(), row.lastName()).filter(part -> part != null && !part.isBlank())
                .reduce((a, b) -> a + " " + b).orElse("");
        if (row.username() == null) {
            return "#" + row.userId();
        }
        return name.isEmpty() ? row.username() : name + " (" + row.username() + ")";
    }

    private void downloadsReport(FolderReadScope scope, int months, Parts parts) {
        if (!downloads.enabled()) {
            parts.notes.add(Note.of("report.downloads.off"));
        }
        List<JalaliMonth> period = period(months);
        List<Instant> bounds = bounds(period);
        Map<Integer, Map<String, Long>> byMonth = new HashMap<>();
        for (ReportRepository.ChannelRow row : repository.downloadsByChannel(scope, bounds)) {
            byMonth.computeIfAbsent(row.bucket(), b -> new HashMap<>()).merge(row.channel(), row.downloads(), Long::sum);
        }
        List<Column> columns = new ArrayList<>();
        columns.add(new Column("report.column.month", Kind.MONTH));
        for (String channel : CHANNELS) {
            columns.add(new Column("fileDownloads.channel." + channel, Kind.COUNT));
        }
        columns.add(new Column("report.column.downloads", Kind.COUNT));
        List<List<Object>> rows = new ArrayList<>();
        long all = 0;
        for (int i = 0; i < period.size(); i++) {
            Map<String, Long> counts = byMonth.getOrDefault(i + 1, Map.of());
            List<Object> row = new ArrayList<>();
            row.add(period.get(i));
            long total = 0;
            for (String channel : CHANNELS) {
                long count = counts.getOrDefault(channel, 0L);
                row.add(count);
                total += count;
            }
            row.add(total);
            all += total;
            rows.add(row);
        }
        parts.stat("report.column.downloads", Kind.COUNT, all);
        parts.tables.add(new Table("months", "report.downloads.months", columns, withBars(rows, columns.size() - 1),
                columns.size() - 1, "report.empty"));

        List<ReportRepository.DownloadedRow> most = repository.mostDownloaded(scope, bounds.getFirst(), LIST_ROWS);
        FolderTrails.Names names = folderTrails.of(most.stream().map(ReportRepository.DownloadedRow::folderPath)
                .filter(path -> path != null).toList());
        List<List<Object>> mostRows = new ArrayList<>();
        for (ReportRepository.DownloadedRow row : most) {
            mostRows.add(Arrays.asList(new FileCell(row.fileId(), row.fileName(),
                    row.folderPath() == null ? "" : String.join(" / ", names.trail(row.folderPath()))), row.downloads(), row.lastAt()));
        }
        parts.tables.add(new Table("files", "report.downloads.files", List.of(new Column("report.column.file", Kind.FILE),
                new Column("report.column.downloads", Kind.COUNT), new Column("report.column.lastDownload", Kind.DATE)),
                withBars(mostRows, 1), 1, "report.empty"));
        if (downloads.retentionDays() > 0) {
            parts.notes.add(Note.of("report.downloads.retention", format.count(downloads.retentionDays())));
        }
    }

    // ------------------------------------------------------------------ cold and duplicate files

    private void cold(FolderReadScope scope, int days, Parts parts) {
        int asked = days;
        if (downloads.retentionDays() > 0 && days > downloads.retentionDays()) {
            days = downloads.retentionDays();
            parts.notes.add(Note.of("report.cold.capped", format.count(asked), format.count(days)));
        }
        if (!downloads.enabled()) {
            parts.notes.add(Note.of("report.downloads.off"));
        }
        Instant since = Instant.now(clock).minus(Duration.ofDays(days));
        ReportRepository.Totals totals = repository.totals(scope);
        List<ReportRepository.ColdRow> found = repository.cold(scope, since, LIST_ROWS);
        long files = found.isEmpty() ? 0 : found.getFirst().coldFiles();
        long bytes = found.isEmpty() ? 0 : found.getFirst().coldBytes();
        parts.stat("report.cold.files", Kind.COUNT, files, Note.of("report.cold.files.hint", format.percent(share(files, totals.files()))));
        parts.stat("report.cold.bytes", Kind.BYTES, bytes, Note.of("report.cold.bytes.hint", format.percent(share(bytes, totals.bytes()))));
        FolderTrails.Names names = folderTrails.of(found.stream().map(ReportRepository.ColdRow::folderPath).toList());
        List<List<Object>> rows = new ArrayList<>();
        for (ReportRepository.ColdRow row : found) {
            rows.add(Arrays.asList(file(row.fileId(), row.fileName(), row.folderPath(), names), row.createdAt(), row.bytes()));
        }
        parts.tables.add(new Table("files", "report.cold.table", List.of(new Column("report.column.file", Kind.FILE),
                new Column("report.column.uploadedAt", Kind.DATE), new Column("report.column.bytes", Kind.BYTES)),
                withBars(rows, 2), 2, "report.cold.none"));
        parts.notes.add(Note.of("report.cold.meaning", format.count(days)));
    }

    private void duplicates(FolderReadScope scope, Parts parts) {
        List<ReportRepository.DuplicateRow> found = repository.duplicates(scope, LIST_ROWS);
        parts.stat("report.duplicates.groups", Kind.COUNT, found.isEmpty() ? 0L : found.getFirst().groups());
        parts.stat("report.duplicates.wasted", Kind.BYTES, found.isEmpty() ? 0L : found.getFirst().allWasted(),
                Note.of("report.duplicates.wasted.hint"));
        FolderTrails.Names names = folderTrails.of(found.stream().map(ReportRepository.DuplicateRow::examplePath).toList());
        List<List<Object>> rows = new ArrayList<>();
        for (ReportRepository.DuplicateRow row : found) {
            rows.add(Arrays.asList(file(row.exampleFileId(), row.exampleName(), row.examplePath(), names), row.copies(), row.files(),
                    row.size(), row.wasted()));
        }
        parts.tables.add(new Table("groups", "report.duplicates.table", List.of(new Column("report.column.example", Kind.FILE),
                new Column("report.column.copies", Kind.COUNT), new Column("report.column.inFiles", Kind.COUNT),
                new Column("report.column.size", Kind.BYTES), new Column("report.column.wasted", Kind.BYTES)),
                withBars(rows, 4), 4, "report.duplicates.none"));
        parts.notes.add(Note.of("report.duplicates.checksums"));
    }

    // ------------------------------------------------------------------ quotas, coverage, integrity

    private void quotas(FolderReadScope scope, Parts parts) {
        List<ReportRepository.QuotaRow> found = repository.quotas(scope, NEARLY_FULL, TOP_FOLDERS);
        parts.stat("report.quotas.count", Kind.COUNT, found.isEmpty() ? 0L : found.getFirst().quotas());
        parts.stat("report.quotas.nearlyFull", Kind.COUNT, found.isEmpty() ? 0L : found.getFirst().nearlyFullCount(),
                Note.of("report.quotas.nearlyFull.hint", format.percent(NEARLY_FULL * 100)));
        FolderTrails.Names names = folderTrails.of(found.stream().map(ReportRepository.QuotaRow::folderPath).toList());
        List<List<Object>> rows = new ArrayList<>();
        for (ReportRepository.QuotaRow row : found) {
            rows.add(Arrays.asList(new FolderCell(row.folderId(), String.join(" / ", names.trail(row.folderPath()))), row.quota(),
                    row.used(), share(row.used(), row.quota())));
        }
        List<Row> withBars = new ArrayList<>();
        for (List<Object> row : rows) {
            withBars.add(new Row(row, (int) Math.min(100, Math.round((Double) row.get(3)))));
        }
        parts.tables.add(new Table("quotas", "report.quotas.table", List.of(new Column("report.column.folder", Kind.FOLDER),
                new Column("report.column.quota", Kind.BYTES), new Column("report.column.used", Kind.BYTES),
                new Column("report.column.usedShare", Kind.PERCENT)), withBars, 3, "report.quotas.none"));
    }

    private void coverage(FolderReadScope scope, Parts parts) {
        ReportRepository.MetadataCoverage metadata = repository.metadataCoverage(scope);
        parts.stat("report.coverage.describedFiles", Kind.PERCENT, share(metadata.describedRevisions(), metadata.latestRevisions()),
                Note.of("report.coverage.describedFiles.hint", format.count(metadata.describedRevisions()),
                        format.count(metadata.latestRevisions())));
        parts.stat("report.coverage.describedFolders", Kind.PERCENT, share(metadata.describedFolders(), metadata.folders()),
                Note.of("report.coverage.describedFolders.hint", format.count(metadata.describedFolders()), format.count(metadata.folders())));
        List<ReportRepository.ContentRow> contents = repository.contentCoverage(scope);
        long all = metadata.revisions();
        Map<String, Long> byState = new LinkedHashMap<>();
        long ocr = 0;
        long queued = 0;
        for (ReportRepository.ContentRow row : contents) {
            byState.merge("contentExtraction.state." + row.state(), row.revisions(), Long::sum);
            queued += row.revisions();
            if ("DONE".equals(row.state()) && "OCR".equals(row.lane())) {
                ocr += row.revisions();
            }
        }
        if (all > queued) {
            byState.put("report.coverage.notQueued", all - queued);
        }
        long read = byState.getOrDefault("contentExtraction.state.DONE", 0L);
        parts.stat("report.coverage.read", Kind.PERCENT, share(read, all),
                Note.of("report.coverage.read.hint", format.count(read), format.count(ocr)));
        List<List<Object>> rows = new ArrayList<>();
        byState.forEach((code, count) -> rows.add(Arrays.asList(code, count, share(count, all))));
        parts.tables.add(new Table("contents", "report.coverage.contents", List.of(new Column("report.column.state", Kind.LABEL),
                new Column("report.column.revisions", Kind.COUNT), new Column("report.column.share", Kind.PERCENT)),
                withBars(rows, 1), 1, "report.empty"));
    }

    private void integrity(FolderReadScope scope, Parts parts) {
        if (!scope.unrestricted()) {
            parts.notes.add(Note.of("report.integrity.unrestrictedOnly"));
            return;
        }
        ReportRepository.Integrity found = repository.integrity(Instant.now(clock).minus(UNSETTLED));
        parts.stat("report.integrity.withoutChecksum", Kind.COUNT, found.withoutChecksum(),
                Note.of("report.integrity.withoutChecksum.hint", format.bytes(found.withoutChecksumBytes())));
        parts.stat("report.integrity.unsettled", Kind.COUNT, found.unsettledWrites(), found.oldestUnsettled() == null
                ? Note.of("report.integrity.unsettled.hint")
                : Note.of("report.integrity.unsettled.since", jalali.format(found.oldestUnsettled())));
        parts.stat("report.integrity.withoutRevisions", Kind.COUNT, found.filesWithoutRevisions(),
                Note.of("report.integrity.withoutRevisions.hint"));
        parts.stat("report.integrity.withoutLatest", Kind.COUNT, found.filesWithoutLatest(),
                Note.of("report.integrity.withoutLatest.hint"));
        parts.stat("report.integrity.contentFailed", Kind.COUNT, found.contentFailed(), Note.of("report.integrity.contentFailed.hint"));
        parts.notes.add(Note.of("report.integrity.meaning"));
    }

    // ------------------------------------------------------------------ helpers

    /** The last {@code months} Jalali months, the current one last. */
    List<JalaliMonth> period(int months) {
        JalaliMonth current = jalali.monthOf(Instant.now(clock));
        List<JalaliMonth> period = new ArrayList<>(months);
        for (int i = months - 1; i >= 0; i--) {
            period.add(current.plus(-i));
        }
        return period;
    }

    /** Each month's start, and the end of the last: bucket {@code i + 1} is month {@code i}. */
    List<Instant> bounds(List<JalaliMonth> period) {
        List<Instant> bounds = new ArrayList<>(period.size() + 1);
        for (JalaliMonth month : period) {
            bounds.add(jalali.startOf(month));
        }
        bounds.add(jalali.startOf(period.getLast().plus(1)));
        return bounds;
    }

    private String rootPath() {
        List<Folder> roots = folderRepository.findRoots();
        return roots.size() == 1 ? roots.getFirst().getPath() : null;
    }

    private static FileCell file(int fileId, String name, String path, FolderTrails.Names names) {
        return new FileCell(fileId, name, String.join(" / ", names.trail(path)));
    }

    /** {@code part} as a percentage of {@code whole}; 0 of nothing. */
    static double share(long part, long whole) {
        return whole <= 0 ? 0 : 100.0 * part / whole;
    }

    /** The rows with their bars: each one's value in {@code column} against the largest. */
    static List<Row> withBars(List<List<Object>> rows, int column) {
        long largest = 0;
        for (List<Object> row : rows) {
            if (row.get(column) instanceof Number number) {
                largest = Math.max(largest, number.longValue());
            }
        }
        List<Row> out = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            int bar = largest > 0 && row.get(column) instanceof Number number
                    ? (int) Math.round(100.0 * Math.max(0, number.longValue()) / largest) : 0;
            out.add(new Row(row, bar));
        }
        return out;
    }
}
