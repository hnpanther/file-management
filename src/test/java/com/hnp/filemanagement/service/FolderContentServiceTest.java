package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FolderContentDTO;
import com.hnp.filemanagement.dto.FolderDetailsDTO;
import com.hnp.filemanagement.dto.FolderSearchDTO;
import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.FolderPermission;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.entity.UserFolderGrant;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.support.FolderFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import jakarta.persistence.EntityManager;

import java.time.LocalDateTime;
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
    private FolderRepository folderRepository;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;

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

        FolderFixture.Chain chain = FolderFixture.chain(folderRepository, tagGroupRepository, owner);
        categoryId = chain.categoryId();
        subCategoryId = chain.subCategoryId();
        otherSubCategoryId = FolderFixture.subCategory(folderRepository, chain.category(), owner,
                "Other" + TestData.nextSequence()).getId();
        tagId = chain.tagId();

        fileName = "report" + TestData.nextSequence();
        FileInfo fileInfo = TestData.fileInfo(owner, chain.tag(), fileName);
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
                .contains(categoryId);
    }

    @Test
    @DisplayName("a child folder carries what is under it, counted by kind")
    void childFoldersCarryTheirOwnCounts() {
        FolderContentDTO content = folderContentService.contentOf(
                categoryId, 0, 100, adminId);

        assertThat(content.folders())
                .filteredOn(entry -> entry.id() == subCategoryId)
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.kind()).isEqualTo("FOLDER");
                    assertThat(entry.folderCount()).as("one tag under it").isEqualTo(1L);
                    assertThat(entry.fileCount()).as("a sub-category cannot hold files").isZero();
                });
    }

    @Test
    @DisplayName("the breadcrumb is the chain of ancestors, root first and without the folder itself")
    void theBreadcrumbIsTheAncestorChain() {
        int tagFolderId = tagId;

        FolderContentDTO content = folderContentService.contentOf(tagFolderId, 0, 100, adminId);

        assertThat(content.folder().id()).isEqualTo(tagFolderId);
        assertThat(content.breadcrumb())
                .extracting(FolderContentDTO.FolderRef::kind)
                .containsExactly("ROOT", "FOLDER", "FOLDER");
        assertThat(content.breadcrumb())
                .extracting(FolderContentDTO.FolderRef::id)
                .doesNotContain(tagFolderId);
    }

    @Test
    @DisplayName("a tag folder lists its files, described by their newest version alone")
    void aTagFolderListsItsFiles() {
        FolderContentDTO content = folderContentService.contentOf(
                tagId, 0, 100, adminId);

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

    /**
     * "Download the latest" has to name one revision. Of the latest version's formats it is the
     * one uploaded last - by {@code created_at}, which is when it was stored, and by id only when
     * two share the instant. The fixture stores both formats of version 2 in one transaction, so
     * their timestamps agree and the id decides; then the docx is made the later upload by
     * moving its timestamp, and it must win despite the smaller id.
     */
    @Test
    @DisplayName("a file's latest revision is the format of the latest version uploaded last - by creation time, then id")
    void theLatestRevisionIsTheFormatUploadedLast() {
        entityManager.flush();
        List<FileDetails> versionTwo = fileDetailsRepository.findAll().stream()
                .filter(row -> row.getFileInfo().getFileName().equals(fileName) && row.getVersion() == 2)
                .toList();
        assertThat(versionTwo).hasSize(2);
        FileDetails docx = versionTwo.stream().filter(row -> row.getFileExtension().equals("docx")).findFirst().orElseThrow();
        FileDetails pdf = versionTwo.stream().filter(row -> row.getFileExtension().equals("pdf")).findFirst().orElseThrow();
        assertThat(docx.getId()).as("the fixture stores the docx first").isLessThan(pdf.getId());
        LocalDateTime sameInstant = docx.getCreatedAt();
        entityManager.createNativeQuery("UPDATE file_details SET created_at = ?1 WHERE id IN (?2, ?3)")
                .setParameter(1, sameInstant).setParameter(2, docx.getId()).setParameter(3, pdf.getId())
                .executeUpdate();
        entityManager.clear();

        FolderContentDTO.FileEntry tied = folderContentService.contentOf(tagId, 0, 100, adminId).files().getFirst();
        assertThat(tied.latestFileDetailsId()).as("same instant: the larger id").isEqualTo(pdf.getId());
        assertThat(tied.latestFormat()).isEqualTo("pdf");

        entityManager.createNativeQuery("UPDATE file_details SET created_at = ?1 WHERE id = ?2")
                .setParameter(1, sameInstant.plusMinutes(5)).setParameter(2, docx.getId())
                .executeUpdate();
        entityManager.clear();

        FolderContentDTO.FileEntry later = folderContentService.contentOf(tagId, 0, 100, adminId).files().getFirst();
        assertThat(later.latestFileDetailsId()).as("uploaded later wins over the larger id").isEqualTo(docx.getId());
        assertThat(later.latestFormat()).isEqualTo("docx");
        assertThat(later.formats()).as("the listing still shows every format of the version").containsExactly("docx", "pdf");

        FolderSearchDTO found = folderContentService.search(fileName, null, 0, 25, adminId);
        assertThat(found.hits().getFirst().file().latestFileDetailsId())
                .as("a search hit is the same entry, so it carries the same revision").isEqualTo(docx.getId());
    }

    // ---------------------------------------------------------------- access

    @Test
    @DisplayName("a folder on the way to a grant opens, says it is not readable, and shows only the route")
    void aTraversalOnlyFolderOpensWithoutRevealingItsContents() {
        grantDirectly(restrictedId, tagId);

        FolderContentDTO category = folderContentService.contentOf(
                categoryId, 0, 100, restrictedId);

        assertThat(category.readable()).as("the grant is below it, not on it").isFalse();
        assertThat(category.folders())
                .extracting(FolderContentDTO.FolderEntry::id)
                .containsExactly(subCategoryId);
        assertThat(category.folders())
                .extracting(FolderContentDTO.FolderEntry::id)
                .doesNotContain(otherSubCategoryId);

        FolderContentDTO tag = folderContentService.contentOf(
                tagId, 0, 100, restrictedId);

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
                categoryId, 0, 100, restrictedId))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------- paging

    @Test
    @DisplayName("a page size out of range is clamped, not refused")
    void thePageSizeIsClamped() {
        int tagFolderId = tagId;

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
            assertThat(hit.folder().id()).isEqualTo(tagId);
            assertThat(hit.breadcrumb())
                    .as("the same shape a listing returns, so one client renders both")
                    .extracting(FolderContentDTO.FolderRef::kind)
                    .containsExactly("ROOT", "FOLDER", "FOLDER");
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
                fileName, subCategoryId, 0, 25, adminId);
        assertThat(inside.scope().id()).isEqualTo(subCategoryId);
        assertThat(inside.hits()).hasSize(1);

        FolderSearchDTO elsewhere = folderContentService.search(
                fileName, otherSubCategoryId, 0, 25, adminId);
        assertThat(elsewhere.hits()).as("the file is not in this branch").isEmpty();
        assertThat(elsewhere.page().totalElements()).isZero();
    }

    @Test
    @DisplayName("search reaches only what may be read, not what may be walked through")
    void searchIsBoundedByWhatMayBeRead() {
        assertThat(folderContentService.search(fileName, null, 0, 25, restrictedId).hits())
                .as("no grant at all")
                .isEmpty();

        grantDirectly(restrictedId, otherSubCategoryId);
        assertThat(folderContentService.search(fileName, null, 0, 25, restrictedId).hits())
                .as("the category above the file is now walkable, which is not permission to read it")
                .isEmpty();

        grantDirectly(restrictedId, tagId);
        assertThat(folderContentService.search(fileName, null, 0, 25, restrictedId).hits())
                .hasSize(1);
    }

    @Test
    @DisplayName("a scope folder outside every grant is refused rather than quietly emptied")
    void aScopeOutsideEveryGrantIsRefused() {
        assertThatThrownBy(() -> folderContentService.search(
                fileName, categoryId, 0, 25, restrictedId))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------- folders in a search, and a folder's details

    @Test
    @DisplayName("a search finds folders too - by id, or by a fragment of the name or the label - each with its trail and counts")
    void searchFindsFolders() {
        String name = folderRepository.findById(tagId).orElseThrow().getName();

        FolderSearchDTO byName = folderContentService.search(name.substring(0, name.length() - 1), null, 0, 25, adminId);
        assertThat(byName.folders()).extracting(hit -> hit.folder().id()).contains(tagId);
        FolderSearchDTO.FolderHit hit = byName.folders().stream().filter(h -> h.folder().id() == tagId).findFirst().orElseThrow();
        assertThat(hit.breadcrumb()).extracting(FolderContentDTO.FolderRef::id)
                .containsExactly(folderRepository.findRoots().getFirst().getId(), categoryId, subCategoryId);
        assertThat(hit.folder().fileCount()).isEqualTo(1);

        FolderSearchDTO byId = folderContentService.search(String.valueOf(subCategoryId), null, 0, 25, adminId);
        assertThat(byId.folders()).extracting(h -> h.folder().id()).contains(subCategoryId);

        FolderSearchDTO byLabel = folderContentService.search(
                folderRepository.findById(categoryId).orElseThrow().getDisplayName(), null, 0, 25, adminId);
        assertThat(byLabel.folders()).extracting(h -> h.folder().id()).contains(categoryId);

        assertThat(folderContentService.search(name, otherSubCategoryId, 0, 25, adminId).folders())
                .as("a scope confines the folders too").isEmpty();
        assertThat(folderContentService.search("Home", null, 0, 25, adminId).folders())
                .as("the root is never a hit").extracting(h -> h.folder().kind()).doesNotContain("ROOT");
    }

    @Test
    @DisplayName("folder hits reach only what may be walked into")
    void folderHitsAreBoundedByVisibility() {
        String name = folderRepository.findById(tagId).orElseThrow().getName();
        assertThat(folderContentService.search(name, null, 0, 25, restrictedId).folders()).isEmpty();

        grantDirectly(restrictedId, tagId);
        assertThat(folderContentService.search(name, null, 0, 25, restrictedId).folders())
                .extracting(h -> h.folder().id()).contains(tagId);
        assertThat(folderContentService.search(name, null, 0, 25, restrictedId).folders())
                .extracting(h -> h.folder().id()).doesNotContain(otherSubCategoryId);
    }

    @Test
    @DisplayName("a folder's details: its trail, its group, what it holds directly and in total, and who made it")
    void detailsOfAFolder() {
        FolderDetailsDTO details = folderContentService.detailsOf(categoryId, adminId);

        assertThat(details.folder().id()).isEqualTo(categoryId);
        assertThat(details.depth()).isEqualTo(1);
        assertThat(details.breadcrumb()).extracting(FolderContentDTO.FolderRef::kind).containsExactly("ROOT");
        assertThat(details.tagGroup()).isNotNull();
        assertThat(details.folderCount()).as("two sub-categories").isEqualTo(2);
        assertThat(details.fileCount()).as("no file directly in it").isZero();
        assertThat(details.totalFiles()).as("the one file three levels down").isEqualTo(1);
        assertThat(details.createdAt()).isNotNull();
        assertThat(details.createdBy()).isNotBlank();

        FolderDetailsDTO tag = folderContentService.detailsOf(tagId, adminId);
        assertThat(tag.depth()).isEqualTo(3);
        assertThat(tag.fileCount()).isEqualTo(1);
        assertThat(tag.tagGroup().id()).as("the top-level folder's group, all the way down").isEqualTo(details.tagGroup().id());

        assertThatThrownBy(() -> folderContentService.detailsOf(tagId, restrictedId))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> folderContentService.detailsOf(999_999, adminId))
                .isInstanceOf(com.hnp.filemanagement.exception.InvalidDataException.class);
    }

    // ---------------------------------------------------------------- helpers


    private void grantDirectly(int userId, int folderId) {
        User user = userRepository.findById(userId).orElseThrow();
        List<UserFolderGrant> grants = new ArrayList<>(user.getFolderGrants());
        grants.add(new UserFolderGrant(user, folderRepository.findById(folderId).orElseThrow(), FolderPermission.READ));
        user.replaceFolderGrants(grants);
        userRepository.save(user);
    }
}
