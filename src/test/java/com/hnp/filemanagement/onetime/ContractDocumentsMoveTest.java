package com.hnp.filemanagement.onetime;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.domain.FolderKind;
import com.hnp.filemanagement.folder.domain.FolderService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one-off move of 2.16.1: each contract's own files into its {@code Documents}, nothing else touched.
 * Removed with {@link ContractDocumentsMove}.
 */
@ServiceIntegrationTest
class ContractDocumentsMoveTest extends DatabaseSupport {

    @Autowired
    private ContractDocumentsMove underTest;
    @Autowired
    private FileService fileService;
    @Autowired
    private FolderService folderService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private String rootPath;
    private int rootId;

    // ERP/PartyCompany/1234/Contract/{C1, C2, C3}, ERP/PartyCompany/5678/contract/C4, ERP/PartyCompany/1234/Letters -
    // ContractState one level short of production's, which nests deeper than the default max-depth (6) allows
    private int c1;
    private int c1State;
    private int c2;
    private int c2Documents;
    private int c3;
    private int c4;
    private int letters;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        adminId = userRepository.save(admin).getId();

        int n = TestData.nextSequence();
        int top = folderService.create(folderRepository.findFirstByKind(FolderKind.ROOT).orElseThrow().getId(),
                "ERP" + n, "ERP" + n, null, "erp" + n, adminId).id();
        rootId = folder(top, "PartyCompany");
        rootPath = "ERP" + n + "/PartyCompany";

        int company = folder(rootId, "1234");
        int contracts = folder(company, "Contract");
        c1 = folder(contracts, "C1");
        c1State = folder(c1, "ContractState");
        c2 = folder(contracts, "C2");
        c2Documents = folder(c2, "Documents");
        c3 = folder(contracts, "C3");
        letters = folder(company, "Letters");
        c4 = folder(folder(folder(rootId, "5678"), "contract"), "C4");
    }

    @Test
    @DisplayName("the preview counts what a run would do, and writes nothing")
    void preview() {
        upload(c1, "a.pdf");
        upload(c1, "b.pdf");
        upload(c1State, "statement.pdf");
        upload(c2Documents, "a.pdf");
        upload(c2, "a.pdf");
        upload(c2, "c.pdf");
        upload(c3State(), "statement.pdf");
        upload(c4, "d.pdf");
        upload(letters, "letter.pdf");
        entityManager.flush();

        ContractDocumentsMove.Plan plan = underTest.preview(rootPath);

        assertThat(plan.contracts()).isEqualTo(4);
        assertThat(plan.contractsWithFiles()).isEqualTo(3);
        assertThat(plan.foldersToCreate()).isEqualTo(2);
        assertThat(plan.files()).isEqualTo(5);
        // C2 has its Documents: its two files only moved, one of them a name Documents holds
        assertThat(plan.contractsWithDocuments()).isEqualTo(1);
        assertThat(plan.filesIntoExisting()).isEqualTo(2);
        assertThat(plan.filesIntoExistingToMove()).isEqualTo(1);
        // C1 and C4 have none: made, then their three files moved
        assertThat(plan.filesIntoNew()).isEqualTo(3);
        assertThat(plan.clashes()).isEqualTo(1);
        assertThat(plan.filesToMove()).isEqualTo(4);
        assertThat(plan.clashesListed()).extracting(ContractDocumentsMove.Skipped::contract)
                .singleElement().asString().endsWith("/C2/");
        assertThat(folderRepository.findByParentIdAndNameIgnoreCase(c1, "Documents")).isEmpty();
    }

    @Test
    @DisplayName("a run moves each contract's own files into its Documents - made where missing - and nothing else")
    void run() {
        int a = upload(c1, "a.pdf");
        int b = upload(c1, "b.pdf");
        int statement = upload(c1State, "statement.pdf");
        upload(c2Documents, "a.pdf");
        int c2a = upload(c2, "a.pdf");
        int c2c = upload(c2, "c.pdf");
        int d = upload(c4, "d.pdf");
        int letter = upload(letters, "letter.pdf");
        entityManager.flush();

        ContractDocumentsMove.Progress progress = underTest.run(rootId, rootPath, adminId);
        entityManager.flush();
        entityManager.clear();

        assertThat(progress.contracts()).isEqualTo(3);
        assertThat(progress.contractsDone()).isEqualTo(3);
        assertThat(progress.filesMoved()).isEqualTo(4);
        assertThat(progress.foldersCreated()).isEqualTo(2);
        assertThat(progress.skippedCount()).isEqualTo(1);
        assertThat(progress.failedCount()).isZero();
        assertThat(progress.finishedAt()).isNotNull();

        int c1Documents = documentsOf(c1);
        assertThat(folderOf(a)).isEqualTo(c1Documents);
        assertThat(folderOf(b)).isEqualTo(c1Documents);
        assertThat(folderOf(c2c)).isEqualTo(c2Documents);
        assertThat(folderOf(d)).isEqualTo(documentsOf(c4));
        // left: a name already in Documents, and everything that is not a contract's own file
        assertThat(folderOf(c2a)).isEqualTo(c2);
        assertThat(folderOf(statement)).isEqualTo(c1State);
        assertThat(folderOf(letter)).isEqualTo(letters);
        assertThat(folderRepository.findByParentIdOrderByNameAsc(c2)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM file_history WHERE file_info_id = ? AND event = 'FILE_MOVED' AND folder_id = ?",
                Integer.class, a, c1Documents)).isEqualTo(1);
    }

    @Test
    @DisplayName("a second run finds only what the first left")
    void runAgain() {
        upload(c1, "a.pdf");
        upload(c2Documents, "a.pdf");
        upload(c2, "a.pdf");
        entityManager.flush();
        underTest.run(rootId, rootPath, adminId);
        entityManager.flush();

        ContractDocumentsMove.Progress again = underTest.run(rootId, rootPath, adminId);

        assertThat(again.contracts()).isEqualTo(1);
        assertThat(again.filesMoved()).isZero();
        assertThat(again.foldersCreated()).isZero();
        assertThat(again.skipped()).singleElement().extracting(ContractDocumentsMove.Skipped::fileName).isEqualTo("a");
    }

    @Test
    @DisplayName("a path that names no folder is refused with a message")
    void unknownRoot() {
        assertThatThrownBy(() -> underTest.preview(rootPath + "/Nothing"))
                .isInstanceOf(InvalidDataException.class)
                .satisfies(e -> assertThat(((InvalidDataException) e).getMessageCode()).contains("contractDocuments.invalid.root"));
        assertThatThrownBy(() -> underTest.preview(" / "))
                .isInstanceOf(InvalidDataException.class);
    }

    private int c3State() {
        return folder(c3, "ContractState");
    }

    private int folder(int parentId, String name) {
        return folderService.create(parentId, name, name, null, null, adminId).id();
    }

    private int documentsOf(int contractId) {
        return folderRepository.findByParentIdAndNameIgnoreCase(contractId, "Documents").orElseThrow().getId();
    }

    private int folderOf(int fileInfoId) {
        return jdbcTemplate.queryForObject("SELECT folder_id FROM file_info WHERE id = ?", Integer.class, fileInfoId);
    }

    private int upload(int folderId, String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("contract " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "application/octet-stream", TestData.bytesFor(fileName)));
        FileDetailsDTO stored = fileService.createNewFile(request, adminId, FileService.PUBLIC);
        return stored.getFileInfoId();
    }
}
