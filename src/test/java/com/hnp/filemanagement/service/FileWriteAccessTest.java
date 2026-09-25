package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileDetailsDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.entity.FolderPermission;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.entity.UserFolderGrant;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue 90: four writes to a file that the endpoint permission alone used to allow anywhere - the
 * whole-file delete, a description change, and making a file or a version public or private. They
 * are judged like every other write since 1.9.0: on the file's own folder, which the caller must
 * hold WRITE on. It matters because the fixed USER role gives every account the permissions for
 * the first two, meant for its own personal folder.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FileWriteAccessTest extends MySqlSupport {

    @Autowired
    private FileService underTest;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private int adminId;
    private int folderId;
    private FileDetailsDTO file;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        adminId = userRepository.save(admin).getId();
        folderId = FolderFixture.chain(folderRepository, tagGroupRepository, admin).tagId();
        file = upload("guarded.txt");
        flushAndClear();
    }

    @Test
    @DisplayName("without a grant on the file's folder, none of the four writes is allowed, and nothing changes")
    void refusedWithoutAGrant() {
        int strangerId = userRepository.save(TestData.user()).getId();
        refusesAll(strangerId);
    }

    @Test
    @DisplayName("a READ grant is not enough to write")
    void refusedWithReadOnly() {
        int readerId = userWith(FolderPermission.READ);
        refusesAll(readerId);
    }

    @Test
    @DisplayName("with WRITE on the folder all four work, the whole-file delete last")
    void allowedWithWrite() {
        int writerId = userWith(FolderPermission.WRITE);

        underTest.updateFileInfoDescription(file.getFileInfoId(), "changed", writerId);
        underTest.changeFileInfoState(file.getFileInfoId(), -1, writerId);
        underTest.changeFileDetailsState(file.getId(), -1, writerId);
        flushAndClear();
        assertThat(fileInfoRepository.findById(file.getFileInfoId()).orElseThrow().getDescription()).isEqualTo("changed");
        assertThat(fileInfoRepository.findById(file.getFileInfoId()).orElseThrow().getState()).isEqualTo(-1);
        assertThat(fileDetailsRepository.findById(file.getId()).orElseThrow().getState()).isEqualTo(-1);

        underTest.deleteCompleteFileById(file.getFileInfoId(), writerId);
        flushAndClear();
        assertThat(fileInfoRepository.findById(file.getFileInfoId())).isEmpty();
    }

    @Test
    @DisplayName("an administrator passes every folder check, as before")
    void anAdministratorStillReachesEverything() {
        underTest.updateFileInfoDescription(file.getFileInfoId(), "by the admin", adminId);
        underTest.deleteCompleteFileById(file.getFileInfoId(), adminId);
        flushAndClear();
        assertThat(fileInfoRepository.findById(file.getFileInfoId())).isEmpty();
    }

    private void refusesAll(int principalId) {
        assertThatThrownBy(() -> underTest.updateFileInfoDescription(file.getFileInfoId(), "hijacked", principalId))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> underTest.changeFileInfoState(file.getFileInfoId(), -1, principalId))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> underTest.changeFileDetailsState(file.getId(), -1, principalId))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> underTest.deleteCompleteFileById(file.getFileInfoId(), principalId))
                .isInstanceOf(AccessDeniedException.class);

        flushAndClear();
        var stored = fileInfoRepository.findById(file.getFileInfoId()).orElseThrow();
        assertThat(stored.getDescription()).isEqualTo("description of guarded.txt");
        assertThat(stored.getState()).isZero();
        assertThat(fileDetailsRepository.findById(file.getId()).orElseThrow().getState()).isZero();
    }

    private int userWith(FolderPermission permission) {
        User user = userRepository.save(TestData.user());
        List<UserFolderGrant> grants = new ArrayList<>(user.getFolderGrants());
        grants.add(new UserFolderGrant(user, folderRepository.findById(folderId).orElseThrow(), permission));
        user.replaceFolderGrants(grants);
        userRepository.save(user);
        flushAndClear();
        return user.getId();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private FileDetailsDTO upload(String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain",
                ("content of " + fileName).getBytes(StandardCharsets.UTF_8)));
        return underTest.createNewFile(request, adminId, FileService.PUBLIC);
    }
}
