package com.hnp.filemanagement.trash;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.domain.Bin;
import com.hnp.filemanagement.folder.domain.FolderAccess;
import com.hnp.filemanagement.folder.domain.FolderAccessService;
import com.hnp.filemanagement.folder.domain.FolderClassAssignment;
import com.hnp.filemanagement.folder.domain.FolderClassService;
import com.hnp.filemanagement.folder.domain.FolderReadScope;
import com.hnp.filemanagement.folder.domain.FolderService;
import com.hnp.filemanagement.folder.domain.FolderTreeDeleteService;
import com.hnp.filemanagement.folder.domain.GrantedFolderPath;
import com.hnp.filemanagement.folder.domain.ReadScopeSql;
import com.hnp.filemanagement.folder.domain.RoleClassGrantService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.FolderSearchRepository;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.report.ReportRepository;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.support.ClassFixture;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The recycle bin reached by nobody (roadmap 13.1), on the grants that could reach it: a class grant on every folder
 * of a class, one within a scope, a folder grant on what was deleted, an API key's, and an administrator's reading of
 * everything - asked of the file list, a download, the folder search, the report totals, the grants in SQL and in
 * Java. The ERP's structure of {@code FolderClassAccessTest}: a contract deleted, a company deleted, and back.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.folders.max-depth=8"})
class TrashExclusionTest extends DatabaseSupport {

    @Autowired
    private FolderClassService classService;
    @Autowired
    private FolderClassAssignment assignment;
    @Autowired
    private RoleClassGrantService roleClassGrantService;
    @Autowired
    private FolderService folderService;
    @Autowired
    private FolderTreeDeleteService folderDeleteService;
    @Autowired
    private TrashRestore trashRestore;
    @Autowired
    private TrashEntryRepository entries;
    @Autowired
    private FolderAccessService folderAccessService;
    @Autowired
    private FolderSearchRepository folderSearchRepository;
    @Autowired
    private ReportRepository reportRepository;
    @Autowired
    private FileService fileService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private ClassFixture.Root root;
    private ClassFixture.Company a1;
    private ClassFixture.Company b2;
    private FileDetailsDTO c1doc;
    private int contractsDept;
    private int scoped;
    private int folderGranted;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        adminId = userRepository.save(admin).getId();
        ClassFixture.Classes classes = ClassFixture.classes(classService, adminId);
        root = ClassFixture.root(folderService, folderRepository, assignment, classes, adminId);
        a1 = ClassFixture.company(folderService, folderRepository, root, "A1", adminId, "C1", "C2");
        b2 = ClassFixture.company(folderService, folderRepository, root, "B2", adminId, "C3");
        c1doc = upload(a1.contracts().get(0).documentsId(), "c1doc.pdf");
        upload(a1.contracts().get(1).documentsId(), "c2doc.pdf");
        upload(b2.contracts().getFirst().documentsId(), "c3doc.pdf");

