package com.hnp.filemanagement.report;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.domain.FileUploadDTO;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.folder.domain.UserFolderGrant;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.report.ReportView.FileCell;
import com.hnp.filemanagement.report.ReportView.FolderCell;
import com.hnp.filemanagement.shared.util.JalaliDate;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reports (roadmap 12.9), each counted over a small archive whose every figure is known: two
 * top-level folders, a reader granted one of them. A reader's figures are exactly those of the folders
 * granted - nothing of the other is counted, named or shown; an administrator's include both. The
 * cache is off here ({@code cache-minutes=0}): it is {@code ReportCacheTest}'s.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.reports.cache-minutes=0",
        "filemanagement.reports.capacity-gb=1"})
class ReportServiceTest extends DatabaseSupport {

    @Autowired
    private ReportService underTest;
    @Autowired
    private StorageSnapshotJob snapshots;
    @Autowired
    private FileService fileService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JalaliDate jalali;
    @Autowired
    private Clock clock;

    private int adminId;
    private int readerId;
    private FolderFixture.Chain a;
    private FolderFixture.Chain b;

    // In A: 100, 200 and 300 bytes in A's deepest folder, the last with a second version of 50; 400 in its
    // middle folder. In B: 1,000. The reader is granted A's top-level folder.
    private FileDetailsDTO small;
    private FileDetailsDTO medium;
    private FileDetailsDTO versioned;
    private int secondVersion;
    private FileDetailsDTO middle;
    private FileDetailsDTO inB;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        adminId = admin.getId();
        a = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        b = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        small = upload("small", a.tag(), 100, "txt");
        medium = upload("medium", a.tag(), 200, "txt");
        versioned = upload("versioned", a.tag(), 300, "txt");
        secondVersion = newVersion(versioned, 50);
        middle = upload("middle", a.subCategory(), 400, "csv");
        inB = upload("other", b.tag(), 1000, "txt");

