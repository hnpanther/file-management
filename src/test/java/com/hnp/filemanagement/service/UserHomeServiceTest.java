package com.hnp.filemanagement.service;

import com.hnp.filemanagement.entity.EntityEnum;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderKind;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.exception.DuplicateResourceException;
import com.hnp.filemanagement.exception.InvalidDataException;
import com.hnp.filemanagement.exception.ResourceNotFoundException;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A user's personal folder (roadmap 10.4): created once under {@code Profiles}, named after the
 * user, writable by them alone, carrying the installation's default quota; and what the tree
 * refuses to do to it and to {@code Profiles}.
 *
 * <p>Folder access is on, because the grant is the point: with enforcement, a user with no role
 * grant at all must still reach their own folder. The default quota is ten megabytes so that
 * "the default" is visible.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = {
        "filemanagement.folder-access.enabled=true",
        "filemanagement.profiles.default-quota-mb=10"})
class UserHomeServiceTest extends DatabaseSupport {

    private static final long MEGABYTE = 1024L * 1024L;

    @Autowired
    private UserHomeService underTest;
    @Autowired
    private FolderService folderService;
    @Autowired
    private FolderTreeDeleteService folderTreeDeleteService;
    @Autowired
    private FolderAccessService folderAccessService;
    @Autowired
    private ActionHistoryService actionHistoryService;
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

    private User admin;
    private int adminId;
    private User person;
    private int personId;

    @BeforeEach
    void setUp() {
        Role adminRole = roleRepository.save(TestData.role("ADMIN"));
        admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();
        person = TestData.user();
        person.setFirstName("Sara");
        person.setLastName("Ahmadi");
        personId = userRepository.save(person).getId();
    }

