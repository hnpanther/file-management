package com.hnp.filemanagement.report;

import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.folder.domain.UserFolderGrant;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every report over a million revisions (roadmap 12.9: "each report one aggregate query, measured at a
 * million revisions"): 250,000 files of four versions in a thousand folders under ten top-level ones,
 * a quota on each of the thousand, 300,000 downloads over a year, deletions, duplicates and four months
 * of the night's counts - counted for an administrator and for a reader granted half the top-level
 * folders. The times are printed and bounded generously - a shared database in a container, not a
 * benchmark. Measured 2026-10-14, each counted afresh (the cache is off here): the folders, growth,
 * downloads and quotas 0.2-0.6 s; the overview, kinds, the largest, uploaders, coverage and integrity
 * 0.5-1.3 s (a reader's coverage 2.0); versions, cold and duplicate files 1.3-2.1 s - a grouping of
 * every revision by its file or its checksum. The night's count 1.7 s.
 * The rows are the test's transaction's, rolled back.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.reports.cache-minutes=0",
        "filemanagement.reports.capacity-gb=2000"})
@Tag("scale")
class ReportScaleTest extends DatabaseSupport {

    static final int FILES = 250_000;
    static final int VERSIONS = 4;
    static final long BOUND_MS = 5_000;

    @Autowired
    private ReportService reports;
    @Autowired
    private StorageSnapshotJob snapshots;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private java.time.Clock clock;

