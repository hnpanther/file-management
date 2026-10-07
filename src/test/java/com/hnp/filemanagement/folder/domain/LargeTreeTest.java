package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.file.domain.FileDownloadService;
import com.hnp.filemanagement.file.domain.FileHistoryService;
import com.hnp.filemanagement.file.domain.FileInfo;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.domain.ShareLinkService;
import com.hnp.filemanagement.file.persistence.FileDownloadQuery;
import com.hnp.filemanagement.file.persistence.FileHistoryQuery;
import com.hnp.filemanagement.file.persistence.FileInfoRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.RoleService;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A folder of thousands of folders (roadmap 12.4), measured: {@value #WIDE} children under one
 * parent, {@value #UNDER_EACH} under each of the first {@value #PARENTS_WITH_CHILDREN} - a hundred
 * thousand folders, the shape the ERP workflow gives a tree, one folder per person.
 *
 * <p>Before 12.4 the explorer read and returned all twenty thousand (1.3 s), the access tree of the
 * role and key pages all hundred thousand (1.2 s), and every list filtered by folder access failed
 * outright for a reader granted the wide folder: its hundred thousand folder ids were bound one
 * parameter each, past PostgreSQL's 65,535. Each listing here is asserted to answer a page, the right
 * page, within a fixed number of statements; the time is printed, and bounded generously - this is a
 * shared database in a container, not a benchmark.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class LargeTreeTest extends DatabaseSupport {

    static final int WIDE = 20_000;
    static final int UNDER_EACH = 40;
    static final int PARENTS_WITH_CHILDREN = 2_000;
    /** What any one listing may take here; they take tens of milliseconds. */
    static final long BOUND_MS = 3_000;

    @Autowired
    private FolderContentService folderContentService;
    @Autowired
    private FileTreeService fileTreeService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private FolderGrantTreeService folderGrantTreeService;
    @Autowired
    private FileService fileService;
    @Autowired
    private FileHistoryService fileHistoryService;
    @Autowired
    private FileDownloadService fileDownloadService;
    @Autowired
    private ShareLinkService shareLinkService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    private Statistics statistics;
    private User admin;
    private Folder wide;
    private int deepId;
    private User reader;
    private User deepReader;

    @BeforeEach
    void setUp() {
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        wide = FolderFixture.chain(folderRepository, tagGroupRepository, admin).subCategory();
        entityManager.flush();

        long started = System.nanoTime();
        generate(wide);
        System.out.printf("[12.4] generated %,d folders in %,d ms%n", WIDE + PARENTS_WITH_CHILDREN * UNDER_EACH,
                (System.nanoTime() - started) / 1_000_000);

        deepId = idOf("P-015000");
        reader = userRepository.save(TestData.user());
        grant(reader, wide.getId());
        deepReader = userRepository.save(TestData.user());
        grant(deepReader, deepId);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("every listing of a level of 20,000 answers a page of it - the right page, the right total, in a few statements")
    void aWideLevelIsPaged() {
        FolderContentDTO first = measure("explorer, the wide level", 8,
                () -> folderContentService.contentOf(wide.getId(), 0, 100, admin.getId()));
        assertThat(first.folders()).hasSize(100);
        assertThat(first.folders().getFirst().name()).isEqualTo("P-000001");
        assertThat(first.folderPage().totalElements()).as("and the fixture's own folder there").isEqualTo(WIDE + 1);
        assertThat(first.folderPage().totalPages()).isEqualTo(WIDE / 100 + 1);
        assertThat(first.folders().getFirst().folderCount()).as("what is under each, counted for the page").isEqualTo(UNDER_EACH);

        FolderContentDTO last = measure("explorer, the last page", 8, () -> folderContentService.contentOf(wide.getId(), 0, 100,
                new FolderContentService.LevelRequest(WIDE / 100 - 1, 100, "", null), admin.getId()));
        assertThat(last.folders()).extracting(FolderContentDTO.FolderEntry::name).last().isEqualTo("P-020000");

        FolderContentDTO filtered = measure("explorer, filtered", 8, () -> folderContentService.contentOf(wide.getId(), 0, 100,
                new FolderContentService.LevelRequest(0, 100, "p-0150", null), admin.getId()));
        assertThat(filtered.folderPage().totalElements()).as("P-015000 to P-015099").isEqualTo(100);
        assertThat(filtered.folders()).allSatisfy(entry -> assertThat(entry.name()).startsWith("P-0150"));
        assertThat(filtered.filter()).isEqualTo("p-0150");

        FolderContentDTO around = measure("explorer, the page around one folder", 10, () -> folderContentService.contentOf(wide.getId(), 0, 100,
                new FolderContentService.LevelRequest(0, 100, "", deepId), admin.getId()));
        assertThat(around.folderPage().number()).as("P-015000 is the 15,000th").isEqualTo(149);
        assertThat(around.folders()).extracting(FolderContentDTO.FolderEntry::id).contains(deepId);

        TreeLevelDTO level = measure("tree page, the wide level", 7,
                () -> fileTreeService.getLevel(TreeNodeDTO.NodeType.FOLDER, wide.getId(), "", 0, 100, admin.getId()));
        assertThat(level.nodes()).hasSize(100);
        assertThat(level.page().totalElements()).isEqualTo(WIDE + 1);

        FolderGrantTreeService.GrantLevel grantLevel = measure("access tree, a level opened", 4,
                () -> folderGrantTreeService.children(wide.getId(), "", 3, 100));
        assertThat(grantLevel.rows()).hasSize(100);
        assertThat(grantLevel.rows().getFirst().getName()).isEqualTo("P-000301");
        assertThat(grantLevel.page().totalElements()).isEqualTo(WIDE + 1);
    }

    @Test
    @DisplayName("access is applied in the query: a reader granted the wide folder pages it, one granted a folder in it sees that folder alone")
    void accessAtScale() {
        FolderContentDTO granted = measure("explorer, reader granted the wide folder", 10,
                () -> folderContentService.contentOf(wide.getId(), 0, 100, reader.getId()));
        assertThat(granted.readable()).isTrue();
        assertThat(granted.folderPage().totalElements()).isEqualTo(WIDE + 1);

        FolderContentDTO passThrough = measure("explorer, reader granted one folder in it", 8,
                () -> folderContentService.contentOf(wide.getId(), 0, 100, deepReader.getId()));
        assertThat(passThrough.readable()).isFalse();
        assertThat(passThrough.folders()).extracting(FolderContentDTO.FolderEntry::id).containsExactly(deepId);
        assertThat(passThrough.folderPage().totalElements()).as("the page and its total agree").isOne();

        TreeLevelDTO treePassThrough = fileTreeService.getLevel(TreeNodeDTO.NodeType.FOLDER, wide.getId(), "", 0, 100, deepReader.getId());
        assertThat(treePassThrough.nodes()).extracting(TreeNodeDTO::getId).containsExactly(deepId);
    }

    /**
     * The defect 12.4 found: a list filtered by folder access sent the readable folders as one bind
     * parameter each, and a grant over more than 65,535 folders made it a statement PostgreSQL
     * refuses. Each of the five such lists is asked here for a reader granted all hundred thousand.
     */
    @Test
    @DisplayName("a reader granted 100,000 folders can search, list, read the history, the downloads and the share links")
    void listsUnderAGrantOfAHundredThousandFolders() {
        Folder deep = folderRepository.findById(deepId).orElseThrow();
        FileInfo file = TestData.fileInfo(admin, deep, "deep-report-" + TestData.nextSequence());
        TestData.fileDetails(admin, file, 1, "pdf");
        int fileId = fileInfoRepository.save(file).getId();
        entityManager.flush();
        entityManager.clear();

        assertThat(measure("file list, searched", 5, () -> fileService.getPageFileInfo(20, 0, "deep-report", reader.getId())).getContent())
                .extracting(dto -> dto.getId()).contains(fileId);
        assertThat(measure("file list, whole", 5, () -> fileService.getPageFileInfo(20, 0, "", reader.getId())).getContent())
                .extracting(dto -> dto.getId()).contains(fileId);
        assertThat(measure("explorer search", 9, () -> folderContentService.search("deep-report", null, 0, 25, reader.getId())).hits())
                .extracting(hit -> hit.file().id()).contains(fileId);
        measure("history", 4, () -> fileHistoryService.search(FileHistoryQuery.everything(), 0, 50, reader.getId()));
        measure("downloads", 4, () -> fileDownloadService.search(FileDownloadQuery.everything(), 0, 50, reader.getId()));
        measure("share links", 4, () -> shareLinkService.listAll(reader.getId(), 0, 50));

        User elsewhere = userRepository.save(TestData.user());
        grant(elsewhere, idOf("P-000002"));
        entityManager.flush();
        entityManager.clear();
        assertThat(fileService.getPageFileInfo(20, 0, "deep-report", elsewhere.getId()).getContent())
                .as("a grant beside it reaches none of it").isEmpty();
        assertThat(folderContentService.search("deep-report", null, 0, 25, elsewhere.getId()).hits()).isEmpty();
    }

    @Test
    @DisplayName("the access tree renders the top, the grant and the way to it - not the hundred thousand - and finds a folder by name")
    void theAccessTree() {
        var rows = measure("access tree, as the page opens", 5, () -> roleService.getFolderTree(List.of(deepId + ":READ")));
        assertThat(rows.size()).isLessThan(250);
        assertThat(rows).extracting(FolderGrantDTO::getId).contains(wide.getId(), deepId);
        assertThat(rows.stream().filter(f -> f.getId() == wide.getId()).findFirst().orElseThrow().getChildCount())
                .as("so the page offers to open the rest").isEqualTo(WIDE + 1);

        var hits = measure("access tree, a folder found by name", 4, () -> folderGrantTreeService.search("P-015000"));
        assertThat(hits).extracting(hit -> hit.folder().getId()).contains(deepId);
        assertThat(hits.stream().filter(hit -> hit.folder().getId() == deepId).findFirst().orElseThrow().chain())
                .extracting(FolderGrantDTO::getId).endsWith(wide.getId());
    }

    // ---------------------------------------------------------------- helpers

    private <T> T measure(String what, int maxStatements, Supplier<T> call) {
        entityManager.clear();
        statistics.clear();
        long started = System.nanoTime();
        T answer = call.get();
        long ms = (System.nanoTime() - started) / 1_000_000;
        long statements = statistics.getPrepareStatementCount();
        System.out.printf("[12.4] %-48s statements=%3d time=%5d ms%n", what, statements, ms);
        if (System.getProperty("largeTree.measure") == null) {
            assertThat(statements).as(what + ": statements").isLessThanOrEqualTo(maxStatements);
        }
        assertThat(ms).as(what + ": milliseconds").isLessThan(BOUND_MS);
        return answer;
    }

    private int idOf(String name) {
        return jdbc.queryForObject("SELECT id FROM folder WHERE parent_id = ? AND name = ?", Integer.class, wide.getId(), name);
    }

    private void grant(User user, int folderId) {
        List<UserFolderGrant> grants = new ArrayList<>(user.getFolderGrants());
        grants.add(new UserFolderGrant(user, folderRepository.findById(folderId).orElseThrow(), FolderPermission.READ));
        user.replaceFolderGrants(grants);
        userRepository.save(user);
    }

    /**
     * {@value #WIDE} children under the folder, then {@value #UNDER_EACH} under each of the first
     * {@value #PARENTS_WITH_CHILDREN} - each row written once, its path built from an id taken from
     * the identity's sequence first, so no second pass rewrites a hundred thousand rows.
     */
    private void generate(Folder parent) {
        jdbc.update("""
                INSERT INTO folder (id, parent_id, name, search_name, display_name, search_display_name, path, key_path,
                                    depth, kind, enabled, state, created_at)
                SELECT n.id, ?, 'P-' || lpad(n.g::text, 6, '0'), 'P-' || lpad(n.g::text, 6, '0'), 'شخص ' || n.g, 'شخص' || n.g,
                       ? || n.id || '/', ? || 'P-' || lpad(n.g::text, 6, '0') || '/', ?, 'FOLDER', 1, 0, now()
                FROM (SELECT nextval(pg_get_serial_sequence('folder', 'id')) AS id, g FROM generate_series(1, ?) g) n
                """, parent.getId(), parent.getPath(), parent.getKeyPath(), parent.getDepth() + 1, WIDE);
        jdbc.update("""
                INSERT INTO folder (id, parent_id, name, search_name, display_name, search_display_name, path, key_path,
                                    depth, kind, enabled, state, created_at)
                SELECT n.id, n.parent_id, 'C-' || n.g, 'C-' || n.g, 'قرارداد ' || n.g, 'قرارداد' || n.g,
                       n.parent_path || n.id || '/', n.parent_key_path || 'C-' || n.g || '/', n.depth + 1, 'FOLDER', 1, 0, now()
                FROM (SELECT nextval(pg_get_serial_sequence('folder', 'id')) AS id, p.id AS parent_id, p.path AS parent_path,
                             p.key_path AS parent_key_path, p.depth, g
                      FROM (SELECT id, path, key_path, depth FROM folder WHERE parent_id = ? ORDER BY id LIMIT ?) p,
                           generate_series(1, ?) g) n
                """, parent.getId(), PARENTS_WITH_CHILDREN, UNDER_EACH);
        jdbc.execute("ANALYZE folder");
        assertThat(folderRepository.findRowsWhoseKeyPathDisagrees()).as("written as the application would").isEmpty();
    }
}