        User reader = userRepository.save(TestData.user());
        reader.replaceFolderGrants(new ArrayList<>(List.of(new UserFolderGrant(reader, a.category(), FolderPermission.READ))));
        userRepository.save(reader);
        flush();
        readerId = reader.getId();
    }

    @Test
    @DisplayName("the overview: a reader's totals are exactly the granted folder's; an administrator's top level holds both")
    void overview() {
        ReportView view = view(Report.OVERVIEW, readerId);
        assertThat(stat(view, "report.stat.files")).isEqualTo(4L);
        assertThat(stat(view, "report.stat.revisions")).isEqualTo(5L);
        assertThat(stat(view, "report.stat.bytes")).isEqualTo(1050L);
        assertThat(stat(view, "report.stat.latestBytes")).isEqualTo(750L);
        assertThat(stat(view, "report.stat.olderBytes")).isEqualTo(300L);
        assertThat(rows(view, "folders")).singleElement().satisfies(row -> {
            assertThat(((FolderCell) row.get(0)).id()).isEqualTo(a.category().getId());
            assertThat(row.subList(1, 6)).containsExactly(4L, 5L, 1050L, 750L, 100.0);
        });
        assertThat(view.notes()).extracting(ReportView.Note::code).contains("report.scoped");

        List<List<Object>> top = rows(view(Report.OVERVIEW, adminId), "folders");
        assertThat(rowOf(top, a.category().getId()).get(3)).isEqualTo(1050L);
        assertThat(rowOf(top, b.category().getId()).get(3)).isEqualTo(1000L);
    }

    @Test
    @DisplayName("the folders: a level at a time, the files of the folder itself in a row of their own; a folder out of sight is not there")
    void folders() {
        List<List<Object>> level = rows(underTest.view(new ReportService.Request(Report.FOLDERS, a.category().getId(), null, null),
                readerId), "folders");
        assertThat(level).singleElement().satisfies(row -> assertThat(((FolderCell) row.get(0)).id()).isEqualTo(a.subCategory().getId()));

        List<List<Object>> below = rows(underTest.view(new ReportService.Request(Report.FOLDERS, a.subCategory().getId(), null, null),
                readerId), "folders");
        assertThat(below).hasSize(2);
        assertThat(((FolderCell) below.get(0).get(0)).id()).isEqualTo(a.tag().getId());
        assertThat(below.get(0).get(3)).isEqualTo(650L);
        assertThat(((FolderCell) below.get(1).get(0)).id()).as("the folder's own files").isNull();
        assertThat(below.get(1).get(3)).isEqualTo(400L);

        ReportView outside = underTest.view(new ReportService.Request(Report.FOLDERS, b.category().getId(), null, null), readerId);
        assertThat(outside.tables()).isEmpty();
        assertThat(outside.notes()).extracting(ReportView.Note::code).containsExactly("report.folders.notFound");
        assertThat(underTest.view(new ReportService.Request(Report.FOLDERS, Integer.MAX_VALUE, null, null), readerId).notes())
                .extracting(ReportView.Note::code).as("the same as one that does not exist").containsExactly("report.folders.notFound");
        assertThat(underTest.trailOf(b.category().getId(), readerId)).isEmpty();
        assertThat(underTest.trailOf(a.tag().getId(), readerId)).extracting(FolderCell::id)
                .containsExactly(a.category().getId(), a.subCategory().getId(), a.tag().getId());
    }

    @Test
    @DisplayName("growth: what arrived each Jalali month, what was deleted, what the night counted - and, for an administrator, when it is full")
    void growth() {
        JalaliDate.JalaliMonth now = jalali.monthOf(Instant.now(clock));
        Instant thisMonth = jalali.startOf(now).plus(Duration.ofHours(1));
        Instant twoAgo = jalali.startOf(now.plus(-2)).plus(Duration.ofHours(1));
        Instant longAgo = jalali.startOf(now.plus(-20));
        createdAt(small.getId(), thisMonth);
        createdAt(medium.getId(), twoAgo);
        createdAt(versioned.getId(), longAgo);
        createdAt(secondVersion, thisMonth);
        createdAt(middle.getId(), longAgo);
        jdbc.update("UPDATE file_info SET created_at = ? WHERE id = ?", Timestamp.from(longAgo), versioned.getFileInfoId());
        jdbc.update("UPDATE file_info SET created_at = ? WHERE id = ?", Timestamp.from(longAgo), middle.getFileInfoId());
        jdbc.update("UPDATE file_info SET created_at = ? WHERE id = ?", Timestamp.from(twoAgo), medium.getFileInfoId());
        jdbc.update("UPDATE file_info SET created_at = ? WHERE id = ?", Timestamp.from(thisMonth), small.getFileInfoId());
        fileService.deleteFileRows(medium.getFileInfoId(), adminId);
        flush();
        snapshots.take(LocalDate.now(clock));

        ReportView view = view(Report.GROWTH, readerId);
        List<List<Object>> months = rows(view, "months");
        assertThat(months).hasSize(12);
        List<Object> current = months.getLast();
        assertThat(current.get(0)).isEqualTo(now);
        assertThat(current.subList(1, 8)).as("new files, revisions, bytes, kept so far, files and revisions deleted, stored")
                .containsExactly(1L, 2L, 150L, 850L, 1L, 0L, 850L);
        assertThat(months.get(9).subList(1, 4)).as("the deleted file's month: it is no longer kept").containsExactly(0L, 0L, 0L);
        assertThat(months.getFirst().get(4)).as("everything older, kept so far").isEqualTo(700L);
        assertThat(view.stats()).extracting(ReportView.Stat::code).doesNotContain("report.growth.capacity");

        ReportView admins = view(Report.GROWTH, adminId);
        assertThat(admins.stats()).extracting(ReportView.Stat::code).contains("report.growth.capacity", "report.growth.full");
        assertThat(admins.notes()).extracting(ReportView.Note::code).contains("report.growth.rateFromArrivals",
                "report.growth.snapshotsSince");
    }

    @Test
    @DisplayName("the night's count: each top-level folder, every version and the latest, again in place the same day")
    void theNightsCount() {
        LocalDate today = LocalDate.now(clock);
        assertThat(snapshots.taken(today.minusYears(30))).isFalse();
        snapshots.take(today);
        snapshots.take(today);
        assertThat(jdbc.queryForList("SELECT files, revisions, bytes, latest_bytes FROM storage_snapshot WHERE day = ? AND folder_id = ?",
                java.sql.Date.valueOf(today), a.category().getId())).singleElement()
                .satisfies(row -> assertThat(List.of(row.get("files"), row.get("revisions"), row.get("bytes"), row.get("latest_bytes")))
                        .containsExactly(4L, 5L, 1050L, 750L));
        assertThat(snapshots.taken(today)).isTrue();
    }

    @Test
    @DisplayName("kinds, the largest, the versions: each counted over the reader's folders")
    void kindsLargestVersions() {
        assertThat(rows(view(Report.KINDS, readerId), "kinds")).extracting(row -> row.get(0), row -> row.get(3))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("txt", 650L), org.assertj.core.groups.Tuple.tuple("csv", 400L));

        ReportView largest = view(Report.LARGEST, readerId);
        assertThat(rows(largest, "revisions")).extracting(row -> ((FileCell) row.get(0)).fileId(), row -> row.get(3)).first()
                .isEqualTo(org.assertj.core.groups.Tuple.tuple(middle.getFileInfoId(), 400L));
        assertThat(rows(largest, "revisions")).hasSize(5);
        assertThat(rows(largest, "folders")).extracting(row -> ((FolderCell) row.get(0)).id())
                .containsExactly(a.tag().getId(), a.subCategory().getId());

        ReportView versions = view(Report.VERSIONS, readerId);
        assertThat(stat(versions, "report.stat.olderBytes")).isEqualTo(300L);
        assertThat(stat(versions, "report.versions.files")).isEqualTo(1L);
        assertThat(rows(versions, "files")).singleElement().satisfies(row ->
                assertThat(row.subList(1, 5)).containsExactly(2L, 2L, 350L, 300L));
    }

    @Test
    @DisplayName("uploaders: a person and an API key apart, the key named with its creator; a month's share of the keys")
    void uploaders() {
        int key = jdbc.queryForObject("""
                INSERT INTO api_key (key_id, secret_hash, title, enabled, created_at, created_by)
                VALUES (?, ?, 'ERP', 1, now(), ?) RETURNING id""", Integer.class,
                "k" + TestData.nextSequence(), "0".repeat(64), adminId);
        jdbc.update("UPDATE file_details SET created_by_api_key_id = ? WHERE id = ?", key, middle.getId());

        ReportView view = view(Report.UPLOADERS, readerId);
        List<List<Object>> rows = rows(view, "uploaders");
        assertThat(rows).extracting(row -> row.get(1), row -> row.get(4)).containsExactly(
                org.assertj.core.groups.Tuple.tuple("report.uploaders.person", 650L),
                org.assertj.core.groups.Tuple.tuple("report.uploaders.apiKey", 400L));
        assertThat((String) rows.get(1).get(0)).contains("ERP");
        List<Object> thisMonth = rows(view, "months").getLast();
        assertThat(thisMonth.subList(1, 5)).containsExactly(4L, 650L, 1L, 400L);
    }

    @Test
    @DisplayName("downloads by month and channel, the most downloaded first - of the reader's folders only")
    void downloads() {
        for (int i = 0; i < 3; i++) {
            download(medium, a.tag(), "PAGE", Instant.now(clock));
        }
        download(medium, a.tag(), "API_V1", Instant.now(clock));
        download(small, a.tag(), "S3", Instant.now(clock));
        download(inB, b.tag(), "PAGE", Instant.now(clock));

        ReportView view = view(Report.DOWNLOADS, readerId);
        assertThat(stat(view, "report.column.downloads")).isEqualTo(5L);
        List<Object> thisMonth = rows(view, "months").getLast();
        assertThat(thisMonth.get(1)).as("PAGE").isEqualTo(3L);
        assertThat(thisMonth.get(5)).as("API_V1").isEqualTo(1L);
        assertThat(thisMonth.get(7)).as("S3").isEqualTo(1L);
        assertThat(thisMonth.get(8)).isEqualTo(5L);
        assertThat(rows(view, "files")).extracting(row -> ((FileCell) row.get(0)).fileId(), row -> row.get(1)).containsExactly(
                org.assertj.core.groups.Tuple.tuple(medium.getFileInfoId(), 4L),
                org.assertj.core.groups.Tuple.tuple(small.getFileInfoId(), 1L));
    }

    @Test
    @DisplayName("cold files: stored long ago and not downloaded since - a new file and a downloaded one are not")
    void cold() {
        Instant twoYearsAgo = Instant.now(clock).minus(Duration.ofDays(800));
        for (FileDetailsDTO file : List.of(small, medium, versioned)) {
            jdbc.update("UPDATE file_info SET created_at = ? WHERE id = ?", Timestamp.from(twoYearsAgo), file.getFileInfoId());
        }
        download(medium, a.tag(), "PAGE", Instant.now(clock).minus(Duration.ofDays(10)));
        download(small, a.tag(), "PAGE", Instant.now(clock).minus(Duration.ofDays(500)));

        ReportView view = underTest.view(new ReportService.Request(Report.COLD, null, null, 365), readerId);
        assertThat(stat(view, "report.cold.files")).isEqualTo(2L);
        assertThat(stat(view, "report.cold.bytes")).isEqualTo(450L);
        assertThat(rows(view, "files")).extracting(row -> ((FileCell) row.get(0)).fileId())
                .containsExactly(versioned.getFileInfoId(), small.getFileInfoId());
    }

    @Test
    @DisplayName("duplicates: the same bytes twice, what the second costs - only among the reader's files")
    void duplicates() {
        FileDetailsDTO copy = uploadBytes("copy", a.subCategory(), contentOf(200, "medium"), "txt");
        uploadBytes("copy-in-b", b.tag(), contentOf(200, "medium"), "txt");
        flush();
        assertThat(jdbc.queryForObject("SELECT checksum_sha256 FROM file_details WHERE id = ?", String.class, copy.getId()))
                .isEqualTo(jdbc.queryForObject("SELECT checksum_sha256 FROM file_details WHERE id = ?", String.class, medium.getId()));

        ReportView view = view(Report.DUPLICATES, readerId);
        assertThat(stat(view, "report.duplicates.groups")).isEqualTo(1L);
        assertThat(stat(view, "report.duplicates.wasted")).isEqualTo(200L);
        assertThat(rows(view, "groups")).singleElement().satisfies(row -> assertThat(row.subList(1, 5)).containsExactly(2L, 2L, 200L, 200L));
    }

    @Test
    @DisplayName("quotas: what is beneath each, every version counted as the quota counts it; the nearly full said so")
    void quotas() {
        jdbc.update("UPDATE folder SET quota_bytes = 1100 WHERE id = ?", a.subCategory().getId());
        jdbc.update("UPDATE folder SET quota_bytes = 5000 WHERE id = ?", a.tag().getId());
        jdbc.update("UPDATE folder SET quota_bytes = 10 WHERE id = ?", b.tag().getId());

        ReportView view = view(Report.QUOTAS, readerId);
        assertThat(stat(view, "report.quotas.count")).isEqualTo(2L);
        assertThat(stat(view, "report.quotas.nearlyFull")).isEqualTo(1L);
        assertThat(rows(view, "quotas")).extracting(row -> ((FolderCell) row.get(0)).id(), row -> row.get(2)).containsExactly(
                org.assertj.core.groups.Tuple.tuple(a.subCategory().getId(), 1050L),
                org.assertj.core.groups.Tuple.tuple(a.tag().getId(), 650L));
    }

    @Test
    @DisplayName("coverage: the latest revisions and folders described, and the reading of contents by state")
    void coverage() {
        jdbc.update("UPDATE file_details SET metadata = '{\"a\":\"1\"}'::jsonb WHERE id = ?", middle.getId());
        jdbc.update("UPDATE folder SET metadata = '{\"a\":\"1\"}'::jsonb WHERE id = ?", a.tag().getId());
        jdbc.update("UPDATE file_content SET state = 'DONE', lane = 'OCR' WHERE file_details_id = ?", small.getId());

        ReportView view = view(Report.COVERAGE, readerId);
        assertThat(stat(view, "report.coverage.describedFiles")).isEqualTo(25.0);
        assertThat(stat(view, "report.coverage.describedFolders")).isEqualTo(100.0 / 3);
        assertThat(stat(view, "report.coverage.read")).isEqualTo(20.0);
        assertThat(rows(view, "contents")).extracting(row -> row.get(0), row -> row.get(1)).contains(
                org.assertj.core.groups.Tuple.tuple("contentExtraction.state.DONE", 1L));
    }

    @Test
    @DisplayName("integrity: over the whole installation, so for an unrestricted reader only")
    void integrity() {
        jdbc.update("UPDATE file_details SET checksum_sha256 = NULL WHERE id = ?", small.getId());
        ReportView admins = view(Report.INTEGRITY, adminId);
        assertThat((Long) stat(admins, "report.integrity.withoutChecksum")).isGreaterThanOrEqualTo(1L);
        ReportView readers = view(Report.INTEGRITY, readerId);
        assertThat(readers.stats()).isEmpty();
        assertThat(readers.notes()).extracting(ReportView.Note::code).containsExactly("report.integrity.unrestrictedOnly");
    }

    @Test
    @DisplayName("a reader of no folder: no figure at all, said so")
    void nothingReadable() {
        User nobody = userRepository.save(TestData.user());
        flush();
        for (Report report : Report.values()) {
            ReportView view = underTest.view(new ReportService.Request(report, null, null, null), nobody.getId());
            assertThat(view.stats()).as(report.slug()).isEmpty();
            assertThat(view.tables()).as(report.slug()).isEmpty();
            assertThat(view.notes()).extracting(ReportView.Note::code).containsExactly("report.nothingReadable");
        }
    }

    @Test
    @DisplayName("every report counts for an administrator without failing")
    void everyReport() {
        for (Report report : Report.values()) {
            ReportView view = underTest.view(new ReportService.Request(report, null, null, null), adminId);
            assertThat(view.report()).isEqualTo(report);
        }
    }

    // ================================================================ helpers

    private ReportView view(Report report, int principalId) {
        return underTest.view(new ReportService.Request(report, null, null, null), principalId);
    }

    private static Object stat(ReportView view, String code) {
        return view.stats().stream().filter(stat -> stat.code().equals(code)).findFirst().orElseThrow(() ->
                new AssertionError("no stat " + code + " in " + view.stats())).value();
    }

    private static List<List<Object>> rows(ReportView view, String table) {
        return view.table(table).orElseThrow(() -> new AssertionError("no table " + table)).rows().stream()
                .map(ReportView.Row::cells).toList();
    }

    private static List<Object> rowOf(List<List<Object>> rows, int folderId) {
        return rows.stream().filter(row -> Objects.equals(((FolderCell) row.get(0)).id(), folderId)).findFirst()
                .orElseThrow(() -> new AssertionError("no row of folder " + folderId));
    }

    private void createdAt(int fileDetailsId, Instant at) {
        jdbc.update("UPDATE file_details SET created_at = ? WHERE id = ?", Timestamp.from(at), fileDetailsId);
    }

    private void download(FileDetailsDTO file, Folder folder, String channel, Instant at) {
        jdbc.update("""
                INSERT INTO file_download (occurred_at, channel, file_info_id, file_details_id, file_name, version, folder_id, user_id)
                VALUES (?, ?, ?, ?, 'x', 1, ?, ?)""", Timestamp.from(at), channel, file.getFileInfoId(), file.getId(),
                folder.getId(), adminId);
    }

    private FileDetailsDTO upload(String name, Folder folder, int size, String extension) {
        return uploadBytes(name, folder, contentOf(size, name), extension);
    }

    private static byte[] contentOf(int size, String seed) {
        StringBuilder text = new StringBuilder(seed);
        while (text.length() < size) {
            text.append('.');
        }
        return text.substring(0, size).getBytes(StandardCharsets.US_ASCII);
    }

    private FileDetailsDTO uploadBytes(String name, Folder folder, byte[] content, String extension) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription(name);
        request.setFileNameDescription(name);
        request.setFolderId(folder.getId());
        String unique = name + "-" + TestData.nextSequence() + "." + extension;
        request.setMultipartFile(new MockMultipartFile("file", unique, "text/plain", content));
        FileDetailsDTO uploaded = fileService.createNewFile(request, adminId, FileService.PRIVATE);
        entityManager.flush();
        return uploaded;
    }

    private int newVersion(FileDetailsDTO first, int size) {
        var row = jdbc.queryForMap("SELECT d.file_name FROM file_details d WHERE d.id = ?", first.getId());
        String fileName = (String) row.get("file_name");
        String title = fileName.substring(0, fileName.lastIndexOf('.'));
        FileUploadDTO next = new FileUploadDTO();
        next.setFileId(first.getFileInfoId());
        next.setFileName(title);
        next.setFileNameWithoutExtension(title);
        next.setVersion(2);
        next.setType("version");
        next.setFileDetailsDescription("v2");
        next.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain", contentOf(size, "v2")));
        fileService.createNewFileDetails(next, adminId);
        entityManager.flush();
        return jdbc.queryForObject("SELECT id FROM file_details WHERE file_info_id = ? AND version = 2", Integer.class,
                first.getFileInfoId());
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }
}
