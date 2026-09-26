package com.hnp.filemanagement;

import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.domain.FileTreeService;
import com.hnp.filemanagement.folder.domain.FolderContentService;
import com.hnp.filemanagement.folder.domain.FolderService;
import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.folder.domain.FolderDTO;
import com.hnp.filemanagement.folder.domain.FolderSearchDTO;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.shared.util.SearchKey;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persian names through the services, as 1.8.0 folds them (issue 86, roadmap step 4): a search
 * finds a name however its half-space and digits were typed, and a folder does not take two names
 * that differ only in that. After every test, each stored key is checked against what
 * {@link SearchKey} computes from the value beside it - the keys are written by the entities'
 * setters, and this is what shows that every write path went through one.
 */
@ServiceIntegrationTest
class PersianNameFoldingTest extends DatabaseSupport {

    private static final String ZWNJ = "‌";
    /** گزارش‌های ۱۴۰۳ - the half-space and Persian digits, as a Persian keyboard writes it. */
    private static final String REPORTS_1403 = "گزارش" + ZWNJ + "های ۱۴۰۳";

    @Autowired
    private FileService fileService;
    @Autowired
    private FolderService folderService;
    @Autowired
    private FolderContentService folderContentService;
    @Autowired
    private FileTreeService fileTreeService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private FolderFixture.Chain chain;

    @BeforeEach
    void setUp() {
        Role adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
    }

    @AfterEach
    void everyStoredKeyIsTheFoldOfItsValue() {
        entityManager.flush();
        assertConsistent("SELECT name, search_name FROM folder");
        assertConsistent("SELECT display_name, search_display_name FROM folder");
        assertConsistent("SELECT file_name, search_name FROM file_info");
        assertConsistent("SELECT description, search_description FROM file_info");
        assertConsistent("SELECT file_name, search_name FROM file_details");
        assertConsistent("SELECT description, search_description FROM file_details");
    }

    // ================================================================ search

    @Test
    @DisplayName("the file list finds a Persian name typed without the half-space, with a space, with ASCII digits or an Arabic yeh")
    void theFileListFindsEveryWriting() {
        FileDetailsDTO uploaded = upload(REPORTS_1403 + ".pdf", chain.tagId());

        for (String typed : List.of("گزارشهای", "گزارش های", "گزارشهاي 1403", "١٤٠٣", "  1403  ")) {
            assertThat(fileService.getPageFileInfo(20, 0, typed, adminId).getContent())
                    .as(typed).extracting(FileInfoDTO::getId).contains(uploaded.getFileInfoId());
        }
        assertThat(fileService.getPageFileInfo(20, 0, "1404", adminId).getContent())
                .extracting(FileInfoDTO::getId).doesNotContain(uploaded.getFileInfoId());
    }

    @Test
    @DisplayName("the explorer and the tree search find it too; a term of only a half-space finds nothing, not everything")
    void theExplorerAndTheTreeFindEveryWriting() {
        FileDetailsDTO uploaded = upload(REPORTS_1403 + ".pdf", chain.tagId());

        FolderSearchDTO found = folderContentService.search("گزارش های 1403", null, 0, 20, adminId);
        assertThat(found.hits()).extracting(hit -> hit.file().id()).contains(uploaded.getFileInfoId());
        assertThat(fileTreeService.search("گزارشهای١٤٠٣", adminId))
                .extracting(hit -> hit.getFileId()).contains(uploaded.getFileInfoId());

        FolderSearchDTO nothing = folderContentService.search(ZWNJ + "َ", null, 0, 20, adminId);
        assertThat(nothing.hits()).isEmpty();
        assertThat(nothing.folders()).isEmpty();
        assertThat(fileTreeService.search(ZWNJ, adminId)).isEmpty();
    }

    @Test
    @DisplayName("a changed description is searched by its new words, folded")
    void aDescriptionChangeIsSearchable() {
        FileDetailsDTO uploaded = upload("plain.pdf", chain.tagId());

        fileService.updateFileInfoDescription(uploaded.getFileInfoId(), "بودجهٔ سال ۱۴۰۵", adminId);

        assertThat(fileService.getPageFileInfo(20, 0, "بودجه سال 1405", adminId).getContent())
                .extracting(FileInfoDTO::getId).contains(uploaded.getFileInfoId());
    }

    // ================================================================ one name per folder

    @Test
    @DisplayName("a file whose name differs only by the half-space, the digits or an Arabic yeh is a duplicate; with a space it is another name")
    void aFileNameIsComparedByItsKey() {
        upload(REPORTS_1403 + ".pdf", chain.tagId());

        for (String duplicate : List.of("گزارشهای ۱۴۰۳.pdf", "گزارش" + ZWNJ + "های 1403.pdf", "گزارشهاي 1403.pdf")) {
            assertThatThrownBy(() -> upload(duplicate, chain.tagId())).as(duplicate)
                    .isInstanceOf(DuplicateResourceException.class);
        }
        assertThat(upload("گزارش های ۱۴۰۳.pdf", chain.tagId()).getId()).as("a space is not a half-space").isNotNull();
        assertThat(upload(REPORTS_1403 + ".pdf", chain.subCategoryId()).getId()).as("another folder").isNotNull();
    }

    @Test
    @DisplayName("a file is not moved into a folder that holds its name in another writing")
    void aMoveComparesTheKey() {
        FileDetailsDTO moving = upload(REPORTS_1403 + ".pdf", chain.subCategoryId());
        upload("گزارشهای 1403.pdf", chain.tagId());

        assertThatThrownBy(() -> fileService.moveFile(moving.getFileInfoId(), chain.tagId(), adminId))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("a folder name is compared by its key among its siblings; renaming a folder to another writing of its own name is allowed")
    void aFolderNameIsComparedByItsKey() {
        FolderDTO created = folderService.create(chain.tagId(), "اسناد" + ZWNJ + "۱۴۰۳", null, null, null, adminId);

        assertThatThrownBy(() -> folderService.create(chain.tagId(), "اسناد1403", null, null, null, adminId))
                .isInstanceOf(DuplicateResourceException.class);
        FolderDTO other = folderService.create(chain.tagId(), "دیگر", null, null, null, adminId);
        assertThatThrownBy(() -> folderService.rename(other.id(), "اسناد۱۴۰۳", null, null, adminId))
                .isInstanceOf(DuplicateResourceException.class);

        FolderDTO renamed = folderService.rename(created.id(), "اسناد1403", "بایگانی ۱۴۰۳", null, adminId);
        assertThat(renamed.name()).isEqualTo("اسناد1403");
        entityManager.flush();
        assertThat(folderRepository.findById(created.id()).orElseThrow().getSearchDisplayName()).isEqualTo("بایگانی 1403");
    }

    // ---------------------------------------------------------------- helpers

    private FileDetailsDTO upload(String fileName, int folderId) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile(fileName, fileName, "application/octet-stream", TestData.bytesFor(fileName)));
        return fileService.createNewFile(request, adminId, FileService.PRIVATE);
    }

    @SuppressWarnings("unchecked")
    private void assertConsistent(String sql) {
        List<Object[]> rows = entityManager.createNativeQuery(sql).getResultList();
        for (Object[] row : rows) {
            String value = (String) row[0];
            assertThat((String) row[1]).as("%s: the key of %s", sql, value).isEqualTo(SearchKey.of(value));
        }
    }
}