        contractsDept = userWith(new RoleClassGrantService.Requested(classes.documents(), "WRITE", ""));
        scoped = userWith(new RoleClassGrantService.Requested(classes.documents(), "READ", root.path() + "/A1"));
        folderGranted = userRepository.save(TestData.user()).getId();
        jdbcTemplate.update("INSERT INTO user_folder (user_id, folder_id, permission) VALUES (?, ?, 'WRITE')",
                folderGranted, a1.contracts().getFirst().id());
        flush();
    }

    @Test
    @DisplayName("a contract deleted: no class grant, folder grant or administrator reaches it, by any way")
    void aContractInTheBinIsReachedByNobody() {
        int c1 = a1.contracts().getFirst().id();
        folderDeleteService.deleteTree(c1, adminId);
        flush();
        String binned = pathOf(c1);
        assertThat(binned).startsWith(Bin.PATH);

        assertThat(listed(contractsDept)).containsExactlyInAnyOrder("c2doc", "c3doc");
        assertThat(listed(scoped)).containsExactly("c2doc");
        assertThat(listed(folderGranted)).isEmpty();
        assertThat(listed(adminId)).doesNotContain("c1doc").contains("c2doc", "c3doc");
        for (int reader : List.of(contractsDept, scoped, folderGranted, adminId)) {
            assertThatThrownBy(() -> fileService.downloadFile(c1doc.getId(), reader)).isInstanceOf(ResourceNotFoundException.class);
            FolderAccess access = folderAccessService.accessFor(reader);
            assertThat(access.canRead(binned)).isFalse();
            assertThat(access.visible(binned)).isFalse();
            assertThat(access.readablePaths()).noneMatch(Bin::holds);
            assertThat(inBinBy(folderAccessService.readScope(reader))).as("reader " + reader).isZero();
        }
        assertThat(folderAccessService.accessFor(folderGranted).isEmpty()).as("its only grant is on what was deleted").isTrue();
        assertThat(inBinBy(FolderReadScope.everything())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM (" + GrantedFolderPath.SQL + ") g WHERE g.path LIKE '/0/%'",
                Integer.class)).as("no grant row names the bin").isZero();

        String all = pathOf(root.bucketId());
        assertThat(folderSearchRepository.visibleMatches(folderAccessService.readScope(contractsDept), null, "Documents", all, 50))
                .hasSize(2);
        assertThat(folderSearchRepository.visibleMatches(folderAccessService.readScope(contractsDept), null, "C1", all, 50))
                .isEmpty();
        assertThat(reportRepository.totals(folderAccessService.readScope(contractsDept)).files()).isEqualTo(2);
    }

    @Test
    @DisplayName("a class grant's scope deleted: it reaches nothing; restored, all of it again")
    void aScopeInTheBinCoversNothingAndComesBack() {
        folderDeleteService.deleteTree(a1.id(), adminId);
        flush();
        assertThat(listed(scoped)).isEmpty();
        assertThat(folderAccessService.accessFor(scoped).isEmpty()).isTrue();
        assertThat(listed(contractsDept)).containsExactly("c3doc");

        trashRestore.restore(entries.findByFolderId(a1.id()).orElseThrow().getId(), null, null, adminId);
        flush();
        assertThat(listed(scoped)).containsExactlyInAnyOrder("c1doc", "c2doc");
        assertThat(listed(contractsDept)).containsExactlyInAnyOrder("c1doc", "c2doc", "c3doc");
        assertThat(folderRepository.findRowsWhoseClassPathDisagrees()).isEmpty();
        assertThat(folderRepository.findRowsWhoseKeyPathDisagrees()).isEmpty();
        assertThat(folderRepository.findRowsWhoseDerivedColumnsDisagree()).isEmpty();
    }

    @Test
    @DisplayName("an API key's grant on a folder deleted covers nothing")
    void anApiKeysGrantInTheBin() {
        int key = jdbcTemplate.queryForObject("""
                INSERT INTO api_key (key_id, secret_hash, title, enabled, created_at, created_by, kind)
                VALUES (?, ?, 'bin test', 1, now(), ?, 'V1') RETURNING id""", Integer.class,
                "k" + TestData.nextSequence(), "0".repeat(64), adminId);
        int documents = a1.contracts().get(1).documentsId();
        jdbcTemplate.update("INSERT INTO api_key_folder (api_key_id, folder_id, permission) VALUES (?, ?, 'READ')", key, documents);
        assertThat(folderAccessService.accessForApiKey(key).canRead(pathOf(documents))).isTrue();

        folderDeleteService.deleteTree(a1.contracts().get(1).id(), adminId);
        flush();
        assertThat(folderAccessService.accessForApiKey(key).isEmpty()).isTrue();
        assertThat(inBinBy(FolderReadScope.ofApiKey(key, false))).isZero();
    }

    // ---------------------------------------------------------------- pieces

    /** How many folders in the bin a scope reads, or may walk into, in SQL - as every list asks. */
    private int inBinBy(FolderReadScope scope) {
        if (scope.nothing()) {
            return 0;
        }
        String sql = ReadScopeSql.grantedWithSignposts(scope) + "SELECT count(*) FROM folder f WHERE f.path LIKE '/0/%' AND ("
                + ReadScopeSql.readable(scope, "f") + " OR " + ReadScopeSql.visible(scope, "f") + ")";
        return namedJdbc.queryForObject(sql, ReadScopeSql.parameters(scope), Integer.class);
    }

    private List<String> listed(int userId) {
        return fileService.getPageFileInfo(100, 0, null, userId).content().stream().map(FileInfoDTO::getFileName).toList();
    }

    private int userWith(RoleClassGrantService.Requested... grants) {
        Role role = roleRepository.save(TestData.role("BinRole" + TestData.nextSequence()));
        roleClassGrantService.replace(role.getId(), List.of(grants), adminId);
        User user = TestData.user();
        user.getRoles().add(role);
        return userRepository.save(user).getId();
    }

    private String pathOf(int folderId) {
        return jdbcTemplate.queryForObject("SELECT path FROM folder WHERE id = ?", String.class, folderId);
    }

    private FileDetailsDTO upload(int folderId, String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription(fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "application/octet-stream", TestData.bytesFor(fileName)));
        return fileService.createNewFile(request, adminId, FileService.PUBLIC);
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }
}
