package com.hnp.filemanagement.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The permission groups of the role page and the definition of the fixed roles. Both are data
 * written in code, and both drift silently: a constant added to {@link PermissionEnum} and placed
 * in no group would never be offered on the role page, and a group added to USER reaches every
 * account on the next start.
 */
class PermissionGroupTest {

    @Test
    @DisplayName("every assignable permission is in exactly one group; ADMIN and API_KEY are in none")
    void everyPermissionHasOneGroup() {
        Map<PermissionEnum, Integer> seen = new EnumMap<>(PermissionEnum.class);
        for (PermissionGroup group : PermissionGroup.values()) {
            assertThat(group.members()).as(group.name()).isNotEmpty().doesNotHaveDuplicates();
            group.members().forEach(permission -> seen.merge(permission, 1, Integer::sum));
        }

        assertThat(seen.keySet()).as("placed in a group").isEqualTo(PermissionGroup.assignable());
        assertThat(seen.values()).as("in exactly one").allMatch(count -> count == 1);
        assertThat(PermissionGroup.NOT_ASSIGNABLE).containsExactlyInAnyOrder(PermissionEnum.ADMIN, PermissionEnum.API_KEY);
        PermissionGroup.NOT_ASSIGNABLE.forEach(permission -> assertThat(PermissionGroup.of(permission)).isEmpty());
    }

    @Test
    @DisplayName("every group has a title and a description in the bundle, in Persian")
    void everyGroupIsTitled() throws IOException {
        Properties bundle = new Properties();
        try (var in = new InputStreamReader(Files.newInputStream(Path.of("src/main/resources/messages.properties")),
                StandardCharsets.UTF_8)) {
            bundle.load(in);
        }
        for (PermissionGroup group : PermissionGroup.values()) {
            assertThat(bundle.getProperty("permissionGroup." + group.name() + ".title")).as(group.name()).isNotBlank();
            assertThat(bundle.getProperty("permissionGroup." + group.name() + ".description")).as(group.name()).isNotBlank();
        }
        for (FixedRole role : FixedRole.values()) {
            assertThat(bundle.getProperty("role.fixed.notice." + role.name())).as(role.name()).isNotBlank();
        }
    }

    /**
     * What every account can do. Written out, not derived, so that a change to the groups USER is
     * made of has to be made here as well - on purpose.
     */
    @Test
    @DisplayName("USER reads, writes and deletes files, manages folders and makes share links - and administers nothing")
    void theUserRole() {
        assertThat(FixedRole.USER.groups()).containsExactly(
                PermissionGroup.FILE_READ, PermissionGroup.FILE_WRITE, PermissionGroup.FILE_DELETE,
                PermissionGroup.FOLDER_MANAGE, PermissionGroup.SHARE_LINKS);
        assertThat(FixedRole.USER_PERMISSIONS).contains(
                PermissionEnum.ACCESS_HOME, PermissionEnum.FILE_EXPLORER_PAGE, PermissionEnum.REST_GET_FOLDER_CONTENT,
                PermissionEnum.DOWNLOAD_FILE, PermissionEnum.SAVE_NEW_FILE, PermissionEnum.REST_DELETE_FILE_INFO,
                PermissionEnum.REST_CREATE_FOLDER, PermissionEnum.CREATE_SHARE_LINK);

        Set<PermissionEnum> administration = PermissionGroup.membersOf(PermissionGroup.USERS_ADMIN, PermissionGroup.ROLES_ADMIN,
                PermissionGroup.SETTINGS_ADMIN, PermissionGroup.API_KEYS_ADMIN, PermissionGroup.API_V1,
                PermissionGroup.FOLDER_DELETE_TREE, PermissionGroup.SHARE_LINKS_ADMIN, PermissionGroup.FILE_PUBLISH);
        assertThat(FixedRole.USER_PERMISSIONS).doesNotContainAnyElementsOf(administration)
                .doesNotContain(PermissionEnum.ADMIN, PermissionEnum.API_KEY);
    }

    @Test
    @DisplayName("ADMIN holds no rows - its name is its reach - and shows every group; a copy of it gets every assignable permission")
    void theAdminRole() {
        assertThat(FixedRole.ADMIN.permissions()).isEmpty();
        assertThat(FixedRole.ADMIN.groups()).containsExactly(PermissionGroup.values());
        assertThat(FixedRole.everything()).isEqualTo(PermissionGroup.assignable())
                .doesNotContain(PermissionEnum.ADMIN, PermissionEnum.API_KEY);
        assertThat(EnumSet.copyOf(FixedRole.everything())).containsAll(FixedRole.USER_PERMISSIONS);
    }

    @Test
    @DisplayName("a fixed role is recognised by its name in any case, and nothing else is one")
    void recognisingAFixedRole() {
        assertThat(FixedRole.of("admin")).contains(FixedRole.ADMIN);
        assertThat(FixedRole.of(" User ")).contains(FixedRole.USER);
        assertThat(FixedRole.isFixed("USER_PREVIOUS")).isFalse();
        assertThat(FixedRole.isFixed("ADMINS")).isFalse();
        assertThat(FixedRole.isFixed(null)).isFalse();
    }
}