    @Test
    @DisplayName("V2.11 left one Profiles folder at the top level, in a group, that takes neither folders nor files by hand")
    void theProfilesFolderExists() {
        Folder profiles = underTest.profiles();

        assertThat(profiles.getKind()).isEqualTo(FolderKind.PROFILES);
        assertThat(profiles.getDepth()).isEqualTo(1);
        assertThat(profiles.getParent().getKind()).isEqualTo(FolderKind.ROOT);
        assertThat(profiles.getPath()).isEqualTo(profiles.getParent().getPath() + profiles.getId() + "/");
        assertThat(profiles.getTagGroup()).isNotNull();
        assertThat(folderService.canHoldFolders(profiles)).isFalse();
        assertThat(FolderService.canHoldFiles(profiles)).isFalse();

        assertThatThrownBy(() -> folderService.create(profiles.getId(), "byhand", null, null, null, adminId))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> folderService.rename(profiles.getId(), "People", null, null, adminId))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("PROFILES");
        assertThatThrownBy(() -> folderService.delete(profiles.getId(), adminId))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("PROFILES");
        assertThatThrownBy(() -> folderTreeDeleteService.deleteTree(profiles.getId(), adminId))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("PROFILES");
    }

    @Test
    @DisplayName("a home is created once: under Profiles, named after the user, labelled with their name, with the default quota and a WRITE grant for them")
    void createsAHomeOnce() {
        assertThat(underTest.homeOf(personId)).isEmpty();

        Folder home = underTest.ensureHome(personId, adminId);
        entityManager.flush();
        entityManager.clear();

        Folder stored = folderRepository.findById(home.getId()).orElseThrow();
        assertThat(stored.getKind()).isEqualTo(FolderKind.USER_HOME);
        assertThat(stored.getOwnerUser().getId()).isEqualTo(personId);
        assertThat(stored.getName()).isEqualTo(person.getUsername());
        assertThat(stored.getDisplayName()).isEqualTo("Sara Ahmadi");
        assertThat(stored.getParent().getKind()).isEqualTo(FolderKind.PROFILES);
        assertThat(stored.getDepth()).isEqualTo(2);
        assertThat(stored.getPath()).isEqualTo(underTest.profiles().getPath() + stored.getId() + "/");
        assertThat(stored.getQuotaBytes()).isEqualTo(10 * MEGABYTE);
        assertThat(stored.getTagGroup()).as("only a top-level folder carries a group").isNull();

        // The grant: the user, with no role at all, writes into their home and nowhere else.
        assertThat(folderAccessService.accessFor(personId).canWrite(stored.getPath())).isTrue();
        assertThat(folderAccessService.accessFor(personId).canWrite(underTest.profiles().getPath())).isFalse();
        assertThat(actionHistoryService.getActionHistoriesOfEntity(stored.getId(), EntityEnum.Folder)).hasSize(1);

        // Idempotent: the same folder, no second row, no second grant.
        assertThat(underTest.ensureHome(personId, adminId).getId()).isEqualTo(stored.getId());
        assertThat(underTest.homeOf(personId).orElseThrow().getId()).isEqualTo(stored.getId());
        assertThat(userRepository.findById(personId).orElseThrow().getFolderGrants()).hasSize(1);
    }

    @Test
    @DisplayName("no home for a missing user, a username that cannot be a directory, or a name already taken under Profiles")
    void refusals() {
        assertThatThrownBy(() -> underTest.ensureHome(999_999, adminId)).isInstanceOf(ResourceNotFoundException.class);

        User odd = TestData.user();
        odd.setUsername("bad/name" + TestData.nextSequence());
        int oddId = userRepository.save(odd).getId();
        assertThatThrownBy(() -> underTest.ensureHome(oddId, adminId))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("cannot name a folder");

        // A folder of the user's name already under Profiles that is nobody's home (an adopted
        // Profiles may hold such things): refused rather than adopted.
        Folder squatter = TestData.folder(admin, underTest.profiles(), person.getUsername(), null);
        folderRepository.saveAndFlush(TestData.placed(folderRepository.save(squatter)));
        assertThatThrownBy(() -> underTest.ensureHome(personId, adminId))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    @DisplayName("a home is renamed with its user and never by hand; it is moved by nobody and deleted by nobody")
    void aHomeIsASystemFolder() {
        Folder home = underTest.ensureHome(personId, adminId);

        assertThatThrownBy(() -> folderService.rename(home.getId(), "other", null, null, adminId))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("USER_HOME");
        assertThatThrownBy(() -> folderService.move(home.getId(), FolderFixture.root(folderRepository).getId(), adminId))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("USER_HOME");
        assertThatThrownBy(() -> folderService.delete(home.getId(), adminId))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("USER_HOME");
        assertThatThrownBy(() -> folderTreeDeleteService.deleteTree(home.getId(), adminId))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("USER_HOME");

        // Nothing may be moved under Profiles either - it holds homes and nothing else.
        FolderFixture.Chain chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        assertThatThrownBy(() -> folderService.move(chain.tagId(), underTest.profiles().getId(), adminId))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("takes no folders");

        underTest.renameHomeOf(personId, "renamed" + TestData.nextSequence(), adminId);
        entityManager.flush();
        entityManager.clear();
        assertThat(folderRepository.findById(home.getId()).orElseThrow().getName()).startsWith("renamed");
        // A username the home cannot follow - a folder of that name already under Profiles - is a
        // 409 said up front, not the sibling index's 500 at flush; renaming to its own name is fine.
        Folder taken = TestData.folder(admin, underTest.profiles(), "taken" + TestData.nextSequence(), null);
        folderRepository.saveAndFlush(TestData.placed(folderRepository.save(taken)));
        assertThatThrownBy(() -> underTest.renameHomeOf(personId, taken.getName().toUpperCase(), adminId))
                .isInstanceOf(DuplicateResourceException.class).hasMessageContaining("under Profiles");
        underTest.renameHomeOf(personId, folderRepository.findById(home.getId()).orElseThrow().getName(), adminId);
        // Inside it, the user does as in any folder: a sub-folder, down to the limit.
        assertThat(folderService.create(home.getId(), "notes", null, null, null, personId).parentId()).isEqualTo(home.getId());
    }

    @Test
    @DisplayName("the quota is set, changed, cleared, and refused when it is nothing or on the root")
    void theQuota() {
        Folder home = underTest.ensureHome(personId, adminId);

        assertThat(underTest.setQuota(home.getId(), 50 * MEGABYTE, adminId).getQuotaBytes()).isEqualTo(50 * MEGABYTE);
        assertThat(underTest.setQuota(home.getId(), 5 * MEGABYTE, adminId).getQuotaBytes())
                .as("lowered, whatever is stored").isEqualTo(5 * MEGABYTE);
        assertThat(underTest.setQuota(home.getId(), null, adminId).getQuotaBytes()).as("cleared").isNull();
        assertThat(actionHistoryService.getActionHistoriesOfEntity(home.getId(), EntityEnum.Folder))
                .as("one creation, three changes").hasSize(4);

        assertThatThrownBy(() -> underTest.setQuota(home.getId(), 0L, adminId)).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> underTest.setQuota(home.getId(), -1L, adminId)).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> underTest.setQuota(FolderFixture.root(folderRepository).getId(), MEGABYTE, adminId))
                .isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> underTest.setQuota(999_999, MEGABYTE, adminId)).isInstanceOf(ResourceNotFoundException.class);
    }
}
