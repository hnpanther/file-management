package com.hnp.filemanagement;

import com.hnp.filemanagement.audit.domain.ActionHistoryMapper;
import com.hnp.filemanagement.file.domain.FileMapper;
import com.hnp.filemanagement.identity.domain.RoleMapper;
import com.hnp.filemanagement.identity.domain.UserMapper;
import com.hnp.filemanagement.audit.domain.ActionHistoryDTO;
import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileUploadOutputDTO;
import com.hnp.filemanagement.folder.domain.FolderContentDTO;
import com.hnp.filemanagement.identity.domain.PermissionDTO;
import com.hnp.filemanagement.file.domain.PublicFileDetailsDTO;
import com.hnp.filemanagement.identity.domain.RoleDTO;
import com.hnp.filemanagement.identity.domain.UserDTO;
import com.hnp.filemanagement.audit.domain.ActionEnum;
import com.hnp.filemanagement.audit.domain.ActionHistory;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.file.domain.FileDetails;
import com.hnp.filemanagement.file.domain.FileInfo;
import com.hnp.filemanagement.identity.domain.FixedRole;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderKind;
import com.hnp.filemanagement.identity.domain.Permission;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.file.domain.FileNames;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-feature mappers that replaced {@code ModelConverterUtil} (issue 29), field by field and
 * with no Spring and no database - the isolation that class never had. Each test builds the
 * entities by hand, with every field set to something distinct, so that a field copied from the
 * wrong getter, or not copied, fails here rather than on a page.
 */
class MappersTest {

    private static final Instant WHEN = Instant.parse("2026-09-26T07:00:15Z");

    @Nested
    class Users {

        @Test
        @DisplayName("every column is carried, and the password never is")
        void mapsAUser() {
            User user = user(7, "hadi");
            user.setPassword("$2a$10$the-hash-itself");

            UserDTO dto = UserMapper.toDto(user);

            assertThat(dto.getId()).isEqualTo(7);
            assertThat(dto.getUsername()).isEqualTo("hadi");
            assertThat(dto.getPersonelCode()).isEqualTo(1007);
            assertThat(dto.getNationalCode()).isEqualTo("0000000007");
            assertThat(dto.getPhoneNumber()).isEqualTo("09120000007");
            assertThat(dto.getEmail()).isEqualTo("hadi@example.test");
            assertThat(dto.getFirstName()).isEqualTo("هادی");
            assertThat(dto.getLastName()).isEqualTo("نیکوئی");
            assertThat(dto.getEnabled()).isEqualTo(1);
            assertThat(dto.getState()).isEqualTo(-1);
            assertThat(dto.getLoginType()).isEqualTo(2);
            assertThat(dto.getPassword()).isEqualTo(UserMapper.PASSWORD_MASK).doesNotContain("$2a$");
        }
    }

    @Nested
    class Roles {

        @Test
        @DisplayName("a role carries its permissions, both unselected, and says whether it is fixed")
        void mapsARole() {
            Role role = new Role();
            role.setId(3);
            role.setRoleName("Editors");
            Permission permission = new Permission();
            permission.setId(40);
            permission.setPermissionName(PermissionEnum.FILE_TREE_PAGE);
            permission.setDescription("the tree");
            role.getPermissions().add(permission);

            RoleDTO dto = RoleMapper.toDto(role);

            assertThat(dto.getId()).isEqualTo(3);
            assertThat(dto.getRoleName()).isEqualTo("Editors");
            assertThat(dto.isFixed()).isFalse();
            assertThat(dto.isSelected()).isFalse();
            assertThat(dto.getPermissionDTOS()).singleElement().satisfies(p -> {
                assertThat(p.getId()).isEqualTo(40);
                assertThat(p.getPermissionName()).isEqualTo(PermissionEnum.FILE_TREE_PAGE);
                assertThat(p.getDescription()).isEqualTo("the tree");
                assertThat(p.isSelected()).isFalse();
            });
        }

        @Test
        @DisplayName("ADMIN and USER are marked fixed, whatever their case")
        void marksTheFixedRoles() {
            Role admin = new Role();
            admin.setRoleName(FixedRole.ADMIN.roleName().toLowerCase());

            assertThat(RoleMapper.toDto(admin).isFixed()).isTrue();
        }

        @Test
        @DisplayName("a permission maps on its own too")
        void mapsAPermission() {
            Permission permission = new Permission();
            permission.setId(9);
            permission.setPermissionName(PermissionEnum.COPY_ROLE);

            PermissionDTO dto = RoleMapper.toPermissionDto(permission);

            assertThat(dto.getId()).isEqualTo(9);
            assertThat(dto.getPermissionName()).isEqualTo(PermissionEnum.COPY_ROLE);
            assertThat(dto.getDescription()).isNull();
        }
    }

    @Nested
    class Files {

