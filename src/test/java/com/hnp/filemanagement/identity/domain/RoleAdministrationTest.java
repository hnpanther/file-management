package com.hnp.filemanagement.identity.domain;

import com.hnp.filemanagement.file.domain.UploadPolicyService;
import com.hnp.filemanagement.identity.bootstrap.DataInitializer;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.folder.domain.RoleFolderGrant;
import com.hnp.filemanagement.file.domain.UploadPolicy;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.persistence.PermissionRepository;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.file.persistence.UploadPolicyRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 1.9.0's role administration, through the services: the two fixed roles, copying a role, the
 * grouped view the role page renders, the start that brings the fixed roles back to their
 * definition without taking access from anyone, and a username only an administrator changes.
 */
@ServiceIntegrationTest
class RoleAdministrationTest extends DatabaseSupport {

    private static final long MB = 1024L * 1024L;

    @Autowired
    private RoleService roleService;
    @Autowired
    private UploadPolicyService uploadPolicyService;
    @Autowired
    private UserService userService;
    @Autowired
    private DataInitializer dataInitializer;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private PermissionRepository permissionRepository;
    @Autowired
    private UploadPolicyRepository uploadPolicyRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private EntityManager entityManager;

    private int principalId;
    private Role admin;
    private Role user;
    private int folderId;

    @BeforeEach
    void setUp() {
        dataInitializer.initialize();
        admin = roleRepository.findByRoleNameIgnoreCase("ADMIN").orElseThrow();
        user = roleRepository.findByRoleNameIgnoreCase("USER").orElseThrow();

        User principal = TestData.user();
        principal.getRoles().add(admin);
        principalId = userRepository.save(principal).getId();
        folderId = FolderFixture.chain(folderRepository, tagGroupRepository, principal).tagId();
        flushAndClear();
    }

    // ================================================================ the fixed roles

    @Nested
    @DisplayName("the fixed roles")
    class Fixed {

        @Test
        @DisplayName("after the start USER holds exactly its definition, ADMIN every assignable permission, and neither a grant or an own policy")
        void theStartDefinesThem() {
            assertThat(permissionsOf(user.getId())).isEqualTo(FixedRole.USER_PERMISSIONS);
            assertThat(permissionsOf(admin.getId())).isEqualTo(FixedRole.everything());
            for (Role role : List.of(admin, user)) {
                assertThat(roleRepository.findByIdWithFolders(role.getId()).orElseThrow().getFolderGrants()).isEmpty();
                assertThat(uploadPolicyRepository.findByRoleId(role.getId())).isEmpty();
                assertThat(roleService.isFixed(role.getId())).isTrue();
            }
        }

        @Test
        @DisplayName("their permissions, folder grants and upload policy are refused, with the reason for the page")
        void theyCannotBeEdited() {
            List<Integer> anyPermission = List.of(permissionRepository.findByPermissionName(PermissionEnum.GET_ALL_USER_PAGE).orElseThrow().getId());
            for (Role role : List.of(admin, user)) {
                assertThatThrownBy(() -> roleService.updatePermissionsOfRole(role.getId(), anyPermission, principalId))
                        .isInstanceOf(InvalidDataException.class)
                        .satisfies(e -> assertThat(((InvalidDataException) e).getMessageCode()).contains("role.fixed"));
                assertThatThrownBy(() -> roleService.updateFoldersOfRole(role.getId(), List.of(folderId + ":WRITE"), principalId))
                        .isInstanceOf(InvalidDataException.class);
                assertThatThrownBy(() -> uploadPolicyService.saveForRole(role.getId(), Map.of("pdf", 5L), principalId))
                        .isInstanceOf(InvalidDataException.class);
                assertThatThrownBy(() -> uploadPolicyService.saveForRole(role.getId(), null, principalId))
                        .isInstanceOf(InvalidDataException.class);
            }
            flushAndClear();
            assertThat(permissionsOf(user.getId())).isEqualTo(FixedRole.USER_PERMISSIONS);
        }

        @Test
        @DisplayName("a role named like one in another case cannot be created beside it")
        void theirNamesAreTaken() {
            assertThatThrownBy(() -> roleService.createRole("user", null, principalId)).isInstanceOf(DuplicateResourceException.class);
            assertThatThrownBy(() -> roleService.copyRole(user.getId(), "Admin", principalId)).isInstanceOf(DuplicateResourceException.class);
        }