    @Test
    @DisplayName("a million revisions: every report, for an administrator and for a reader of half, within bounds")
    void atScale() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        Folder root = FolderFixture.root(folderRepository);
        List<Folder> tops = new ArrayList<>();
        List<Folder> leaves = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Folder top = FolderFixture.category(folderRepository, admin, "R" + i + "-" + TestData.nextSequence(),
                    tagGroupRepository.save(TestData.tagGroup(admin, "G" + i + "-" + TestData.nextSequence())));
            tops.add(top);
            for (int j = 0; j < 100; j++) {
                leaves.add(FolderFixture.tag(folderRepository, top, admin, "L" + i + "-" + j + "-" + TestData.nextSequence()));
            }
        }
        entityManager.flush();
        assertThat(root.getKind().name()).isEqualTo("ROOT");

        long started = System.nanoTime();
        Integer[] leafIds = leaves.stream().map(Folder::getId).toArray(Integer[]::new);
        jdbc.update("UPDATE folder SET quota_bytes = 300000000 WHERE id = ANY (?::int[])", (Object) leafIds);
        jdbc.update("""
                INSERT INTO file_info (external_id, file_name, search_name, code_name, file_name_description, last_version,
                                       folder_id, enabled, state, created_at, created_by)
                SELECT gen_random_uuid()::text, 'r-' || g, 'R-' || g, 'c', 'c', ?, (?::int[])[1 + g % 1000], 1, 0,
                       now() - make_interval(days => g % 1100), ?
                FROM generate_series(1, ?) g""", VERSIONS, leafIds, admin.getId(), FILES);
        // Four versions a file, a version a few days after the last; sizes up to 2 MB; one in twenty the
        // same bytes as another (a checksum shared by twenty files); a tenth Word documents.
        jdbc.update("""
                INSERT INTO file_details (file_info_id, external_id, file_name, search_name, file_extension, content_type,
                                          version, version_name, description, search_description, storage_key, file_size,
                                          checksum_sha256, enabled, state, created_at, created_by, metadata)
                SELECT fi.id, gen_random_uuid()::text, fi.file_name || '.pdf', fi.search_name,
                       CASE WHEN fi.id % 10 = 0 THEN 'docx' ELSE 'pdf' END, 'application/pdf', v, 'v' || v, '', '',
                       'files/00/' || fi.id || '/rev/v' || v || '/x.pdf', 1000 + (fi.id::bigint * 7919 + v * 104729) % 2000000,
                       CASE WHEN fi.id % 20 = 0 THEN md5((fi.id % 1000)::text) || md5('d')
                            ELSE md5(fi.id::text || '-' || v) || md5(v::text) END,
                       1, 0, fi.created_at + make_interval(days => v * 3), ?,
                       CASE WHEN fi.id % 3 = 0 THEN '{"a":"1"}'::jsonb END
                FROM file_info fi CROSS JOIN generate_series(1, ?) v
                WHERE fi.folder_id = ANY (?::int[])""", admin.getId(), VERSIONS, leafIds);
        jdbc.update("""
                INSERT INTO file_content (file_details_id, state, lane, next_attempt_at, queued_at, read_at)
                SELECT d.id, CASE WHEN d.id % 7 = 0 THEN 'PENDING' ELSE 'DONE' END, 'OCR', now(), now(), now()
                FROM file_details d JOIN file_info fi ON fi.id = d.file_info_id WHERE fi.folder_id = ANY (?::int[])""",
                (Object) leafIds);
        jdbc.update("""
                INSERT INTO file_download (occurred_at, channel, file_info_id, file_details_id, file_name, version, folder_id, user_id)
                SELECT now() - make_interval(mins => (g * 37) % 525600),
                       (ARRAY['PAGE','PREVIEW','PUBLIC','SHARE_LINK','API_V1','API_V2','S3'])[1 + g % 7],
                       fi.id, fi.id, fi.file_name, 1, fi.folder_id, ?
                FROM generate_series(1, 300000) g
                JOIN file_info fi ON fi.id = (SELECT min(id) FROM file_info WHERE folder_id = ANY (?::int[])) + (g * 13) % 60000""",
                admin.getId(), leafIds);
        jdbc.update("""
                INSERT INTO file_history (occurred_at, event, file_info_id, folder_id, user_id, username)
                SELECT now() - make_interval(days => g % 365), CASE WHEN g % 3 = 0 THEN 'FILE_DELETED' ELSE 'REVISION_DELETED' END,
                       g, (?::int[])[1 + g % 1000], ?, 'u'
                FROM generate_series(1, 10000) g""", leafIds, admin.getId());
        for (String table : List.of("file_info", "file_details", "file_content", "file_download", "file_history", "folder")) {
            jdbc.execute("ANALYZE " + table);
        }
        long snapshotStarted = System.nanoTime();
        java.time.LocalDate today = java.time.LocalDate.now(clock);
        snapshots.take(today);
        long snapshotMs = (System.nanoTime() - snapshotStarted) / 1_000_000;
        // Four months of nights before today's, growing 1 GB a day.
        jdbc.update("""
                INSERT INTO storage_snapshot (day, folder_id, folder_name, folder_path, files, revisions, bytes, latest_bytes, taken_at)
                SELECT s.day - g, s.folder_id, s.folder_name, s.folder_path, s.files, s.revisions,
                       greatest(0, s.bytes - g::bigint * 107374182), s.latest_bytes, s.taken_at
                FROM storage_snapshot s CROSS JOIN generate_series(1, 120) g
                WHERE s.day = ? AND s.folder_id = ANY (?::int[])
                ON CONFLICT DO NOTHING""", java.sql.Date.valueOf(today), tops.stream().map(Folder::getId).toArray(Integer[]::new));
        System.out.printf("[12.9] reports scale: %,d revisions written in %,d ms; the night's count %,d ms%n",
                jdbc.queryForObject("SELECT count(*) FROM file_details d JOIN file_info fi ON fi.id = d.file_info_id "
                        + "WHERE fi.folder_id = ANY (?::int[])", Long.class, (Object) leafIds),
                (System.nanoTime() - started) / 1_000_000, snapshotMs);
        assertThat(snapshotMs).isLessThan(BOUND_MS * 2);

        int adminId = admin.getId();
        User reader = userRepository.save(TestData.user());
        List<UserFolderGrant> grants = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            grants.add(new UserFolderGrant(reader, tops.get(i), FolderPermission.READ));
        }
        reader.replaceFolderGrants(grants);
        userRepository.save(reader);
        entityManager.flush();
        int readerId = reader.getId();

        for (Report report : Report.values()) {
            Integer folder = report == Report.FOLDERS ? tops.get(2).getId() : null;
            ReportView admins = timed(report.slug() + ", administrator", adminId, new ReportService.Request(report, folder, null, null));
            ReportView readers = timed(report.slug() + ", a reader of half", readerId, new ReportService.Request(report, folder, null, null));
            if (report != Report.INTEGRITY) {
                assertThat(admins.tables().size() + admins.stats().size()).as(report.slug()).isPositive();
                assertThat(readers.tables().size() + readers.stats().size()).as(report.slug()).isPositive();
            }
        }
        ReportView growth = reports.view(new ReportService.Request(Report.GROWTH, null, 36, null), adminId);
        assertThat(growth.notes()).extracting(ReportView.Note::code).contains("report.growth.rateFromSnapshots");
        assertThat(growth.stats()).extracting(ReportView.Stat::code).contains("report.growth.full");
    }

    private ReportView timed(String what, int principalId, ReportService.Request request) {
        long started = System.nanoTime();
        ReportView view = reports.view(request, principalId);
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("[12.9] report %s: %,d ms%n", what, ms);
        assertThat(ms).as(what).isLessThan(BOUND_MS);
        return view;
    }
}