        private final User creator = user(5, "creator");
        private final Folder home = folder(1, "Home", "", FolderKind.ROOT);
        private final Folder procedures = folder(20, "Procedures", "رویه‌ها", FolderKind.FOLDER);
        private final Folder quality = folder(16, "Quality", "  ", FolderKind.FOLDER);

        @Test
        @DisplayName("a revision carries its own columns, its file's ids and who stored it")
        void mapsARevision() {
            FileInfo file = file();
            FileDetails revision = revision(file, 200, 2, "pdf");

            FileDetailsDTO dto = FileMapper.toDto(revision);

            assertThat(dto.getId()).isEqualTo(200);
            assertThat(dto.getExternalId()).isEqualTo("rev-200");
            assertThat(dto.getFileInfoId()).isEqualTo(100);
            assertThat(dto.getFileInfoExternalId()).isEqualTo("file-100");
            assertThat(dto.getChecksumSha256()).isEqualTo("sha-200");
            assertThat(dto.getFileName()).isEqualTo("report.pdf");
            assertThat(dto.getFileExtension()).isEqualTo("pdf");
            assertThat(dto.getContentType()).isEqualTo("application/pdf");
            assertThat(dto.getDescription()).isEqualTo("revision 200");
            assertThat(dto.getFileLink()).isEqualTo("link-200");
            assertThat(dto.getFileSize()).isEqualTo(3_221_225_472L);
            assertThat(dto.getVersion()).isEqualTo(2);
            assertThat(dto.getVersionName()).isEqualTo("V2");
            assertThat(dto.getVersionNameDescription()).isEqualTo("second");
            assertThat(dto.getEnabled()).isEqualTo(1);
            assertThat(dto.getState()).isEqualTo(0);
            assertThat(dto.getCreatedById()).isEqualTo(5);
            assertThat(dto.getCreatedBy()).isEqualTo("creator");
            assertThat(dto.getCreatedAt()).isEqualTo(WHEN);
        }

        @Test
        @DisplayName("a file carries its revisions and the folders above it, the title falling back to the name")
        void mapsAFile() {
            FileInfo file = file();
            revision(file, 200, 1, "pdf");
            revision(file, 201, 1, "docx");

            FileInfoDTO dto = FileMapper.toDto(file, List.of(home, procedures, quality));

            assertThat(dto.getId()).isEqualTo(100);
            assertThat(dto.getExternalId()).isEqualTo("file-100");
            assertThat(dto.getFileName()).isEqualTo("report");
            assertThat(dto.getFileNameDescription()).isEqualTo("the report");
            assertThat(dto.getDescription()).isEqualTo("annual");
            assertThat(dto.getFileLink()).isEqualTo("link-100");
            assertThat(dto.getLastVersion()).isEqualTo(2);
            assertThat(dto.getFolderId()).isEqualTo(16);
            assertThat(dto.getState()).isEqualTo(-1);
            assertThat(dto.getEnabled()).isEqualTo(1);
            assertThat(dto.getCreatedAt()).isEqualTo(WHEN);
            assertThat(dto.getCreatedBy()).isEqualTo("creator");
            assertThat(dto.getFileDetailsDTOS()).extracting(FileDetailsDTO::getId).containsExactly(200, 201);
            assertThat(dto.getFolderPath()).containsExactly(
                    new FolderContentDTO.FolderRef(1, "Home", "Home", "ROOT"),
                    new FolderContentDTO.FolderRef(20, "Procedures", "رویه‌ها", "FOLDER"),
                    new FolderContentDTO.FolderRef(16, "Quality", "Quality", "FOLDER"));
            assertThat(dto.getFolderTitle()).isEqualTo("Home / رویه‌ها / Quality");
        }

        @Test
        @DisplayName("a public revision carries less, and the folder as one line")
        void mapsAPublicRevision() {
            FileDetails revision = revision(file(), 200, 2, "pdf");

            PublicFileDetailsDTO dto = FileMapper.toPublicDto(revision, List.of(home, procedures));

            assertThat(dto.getId()).isEqualTo(200);
            assertThat(dto.getFileInfoId()).isEqualTo(100);
            assertThat(dto.getFileName()).isEqualTo("report.pdf");
            assertThat(dto.getDescription()).isEqualTo("revision 200");
            assertThat(dto.getFolderTitle()).isEqualTo("Home" + FileMapper.FOLDER_PATH_SEPARATOR + "رویه‌ها");
            assertThat(dto.getVersion()).isEqualTo("V2");
            assertThat(dto.getSize()).isEqualTo(3_221_225_472L);
            assertThat(dto.getFileInfoName()).isEqualTo("annual");
        }

