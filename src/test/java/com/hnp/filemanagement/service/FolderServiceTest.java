package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FolderDTO;
import com.hnp.filemanagement.dto.TagGroupDTO;
import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderKind;
import com.hnp.filemanagement.entity.FolderPermission;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.Tag;
import com.hnp.filemanagement.entity.TagGroup;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.entity.UserFolderGrant;
import com.hnp.filemanagement.exception.DependencyResourceException;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The writer of the folder tree (Phase 7 step 4): what a create, a rename and a delete accept,
 * refuse and leave behind.
 *
 * <p>Folder access is switched on, because half of the rules are about it: who may create under
 * a folder, rename it, or remove it. The tree is exactly three levels deep - category,
 * sub-category, tag - and a general tag is <em>not</em> a folder: it is the {@code tag_group} a
 * category carries, which is why creating a category needs one and creating anything else
 * refuses one.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FolderServiceTest extends MySqlSupport {

    @Autowired
    private FolderService underTest;
    @Autowired
    private ActionHistoryService actionHistoryService;
    @Autowired
    private TagMirrorService tagMirrorService;

    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;

    private User admin;
    private int adminId;
    private int restrictedId;
    private FolderFixture.Chain chain;
    private int rootId;

    @BeforeEach
    void setUp() {
        Role adminRole = roleRepository.save(TestData.role("ADMIN"));
        admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();
        restrictedId = userRepository.save(TestData.user()).getId();

        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        rootId = FolderFixture.root(folderRepository).getId();
    }

    // ================================================================ create

    @Nested
    @DisplayName("creating")
    class Creating {

        @Test
        @DisplayName("the kind follows from the parent: category under the root, sub-category under a category, tag under a sub-category")
        void theKindFollowsFromTheParent() {
            String n = "N" + TestData.nextSequence();
            FolderDTO category = underTest.create(rootId, "Cat" + n, "دسته", null, "group" + n, adminId);
            FolderDTO subCategory = underTest.create(category.id(), "Sub" + n, null, null, null, adminId);
            FolderDTO tag = underTest.create(subCategory.id(), "Tag" + n, "برچسب", null, null, adminId);

            assertThat(category.kind()).isEqualTo("CATEGORY");
            assertThat(category.depth()).isEqualTo(1);
            assertThat(category.displayName()).isEqualTo("دسته");
            assertThat(subCategory.kind()).isEqualTo("SUB_CATEGORY");
            assertThat(subCategory.parentId()).isEqualTo(category.id());
            assertThat(subCategory.displayName()).as("no label given: the name is the label").isEqualTo("Sub" + n);
            assertThat(tag.kind()).isEqualTo("TAG");
            assertThat(tag.depth()).isEqualTo(3);

            Folder stored = folderRepository.findById(tag.id()).orElseThrow();
            assertThat(stored.getPath()).isEqualTo("/" + rootId + "/" + category.id() + "/" + subCategory.id() + "/" + tag.id() + "/");
            assertThat(actionHistoryService.getActionHistoriesOfEntity(tag.id(), EntityEnum.Folder)).hasSize(1);
        }

        @Test
        @DisplayName("a tag folder holds files, not folders - the tree is three levels deep and no deeper")
        void aTagFolderTakesNoChildren() {
            assertThatThrownBy(() -> underTest.create(chain.tagId(), "Deeper", null, null, null, adminId))
                    .isInstanceOf(InvalidDataException.class)
                    .hasMessageContaining("holds files");
        }

        @Test
        @DisplayName("a category needs a tag group - an existing one by id, or a new one by name - and nothing else may carry one")
        void aCategoryNeedsATagGroupAndOnlyACategory() {
            assertThatThrownBy(() -> underTest.create(rootId, "NoGroup" + TestData.nextSequence(), null, null, null, adminId))
                    .isInstanceOf(InvalidDataException.class)
                    .hasMessageContaining("tag group");
            assertThatThrownBy(() -> underTest.create(rootId, "BadGroup" + TestData.nextSequence(), null, 999_999, null, adminId))
                    .isInstanceOf(ResourceNotFoundException.class);

            TagGroup existing = chain.category().getTagGroup();
            FolderDTO byId = underTest.create(rootId, "ById" + TestData.nextSequence(), null, existing.getId(), null, adminId);
            assertThat(byId.tagGroupId()).isEqualTo(existing.getId());

            String newName = "fresh" + TestData.nextSequence();
            FolderDTO byName = underTest.create(rootId, "ByName" + TestData.nextSequence(), null, null, newName, adminId);
            assertThat(tagGroupRepository.findByName(newName)).isPresent()
                    .get().satisfies(group -> assertThat(group.getId()).isEqualTo(byName.tagGroupId()));
            FolderDTO sameName = underTest.create(rootId, "SameName" + TestData.nextSequence(), null, null, newName, adminId);
            assertThat(sameName.tagGroupId()).as("naming an existing group joins it").isEqualTo(byName.tagGroupId());

            // A general tag is not a folder, and a folder below a category does not carry one.
            assertThatThrownBy(() -> underTest.create(chain.categoryId(), "Sub" + TestData.nextSequence(), null, existing.getId(), null, adminId))
                    .isInstanceOf(InvalidDataException.class)
                    .hasMessageContaining("only a category");
            assertThatThrownBy(() -> underTest.create(chain.subCategoryId(), "Tag" + TestData.nextSequence(), null, null, "x", adminId))
                    .isInstanceOf(InvalidDataException.class)
                    .hasMessageContaining("only a category");
            assertThat(underTest.tagGroups()).extracting(TagGroupDTO::name).contains(newName, existing.getName());
        }

        @Test
        @DisplayName("a name is directory-safe and unique among its siblings, case-insensitively")
        void namesAreDirectorySafeAndUniqueAmongSiblings() {
            for (String bad : List.of("", "  ", "with space", "dot.name", "a/b", "x".repeat(101))) {
                assertThatThrownBy(() -> underTest.create(chain.subCategoryId(), bad, null, null, null, adminId))
                        .as("name %s", bad)
                        .isInstanceOf(InvalidDataException.class);
            }
            assertThatThrownBy(() -> underTest.create(chain.subCategoryId(), "ok", "L".repeat(201), null, null, adminId))
                    .isInstanceOf(InvalidDataException.class)
                    .hasMessageContaining("label");

            assertThatThrownBy(() -> underTest.create(chain.subCategoryId(), chain.tag().getName().toUpperCase(), null, null, null, adminId))
                    .isInstanceOf(DuplicateResourceException.class);
            // The same name under another parent is fine; siblings are what the disk keeps apart.
            Folder otherSub = FolderFixture.subCategory(folderRepository, chain.category(), admin, "Else" + TestData.nextSequence());
            assertThat(underTest.create(otherSub.getId(), chain.tag().getName(), null, null, null, adminId).id()).isPositive();
        }

        @Test
        @DisplayName("creating needs write access on the parent; an ancestor's grant reaches down, a read grant does not")
        void creatingNeedsWriteAccessOnTheParent() {
            assertThatThrownBy(() -> underTest.create(chain.subCategoryId(), "T" + TestData.nextSequence(), null, null, null, restrictedId))
                    .isInstanceOf(AccessDeniedException.class);

            grant(restrictedId, chain.categoryId(), FolderPermission.READ);
            assertThatThrownBy(() -> underTest.create(chain.subCategoryId(), "T" + TestData.nextSequence(), null, null, null, restrictedId))
                    .isInstanceOf(AccessDeniedException.class);

            grant(restrictedId, chain.categoryId(), FolderPermission.WRITE);
            assertThat(underTest.create(chain.subCategoryId(), "T" + TestData.nextSequence(), null, null, null, restrictedId).id())
                    .isPositive();
            assertThatThrownBy(() -> underTest.create(rootId, "C" + TestData.nextSequence(), null, null, "g", restrictedId))
                    .as("the grant does not reach upwards to the root")
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    // ================================================================ rename

    @Nested
    @DisplayName("renaming")
    class Renaming {

        @Test
        @DisplayName("a changed name re-derives the tags of every file beneath; a changed label alone does not touch them; keys never move")
        void renamingRetagsTheFilesBeneath() {
            FileInfo file = fileInfoRepository.save(TestData.fileInfo(admin, chain.tag(), "report" + TestData.nextSequence()));
            TestData.fileDetails(admin, file, 1, "txt");
            // Written through the repository, so tagged here as an upload would tag it.
            tagMirrorService.retag(file);
            fileInfoRepository.saveAndFlush(file);
            String keyBefore = file.getFileDetailsList().getFirst().getStorageKey();
            String oldName = chain.subCategory().getName();
            assertThat(tagNamesOf(file.getId())).contains(oldName);

            FolderDTO relabelled = underTest.rename(chain.subCategoryId(), oldName, "برچسب جدید", adminId);
            assertThat(relabelled.name()).isEqualTo(oldName);
            assertThat(relabelled.displayName()).isEqualTo("برچسب جدید");
            assertThat(tagNamesOf(file.getId())).contains(oldName);

            String newName = oldName + "_v2";
            FolderDTO renamed = underTest.rename(chain.subCategoryId(), newName, null, adminId);
            assertThat(renamed.name()).isEqualTo(newName);
            assertThat(renamed.displayName()).as("the label is kept when none is given... as the name").isEqualTo(newName);
            entityManager.flush();
            entityManager.clear();

            assertThat(tagNamesOf(file.getId()))
                    .contains(newName, chain.category().getName(), chain.tag().getName())
                    .doesNotContain(oldName);
            assertThat(fileInfoRepository.findByIdAndFetchFileDetails(file.getId()).orElseThrow()
                    .getFileDetailsList().getFirst().getStorageKey())
                    .as("the bytes stay where they are; the key records where they went")
                    .isEqualTo(keyBefore);
            assertThat(fileInfoRepository.findIdsWhoseTagsDisagreeWithTheFolders()).isEmpty();
        }

        @Test
        @DisplayName("the root and a home folder are not renamed; a missing folder is a 404; a taken sibling name is a 409")
        void renamingRefusesTheRootAMissingFolderAndATakenName() {
            assertThatThrownBy(() -> underTest.rename(rootId, "elsewhere", null, adminId))
                    .isInstanceOf(InvalidDataException.class).hasMessageContaining("ROOT");
            assertThatThrownBy(() -> underTest.rename(999_999, "gone", null, adminId))
                    .isInstanceOf(ResourceNotFoundException.class);

            Folder sibling = FolderFixture.tag(folderRepository, chain.subCategory(), admin, "Sib" + TestData.nextSequence());
            assertThatThrownBy(() -> underTest.rename(chain.tagId(), sibling.getName(), null, adminId))
                    .isInstanceOf(DuplicateResourceException.class);
            assertThat(underTest.rename(chain.tagId(), chain.tag().getName(), "only the label", adminId).displayName())
                    .as("keeping one's own name is not a collision with oneself")
                    .isEqualTo("only the label");
        }

        @Test
        @DisplayName("renaming needs write access on the folder itself")
        void renamingNeedsWriteAccess() {
            grant(restrictedId, chain.tagId(), FolderPermission.READ);
            assertThatThrownBy(() -> underTest.rename(chain.tagId(), "Renamed" + TestData.nextSequence(), null, restrictedId))
                    .isInstanceOf(AccessDeniedException.class);

            grant(restrictedId, chain.subCategoryId(), FolderPermission.WRITE);
            assertThat(underTest.rename(chain.tagId(), "Renamed" + TestData.nextSequence(), null, restrictedId).id())
                    .isEqualTo(chain.tagId());
        }
    }

    // ================================================================ delete

    @Nested
    @DisplayName("deleting")
    class Deleting {

        @Test
        @DisplayName("only an empty folder goes: one holding a folder or a file is a 409, and nothing is removed")
        void onlyAnEmptyFolderIsDeleted() {
            assertThatThrownBy(() -> underTest.delete(chain.subCategoryId(), adminId))
                    .isInstanceOf(DependencyResourceException.class).hasMessageContaining("folder(s)");

            fileInfoRepository.saveAndFlush(TestData.fileInfo(admin, chain.tag(), "keep" + TestData.nextSequence()));
            assertThatThrownBy(() -> underTest.delete(chain.tagId(), adminId))
                    .isInstanceOf(DependencyResourceException.class).hasMessageContaining("file(s)");
            assertThat(folderRepository.findById(chain.tagId())).isPresent();

            Folder empty = FolderFixture.tag(folderRepository, chain.subCategory(), admin, "Empty" + TestData.nextSequence());
            underTest.delete(empty.getId(), adminId);
            entityManager.flush();
            assertThat(folderRepository.findById(empty.getId())).isEmpty();
            assertThat(actionHistoryService.getActionHistoriesOfEntity(empty.getId(), EntityEnum.Folder)).hasSize(1);
        }

        @Test
        @DisplayName("deleting takes its grants with it, needs write access on the parent, and never touches the root")
        void deletingTakesGrantsNeedsTheParentAndSparesTheRoot() {
            Folder empty = FolderFixture.tag(folderRepository, chain.subCategory(), admin, "Empty" + TestData.nextSequence());
            grant(restrictedId, empty.getId(), FolderPermission.WRITE);
            assertThatThrownBy(() -> underTest.delete(empty.getId(), restrictedId))
                    .as("write on the folder itself is not write on its parent")
                    .isInstanceOf(AccessDeniedException.class);

            grant(restrictedId, chain.subCategoryId(), FolderPermission.WRITE);
            underTest.delete(empty.getId(), restrictedId);
            entityManager.flush();
            entityManager.clear();
            assertThat(folderRepository.findById(empty.getId())).isEmpty();
            assertThat(userRepository.findById(restrictedId).orElseThrow().getFolderGrants())
                    .extracting(g -> g.getFolder().getId())
                    .doesNotContain(empty.getId());

            assertThatThrownBy(() -> underTest.delete(rootId, adminId))
                    .isInstanceOf(InvalidDataException.class).hasMessageContaining("ROOT");
            assertThatThrownBy(() -> underTest.delete(999_999, adminId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ================================================================ the chain

    @Test
    @DisplayName("chainOf reads the three levels off a tag folder and refuses anything else")
    void chainOfReadsTheThreeLevels() {
        Folder tag = underTest.requireWithChain(chain.tagId());
        FolderService.Chain read = FolderService.chainOf(tag);

        assertThat(read.category().getId()).isEqualTo(chain.categoryId());
        assertThat(read.subCategory().getId()).isEqualTo(chain.subCategoryId());
        assertThat(read.tag().getId()).isEqualTo(chain.tagId());
        assertThat(read.directory()).isEqualTo(chain.directory());

        assertThatThrownBy(() -> FolderService.chainOf(underTest.requireWithChain(chain.subCategoryId())))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> underTest.requireWithChain(999_999))
                .isInstanceOf(InvalidDataException.class);
        assertThat(underTest.root().getKind()).isEqualTo(FolderKind.ROOT);
    }

    // ---------------------------------------------------------------- helpers

    private List<String> tagNamesOf(int fileInfoId) {
        entityManager.flush();
        entityManager.clear();
        return fileInfoRepository.findById(fileInfoId).orElseThrow().getTags().stream().map(Tag::getName).toList();
    }

    private void grant(int userId, int folderId, FolderPermission permission) {
        User user = userRepository.findById(userId).orElseThrow();
        List<UserFolderGrant> grants = new java.util.ArrayList<>(user.getFolderGrants());
        grants.add(new UserFolderGrant(user, folderRepository.findById(folderId).orElseThrow(), permission));
        user.replaceFolderGrants(grants);
        userRepository.save(user);
        // Flushed and cleared, as a request boundary would: the service under test then reads
        // grants from the database, not from a user this test still holds in the context.
        entityManager.flush();
        entityManager.clear();
    }
}
