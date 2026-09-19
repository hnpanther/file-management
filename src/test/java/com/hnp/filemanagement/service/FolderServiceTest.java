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
 * The writer of the folder tree: what a create, a rename, a move and a delete accept, refuse and
 * leave behind.
 *
 * <p>Folder access is switched on, because half of the rules are about it: who may create under
 * a folder, rename it, move it, or remove it. The tree goes to any depth up to the configured
 * limit - four here, so the limit is reachable in a test - and a general tag is <em>not</em> a
 * folder: it is the {@code tag_group} a top-level folder carries, which is why creating one under
 * the root needs a group and creating anything deeper refuses one.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = {
        "filemanagement.folder-access.enabled=true",
        "filemanagement.folders.max-depth=4"})
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
        @DisplayName("a folder goes under any folder, one level deeper, until the depth limit; every one is a FOLDER")
        void aFolderGoesUnderAnyFolderUntilTheLimit() {
            String n = "N" + TestData.nextSequence();
            FolderDTO top = underTest.create(rootId, "Top" + n, "بالا", null, "group" + n, adminId);
            FolderDTO second = underTest.create(top.id(), "Second" + n, null, null, null, adminId);
            FolderDTO third = underTest.create(second.id(), "Third" + n, "سوم", null, null, adminId);
            FolderDTO fourth = underTest.create(third.id(), "Fourth" + n, null, null, null, adminId);

            assertThat(top.kind()).isEqualTo("FOLDER");
            assertThat(top.depth()).isEqualTo(1);
            assertThat(top.displayName()).isEqualTo("بالا");
            assertThat(second.parentId()).isEqualTo(top.id());
            assertThat(second.displayName()).as("no label given: the name is the label").isEqualTo("Second" + n);
            assertThat(fourth.depth()).isEqualTo(4);
            assertThat(underTest.canHoldFolders(folderRepository.findById(fourth.id()).orElseThrow())).isFalse();

            assertThatThrownBy(() -> underTest.create(fourth.id(), "Fifth", null, null, null, adminId))
                    .isInstanceOf(InvalidDataException.class)
                    .hasMessageContaining("max-depth");

            Folder stored = folderRepository.findById(fourth.id()).orElseThrow();
            assertThat(stored.getPath()).isEqualTo("/" + rootId + "/" + top.id() + "/" + second.id() + "/" + third.id() + "/" + fourth.id() + "/");
            assertThat(actionHistoryService.getActionHistoriesOfEntity(fourth.id(), EntityEnum.Folder)).hasSize(1);
            assertThat(underTest.maxDepth()).isEqualTo(4);
        }

        @Test
        @DisplayName("a top-level folder needs a tag group - an existing one by id, or a new one by name - and nothing deeper may carry one")
        void aTopLevelFolderNeedsATagGroupAndOnlyATopLevelFolder() {
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

            // A general tag is not a folder, and a folder below the top level does not carry one.
            assertThatThrownBy(() -> underTest.create(chain.categoryId(), "Sub" + TestData.nextSequence(), null, existing.getId(), null, adminId))
                    .isInstanceOf(InvalidDataException.class)
                    .hasMessageContaining("only a top-level folder");
            assertThatThrownBy(() -> underTest.create(chain.subCategoryId(), "Tag" + TestData.nextSequence(), null, null, "x", adminId))
                    .isInstanceOf(InvalidDataException.class)
                    .hasMessageContaining("only a top-level folder");
            assertThat(underTest.tagGroups()).extracting(TagGroupDTO::name).contains(newName, existing.getName());
        }

        @Test
        @DisplayName("a name is a safe path segment, unique among its siblings case-insensitively, and never the storage layout's own directory at the top level")
        void namesAreDirectorySafeAndUniqueAmongSiblings() {
            for (String bad : List.of("", "  ", "a/b", "a\\b", "..", "a:b", "trailing.", "x".repeat(101))) {
                assertThatThrownBy(() -> underTest.create(chain.subCategoryId(), bad, null, null, null, adminId))
                        .as("name %s", bad)
                        .isInstanceOf(InvalidDataException.class);
            }
            // Spaces, dots and Persian are names, not directories, since V2.9.
            assertThat(underTest.create(chain.subCategoryId(), "گزارش های ماهانه 2024.03", "گزارش‌های ماهانه", null, null, adminId).name())
                    .isEqualTo("گزارش های ماهانه 2024.03");
            assertThatThrownBy(() -> underTest.create(chain.subCategoryId(), "ok", "L".repeat(201), null, null, adminId))
                    .isInstanceOf(InvalidDataException.class)
                    .hasMessageContaining("label");

            assertThatThrownBy(() -> underTest.create(chain.subCategoryId(), chain.tag().getName().toUpperCase(), null, null, null, adminId))
                    .isInstanceOf(DuplicateResourceException.class);
            // The same name under another parent is fine.
            Folder otherSub = FolderFixture.subCategory(folderRepository, chain.category(), admin, "Else" + TestData.nextSequence());
            assertThat(underTest.create(otherSub.getId(), chain.tag().getName(), null, null, null, adminId).id()).isPositive();

            // "files" is where the id-based keys live; a top-level folder of that name would share it.
            assertThatThrownBy(() -> underTest.create(rootId, "Files", null, null, "g" + TestData.nextSequence(), adminId))
                    .isInstanceOf(InvalidDataException.class)
                    .hasMessageContaining("reserved");
            assertThat(underTest.create(chain.categoryId(), "files", null, null, null, adminId).id())
                    .as("below the top level the name is like any other")
                    .isPositive();
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

            FolderDTO relabelled = underTest.rename(chain.subCategoryId(), oldName, "برچسب جدید", null, adminId);
            assertThat(relabelled.name()).isEqualTo(oldName);
            assertThat(relabelled.displayName()).isEqualTo("برچسب جدید");
            assertThat(tagNamesOf(file.getId())).contains(oldName);

            String newName = oldName + "_v2";
            FolderDTO renamed = underTest.rename(chain.subCategoryId(), newName, null, null, adminId);
            assertThat(renamed.name()).isEqualTo(newName);
            assertThat(renamed.displayName()).as("no label given: the name is the label").isEqualTo(newName);
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
        @DisplayName("a top-level folder's tag group can be changed on a rename, re-grouping the tags beneath; deeper folders refuse one")
        void renamingATopLevelFolderMayChangeItsGroup() {
            FileInfo file = fileInfoRepository.save(TestData.fileInfo(admin, chain.tag(), "grouped" + TestData.nextSequence()));
            tagMirrorService.retag(file);
            fileInfoRepository.saveAndFlush(file);
            TagGroup other = tagGroupRepository.save(TestData.tagGroup(admin, "other" + TestData.nextSequence()));

            FolderDTO regrouped = underTest.rename(chain.categoryId(), chain.category().getName(), null, other.getId(), adminId);
            assertThat(regrouped.tagGroupId()).isEqualTo(other.getId());
            entityManager.flush();
            entityManager.clear();
            assertThat(fileInfoRepository.findById(file.getId()).orElseThrow().getTags())
                    .allSatisfy(tag -> assertThat(tag.getGroup().getId()).isEqualTo(other.getId()));
            assertThat(fileInfoRepository.findIdsWhoseTagsDisagreeWithTheFolders()).isEmpty();

            assertThatThrownBy(() -> underTest.rename(chain.subCategoryId(), chain.subCategory().getName(), null, other.getId(), adminId))
                    .isInstanceOf(InvalidDataException.class)
                    .hasMessageContaining("only a top-level folder");
        }

        @Test
        @DisplayName("the root and a home folder are not renamed; a missing folder is a 404; a taken sibling name is a 409")
        void renamingRefusesTheRootAMissingFolderAndATakenName() {
            assertThatThrownBy(() -> underTest.rename(rootId, "elsewhere", null, null, adminId))
                    .isInstanceOf(InvalidDataException.class).hasMessageContaining("ROOT");
            assertThatThrownBy(() -> underTest.rename(999_999, "gone", null, null, adminId))
                    .isInstanceOf(ResourceNotFoundException.class);

            Folder sibling = FolderFixture.tag(folderRepository, chain.subCategory(), admin, "Sib" + TestData.nextSequence());
            assertThatThrownBy(() -> underTest.rename(chain.tagId(), sibling.getName(), null, null, adminId))
                    .isInstanceOf(DuplicateResourceException.class);
            assertThat(underTest.rename(chain.tagId(), chain.tag().getName(), "only the label", null, adminId).displayName())
                    .as("keeping one's own name is not a collision with oneself")
                    .isEqualTo("only the label");
        }

        @Test
        @DisplayName("renaming needs write access on the folder itself")
        void renamingNeedsWriteAccess() {
            grant(restrictedId, chain.tagId(), FolderPermission.READ);
            assertThatThrownBy(() -> underTest.rename(chain.tagId(), "Renamed" + TestData.nextSequence(), null, null, restrictedId))
                    .isInstanceOf(AccessDeniedException.class);

            grant(restrictedId, chain.subCategoryId(), FolderPermission.WRITE);
            assertThat(underTest.rename(chain.tagId(), "Renamed" + TestData.nextSequence(), null, null, restrictedId).id())
                    .isEqualTo(chain.tagId());
        }
    }

    // ================================================================ move

    @Nested
    @DisplayName("moving")
    class Moving {

        @Test
        @DisplayName("a move rewrites parent, depth and path for the whole subtree, re-derives the files' tags, and moves no key")
        void aMoveRewritesTheSubtree() {
            FileInfo file = fileInfoRepository.save(TestData.fileInfo(admin, chain.tag(), "moved" + TestData.nextSequence()));
            TestData.fileDetails(admin, file, 1, "txt");
            tagMirrorService.retag(file);
            fileInfoRepository.saveAndFlush(file);
            String keyBefore = file.getFileDetailsList().getFirst().getStorageKey();
            Folder otherTop = FolderFixture.category(folderRepository, admin, "OtherTop" + TestData.nextSequence(),
                    tagGroupRepository.save(TestData.tagGroup(admin, "og" + TestData.nextSequence())));

            // The sub-category, with the tag folder and the file under it, goes under another top-level folder.
            FolderDTO moved = underTest.move(chain.subCategoryId(), otherTop.getId(), adminId);
            entityManager.flush();
            entityManager.clear();

            assertThat(moved.parentId()).isEqualTo(otherTop.getId());
            Folder sub = folderRepository.findById(chain.subCategoryId()).orElseThrow();
            Folder tag = folderRepository.findById(chain.tagId()).orElseThrow();
            assertThat(sub.getPath()).isEqualTo(otherTop.getPath() + sub.getId() + "/");
            assertThat(tag.getPath()).isEqualTo(sub.getPath() + tag.getId() + "/");
            assertThat(tag.getDepth()).isEqualTo(3);
            assertThat(folderRepository.findRowsWhoseDerivedColumnsDisagree()).isEmpty();

            assertThat(tagNamesOf(file.getId()))
                    .contains(otherTop.getName(), sub.getName(), tag.getName())
                    .doesNotContain(chain.category().getName());
            assertThat(fileInfoRepository.findById(file.getId()).orElseThrow().getTags())
                    .allSatisfy(t -> assertThat(t.getGroup().getId()).isEqualTo(otherTop.getTagGroup().getId()));
            assertThat(fileInfoRepository.findIdsWhoseTagsDisagreeWithTheFolders()).isEmpty();
            assertThat(fileInfoRepository.findByIdAndFetchFileDetails(file.getId()).orElseThrow()
                    .getFileDetailsList().getFirst().getStorageKey()).isEqualTo(keyBefore);
            assertThat(actionHistoryService.getActionHistoriesOfEntity(chain.subCategoryId(), EntityEnum.Folder)).hasSize(1);
        }

        @Test
        @DisplayName("moved to the top level a folder keeps the group it came from; moved below one, a top-level folder loses its own")
        void theTagGroupFollowsTheTopLevel() {
            TagGroup group = chain.category().getTagGroup();

            FolderDTO promoted = underTest.move(chain.subCategoryId(), rootId, adminId);
            assertThat(promoted.depth()).isEqualTo(1);
            assertThat(promoted.tagGroupId()).as("the group of the top-level folder it was under").isEqualTo(group.getId());

            FolderDTO demoted = underTest.move(chain.categoryId(), chain.subCategoryId(), adminId);
            assertThat(demoted.depth()).isEqualTo(2);
            assertThat(demoted.tagGroupId()).as("only the top level carries a group").isNull();
            entityManager.flush();
            entityManager.clear();
            assertThat(folderRepository.findById(chain.tagId()).orElseThrow().getDepth()).isEqualTo(2);
            assertThat(folderRepository.findRowsWhoseDerivedColumnsDisagree()).isEmpty();
        }

        @Test
        @DisplayName("refused: into itself or below itself, past the depth limit, onto a taken name, the root, a missing folder")
        void refusedMoves() {
            assertThatThrownBy(() -> underTest.move(chain.categoryId(), chain.tagId(), adminId))
                    .isInstanceOf(InvalidDataException.class).hasMessageContaining("into itself");
            assertThatThrownBy(() -> underTest.move(chain.categoryId(), chain.categoryId(), adminId))
                    .isInstanceOf(InvalidDataException.class).hasMessageContaining("into itself");

            // The chain is three deep; under the tag folder its sub-category would reach depth 5 > 4.
            Folder deeper = FolderFixture.tag(folderRepository, chain.tag(), admin, "Deeper" + TestData.nextSequence());
            Folder otherTop = FolderFixture.category(folderRepository, admin, "Top" + TestData.nextSequence(),
                    tagGroupRepository.save(TestData.tagGroup(admin, "tg" + TestData.nextSequence())));
            Folder otherSub = FolderFixture.subCategory(folderRepository, otherTop, admin, "S" + TestData.nextSequence());
            Folder otherTag = FolderFixture.tag(folderRepository, otherSub, admin, "T" + TestData.nextSequence());
            assertThatThrownBy(() -> underTest.move(chain.subCategoryId(), otherTag.getId(), adminId))
                    .isInstanceOf(InvalidDataException.class).hasMessageContaining("depth");
            assertThat(underTest.move(deeper.getId(), otherTag.getId(), adminId).depth())
                    .as("a leaf may go to depth 4").isEqualTo(4);

            FolderFixture.subCategory(folderRepository, otherTop, admin, chain.subCategory().getName());
            assertThatThrownBy(() -> underTest.move(chain.subCategoryId(), otherTop.getId(), adminId))
                    .isInstanceOf(DuplicateResourceException.class);

            assertThatThrownBy(() -> underTest.move(rootId, chain.categoryId(), adminId))
                    .isInstanceOf(InvalidDataException.class).hasMessageContaining("ROOT");
            assertThatThrownBy(() -> underTest.move(999_999, rootId, adminId))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> underTest.move(chain.tagId(), 999_999, adminId))
                    .isInstanceOf(InvalidDataException.class);
        }

        @Test
        @DisplayName("moving needs write access on both parents")
        void movingNeedsWriteAccessOnBothParents() {
            Folder otherTop = FolderFixture.category(folderRepository, admin, "Top" + TestData.nextSequence(),
                    tagGroupRepository.save(TestData.tagGroup(admin, "tg" + TestData.nextSequence())));

            grant(restrictedId, chain.categoryId(), FolderPermission.WRITE);
            assertThatThrownBy(() -> underTest.move(chain.subCategoryId(), otherTop.getId(), restrictedId))
                    .as("write on the source only")
                    .isInstanceOf(AccessDeniedException.class);

            grant(restrictedId, otherTop.getId(), FolderPermission.WRITE);
            assertThat(underTest.move(chain.subCategoryId(), otherTop.getId(), restrictedId).parentId())
                    .isEqualTo(otherTop.getId());
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

    // ================================================================ ancestry

    @Test
    @DisplayName("the ancestry of a folder is read off its path, top-level first, the root left out")
    void ancestryIsReadOffThePath() {
        List<Folder> ancestry = underTest.ancestryOf(underTest.requireWithTagGroup(chain.tagId()));

        assertThat(ancestry).extracting(Folder::getId)
                .containsExactly(chain.categoryId(), chain.subCategoryId(), chain.tagId());
        assertThat(FolderService.topOf(ancestry).getTagGroup().getId()).isEqualTo(chain.category().getTagGroup().getId());
        assertThat(underTest.ancestryOf(underTest.root())).isEmpty();
        assertThatThrownBy(() -> underTest.requireWithTagGroup(999_999))
                .isInstanceOf(InvalidDataException.class);
        assertThat(underTest.root().getKind()).isEqualTo(FolderKind.ROOT);
        assertThat(FolderService.canHoldFiles(underTest.root())).isFalse();
        assertThat(FolderService.canHoldFiles(chain.category())).isTrue();
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
