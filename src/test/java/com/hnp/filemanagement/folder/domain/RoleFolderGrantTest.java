package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.identity.domain.RoleService;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.support.FolderFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Granting folders to a role from the edit page — the write half of roadmap 6.5.
 *
 * <p>The page posts the whole selection, so the interesting cases are the ones where "what was sent"
 * and "what the role ends up with" could drift apart: removing every grant, sending an id that does
 * not exist, and the difference between a folder granted outright and one merely reached through an
 * ancestor.
 */
@ServiceIntegrationTest
class RoleFolderGrantTest extends DatabaseSupport {

    @Autowired
    private RoleService underTest;
    @Autowired
    private ActionHistoryService actionHistoryService;

    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;

    private int roleId;
    private int principalId;
    private int categoryFolderId;
    private int subCategoryFolderId;
    private int tagFolderId;

    @BeforeEach
    void setUp() {
        User creator = userRepository.save(TestData.user());
        principalId = creator.getId();
        roleId = roleRepository.save(TestData.role("READERS" + TestData.nextSequence())).getId();

        FolderFixture.Chain chain = FolderFixture.chain(folderRepository, tagGroupRepository, creator);
        categoryFolderId = chain.categoryId();
        subCategoryFolderId = chain.subCategoryId();
        tagFolderId = chain.tagId();
    }

    @Test
    @DisplayName("granting a folder writes the row and an audit line")
    void grantingAFolder() {
        underTest.updateFoldersOfRole(roleId, List.of(subCategoryFolderId + ":READ"), principalId);

        assertThat(grantsOf(roleId))
                .extracting(grant -> grant.getFolder().getId())
                .containsExactly(subCategoryFolderId);
        assertThat(actionHistoryService.getActionHistoriesOfEntity(roleId, EntityEnum.RoleFolder))
                .hasSize(1);
    }

    @Test
    @DisplayName("the posted selection replaces the previous one, so an unticked folder is removed")
    void theSelectionReplacesWhatWasThere() {
        underTest.updateFoldersOfRole(roleId, List.of(subCategoryFolderId + ":READ"), principalId);

        underTest.updateFoldersOfRole(roleId, List.of(categoryFolderId + ":READ"), principalId);

        assertThat(grantsOf(roleId))
                .extracting(grant -> grant.getFolder().getId())
                .containsExactly(categoryFolderId);
    }

    @Test
    @DisplayName("an empty selection takes every grant away rather than being read as 'unchanged'")
    void everyGrantCanBeRemoved() {
        underTest.updateFoldersOfRole(roleId, List.of(categoryFolderId + ":READ"), principalId);

        // A browser omits the checkbox group entirely when nothing is ticked, so this arrives null.
        underTest.updateFoldersOfRole(roleId, null, principalId);

        assertThat(grantsOf(roleId)).isEmpty();
    }