        @Test
        @DisplayName("the page shows USER's groups and every group of ADMIN as ticked")
        void theirPageShowsTheDefinition() {
            List<PermissionGroupDTO> userGroups = roleService.getPermissionGroupsOfRole(user.getId());
            assertThat(userGroups).filteredOn(PermissionGroupDTO::isAllSelected).extracting(PermissionGroupDTO::name)
                    .containsExactlyElementsOf(FixedRole.USER.groups().stream().map(Enum::name).toList());
            assertThat(roleService.getPermissionGroupsOfRole(admin.getId())).allMatch(PermissionGroupDTO::isAllSelected);
        }
    }

    // ================================================================ copying

    @Nested
    @DisplayName("copying a role")
    class Copying {

        @Test
        @DisplayName("the copy has the same permissions, folder grants and own upload policy, and nobody holds it")
        void copiesAllThree() {
            int source = roleService.createRole("SOURCE_" + TestData.nextSequence(), null, principalId);
            roleService.updatePermissionsOfRole(source, idsOf(PermissionEnum.FILE_EXPLORER_PAGE, PermissionEnum.DOWNLOAD_FILE), principalId);
            roleService.updateFoldersOfRole(source, List.of(folderId + ":WRITE"), principalId);
            uploadPolicyService.saveForRole(source, Map.of("pdf", 5L, "png", 2L), principalId);
            flushAndClear();

            int copy = roleService.copyRole(source, "  COPY_" + TestData.nextSequence() + "  ", principalId);
            flushAndClear();

            assertThat(roleRepository.findById(copy).orElseThrow().getRoleName()).as("trimmed").doesNotStartWith(" ").startsWith("COPY_");
            assertThat(permissionsOf(copy)).containsExactlyInAnyOrder(PermissionEnum.FILE_EXPLORER_PAGE, PermissionEnum.DOWNLOAD_FILE);
            assertThat(roleRepository.findByIdWithFolders(copy).orElseThrow().getFolderGrants())
                    .singleElement().satisfies(grant -> {
                        assertThat(grant.getFolder().getId()).isEqualTo(folderId);
                        assertThat(grant.getPermission()).isEqualTo(FolderPermission.WRITE);
                    });
            assertThat(uploadPolicyService.roleLimits(copy)).contains(Map.of("pdf", 5 * MB, "png", 2 * MB));
            assertThat(userRepository.findHoldersOfRole(copy)).isEmpty();
        }

        @Test
        @DisplayName("the two are independent afterwards: editing the copy leaves the source as it was")
        void theCopyIsIndependent() {
            int source = roleService.createRole("SRC_" + TestData.nextSequence(), null, principalId);
            roleService.updatePermissionsOfRole(source, idsOf(PermissionEnum.FILE_EXPLORER_PAGE), principalId);
            int copy = roleService.copyRole(source, "CPY_" + TestData.nextSequence(), principalId);

            roleService.updatePermissionsOfRole(copy, idsOf(PermissionEnum.SAVE_NEW_FILE), principalId);
            roleService.updateFoldersOfRole(copy, List.of(folderId + ":READ"), principalId);
            flushAndClear();

            assertThat(permissionsOf(source)).containsExactly(PermissionEnum.FILE_EXPLORER_PAGE);
            assertThat(roleRepository.findByIdWithFolders(source).orElseThrow().getFolderGrants()).isEmpty();
        }

        @Test
        @DisplayName("a copy of a role on the system-wide upload policy stays on it")
        void noOwnPolicyNoCopy() {
            int source = roleService.createRole("PLAIN_" + TestData.nextSequence(), null, principalId);
            int copy = roleService.copyRole(source, "PLAINCOPY_" + TestData.nextSequence(), principalId);
            assertThat(uploadPolicyService.roleLimits(copy)).isEmpty();
        }

        @Test
        @DisplayName("a copy of ADMIN gets every assignable permission but not the name's reach; a copy of USER gets USER's")
        void copiesOfTheFixedRoles() {
            int adminCopy = roleService.copyRole(admin.getId(), "ALMOST_ADMIN_" + TestData.nextSequence(), principalId);
            int userCopy = roleService.copyRole(user.getId(), "USER_PLUS_" + TestData.nextSequence(), principalId);
            flushAndClear();

            assertThat(permissionsOf(adminCopy)).isEqualTo(FixedRole.everything()).doesNotContain(PermissionEnum.ADMIN);
            assertThat(permissionsOf(userCopy)).isEqualTo(FixedRole.USER_PERMISSIONS);
            assertThat(roleService.isFixed(adminCopy)).isFalse();

            // And the copy of USER is editable, which is the point of copying it.
            roleService.updatePermissionsOfRole(userCopy, idsOf(PermissionEnum.GET_ALL_USER_PAGE), principalId);
        }

