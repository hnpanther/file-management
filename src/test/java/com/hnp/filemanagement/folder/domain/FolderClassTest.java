package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.domain.FileUploadDTO;
import com.hnp.filemanagement.folder.domain.FolderClassService.TemplateFolder;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.RoleClassGrantRepository;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Folder classes (roadmap Phase 14): what a class may be defined as, the class a folder takes when it is made -
 * by hand, by v1 or by S3, all through {@code FolderService.create} - the template made with it, the folder that
 * keeps files out, a class set by hand and the rules applied to what exists; and {@code class_path} kept true by
 * all of it, asserted after each ({@code findRowsWhoseClassPathDisagrees}). The access a class gives is
 * {@code FolderClassAccessTest}'s.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = "filemanagement.folders.max-depth=8")
class FolderClassTest extends DatabaseSupport {

    @Autowired
    private FolderClassService classService;
    @Autowired
    private FolderClassAssignment assignment;
    @Autowired
    private FolderService folderService;
    @Autowired
    private FileService fileService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private RoleClassGrantService roleClassGrantService;
    @Autowired
    private RoleClassGrantRepository roleClassGrantRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private ClassFixture.Classes classes;
    private ClassFixture.Root root;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        adminId = userRepository.save(admin).getId();
        classes = ClassFixture.classes(classService, adminId);
        root = ClassFixture.root(folderService, folderRepository, assignment, classes, adminId);
    }

    // ---------------------------------------------------------------- nothing changes without a class

    @Test
    @DisplayName("a folder under a folder without a class has none, and a class path of '/': everything as before")
    void noClassNoChange() {
        int plain = folderService.create(root.bucketId(), "Plain", "Plain", null, null, adminId).id();
        int below = folderService.create(plain, "Below", "Below", null, null, adminId).id();
        flush();

        assertThat(classOf(plain)).isNull();
        assertThat(classOf(below)).isNull();
        assertThat(classPathOf(below)).isEqualTo("/");
        assertThat(folderRepository.findByParentIdOrderByNameAsc(below)).isEmpty();
        assertThat(folderRepository.findRowsWhoseClassPathDisagrees()).isEmpty();
    }

    // ---------------------------------------------------------------- the class a new folder takes

    @Test
    @DisplayName("a company made under PartyCompany is a company, makes its Contract, and a contract its Documents and ContractState")
    void templatesCascade() {
        ClassFixture.Company company = ClassFixture.company(folderService, folderRepository, root, "A1", adminId, "C1");
        flush();
        ClassFixture.Contract c1 = company.contracts().getFirst();

        assertThat(classOf(company.id())).isEqualTo(classes.company());
        assertThat(classOf(company.contractsId())).isEqualTo(classes.contracts());
        assertThat(classOf(c1.id())).isEqualTo(classes.contract());
        assertThat(classOf(c1.documentsId())).isEqualTo(classes.documents());
        assertThat(classOf(c1.contractStateId())).isEqualTo(classes.statements());
        assertThat(displayNameOf(c1.documentsId())).isEqualTo("اسناد قرارداد");
        assertThat(classPathOf(c1.documentsId())).isEqualTo("/" + classes.companies() + "/" + classes.company() + "/"
                + classes.contracts() + "/" + classes.contract() + "/" + classes.documents() + "/");
        // what a container's rule gives: a statement under ContractState
        int statement = folderService.create(c1.contractStateId(), "5", "5", null, null, adminId).id();
        flush();
        assertThat(classOf(statement)).isEqualTo(classes.statement());
        assertThat(folderRepository.findRowsWhoseClassPathDisagrees()).isEmpty();
    }

    @Test
    @DisplayName("a template name matches folded - documents is Documents - and a company's other folder has no class")
    void foldedNamesAndOtherChildren() {
        ClassFixture.Company company = ClassFixture.company(folderService, folderRepository, root, "A1", adminId);
        int letters = folderService.create(company.id(), "Letters", "Letters", null, null, adminId).id();
        int contract = folderService.create(company.contractsId(), "C9", "C9", null, null, adminId).id();
        flush();

        assertThat(classOf(letters)).isNull();
        assertThat(classPathOf(letters)).isEqualTo("/" + classes.companies() + "/" + classes.company() + "/");
        // Documents was made by the template; asking for it by another case finds the same folder
        assertThat(folderRepository.findByParentIdAndNameIgnoreCase(contract, "documents")).isPresent();
        assertThat(folderRepository.findByParentIdOrderByNameAsc(contract)).hasSize(2);
    }

    @Test
    @DisplayName("a name a contract's class refuses is not made - by create, rename or move - and nothing is left behind")
    void refusedNames() {
        ClassFixture.Company company = ClassFixture.company(folderService, folderRepository, root, "A1", adminId, "C1");
        ClassFixture.Contract c1 = company.contracts().getFirst();
        int elsewhere = folderService.create(company.id(), "Loose", "Loose", null, null, adminId).id();
        flush();

        assertThatThrownBy(() -> folderService.create(c1.id(), "Misc", "Misc", null, null, adminId))
                .isInstanceOf(InvalidDataException.class)
                .satisfies(e -> assertThat(((InvalidDataException) e).getMessageCode()).contains("folderClass.refused"));
        assertThatThrownBy(() -> folderService.rename(c1.documentsId(), "Docs", "Docs", null, adminId))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> folderService.move(elsewhere, c1.id(), adminId))
                .isInstanceOf(InvalidDataException.class);
        // a rename that keeps a template name, and a label-only rename, are fine
        folderService.rename(c1.documentsId(), "Documents", "اسناد", null, adminId);
        flush();
        assertThat(folderRepository.findByParentIdOrderByNameAsc(c1.id())).hasSize(2);
    }

    @Test
    @DisplayName("a move keeps the folder's class, and rewrites the class path of everything below it")
    void moveRewritesClassPaths() {
        ClassFixture.Company a1 = ClassFixture.company(folderService, folderRepository, root, "A1", adminId, "C1");
        ClassFixture.Company b2 = ClassFixture.company(folderService, folderRepository, root, "B2", adminId);
        int plain = folderService.create(root.bucketId(), "Archive", "Archive", null, null, adminId).id();
        ClassFixture.Contract c1 = a1.contracts().getFirst();
        flush();

        folderService.move(c1.id(), b2.contractsId(), adminId);
        flush();
        assertThat(classOf(c1.id())).isEqualTo(classes.contract());
        assertThat(folderRepository.findRowsWhoseClassPathDisagrees()).isEmpty();

        folderService.move(c1.id(), plain, adminId);
        flush();
        assertThat(classPathOf(c1.documentsId())).isEqualTo("/" + classes.contract() + "/" + classes.documents() + "/");
        assertThat(folderRepository.findRowsWhoseClassPathDisagrees()).isEmpty();
    }

    // ---------------------------------------------------------------- container only

    @Test
    @DisplayName("a container takes no new file - not even from an administrator, nor by a move - and its subfolders do")
    void containerOnly() {
        ClassFixture.Company company = ClassFixture.company(folderService, folderRepository, root, "A1", adminId, "C1");
        ClassFixture.Contract c1 = company.contracts().getFirst();
        flush();

        assertThatThrownBy(() -> upload(c1.id(), "contract.pdf"))
                .isInstanceOf(InvalidDataException.class)
                .satisfies(e -> assertThat(((InvalidDataException) e).getMessageCode()).contains("upload.invalid.folderIsContainer"));
        FileDetailsDTO inDocuments = upload(c1.documentsId(), "contract.pdf");
        assertThatThrownBy(() -> fileService.moveFile(inDocuments.getFileInfoId(), c1.id(), adminId))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> fileService.uploadTargetOf(c1.id(), adminId)).isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("a file already in a folder that becomes a container stays, is listed and takes new versions")
    void filesAlreadyInAContainerStay() {
        int folder = folderService.create(root.bucketId(), "Old", "Old", null, null, adminId).id();
        FileDetailsDTO old = upload(folder, "old.pdf");
        flush();

        assignment.set(folder, classes.contract(), adminId);
        flush();

        fileService.createNewFileDetails(version(old.getFileInfoId(), "old", "pdf", 2), adminId);
        flush();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM file_details WHERE file_info_id = ?", Integer.class,
                old.getFileInfoId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT folder_id FROM file_info WHERE id = ?", Integer.class,
                old.getFileInfoId())).isEqualTo(folder);
    }

    // ---------------------------------------------------------------- a class set by hand

    @Test
    @DisplayName("setting a class rewrites the class paths below and makes the template; the system folders take none")
    void setClass() {
        int folder = folderService.create(root.bucketId(), "Deal", "Deal", null, null, adminId).id();
        int below = folderService.create(folder, "Notes", "Notes", null, null, adminId).id();
        flush();

        FolderClassAssignment.Assigned assigned = assignment.set(folder, classes.contract(), adminId);
        flush();
        assertThat(assigned.templateFoldersMade()).isEqualTo(2);
        assertThat(classPathOf(below)).isEqualTo("/" + classes.contract() + "/");
        assertThat(folderRepository.findRowsWhoseClassPathDisagrees()).isEmpty();

        assignment.set(folder, null, adminId);
        flush();
        assertThat(classOf(folder)).isNull();
        assertThat(classPathOf(below)).isEqualTo("/");
        assertThat(folderRepository.findRowsWhoseClassPathDisagrees()).isEmpty();

        int rootId = folderRepository.findFirstByKind(FolderKind.ROOT).orElseThrow().getId();
        assertThatThrownBy(() -> assignment.set(rootId, classes.contract(), adminId)).isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("a class that does not exist is refused, and nothing changes")
    void unknownClass() {
        int folder = folderService.create(root.bucketId(), "Deal", "Deal", null, null, adminId).id();
        flush();

        assertThatThrownBy(() -> assignment.set(folder, 999_999, adminId)).isInstanceOf(InvalidDataException.class);
        assertThat(classOf(folder)).isNull();
    }

    // ---------------------------------------------------------------- the rules applied to what exists

    @Test
    @DisplayName("applied below PartyCompany: an old tree takes its classes top down, gets its templates, and keeps a class it had")
    void applyToWhatExists() {
        // made before any class: the shape n8n made
        int bucket = folderService.create(folderRepository.findFirstByKind(FolderKind.ROOT).orElseThrow().getId(),
                "OLD" + TestData.nextSequence(), "OLD", null, "old" + TestData.nextSequence(), adminId).id();
        int party = folderService.create(bucket, "PartyCompany", "PartyCompany", null, null, adminId).id();
        int company = folderService.create(party, "1234", "1234", null, null, adminId).id();
        int contracts = folderService.create(company, "Contract", "Contract", null, null, adminId).id();
        int c1 = folderService.create(contracts, "98-001", "98-001", null, null, adminId).id();
        int documents = folderService.create(c1, "Documents", "Documents", null, null, adminId).id();
        int stray = folderService.create(c1, "Stray", "Stray", null, null, adminId).id();
        int c2 = folderService.create(contracts, "98-002", "98-002", null, null, adminId).id();
        int keeps = folderService.create(company, "Letters", "Letters", null, null, adminId).id();
        assignment.set(keeps, classes.statement(), adminId);
        assignment.set(party, classes.companies(), adminId);
        flush();

        FolderClassAssignment.Plan plan = assignment.preview(party, adminId);
        assertThat(plan.folders()).isEqualTo(7);
        assertThat(plan.classedAlready()).isEqualTo(1);
        assertThat(plan.toClassifyTotal()).isEqualTo(5); // company, Contract, two contracts, Documents
        assertThat(plan.refusedCount()).isEqualTo(1);
        assertThat(plan.refused()).extracting(FolderClassAssignment.Refused::keyPath).singleElement().asString().endsWith("/Stray/");
        // C1 lacks ContractState, C2 lacks both
        assertThat(plan.templateFoldersToMake()).isEqualTo(3);
        assertThat(classOf(company)).as("a preview writes nothing").isNull();

        FolderClassAssignment.Progress done = assignment.run(party, adminId);
        flush();
        assertThat(done.classified()).isEqualTo(5);
        assertThat(done.templateFoldersMade()).isEqualTo(3);
        assertThat(done.failedCount()).isZero();
        assertThat(classOf(company)).isEqualTo(classes.company());
        assertThat(classOf(contracts)).isEqualTo(classes.contracts());
        assertThat(classOf(c1)).isEqualTo(classes.contract());
        assertThat(classOf(c2)).isEqualTo(classes.contract());
        assertThat(classOf(documents)).isEqualTo(classes.documents());
        assertThat(classOf(stray)).isNull();
        assertThat(classOf(keeps)).isEqualTo(classes.statement());
        assertThat(folderRepository.findByParentIdOrderByNameAsc(c2)).hasSize(2);
        assertThat(folderRepository.findRowsWhoseClassPathDisagrees()).isEmpty();

        FolderClassAssignment.Plan again = assignment.preview(party, adminId);
        assertThat(again.toClassifyTotal()).isZero();
        assertThat(again.templateFoldersToMake()).isZero();
    }

    @Test
    @DisplayName("the start's repair sets class paths a release before 2.17.0 would have left behind, and touches nothing right")
    void repair() {
        ClassFixture.Company company = ClassFixture.company(folderService, folderRepository, root, "A1", adminId, "C1");
        int documents = company.contracts().getFirst().documentsId();
        flush();
        jdbcTemplate.update("UPDATE folder SET class_path = '/' WHERE id = ?", documents);

        assertThat(folderRepository.findRowsWhoseClassPathDisagrees()).contains(documents);
        assertThat(folderRepository.repairClassPaths()).isEqualTo(1);
        assertThat(folderRepository.findRowsWhoseClassPathDisagrees()).isEmpty();
        assertThat(folderRepository.repairClassPaths()).isZero();
    }

    // ---------------------------------------------------------------- what a class may be

    @Test
    @DisplayName("a class's name is unique without case and of letters, digits, _ and -; its title is required")
    void definitionsAreChecked() {
        assertThatThrownBy(() -> classService.create(ClassFixture.definition("bad name", "t", false, "NONE", null, List.of()), adminId))
                .isInstanceOf(InvalidDataException.class);
        String name = "dup" + TestData.nextSequence();
        classService.create(ClassFixture.definition(name, "t", false, "NONE", null, List.of()), adminId);
        assertThatThrownBy(() -> classService.create(ClassFixture.definition(name.toUpperCase(), "t", false, "NONE", null, List.of()), adminId))
                .isInstanceOf(DuplicateResourceException.class);
        assertThatThrownBy(() -> classService.create(ClassFixture.definition("x" + TestData.nextSequence(), " ", false, "NONE", null, List.of()), adminId))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> classService.create(ClassFixture.definition("x" + TestData.nextSequence(), "t", false, "CLASS", null, List.of()), adminId))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("a template's names are folder names, unique folded, each with a class - and no template makes its own class again")
    void templatesAreChecked() {
        assertThatThrownBy(() -> classService.create(ClassFixture.definition("t" + TestData.nextSequence(), "t", false, "NONE", null,
                List.of(new TemplateFolder("a/b", "", classes.documents()))), adminId)).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> classService.create(ClassFixture.definition("t" + TestData.nextSequence(), "t", false, "NONE", null,
                List.of(new TemplateFolder("Docs", "", classes.documents()), new TemplateFolder("docs", "", classes.documents()))), adminId))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> classService.create(ClassFixture.definition("t" + TestData.nextSequence(), "t", false, "NONE", null,
                List.of(new TemplateFolder("Docs", "", 999_999))), adminId)).isInstanceOf(InvalidDataException.class);

        // the class itself in its own template: refused, the folder and its class named in the refusal
        int self = classService.create(ClassFixture.definition("s" + TestData.nextSequence(), "خود", false, "NONE", null,
                List.of()), adminId);
        assertThatThrownBy(() -> classService.update(self, ClassFixture.definition(classService.one(self).name(), "خود", false,
                "NONE", null, List.of(new TemplateFolder("Documents", "", self))), adminId))
                .isInstanceOf(InvalidDataException.class)
                .satisfies(e -> assertThat(((InvalidDataException) e).getMessageArguments()).containsExactly("خود", "Documents", "خود"));

        // documents -> (new) wrapper -> documents: a cycle, refused, and the class left as it was
        int wrapper = classService.create(ClassFixture.definition("w" + TestData.nextSequence(), "w", false, "NONE", null,
                List.of(new TemplateFolder("Inner", "", classes.documents()))), adminId);
        assertThatThrownBy(() -> classService.update(classes.documents(), ClassFixture.definition(
                classService.one(classes.documents()).name(), "اسناد", false, "NONE", null,
                List.of(new TemplateFolder("Outer", "", wrapper))), adminId))
                .isInstanceOf(InvalidDataException.class)
                .satisfies(e -> assertThat(((InvalidDataException) e).getMessageCode()).contains("folderClass.invalid.templateCycle"));
    }

    @Test
    @DisplayName("a template folder of no class is made with the others, and has no class - nor do the folders made in it")
    void templateFolderWithoutAClass() {
        int deal = classService.create(ClassFixture.definition("deal" + TestData.nextSequence(), "معامله", true, "REFUSE", null,
                List.of(new TemplateFolder("Documents", "اسناد", classes.documents()), new TemplateFolder("Misc", "متفرقه", null))),
                adminId);
        assertThat(classService.one(deal).template()).extracting(FolderClassService.TemplateView::classId)
                .containsExactly(classes.documents(), null);
        int folder = folderService.create(root.bucketId(), "Deal", "Deal", null, null, adminId).id();
        assignment.set(folder, deal, adminId);
        flush();

        int misc = folderRepository.findByParentIdAndNameIgnoreCase(folder, "Misc").orElseThrow().getId();
        assertThat(classOf(misc)).isNull();
        assertThat(classPathOf(misc)).isEqualTo("/" + deal + "/");
        int inside = folderService.create(misc, "Notes", "Notes", null, null, adminId).id();
        flush();
        assertThat(classOf(inside)).isNull();
        // a name in the template is allowed under a refusing class even with no class to take
        assertThat(folderRepository.findByParentIdOrderByNameAsc(folder)).hasSize(2);
        assertThat(assignment.preview(root.bucketId(), adminId).templateFoldersToMake()).isZero();
        assertThat(folderRepository.findRowsWhoseClassPathDisagrees()).isEmpty();
    }

    @Test
    @DisplayName("a class is deleted only when no folder, grant or other class uses it")
    void deleteOnlyWhenUnused() {
        int unused = classService.create(ClassFixture.definition("u" + TestData.nextSequence(), "u", false, "NONE", null, List.of()), adminId);
        classService.delete(unused, adminId);
        flush();
        assertThat(classService.all()).noneMatch(view -> view.id() == unused);

        // carried by PartyCompany
        assertThatThrownBy(() -> classService.delete(classes.companies(), adminId)).isInstanceOf(InvalidDataException.class);
        // named by the contract's template
        assertThatThrownBy(() -> classService.delete(classes.documents(), adminId)).isInstanceOf(InvalidDataException.class);
        // granted to a role
        int lonely = classService.create(ClassFixture.definition("l" + TestData.nextSequence(), "l", false, "NONE", null, List.of()), adminId);
        Role role = roleRepository.save(TestData.role("R" + TestData.nextSequence()));
        roleClassGrantService.replace(role.getId(), List.of(new RoleClassGrantService.Requested(lonely, "READ", "")), adminId);
        flush();
        assertThatThrownBy(() -> classService.delete(lonely, adminId))
                .isInstanceOf(InvalidDataException.class)
                .satisfies(e -> assertThat(((InvalidDataException) e).getMessageCode()).contains("folderClass.inUse.grants"));
    }

    // ---------------------------------------------------------------- a role's class grants

    @Test
    @DisplayName("a role's class grants are replaced whole, a scope named by its path; a fixed role refuses them; a copy keeps them")
    void roleGrants() {
        Role role = roleRepository.save(TestData.role("Contracts" + TestData.nextSequence()));
        roleClassGrantService.replace(role.getId(), List.of(
                new RoleClassGrantService.Requested(classes.documents(), "READ", ""),
                new RoleClassGrantService.Requested(classes.statement(), "WRITE", root.path())), adminId);
        flush();
        assertThat(roleClassGrantService.of(role.getId()))
                .extracting(RoleClassGrantService.GrantView::scope)
                .containsExactlyInAnyOrder(null, root.path());

        assertThatThrownBy(() -> roleClassGrantService.replace(role.getId(), List.of(
                new RoleClassGrantService.Requested(classes.documents(), "READ", root.path() + "/Nothing")), adminId))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> roleClassGrantService.replace(role.getId(), List.of(
                new RoleClassGrantService.Requested(classes.documents(), "READ", ""),
                new RoleClassGrantService.Requested(classes.documents(), "WRITE", " ")), adminId))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> roleClassGrantService.replace(role.getId(), List.of(
                new RoleClassGrantService.Requested(classes.documents(), "DELETE", "")), adminId))
                .isInstanceOf(InvalidDataException.class);

        Role user = roleRepository.findByRoleNameIgnoreCase("USER").orElseGet(() -> roleRepository.save(TestData.role("USER")));
        assertThatThrownBy(() -> roleClassGrantService.replace(user.getId(), List.of(
                new RoleClassGrantService.Requested(classes.documents(), "READ", "")), adminId))
                .isInstanceOf(InvalidDataException.class);

        roleClassGrantService.replace(role.getId(), List.of(), adminId);
        flush();
        assertThat(roleClassGrantRepository.findByRoleIdWithClassAndScope(role.getId())).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private Integer classOf(int folderId) {
        return jdbcTemplate.queryForObject("SELECT class_id FROM folder WHERE id = ?", Integer.class, folderId);
    }

    private String classPathOf(int folderId) {
        return jdbcTemplate.queryForObject("SELECT class_path FROM folder WHERE id = ?", String.class, folderId);
    }

    private String displayNameOf(int folderId) {
        return jdbcTemplate.queryForObject("SELECT display_name FROM folder WHERE id = ?", String.class, folderId);
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }

    private FileDetailsDTO upload(int folderId, String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription(fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "application/octet-stream", TestData.bytesFor(fileName)));
        return fileService.createNewFile(request, adminId, FileService.PUBLIC);
    }

    private static FileUploadDTO version(int fileId, String name, String extension, int version) {
        FileUploadDTO request = new FileUploadDTO();
        request.setFileId(fileId);
        request.setFileName(name);
        request.setFileNameWithoutExtension(name);
        request.setVersion(version);
        request.setType("version");
        request.setFileDetailsDescription("v" + version);
        request.setMultipartFile(new MockMultipartFile("file", name + "." + extension, "application/octet-stream",
                TestData.bytesFor(name + "." + extension)));
        return request;
    }
}
