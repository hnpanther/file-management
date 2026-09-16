package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileCategoryDTO;
import com.hnp.filemanagement.dto.FileSubCategoryDTO;
import com.hnp.filemanagement.dto.FolderContentDTO;
import com.hnp.filemanagement.dto.FolderSearchDTO;
import com.hnp.filemanagement.dto.MainTagFileDTO;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderPermission;
import com.hnp.filemanagement.entity.FolderSourceType;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.entity.UserFolderGrant;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.GeneralTagRepository;
import com.hnp.filemanagement.repository.MainTagFileRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The folder-first listing the file explorer is built on.
 *
 * <p>Enforcement is switched on here, as in {@link FolderAccessEnforcementTest}, because half of
 * what this service has to get right only exists when it is: a folder that may be walked through
 * but not read has to come back openable, empty of files, and <em>saying so</em> — the distinction
 * a plain "this folder is empty" loses, which is the confusion behind
 * {@code docs/issues.md} issue 75.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FolderContentServiceTest extends MySqlSupport {

    @Autowired
    private FolderContentService folderContentService;

    @Autowired
    private FileCategoryService fileCategoryService;
    @Autowired
    private FileSubCategoryService fileSubCategoryService;
    @Autowired
    private MainTagFileService mainTagFileService;

    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private FileCategoryRepository fileCategoryRepository;
    @Autowired
    private FileSubCategoryRepository fileSubCategoryRepository;
    @Autowired
    private MainTagFileRepository mainTagFileRepository;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private GeneralTagRepository generalTagRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRepository userRepository;

    private int adminId;
    private int restrictedId;
    private int categoryId;
    private int subCategoryId;
    private int tagId;
    private int otherSubCategoryId;
    private String fileName;

    /**
     * One category, two sub-categories, one tag under the first, one file with two formats at
     * version 2. The second sub-category exists so that "walking through reveals only the route to
     * the grant" has something it must <em>not</em> reveal.
     */
    @BeforeEach
    void setUp() {
        User owner = userRepository.save(TestData.user());

        Role adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();

        restrictedId = userRepository.save(TestData.user()).getId();

        GeneralTag generalTag = generalTagRepository.save(
                TestData.generalTag(owner, "gt" + TestData.nextSequence()));

        categoryId = createCategory(owner, generalTag);
        subCategoryId = createSubCategory(owner, categoryId);
        otherSubCategoryId = createSubCategory(owner, categoryId);
        tagId = createMainTag(owner, categoryId, subCategoryId);

        MainTagFile tag = mainTagFileRepository.findById(tagId).orElseThrow();
        fileName = "report" + TestData.nextSequence();
        FileInfo fileInfo = TestData.fileInfo(owner, tag, fileName);
        // Written through the repository, the file has no folder - exactly a row from before V2.3
        // that the backfill missed - and the explorer reads files by folder now (roadmap 7.2 step 3),
        // so such a row is deliberately invisible (FolderContentFolderReadTest). Link it as the
        // backfill would.
        fileInfo.setFolder(folderRepository.findBySourceTypeAndSourceId(FolderSourceType.MAIN_TAG, tagId).orElseThrow());
        TestData.fileDetails(owner, fileInfo, 1, "pdf");
        TestData.fileDetails(owner, fileInfo, 2, "docx");
        TestData.fileDetails(owner, fileInfo, 2, "pdf");
        fileInfoRepository.save(fileInfo);
    }

    // ---------------------------------------------------------------- the shape of an answer

    @Test
    @DisplayName("the root is the same request without an id, and carries no breadcrumb")
    void theRootListsCategoriesAndHasNoBreadcrumb() {
        FolderContentDTO content = folderContentService.contentOf(null, 0, 100, adminId);

        assertThat(content.folder().kind()).isEqualTo("ROOT");
        assertThat(content.breadcrumb()).isEmpty();
        assertThat(content.files()).isEmpty();
        assertThat(content.folders())
                .extracting(FolderContentDTO.FolderEntry::id)
                .contains(folderIdOf(FolderSourceType.CATEGORY, categoryId));
    }

    @Test
    @DisplayName("a child folder carries what is under it, counted by kind")
    void childFoldersCarryTheirOwnCounts() {
        FolderContentDTO content = folderContentService.contentOf(
                folderIdOf(FolderSourceType.CATEGORY, categoryId), 0, 100, adminId);

        assertThat(content.folders())
                .filteredOn(entry -> entry.id() == folderIdOf(FolderSourceType.SUB_CATEGORY, subCategoryId))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.kind()).isEqualTo("SUB_CATEGORY");
                    assertThat(entry.folderCount()).as("one tag under it").isEqualTo(1L);
                    assertThat(entry.fileCount()).as("a sub-category cannot hold files").isZero();
                });
    }

    @Test
    @DisplayName("the breadcrumb is the chain of ancestors, root first and without the folder itself")
    void theBreadcrumbIsTheAncestorChain() {
        int tagFolderId = folderIdOf(FolderSourceType.MAIN_TAG, tagId);

        FolderContentDTO content = folderContentService.contentOf(tagFolderId, 0, 100, adminId);

        assertThat(content.folder().id()).isEqualTo(tagFolderId);
        assertThat(content.breadcrumb())
                .extracting(FolderContentDTO.FolderRef::kind)
                .containsExactly("ROOT", "CATEGORY", "SUB_CATEGORY");
        assertThat(content.breadcrumb())
                .extracting(FolderContentDTO.FolderRef::id)
                .doesNotContain(tagFolderId);
    }

    @Test
    @DisplayName("a tag folder lists its files, described by their newest version alone")
    void aTagFolderListsItsFiles() {
        FolderContentDTO content = folderContentService.contentOf(
                folderIdOf(FolderSourceType.MAIN_TAG, tagId), 0, 100, adminId);

        assertThat(content.readable()).isTrue();
        assertThat(content.folders()).isEmpty();
        assertThat(content.page().totalElements()).isEqualTo(1L);
        assertThat(content.files()).singleElement().satisfies(file -> {
            assertThat(file.name()).isEqualTo(fileName);
            assertThat(file.lastVersion()).isEqualTo(2);
            assertThat(file.formats()).as("version 2 only, not every format ever stored")
                    .containsExactly("docx", "pdf");
            assertThat(file.size()).as("both formats of version 2, summed").isEqualTo(2048L);
        });
    }

    // ---------------------------------------------------------------- access

    @Test
    @DisplayName("a folder on the way to a grant opens, says it is not readable, and shows only the route")
    void aTraversalOnlyFolderOpensWithoutRevealingItsContents() {
        grantDirectly(restrictedId, FolderSourceType.MAIN_TAG, tagId);

        FolderContentDTO category = folderContentService.contentOf(
                folderIdOf(FolderSourceType.CATEGORY, categoryId), 0, 100, restrictedId);

        assertThat(category.readable()).as("the grant is below it, not on it").isFalse();
        assertThat(category.folders())
                .extracting(FolderContentDTO.FolderEntry::id)
                .containsExactly(folderIdOf(FolderSourceType.SUB_CATEGORY, subCategoryId));
        assertThat(category.folders())
                .extracting(FolderContentDTO.FolderEntry::id)
                .doesNotContain(folderIdOf(FolderSourceType.SUB_CATEGORY, otherSubCategoryId));

        FolderContentDTO tag = folderContentService.contentOf(
                folderIdOf(FolderSourceType.MAIN_TAG, tagId), 0, 100, restrictedId);

        assertThat(tag.readable()).isTrue();
        assertThat(tag.files()).hasSize(1);
    }

    @Test
    @DisplayName("someone with no grant gets an empty root rather than a refusal")
    void anUngrantedPersonGetsAnEmptyRoot() {
        FolderContentDTO content = folderContentService.contentOf(null, 0, 100, restrictedId);

        assertThat(content.folder().kind()).isEqualTo("ROOT");
        assertThat(content.folders()).isEmpty();
        assertThat(content.files()).isEmpty();
        assertThat(content.readable()).isFalse();
    }

    @Test
    @DisplayName("a named folder outside every grant is refused")
    void aFolderOutsideEveryGrantIsRefused() {
        assertThatThrownBy(() -> folderContentService.contentOf(
                folderIdOf(FolderSourceType.CATEGORY, categoryId), 0, 100, restrictedId))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------- paging

    @Test
    @DisplayName("a page size out of range is clamped, not refused")
    void thePageSizeIsClamped() {
        int tagFolderId = folderIdOf(FolderSourceType.MAIN_TAG, tagId);

        assertThat(folderContentService.contentOf(tagFolderId, 0, 100_000, adminId).page().size())
                .isEqualTo(FolderContentService.MAX_PAGE_SIZE);
        assertThat(folderContentService.contentOf(tagFolderId, -3, 0, adminId).page())
                .satisfies(page -> {
                    assertThat(page.number()).isZero();
                    assertThat(page.size()).isEqualTo(FolderContentService.DEFAULT_PAGE_SIZE);
                });
    }

    @Test
    @DisplayName("an id that names no folder is a bad request, not a refusal")
    void anUnknownFolderIdIsARequestError() {
        assertThatThrownBy(() -> folderContentService.contentOf(999_999, 0, 100, adminId))
                .isInstanceOf(InvalidDataException.class);
    }

    // ---------------------------------------------------------------- search

    @Test
    @DisplayName("a match carries the folder it lives in and the trail down to it")
    void aHitCarriesItsWholePath() {
        FolderSearchDTO found = folderContentService.search(fileName, null, 0, 25, adminId);

        assertThat(found.query()).isEqualTo(fileName);
        assertThat(found.scope()).as("searched everywhere").isNull();
        assertThat(found.hits()).singleElement().satisfies(hit -> {
            assertThat(hit.file().name()).isEqualTo(fileName);
            assertThat(hit.file().formats()).containsExactly("docx", "pdf");
            assertThat(hit.folder().id()).isEqualTo(folderIdOf(FolderSourceType.MAIN_TAG, tagId));
            assertThat(hit.breadcrumb())
                    .as("the same shape a listing returns, so one client renders both")
                    .extracting(FolderContentDTO.FolderRef::kind)
                    .containsExactly("ROOT", "CATEGORY", "SUB_CATEGORY");
        });
    }

    @Test
    @DisplayName("a query that parses as an id finds that file exactly")
    void findsByExactId() {
        int fileId = fileInfoRepository.findAll().stream()
                .filter(file -> file.getFileName().equals(fileName))
                .findFirst().orElseThrow().getId();

        assertThat(folderContentService.search(String.valueOf(fileId), null, 0, 25, adminId).hits())
                .singleElement()
                .satisfies(hit -> assertThat(hit.file().id()).isEqualTo(fileId));
    }

    @Test
    @DisplayName("a Persian-numeral query is text, not an id that fails to parse")
    void persianDigitsAreTreatedAsText() {
        assertThat(folderContentService.search("۱۵۷۸", null, 0, 25, adminId).hits()).isEmpty();
    }

    @Test
    @DisplayName("a blank query finds nothing rather than everything")
    void aBlankQueryFindsNothing() {
        assertThat(folderContentService.search("   ", null, 0, 25, adminId).hits()).isEmpty();
        assertThat(folderContentService.search(null, null, 0, 25, adminId).page().totalElements()).isZero();
    }

    @Test
    @DisplayName("a scope confines the search to one subtree")
    void theScopeConfinesTheSearch() {
        FolderSearchDTO inside = folderContentService.search(
                fileName, folderIdOf(FolderSourceType.SUB_CATEGORY, subCategoryId), 0, 25, adminId);
        assertThat(inside.scope().id()).isEqualTo(folderIdOf(FolderSourceType.SUB_CATEGORY, subCategoryId));
        assertThat(inside.hits()).hasSize(1);

        FolderSearchDTO elsewhere = folderContentService.search(
                fileName, folderIdOf(FolderSourceType.SUB_CATEGORY, otherSubCategoryId), 0, 25, adminId);
        assertThat(elsewhere.hits()).as("the file is not in this branch").isEmpty();
        assertThat(elsewhere.page().totalElements()).isZero();
    }

    @Test
    @DisplayName("search reaches only what may be read, not what may be walked through")
    void searchIsBoundedByWhatMayBeRead() {
        assertThat(folderContentService.search(fileName, null, 0, 25, restrictedId).hits())
                .as("no grant at all")
                .isEmpty();

        grantDirectly(restrictedId, FolderSourceType.SUB_CATEGORY, otherSubCategoryId);
        assertThat(folderContentService.search(fileName, null, 0, 25, restrictedId).hits())
                .as("the category above the file is now walkable, which is not permission to read it")
                .isEmpty();

        grantDirectly(restrictedId, FolderSourceType.MAIN_TAG, tagId);
        assertThat(folderContentService.search(fileName, null, 0, 25, restrictedId).hits())
                .hasSize(1);
    }

    @Test
    @DisplayName("a scope folder outside every grant is refused rather than quietly emptied")
    void aScopeOutsideEveryGrantIsRefused() {
        assertThatThrownBy(() -> folderContentService.search(
                fileName, folderIdOf(FolderSourceType.CATEGORY, categoryId), 0, 25, restrictedId))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------- helpers

    private int createCategory(User owner, GeneralTag generalTag) {
        FileCategoryDTO category = new FileCategoryDTO();
        category.setCategoryName("cat" + TestData.nextSequence());
        category.setCategoryNameDescription(category.getCategoryName() + " label");
        category.setDescription("a category");
        category.setGeneralTagId(generalTag.getId());
        fileCategoryService.createCategory(category, owner.getId());

        return fileCategoryRepository.findAll().stream()
                .filter(c -> c.getCategoryName().equals(category.getCategoryName()))
                .findFirst().map(FileCategory::getId).orElseThrow();
    }

    private int createSubCategory(User owner, int parentCategoryId) {
        FileSubCategoryDTO subCategory = new FileSubCategoryDTO();
        subCategory.setSubCategoryName("sub" + TestData.nextSequence());
        subCategory.setSubCategoryNameDescription(subCategory.getSubCategoryName() + " label");
        subCategory.setDescription("a sub-category");
        subCategory.setFileCategoryId(parentCategoryId);
        fileSubCategoryService.createFileSubCategory(subCategory, owner.getId());

        return fileSubCategoryRepository.findAll().stream()
                .filter(sc -> sc.getSubCategoryName().equals(subCategory.getSubCategoryName()))
                .findFirst().map(FileSubCategory::getId).orElseThrow();
    }

    private int createMainTag(User owner, int parentCategoryId, int parentSubCategoryId) {
        MainTagFileDTO tag = new MainTagFileDTO();
        tag.setTagName("tag" + TestData.nextSequence());
        tag.setTagNameDescription(tag.getTagName() + " label");
        tag.setDescription("a tag");
        tag.setFileSubCategoryId(parentSubCategoryId);
        tag.setFileCategoryId(parentCategoryId);
        tag.setType(0);
        mainTagFileService.createMainTagFile(tag, owner.getId());

        return mainTagFileRepository.findAll().stream()
                .filter(mt -> mt.getTagName().equals(tag.getTagName()))
                .findFirst().map(MainTagFile::getId).orElseThrow();
    }

    private void grantDirectly(int userId, FolderSourceType sourceType, int sourceId) {
        User user = userRepository.findById(userId).orElseThrow();
        List<UserFolderGrant> grants = new ArrayList<>(user.getFolderGrants());
        grants.add(new UserFolderGrant(user, folderOf(sourceType, sourceId), FolderPermission.READ));
        user.replaceFolderGrants(grants);
        userRepository.save(user);
    }

    private int folderIdOf(FolderSourceType sourceType, int sourceId) {
        return folderOf(sourceType, sourceId).getId();
    }

    private Folder folderOf(FolderSourceType sourceType, int sourceId) {
        return folderRepository.findBySourceTypeAndSourceId(sourceType, sourceId).orElseThrow();
    }
}