        @Test
        @DisplayName("an empty, too long or taken name is refused, and nothing is created")
        void badNames() {
            long before = roleRepository.count();
            assertThatThrownBy(() -> roleService.copyRole(user.getId(), "   ", principalId)).isInstanceOf(InvalidDataException.class)
                    .satisfies(e -> assertThat(((InvalidDataException) e).getMessageCode()).contains("role.invalidName"));
            assertThatThrownBy(() -> roleService.copyRole(user.getId(), null, principalId)).isInstanceOf(InvalidDataException.class);
            assertThatThrownBy(() -> roleService.copyRole(user.getId(), "x".repeat(101), principalId)).isInstanceOf(InvalidDataException.class);
            assertThatThrownBy(() -> roleService.copyRole(user.getId(), "USER", principalId)).isInstanceOf(DuplicateResourceException.class);
            assertThat(roleRepository.count()).isEqualTo(before);
        }
    }

    // ================================================================ the permissions tab

    @Test
    @DisplayName("saving the permissions tab keeps what the page does not offer, and refuses it in the post")
    void theHiddenPermissionsAreKept() {
        int role = roleService.createRole("KEEPS_" + TestData.nextSequence(), null, principalId);
        Role entity = roleRepository.findByIdWithPermissions(role).orElseThrow();
        entity.getPermissions().add(permissionRepository.findByPermissionName(PermissionEnum.API_KEY).orElseThrow());
        flushAndClear();

        roleService.updatePermissionsOfRole(role, idsOf(PermissionEnum.FILE_EXPLORER_PAGE), principalId);
        flushAndClear();
        assertThat(permissionsOf(role)).containsExactlyInAnyOrder(PermissionEnum.FILE_EXPLORER_PAGE, PermissionEnum.API_KEY);

        assertThatThrownBy(() -> roleService.updatePermissionsOfRole(role, idsOf(PermissionEnum.ADMIN), principalId))
                .isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("the grouped view lists every assignable permission once, in its group, with the role's selection")
    void theGroupedView() {
        int role = roleService.createRole("GROUPED_" + TestData.nextSequence(), null, principalId);
        roleService.updatePermissionsOfRole(role, idsOf(PermissionGroup.FILE_READ.members().toArray(PermissionEnum[]::new)), principalId);
        roleService.updatePermissionsOfRole(role, idsOf(concat(PermissionGroup.FILE_READ.members(), PermissionEnum.SAVE_NEW_FILE)), principalId);
        flushAndClear();

        List<PermissionGroupDTO> groups = roleService.getPermissionGroupsOfRole(role);

        assertThat(groups).extracting(PermissionGroupDTO::name)
                .containsExactly(java.util.Arrays.stream(PermissionGroup.values()).map(Enum::name).toArray(String[]::new));
        assertThat(groups.stream().flatMap(group -> group.permissions().stream()).map(p -> p.getPermissionName()).toList())
                .doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(PermissionGroup.assignable());
        PermissionGroupDTO read = groups.stream().filter(g -> g.name().equals("FILE_READ")).findFirst().orElseThrow();
        PermissionGroupDTO write = groups.stream().filter(g -> g.name().equals("FILE_WRITE")).findFirst().orElseThrow();
        assertThat(read.isAllSelected()).isTrue();
        assertThat(write.isSomeSelected()).isTrue();
        assertThat(write.selectedCount()).isOne();
    }

    // ================================================================ the start

    @Nested
    @DisplayName("the start, on an installation where the fixed roles were edited by hand")
    class Reconcile {

        @Test
        @DisplayName("USER's extras move into USER_PREVIOUS, given to every holder, so nobody loses anything; USER is reset")
        void userExtrasArePreserved() {
            User holder = TestData.user();
            holder.getRoles().add(user);
            int holderId = userRepository.save(holder).getId();
            Set<PermissionEnum> before = handEdit(user, EnumSet.of(PermissionEnum.GET_ALL_USER_PAGE, PermissionEnum.FILE_EXPLORER_PAGE), true, true);
            Set<PermissionEnum> reachBefore = reachOf(holderId);

            dataInitializer.initialize();
            flushAndClear();

            Role previous = roleRepository.findByRoleNameIgnoreCase("USER_PREVIOUS").orElseThrow();
            assertThat(permissionsOf(previous.getId())).isEqualTo(before);
            assertThat(roleRepository.findByIdWithFolders(previous.getId()).orElseThrow().getFolderGrants())
                    .singleElement().satisfies(grant -> assertThat(grant.getFolder().getId()).isEqualTo(folderId));
            assertThat(uploadPolicyService.roleLimits(previous.getId())).contains(Map.of("mp4", 7 * MB));
            assertThat(userRepository.findHoldersOfRole(previous.getId())).extracting(User::getId).contains(holderId);

            assertThat(permissionsOf(user.getId())).isEqualTo(FixedRole.USER_PERMISSIONS);
            assertThat(roleRepository.findByIdWithFolders(user.getId()).orElseThrow().getFolderGrants()).isEmpty();
            assertThat(uploadPolicyService.roleLimits(user.getId())).isEmpty();

            assertThat(reachOf(holderId)).as("what the holder can do is unchanged").containsAll(reachBefore);

            // And the next start finds nothing to do: no second copy.
            dataInitializer.initialize();
            flushAndClear();
            assertThat(roleRepository.findByRoleNameIgnoreCase("USER_PREVIOUS_2")).isEmpty();
        }

        @Test
        @DisplayName("a permission USER lacks is only added; nothing is copied for it")
        void aMissingPermissionIsAdded() {
            Role entity = roleRepository.findByIdWithPermissions(user.getId()).orElseThrow();
            entity.getPermissions().removeIf(p -> p.getPermissionName() == PermissionEnum.DOWNLOAD_FILE);
            flushAndClear();

            dataInitializer.initialize();
            flushAndClear();

            assertThat(permissionsOf(user.getId())).isEqualTo(FixedRole.USER_PERMISSIONS);
            assertThat(roleRepository.findByRoleNameIgnoreCase("USER_PREVIOUS")).isEmpty();
        }

        @Test
        @DisplayName("ADMIN's folder grants, own upload policy and non-assignable rows are dropped without a copy - its holders already reach everything")
        void adminExtras() {
            Role entity = roleRepository.findByIdWithPermissions(admin.getId()).orElseThrow();
            entity.getPermissions().add(permissionRepository.findByPermissionName(PermissionEnum.API_KEY).orElseThrow());
            handEdit(admin, EnumSet.noneOf(PermissionEnum.class), true, true);

            dataInitializer.initialize();
            flushAndClear();

            assertThat(permissionsOf(admin.getId())).isEqualTo(FixedRole.everything());
            assertThat(roleRepository.findByIdWithFolders(admin.getId()).orElseThrow().getFolderGrants()).isEmpty();
            assertThat(uploadPolicyService.roleLimits(admin.getId())).isEmpty();
            assertThat(roleRepository.findByRoleNameIgnoreCase("ADMIN_PREVIOUS")).as("nothing worth keeping").isEmpty();
        }

        /**
         * What happens on the start after a release adds a constant to PermissionEnum: the seeding
         * inserts its row and the reconcile gives it to ADMIN. Simulated by taking rows away,
         * which leaves ADMIN in the same state a new constant would.
         */
        @Test
        @DisplayName("a permission ADMIN lacks - a new one, after an upgrade - is given to it by the next start, without a copy")
        void aNewPermissionReachesAdmin() {
            Role entity = roleRepository.findByIdWithPermissions(admin.getId()).orElseThrow();
            entity.getPermissions().removeIf(p -> p.getPermissionName() == PermissionEnum.COPY_ROLE
                    || p.getPermissionName() == PermissionEnum.REST_DELETE_FOLDER_TREE);
            flushAndClear();
            assertThat(permissionsOf(admin.getId())).doesNotContain(PermissionEnum.COPY_ROLE);

            dataInitializer.initialize();
            flushAndClear();

            assertThat(permissionsOf(admin.getId())).isEqualTo(FixedRole.everything());
            assertThat(roleRepository.findByRoleNameIgnoreCase("ADMIN_PREVIOUS")).isEmpty();
        }

        @Test
        @DisplayName("when USER_PREVIOUS is taken, the copy is USER_PREVIOUS_2")
        void aTakenNameIsNotReused() {
            roleService.createRole("USER_PREVIOUS", null, principalId);
            handEdit(user, EnumSet.of(PermissionEnum.GET_ALL_USER_PAGE), false, false);

            dataInitializer.initialize();
            flushAndClear();

            assertThat(roleRepository.findByRoleNameIgnoreCase("USER_PREVIOUS_2")).isPresent();
        }

        /** Edits a fixed role behind the service's back, as the role page could before 1.9.0. */
        private Set<PermissionEnum> handEdit(Role fixed, Set<PermissionEnum> extra, boolean grant, boolean policy) {
            Role entity = roleRepository.findByIdWithPermissions(fixed.getId()).orElseThrow();
            entity.getPermissions().addAll(permissionRepository.findByPermissionNameIn(List.copyOf(extra)));
            if (grant) {
                Role withGrants = roleRepository.findByIdWithFolders(fixed.getId()).orElseThrow();
                withGrants.replaceFolderGrants(List.of(new RoleFolderGrant(withGrants,
                        folderRepository.findById(folderId).orElseThrow(), FolderPermission.WRITE)));
            }
            if (policy) {
                UploadPolicy own = new UploadPolicy();
                own.setRole(entity);
                own.replaceRules(Map.of("mp4", 7 * MB));
                uploadPolicyRepository.save(own);
            }
            flushAndClear();
            return permissionsOf(fixed.getId());
        }
    }

    // ================================================================ usernames

    @Nested
    @DisplayName("a username")
    class Usernames {

        @Test
        @DisplayName("somebody without the ADMIN role cannot change one, even with SAVE_UPDATED_USER; the rest of the form still saves")
        void onlyAnAdministratorRenames() {
            int editor = userRepository.save(TestData.user()).getId();
            User subject = userRepository.save(TestData.user());
            flushAndClear();

            UserDTO rename = toDto(subject);
            rename.setUsername("renamed" + TestData.nextSequence());
            assertThatThrownBy(() -> userService.updateUser(rename, editor))
                    .isInstanceOf(InvalidDataException.class)
                    .satisfies(e -> assertThat(((InvalidDataException) e).getMessageCode()).contains("user.usernameAdminOnly"));
            flushAndClear();
            assertThat(userRepository.findById(subject.getId()).orElseThrow().getUsername()).isEqualTo(subject.getUsername());

            UserDTO other = toDto(subject);
            other.setFirstName("Edited");
            userService.updateUser(other, editor);
            flushAndClear();
            assertThat(userRepository.findById(subject.getId()).orElseThrow().getFirstName()).isEqualTo("Edited");
        }

        @Test
        @DisplayName("an administrator can")
        void anAdministratorRenames() {
            User subject = userRepository.save(TestData.user());
            flushAndClear();
            String newName = "renamed" + TestData.nextSequence();

            UserDTO rename = toDto(subject);
            rename.setUsername(newName);
            userService.updateUser(rename, principalId);
            flushAndClear();

            assertThat(userRepository.findById(subject.getId()).orElseThrow().getUsername()).isEqualTo(newName);
        }

        private UserDTO toDto(User user) {
            UserDTO dto = new UserDTO();
            dto.setId(user.getId());
            dto.setUsername(user.getUsername());
            dto.setPersonelCode(user.getPersonelCode());
            dto.setNationalCode(user.getNationalCode());
            dto.setPhoneNumber(user.getPhoneNumber());
            dto.setFirstName(user.getFirstName());
            dto.setLastName(user.getLastName());
            dto.setEmail(user.getEmail());
            return dto;
        }
    }

    // ---------------------------------------------------------------- helpers

    private Set<PermissionEnum> permissionsOf(int roleId) {
        return roleRepository.findByIdWithPermissions(roleId).orElseThrow().getPermissions().stream()
                .map(Permission::getPermissionName)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(PermissionEnum.class)));
    }

    /** Every permission a person holds through any role. */
    private Set<PermissionEnum> reachOf(int userId) {
        return userService.getAllPermissionsOfUser(userId).stream().map(Permission::getPermissionName)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(PermissionEnum.class)));
    }

    private List<Integer> idsOf(PermissionEnum... names) {
        return permissionRepository.findByPermissionNameIn(List.of(names)).stream().map(Permission::getId).toList();
    }

    private static PermissionEnum[] concat(List<PermissionEnum> members, PermissionEnum extra) {
        Set<PermissionEnum> all = new LinkedHashSet<>(members);
        all.add(extra);
        return all.toArray(PermissionEnum[]::new);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