        @Test
        @DisplayName("the v1 upload answer carries both ids of the file and of the revision, and the checksum")
        void mapsTheUploadAnswer() {
            FileUploadOutputDTO dto = FileMapper.toUploadOutput(FileMapper.toDto(revision(file(), 200, 2, "pdf")));

            assertThat(dto.getFileId()).isEqualTo(100);
            assertThat(dto.getFileDetailsId()).isEqualTo(200);
            assertThat(dto.getFileExternalId()).isEqualTo("file-100");
            assertThat(dto.getFileDetailsExternalId()).isEqualTo("rev-200");
            assertThat(dto.getChecksumSha256()).isEqualTo("sha-200");
            assertThat(dto.getFileName()).isEqualTo("report.pdf");
            assertThat(dto.getFileExtension()).isEqualTo("pdf");
            assertThat(dto.getContentType()).isEqualTo("application/pdf");
            assertThat(dto.getDescription()).isEqualTo("revision 200");
        }

        private FileInfo file() {
            FileInfo file = new FileInfo();
            file.setId(100);
            file.setExternalId("file-100");
            file.setFileName("report");
            file.setFileNameDescription("the report");
            file.setDescription("annual");
            file.setFileLink("link-100");
            file.setLastVersion(2);
            file.setFolder(quality);
            file.setState(-1);
            file.setEnabled(1);
            file.setCreatedAt(WHEN);
            file.setCreatedBy(creator);
            return file;
        }

        private FileDetails revision(FileInfo file, int id, int version, String extension) {
            FileDetails revision = new FileDetails();
            revision.setId(id);
            revision.setExternalId("rev-" + id);
            revision.setChecksumSha256("sha-" + id);
            revision.setFileName("report." + extension);
            revision.setFileExtension(extension);
            revision.setContentType("pdf".equals(extension) ? "application/pdf" : "application/x");
            revision.setDescription("revision " + id);
            revision.setFileLink("link-" + id);
            revision.setFileSize(3_221_225_472L);
            revision.setVersion(version);
            revision.setVersionName("V" + version);
            revision.setVersionNameDescription(version == 2 ? "second" : null);
            revision.setEnabled(1);
            revision.setState(0);
            revision.setCreatedAt(WHEN);
            revision.setCreatedBy(creator);
            file.addFileDetails(revision);
            return revision;
        }
    }

    @Nested
    class History {

        @Test
        @DisplayName("an audit row carries its columns, the table's name and who acted, in full")
        void mapsAnAuditRow() {
            ActionHistory history = new ActionHistory();
            history.setId(11);
            history.setEntityName(EntityEnum.FileInfo);
            history.setEntityId(100);
            history.setAction(ActionEnum.DELETE);
            history.setActionDescription("DELETE FILE_INFO");
            history.setDescription("removed");
            history.setEnabled(1);
            history.setState(0);
            history.setCreatedAt(WHEN);
            history.setUser(user(5, "hadi"));

            ActionHistoryDTO dto = ActionHistoryMapper.toDto(history);

            assertThat(dto.getId()).isEqualTo(11);
            assertThat(dto.getEntityName()).isEqualTo(EntityEnum.FileInfo);
            assertThat(dto.getTableName()).isEqualTo("file_info");
            assertThat(dto.getEntityId()).isEqualTo(100);
            assertThat(dto.getAction()).isEqualTo(ActionEnum.DELETE);
            assertThat(dto.getActionDescription()).isEqualTo("DELETE FILE_INFO");
            assertThat(dto.getDescription()).isEqualTo("removed");
            assertThat(dto.getEnabled()).isEqualTo(1);
            assertThat(dto.getState()).isEqualTo(0);
            assertThat(dto.getCreatedAt()).isEqualTo(WHEN);
            assertThat(dto.getUsername()).isEqualTo("hadi");
            assertThat(dto.getFullName()).isEqualTo("هادی نیکوئی");
        }
    }

    @Nested
    class Names {

        @Test
        @DisplayName("a file's name is its stored name without the last extension")
        void dropsTheLastExtension() {
            assertThat(FileNames.withoutExtension("report.pdf")).isEqualTo("report");
            assertThat(FileNames.withoutExtension("report.v2.pdf")).isEqualTo("report.v2");
            assertThat(FileNames.withoutExtension("گزارش‌ها.docx")).isEqualTo("گزارش‌ها");
            assertThat(FileNames.withoutExtension("README")).isEqualTo("README");
        }
    }

    // ---------------------------------------------------------------- builders

    private static User user(int id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPersonelCode(1000 + id);
        user.setNationalCode(String.format("%010d", id));
        user.setPhoneNumber("0912" + String.format("%07d", id));
        user.setEmail(username + "@example.test");
        user.setFirstName("هادی");
        user.setLastName("نیکوئی");
        user.setEnabled(1);
        user.setState(-1);
        user.setLoginType(2);
        return user;
    }

    private static Folder folder(int id, String name, String displayName, FolderKind kind) {
        Folder folder = new Folder();
        folder.setId(id);
        folder.setName(name);
        folder.setDisplayName(displayName);
        folder.setKind(kind);
        return folder;
    }
}
