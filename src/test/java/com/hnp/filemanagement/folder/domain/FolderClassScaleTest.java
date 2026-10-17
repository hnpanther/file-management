package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.FolderSearchRepository;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.ClassFixture;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Folder classes at the size of the ERP's contracts (roadmap Phase 14), measured: {@value #COMPANIES} companies of
 * {@value #CONTRACTS_EACH} contracts - {@value #CONTRACTS} contracts, each with its {@code Documents} and its
 * {@code ContractState}, a file in each - and a contracts department granted {@code documents} by one row. What
 * is asserted: each answer right, in a bounded number of statements, within a generous bound (a shared database
 * in a container, not a benchmark); the times are printed. A class grant is one grant row to the SQL however
 * many folders carry the class, and five thousand sorted paths to the Java - a lookup each.
 */
@Tag("scale")
@ServiceIntegrationTest
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.folders.max-depth=8"})
class FolderClassScaleTest extends DatabaseSupport {

    static final int COMPANIES = 500;
    static final int CONTRACTS_EACH = 10;
    static final int CONTRACTS = COMPANIES * CONTRACTS_EACH;
    static final long BOUND_MS = 3_000;

    @Autowired
    private FolderClassService classService;
    @Autowired
    private FolderClassAssignment assignment;
    @Autowired
    private RoleClassGrantService roleClassGrantService;
    @Autowired
    private FolderService folderService;
    @Autowired
    private FolderAccessService folderAccessService;
    @Autowired
    private FolderContentService folderContentService;
    @Autowired
    private FolderSearchRepository folderSearchRepository;
    @Autowired
    private FileService fileService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;
    @Autowired
    private EntityManager entityManager;

    private Statistics statistics;
    private ClassFixture.Classes classes;
    private ClassFixture.Root root;
    private int contractsDept;
    private int finance;

    @BeforeEach
    void setUp() {
        statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        int adminId = userRepository.save(admin).getId();
        classes = ClassFixture.classes(classService, adminId);
        root = ClassFixture.root(folderService, folderRepository, assignment, classes, adminId);
        entityManager.flush();

        long started = System.nanoTime();
        generate(adminId);
        System.out.printf("[14] generated %,d folders and %,d files in %,d ms%n",
                COMPANIES * 2 + CONTRACTS * 3, CONTRACTS * 2, (System.nanoTime() - started) / 1_000_000);

        contractsDept = userWith(adminId, classes.documents());
        finance = userWith(adminId, classes.statements());
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @DisplayName("five thousand contracts: the access, the file list, the explorer and its search for a class grant, bounded")
    void atScale() {
        FolderAccess access = measure("access of the contracts department", 3, () -> folderAccessService.accessFor(contractsDept));
        assertThat(access.readablePaths()).hasSize(CONTRACTS);

        var list = measure("file list, the contracts department", 6, () -> fileService.getPageFileInfo(50, 0, null, contractsDept));
        assertThat(list.totalElements()).isEqualTo(CONTRACTS);
        String aStatement = jdbc.queryForObject("SELECT fi.file_name FROM file_info fi JOIN folder f ON f.id = fi.folder_id"
                + " WHERE f.class_id = ? ORDER BY fi.id DESC LIMIT 1", String.class, classes.statements());
        var searched = measure("file list searched, finance", 6, () -> fileService.getPageFileInfo(50, 0, aStatement, finance));
        assertThat(searched.content()).extracting(dto -> dto.getFileName()).contains(aStatement);

        var companies = measure("explorer: PartyCompany, the way down", 12,
                () -> folderContentService.contentOf(root.partyCompanyId(), 0, 50, contractsDept));
        assertThat(companies.readable()).isFalse();
        assertThat(companies.folderPage().totalElements()).isEqualTo(COMPANIES);

        int company = jdbc.queryForObject("SELECT id FROM folder WHERE parent_id = ? AND name = 'CO-250'", Integer.class,
                root.partyCompanyId());
        int container = jdbc.queryForObject("SELECT id FROM folder WHERE parent_id = ?", Integer.class, company);
        var contracts = measure("explorer: a company's contracts", 12, () -> folderContentService.contentOf(container, 0, 50, contractsDept));
        assertThat(contracts.folderPage().totalElements()).isEqualTo(CONTRACTS_EACH);
        int contract = contracts.folders().getFirst().id();
        var contractPage = measure("explorer: a contract - its Documents alone", 12,
                () -> folderContentService.contentOf(contract, 0, 50, contractsDept));
        assertThat(contractPage.folders()).extracting(FolderContentDTO.FolderEntry::name).containsExactly("Documents");

        FolderReadScope scope = folderAccessService.readScope(contractsDept);
        // the same search unrestricted, for what five thousand folders of one name cost before any grant is asked
        measure("folder search: Documents, unrestricted", 4, () -> folderSearchRepository.visibleMatches(
                FolderReadScope.everything(), null, "Documents", pathOf(root.bucketId()), 20));
        var found = measure("folder search: Documents", 4, () -> folderSearchRepository.visibleMatches(scope, null, "Documents",
                pathOf(root.bucketId()), 20));
        assertThat(found).hasSize(20);
        assertThat(measure("folder search: ContractState", 4, () -> folderSearchRepository.visibleMatches(scope, null,
                "ContractState", pathOf(root.bucketId()), 20))).isEmpty();

        // the condition every report, metadata and content search shares, over every folder
        Integer readable = measure("readable folders, as a report counts them", 2, () -> namedJdbc.queryForObject(
                ReadScopeSql.granted(scope) + "SELECT count(*) FROM folder f WHERE " + ReadScopeSql.readable(scope, "f"),
                ReadScopeSql.parameters(scope), Integer.class));
        assertThat(readable).isEqualTo(CONTRACTS);
    }

    /** As the application would write them: each with its class and its class path, key path and folded name. */
    private void generate(int adminId) {
        String party = pathOf(root.partyCompanyId());
        String partyKey = jdbc.queryForObject("SELECT key_path FROM folder WHERE id = ?", String.class, root.partyCompanyId());
        String partyClass = jdbc.queryForObject("SELECT class_path FROM folder WHERE id = ?", String.class, root.partyCompanyId());
        int depth = jdbc.queryForObject("SELECT depth FROM folder WHERE id = ?", Integer.class, root.partyCompanyId());
        insert("""
                SELECT n.id, ?, 'CO-' || n.g, 'CO-' || n.g, 'شرکت ' || n.g, 'شرکت' || n.g, ? || n.id || '/', ? || 'CO-' || n.g || '/',
                       ?, ?, ? || ? || '/'
                FROM (SELECT nextval(pg_get_serial_sequence('folder', 'id')) AS id, g FROM generate_series(1, ?) g) n
                """, root.partyCompanyId(), party, partyKey, depth + 1, classes.company(), partyClass, classes.company(), COMPANIES);
        children("Contract", "CONTRACT", "قراردادها", classes.company(), classes.contracts(), 1);
        insert("""
                SELECT n.id, p.id, 'K-' || p.id || '-' || n.g, 'K-' || p.id || '-' || n.g, 'K-' || p.id || '-' || n.g,
                       'K-' || p.id || '-' || n.g, p.path || n.id || '/', p.key_path || 'K-' || p.id || '-' || n.g || '/', p.depth + 1, ?,
                       p.class_path || ? || '/'
                FROM folder p
                CROSS JOIN LATERAL (SELECT nextval(pg_get_serial_sequence('folder', 'id')) AS id, g FROM generate_series(1, ?) g) n
                WHERE p.class_id = ? AND p.path LIKE ? || '%'
                """, classes.contract(), classes.contract(), CONTRACTS_EACH, classes.contracts(), party);
        children("Documents", "DOCUMENTS", "اسناد قرارداد", classes.contract(), classes.documents(), 1);
        children("ContractState", "CONTRACTSTATE", "صورت وضعیت‌ها", classes.contract(), classes.statements(), 1);
        for (var kind : List.of(new Object[]{classes.documents(), "doc"}, new Object[]{classes.statements(), "statement"})) {
            jdbc.update("""
                    INSERT INTO file_info (external_id, file_name, search_name, code_name, file_name_description, last_version,
                                           folder_id, enabled, state, created_at, created_by)
                    SELECT gen_random_uuid()::text, ? || '-' || f.id, upper(?) || '-' || f.id, 'c', 'c', 1, f.id, 1, 0, now(), ?
                    FROM folder f WHERE f.class_id = ? AND f.path LIKE ? || '%'
                    """, kind[1], kind[1], adminId, kind[0], party);
        }
        jdbc.update("""
                INSERT INTO file_details (file_info_id, external_id, file_name, search_name, file_extension, content_type,
                                          version, version_name, description, search_description, storage_key, file_size,
                                          enabled, state, created_at, created_by)
                SELECT fi.id, gen_random_uuid()::text, fi.file_name || '.pdf', fi.search_name, 'pdf', 'application/pdf', 1, 'v1',
                       '', '', 'files/00/' || fi.id || '/rev/v1/x.pdf', 1, 1, 0, now(), ?
                FROM file_info fi JOIN folder f ON f.id = fi.folder_id WHERE f.path LIKE ? || '%'
                """, adminId, party);
        jdbc.execute("ANALYZE folder");
        jdbc.execute("ANALYZE file_info");
        jdbc.execute("ANALYZE file_details");
        assertThat(folderRepository.findRowsWhoseClassPathDisagrees()).as("written as the application would").isEmpty();
        assertThat(folderRepository.findRowsWhoseKeyPathDisagrees()).as("written as the application would").isEmpty();
    }

    /** One folder named so under each folder of {@code parentClass} below PartyCompany, of {@code childClass}. */
    private void children(String name, String searchName, String label, int parentClass, int childClass, int each) {
        insert("""
                SELECT n.id, p.id, ?, ?, ?, ?, p.path || n.id || '/', p.key_path || ? || '/', p.depth + 1, ?, p.class_path || ? || '/'
                FROM folder p
                CROSS JOIN LATERAL (SELECT nextval(pg_get_serial_sequence('folder', 'id')) AS id, g FROM generate_series(1, ?) g) n
                WHERE p.class_id = ? AND p.path LIKE ? || '%'
                """, name, searchName, label, label.replace(" ", ""), name, childClass, childClass, each, parentClass,
                pathOf(root.partyCompanyId()));
    }

    private void insert(String select, Object... arguments) {
        jdbc.update("""
                INSERT INTO folder (id, parent_id, name, search_name, display_name, search_display_name, path, key_path,
                                    depth, class_id, class_path, kind, enabled, state, created_at)
                SELECT s.*, 'FOLDER', 1, 0, now() FROM (""" + select + ") s", arguments);
    }

    private int userWith(int adminId, int classId) {
        Role role = roleRepository.save(TestData.role("ScaleRole" + TestData.nextSequence()));
        roleClassGrantService.replace(role.getId(), List.of(new RoleClassGrantService.Requested(classId, "READ", "")), adminId);
        User user = TestData.user();
        user.getRoles().add(role);
        return userRepository.save(user).getId();
    }

    private String pathOf(int folderId) {
        return jdbc.queryForObject("SELECT path FROM folder WHERE id = ?", String.class, folderId);
    }

    private <T> T measure(String what, int maxStatements, Supplier<T> call) {
        entityManager.clear();
        statistics.clear();
        long started = System.nanoTime();
        T answer = call.get();
        long ms = (System.nanoTime() - started) / 1_000_000;
        long statements = statistics.getPrepareStatementCount();
        System.out.printf("[14] %-48s statements=%3d time=%5d ms%n", what, statements, ms);
        assertThat(statements).as(what + ": statements").isLessThanOrEqualTo(maxStatements);
        assertThat(ms).as(what + ": milliseconds").isLessThan(BOUND_MS);
        return answer;
    }
}
