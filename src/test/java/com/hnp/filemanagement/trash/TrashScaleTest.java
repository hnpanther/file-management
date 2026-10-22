package com.hnp.filemanagement.trash;

import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderAccessService;
import com.hnp.filemanagement.folder.domain.FolderContentService;
import com.hnp.filemanagement.folder.domain.FolderQuotaService;
import com.hnp.filemanagement.folder.domain.FolderReadScope;
import com.hnp.filemanagement.folder.domain.FolderService;
import com.hnp.filemanagement.folder.domain.FolderTreeDeleteService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.FolderSearchRepository;
import com.hnp.filemanagement.file.persistence.FileInfoRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.report.ReportRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recycle bin at size (roadmap 13.1): a bin of {@value #ENTRIES} entries beside a level of {@value #FOLDERS}
 * folders with a file each, and a writer granted one of them. Measured: what every list, search and report costs an
 * administrator and the writer with the bin there - it must not be what they find, nor what they wait for - the bin's
 * page for each, a quota counting the bin, and the whole level deleted into the bin and restored. Each answer right,
 * within a generous bound (a shared database in a container, not a benchmark); the times are printed.
 */
@Tag("scale")
@ServiceIntegrationTest
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.folders.max-delete-files=50000"})
class TrashScaleTest extends DatabaseSupport {

    static final int FOLDERS = 20_000;
    static final int ENTRIES = 100_000;
    static final long BOUND_MS = 3_000;
    static final long TREE_BOUND_MS = 120_000;

    @Autowired
    private FileService fileService;
    @Autowired
    private FolderService folderService;
    @Autowired
    private FolderTreeDeleteService folderDeleteService;
    @Autowired
    private TrashRestore trashRestore;
    @Autowired
    private TrashListing trashListing;
    @Autowired
    private TrashEntryRepository entries;
    @Autowired
    private FolderAccessService folderAccessService;
    @Autowired
    private FolderContentService folderContentService;
    @Autowired
    private FolderSearchRepository folderSearchRepository;
    @Autowired
    private FolderQuotaService folderQuotaService;
    @Autowired
    private ReportRepository reportRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private int writerId;
    private int big;
    private int granted;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        adminId = userRepository.save(admin).getId();
        int rootId = folderRepository.findFirstByKind(com.hnp.filemanagement.folder.domain.FolderKind.ROOT).orElseThrow().getId();
        int n = TestData.nextSequence();
        int bucket = folderService.create(rootId, "BIN" + n, "BIN" + n, null, "bin" + n, adminId).id();
        big = folderService.create(bucket, "Big", "Big", null, null, adminId).id();
        entityManager.flush();

        long started = System.nanoTime();
        generate("h" + n + "-");
        System.out.printf("[13.1] generated %,d folders with a file each, and a bin of %,d entries, in %,d ms%n",
                FOLDERS, ENTRIES, (System.nanoTime() - started) / 1_000_000);

        granted = jdbc.queryForObject("SELECT id FROM folder WHERE parent_id = ? ORDER BY id LIMIT 1", Integer.class, big);
        User writer = userRepository.save(TestData.user());
        writerId = writer.getId();
        jdbc.update("INSERT INTO user_folder (user_id, folder_id, permission) VALUES (?, ?, 'WRITE')", writerId, granted);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("a bin of a hundred thousand: no list finds it, none waits for it; its page is a page; a level of 20,000 to it and back")
    void atScale() {
        long live = jdbc.queryForObject("""
                SELECT count(*) FROM file_info fi JOIN folder f ON f.id = fi.folder_id WHERE f.path NOT LIKE '/0/%'""", Long.class);

        var all = measure("file list, an administrator", () -> fileService.getPageFileInfo(50, 0, null, adminId));
        assertThat(all.totalElements()).isEqualTo(live);
        var searched = measure("file list searched, an administrator", () -> fileService.getPageFileInfo(50, 0, "scale", adminId));
        assertThat(searched.content()).allSatisfy(file -> assertThat(file.getFileName()).startsWith("scale-live"));
        var mine = measure("file list, the writer", () -> fileService.getPageFileInfo(50, 0, null, writerId));
        assertThat(mine.totalElements()).isEqualTo(1);

        String bigPath = pathOf(big);
        measure("folder search, an administrator", () -> folderSearchRepository.visibleMatches(
                FolderReadScope.everything(), null, "L-1", bigPath, 20));
        var level = measure("explorer: the level of 20,000", () -> folderContentService.contentOf(big, 0, 50, adminId));
        assertThat(level.folderPage().totalElements()).isEqualTo(FOLDERS);
        var totals = measure("report totals, an administrator", () -> reportRepository.totals(FolderReadScope.everything()));
        assertThat(totals.files()).isEqualTo(live);

        var page = measure("the bin's page, an administrator", () -> trashListing.page(adminId, TrashListing.Filter.ALL, "", 0, null));
        assertThat(page.rows()).hasSize(TrashListing.PAGE_SIZE);
        var older = measure("the bin's page, older", () -> trashListing.page(adminId, TrashListing.Filter.ALL, "", 0,
                page.olderCursor()));
        assertThat(older.rows()).hasSize(TrashListing.PAGE_SIZE);
        var named = measure("the bin's page, searched by name", () -> trashListing.page(adminId, TrashListing.Filter.ALL,
                "binned-77", 0, null));
        assertThat(named.rows()).isNotEmpty();
        var summary = measure("the bin's summary, an administrator", () -> trashListing.summary(adminId));
        assertThat(summary.entries()).isGreaterThanOrEqualTo(ENTRIES);
        var writers = measure("the bin's page, the writer", () -> trashListing.page(writerId, TrashListing.Filter.ALL, "", 0, null));
        assertThat(writers.rows()).hasSize(ENTRIES / FOLDERS);
        Folder bigFolder = folderRepository.findById(big).orElseThrow();
        long quota = measure("a quota's usage, the bin counted", () -> folderQuotaService.usageOf(bigFolder));
        assertThat(quota).isEqualTo(FOLDERS + (long) ENTRIES);

        long started = System.nanoTime();
        folderDeleteService.deleteTree(big, adminId);
        entityManager.flush();
        entityManager.clear();
        long deleted = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("[13.1] %-48s time=%6d ms%n", "the level of 20,000 deleted into the bin", deleted);
        assertThat(deleted).isLessThan(TREE_BOUND_MS);
        assertThat(pathOf(big)).startsWith("/0/");
        assertThat(fileService.getPageFileInfo(50, 0, null, writerId).totalElements()).isZero();

        started = System.nanoTime();
        trashRestore.restore(entries.findByFolderId(big).orElseThrow().getId(), null, null, adminId);
        entityManager.flush();
        entityManager.clear();
        long restored = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("[13.1] %-48s time=%6d ms%n", "and restored", restored);
        assertThat(restored).isLessThan(TREE_BOUND_MS);
        assertThat(pathOf(big)).isEqualTo(bigPath);
        assertThat(fileService.getPageFileInfo(50, 0, null, writerId).totalElements()).isEqualTo(1);
        assertThat(folderRepository.findRowsWhoseDerivedColumnsDisagree()).isEmpty();
        assertThat(folderRepository.findRowsWhoseKeyPathDisagrees()).isEmpty();
        assertThat(folderRepository.findRowsWhoseClassPathDisagrees()).isEmpty();
    }

    /**
     * As the application would write them: the level with a file each; and the bin - a holder each, a file in it, its
     * revision and its entry, deleted from the level's folders in turn, oldest first.
     *
     * <p>This test's holders are named with {@code holders} and picked by it, never as "every folder in the bin": the
     * database is shared by the fork, and the tests that commit a delete leave their own holders there, each with its
     * file, revision and entry - taken for this test's, they failed the revisions' unique key. The prefix must match
     * none of the terms searched below ({@code scale}, {@code L-1}): a folder whose name matches brings every file
     * beneath it into a search, and a hundred thousand holders called {@code scale…} made its count run for minutes.
     */
    private void generate(String holders) {
        Folder parent = folderRepository.findById(big).orElseThrow();
        jdbc.update("""
                INSERT INTO folder (id, parent_id, name, search_name, display_name, search_display_name, path, key_path, depth,
                                    class_path, kind, enabled, state, created_at)
                SELECT n.id, ?, 'L-' || n.g, 'L-' || n.g, 'L-' || n.g, 'L-' || n.g, ? || n.id || '/', ? || 'L-' || n.g || '/', ?,
                       '/', 'FOLDER', 1, 0, now()
                FROM (SELECT nextval(pg_get_serial_sequence('folder', 'id')) AS id, g FROM generate_series(1, ?) g) n
                """, big, parent.getPath(), parent.getKeyPath(), parent.getDepth() + 1, FOLDERS);
        jdbc.update("""
                INSERT INTO file_info (external_id, file_name, search_name, code_name, file_name_description, last_version,
                                       folder_id, enabled, state, created_at, created_by)
                SELECT gen_random_uuid()::text, 'scale-live-' || f.id, 'SCALE-LIVE-' || f.id, 'c', 'c', 1, f.id, 1, 0, now(), ?
                FROM folder f WHERE f.parent_id = ?
                """, adminId, big);
        // Their tags, as an upload derives them: one per folder on the chain, in the bucket's group.
        int group = jdbc.queryForObject("""
                SELECT b.tag_group_id FROM folder b WHERE b.id = CAST(split_part(?, '/', 3) AS integer)""",
                Integer.class, parent.getPath());
        jdbc.update("""
                INSERT INTO tag (group_id, name, title, enabled, created_at, created_by)
                SELECT ?, a.name, a.display_name, 1, now(), ?
                FROM folder a
                WHERE (a.parent_id = ? OR a.id IN (SELECT CAST(x AS integer) FROM unnest(string_to_array(btrim(?, '/'), '/')) x))
                  AND a.depth > 0
                  AND NOT EXISTS (SELECT 1 FROM tag t WHERE t.group_id = ? AND upper(t.name) = upper(a.name))
                """, group, adminId, big, parent.getPath(), group);
        jdbc.update("""
                INSERT INTO file_tag (file_info_id, tag_id)
                SELECT DISTINCT fi.id, t.id
                FROM file_info fi
                JOIN folder f ON f.id = fi.folder_id AND f.parent_id = ?
                JOIN folder a ON a.depth > 0 AND f.path LIKE a.path || '%'
                JOIN tag t ON t.group_id = ? AND upper(t.name) = upper(a.name)
                """, big, group);
        // The bin: a holder each, under /0/.
        jdbc.update("""
                INSERT INTO folder (id, parent_id, name, search_name, display_name, search_display_name, path, key_path, depth,
                                    class_path, kind, enabled, state, created_at)
                SELECT n.id, 0, ? || n.id, upper(?) || n.id, ? || n.id, upper(?) || n.id, '/0/' || n.id || '/', ? || n.id || '/', 1,
                       '/', 'FOLDER', 1, 0, now()
                FROM (SELECT nextval(pg_get_serial_sequence('folder', 'id')) AS id, g FROM generate_series(1, ?) g) n
                """, holders, holders, holders, holders, holders, ENTRIES);
        jdbc.update("""
                INSERT INTO file_info (external_id, file_name, search_name, code_name, file_name_description, last_version,
                                       folder_id, enabled, state, created_at, created_by)
                SELECT gen_random_uuid()::text, 'scale-binned-' || f.id, 'SCALE-BINNED-' || f.id, 'c', 'c', 1, f.id, 1, 0, now(), ?
                FROM folder f WHERE f.parent_id = 0 AND f.name LIKE ? || '%'
                """, adminId, holders);
        jdbc.update("""
                INSERT INTO file_details (file_info_id, external_id, file_name, search_name, file_extension, content_type,
                                          version, version_name, description, search_description, storage_key, file_size,
                                          enabled, state, created_at, created_by)
                SELECT fi.id, gen_random_uuid()::text, fi.file_name || '.pdf', fi.search_name, 'pdf', 'application/pdf', 1, 'v1',
                       '', '', 'files/s000/' || fi.id || '/rev/v1/x.pdf', 1, 1, 0, now(), ?
                FROM file_info fi JOIN folder f ON f.id = fi.folder_id
                WHERE (f.parent_id = 0 AND f.name LIKE ? || '%') OR f.parent_id = ?
                """, adminId, holders, big);
        jdbc.update("""
                WITH origins AS (SELECT row_number() OVER (ORDER BY id) - 1 AS k, id, path FROM folder WHERE parent_id = ?),
                     binned AS (SELECT row_number() OVER (ORDER BY fi.id) - 1 AS k, fi.id AS file_id, f.id AS holder
                                FROM file_info fi JOIN folder f ON f.id = fi.folder_id
                                WHERE f.parent_id = 0 AND f.name LIKE ? || '%')
                INSERT INTO trash_entry (kind, holder_folder_id, file_info_id, origin_folder_id, origin_path, origin_title,
                                         original_name, search_name, deleted_at, deleted_by, bytes, files, folders)
                SELECT 'FILE', b.holder, b.file_id, o.id, o.path, 'Big / L', 'binned-' || b.k, 'BINNED-' || b.k,
                       now() - (b.k || ' seconds')::interval, ?, 1, 1, 0
                FROM binned b JOIN origins o ON o.k = b.k % ?
                """, big, holders, adminId, FOLDERS);
        jdbc.execute("ANALYZE folder");
        jdbc.execute("ANALYZE file_info");
        jdbc.execute("ANALYZE file_details");
        jdbc.execute("ANALYZE trash_entry");
        assertThat(folderRepository.findRowsWhoseKeyPathDisagrees()).as("written as the application would").isEmpty();
        // Three tags a file - the bucket, Big, its own folder - as an upload derives them (the whole-database check,
        // findIdsWhoseTagsDisagreeWithTheFolders, is for small trees).
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM file_tag ft JOIN file_info fi ON fi.id = ft.file_info_id JOIN folder f ON f.id = fi.folder_id
                WHERE f.parent_id = ?""", Long.class, big)).as("written as the application would").isEqualTo(3L * FOLDERS);
    }

    private String pathOf(int folderId) {
        return jdbc.queryForObject("SELECT path FROM folder WHERE id = ?", String.class, folderId);
    }

    private <T> T measure(String what, Supplier<T> call) {
        entityManager.clear();
        long started = System.nanoTime();
        T answer = call.get();
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("[13.1] %-48s time=%6d ms%n", what, ms);
        assertThat(ms).as(what + ": milliseconds").isLessThan(BOUND_MS);
        return answer;
    }
}