    @Test
    @DisplayName("a folder id that does not exist is refused rather than silently dropped")
    void anUnknownFolderIsRefused() {
        assertThatThrownBy(() -> underTest.updateFoldersOfRole(roleId, List.of("999999:READ"), principalId))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("the tree marks a granted folder, and marks what is beneath it as reached through it")
    void theTreeSeparatesGrantedFromInherited() {
        underTest.updateFoldersOfRole(roleId, List.of(categoryFolderId + ":READ", tagFolderId + ":WRITE"), principalId);

        List<FolderGrantDTO> tree = underTest.getFolderTreeForRole(roleId);

        FolderGrantDTO granted = row(tree, categoryFolderId);
        assertThat(granted.getPermission()).as("the folder that has a row").isEqualTo("READ");
        assertThat(granted.getInherited()).as("a grant does not cover itself").isEmpty();

        FolderGrantDTO between = row(tree, subCategoryFolderId);
        assertThat(between.getPermission()).as("rendered on the way to a grant, no row of its own").isEmpty();
        assertThat(between.getInherited()).as("but reached through its parent").isEqualTo("READ");

        FolderGrantDTO deeper = row(tree, tagFolderId);
        assertThat(deeper.getPermission()).isEqualTo("WRITE");
        assertThat(deeper.getInherited()).as("what the category above it allows too").isEqualTo("READ");
    }

    // ---------------------------------------------------------------- the verb (roadmap 9.1)

    @Test
    @DisplayName("a write grant is stored as a write grant, not silently weakened to a read")
    void aWriteGrantIsStoredAsSuch() {
        underTest.updateFoldersOfRole(roleId, List.of(categoryFolderId + ":WRITE"), principalId);

        assertThat(grantsOf(roleId)).singleElement().satisfies(grant -> {
            assertThat(grant.getFolder().getId()).isEqualTo(categoryFolderId);
            assertThat(grant.getPermission()).isEqualTo(FolderPermission.WRITE);
        });
    }

    @Test
    @DisplayName("changing the verb on a folder replaces the grant rather than adding a second row")
    void theVerbCanBeChanged() {
        underTest.updateFoldersOfRole(roleId, List.of(categoryFolderId + ":READ"), principalId);

        underTest.updateFoldersOfRole(roleId, List.of(categoryFolderId + ":WRITE"), principalId);

        assertThat(grantsOf(roleId)).singleElement()
                .extracting(RoleFolderGrant::getPermission)
                .isEqualTo(FolderPermission.WRITE);
    }

    @Test
    @DisplayName("the tree reports the strongest thing an ancestor already allows")
    void inheritedReportsTheAncestorVerb() {
        underTest.updateFoldersOfRole(roleId, List.of(categoryFolderId + ":WRITE", tagFolderId + ":READ"), principalId);

        assertThat(row(underTest.getFolderTreeForRole(roleId), subCategoryFolderId).getInherited())
                .isEqualTo("WRITE");
        assertThat(row(underTest.getFolderTreeForRole(roleId), tagFolderId).getInherited())
                .as("a weaker grant beneath a stronger one").isEqualTo("WRITE");
    }

    @Test
    @DisplayName("a folder set to 'no access' posts an empty value, which is not a malformed grant")
    void blankEntriesMeanNoAccess() {
        underTest.updateFoldersOfRole(roleId,
                List.of("", categoryFolderId + ":READ", ""), principalId);

        assertThat(grantsOf(roleId)).hasSize(1);
    }

    @Test
    @DisplayName("a grant the page could not have produced is refused rather than guessed at")
    void malformedGrantsAreRefused() {
        assertThatThrownBy(() -> underTest.updateFoldersOfRole(roleId,
                List.of(categoryFolderId + ":DELETE"), principalId))
                .isInstanceOf(InvalidDataException.class);

        assertThatThrownBy(() -> underTest.updateFoldersOfRole(roleId,
                List.of(String.valueOf(categoryFolderId)), principalId))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("the tree comes back with every ancestor before its descendants")
    void theTreeIsOrderedForRendering() {
        underTest.updateFoldersOfRole(roleId, List.of(tagFolderId + ":READ"), principalId);

        List<FolderGrantDTO> tree = underTest.getFolderTreeForRole(roleId);

        assertThat(tree).isNotEmpty();
        assertThat(tree.getFirst().getDepth()).as("the root comes first").isZero();
        assertThat(tree.indexOf(row(tree, categoryFolderId)))
                .as("a category is listed before its own sub-category")
                .isLessThan(tree.indexOf(row(tree, subCategoryFolderId)));
        assertThat(tree.indexOf(row(tree, subCategoryFolderId)))
                .as("and that before the granted folder beneath it")
                .isLessThan(tree.indexOf(row(tree, tagFolderId)));
    }

    /**
     * Roadmap 12.4: the page renders the top, the granted folders and the folders above them - not
     * the whole tree. What it leaves out holds no grant, so posting back what it rendered, unchanged,
     * changes nothing; the rest is opened on demand, in name order.
     */
    @Test
    @DisplayName("the tree renders the top, each grant and the way to it - and posting it back unchanged keeps every grant")
    void theTreeRendersTheGrantsAndTheWayToThem() {
        Folder category = folderRepository.findById(categoryFolderId).orElseThrow();
        User creator = userRepository.findById(principalId).orElseThrow();
        Folder beside = FolderFixture.tag(folderRepository, folderRepository.findById(subCategoryFolderId).orElseThrow(),
                creator, "Beside" + TestData.nextSequence());
        underTest.updateFoldersOfRole(roleId, List.of(tagFolderId + ":WRITE"), principalId);

        List<FolderGrantDTO> tree = underTest.getFolderTreeForRole(roleId);

        assertThat(tree).extracting(FolderGrantDTO::getId)
                .contains(categoryFolderId, subCategoryFolderId, tagFolderId)
                .doesNotContain(beside.getId());
        assertThat(row(tree, subCategoryFolderId).getChildCount()).as("so the page offers to open the rest").isEqualTo(2);
        assertThat(row(tree, categoryFolderId).getPath()).isEqualTo(category.getPath());

        // What the form posts: every rendered row's select, the none-selected ones blank.
        List<String> posted = tree.stream()
                .map(f -> f.getPermission().isEmpty() ? "" : f.getId() + ":" + f.getPermission())
                .toList();
        underTest.updateFoldersOfRole(roleId, posted, principalId);

        assertThat(grantsOf(roleId)).singleElement().satisfies(grant -> {
            assertThat(grant.getFolder().getId()).isEqualTo(tagFolderId);
            assertThat(grant.getPermission()).isEqualTo(FolderPermission.WRITE);
        });
    }

    private List<RoleFolderGrant> grantsOf(int roleId) {
        return roleRepository.findByIdWithFolders(roleId).orElseThrow().getFolderGrants();
    }

    private FolderGrantDTO row(List<FolderGrantDTO> tree, int folderId) {
        return tree.stream().filter(f -> f.getId() == folderId).findFirst().orElseThrow();
    }
}
