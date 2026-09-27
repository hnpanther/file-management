package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.FixedRole;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.PermissionGroup;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A version's external id on the file page (2.4.0): shown only to whoever holds
 * {@code VIEW_FILE_EXTERNAL_ID} - and to ADMIN, which holds every assignable permission - and
 * absent from the page for everyone else, not merely hidden by a style.
 *
 * <p>It is the id the v1 API takes since 2.4.0, so it is what the person setting up an integration
 * needs to read off a file; it is not what everybody who can open a file needs to see.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "filemanagement.folder-access.enabled=false")
class ExternalIdOnTheFilePageTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FileService fileService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;

    private int principalId;
    private int tagFolderId;

    @BeforeEach
    void setUp() {
        User creator = userRepository.save(TestData.user());
        principalId = creator.getId();
        tagFolderId = FolderFixture.chain(folderRepository, tagGroupRepository, creator).tagId();
    }

    @Test
    @DisplayName("with the permission, every version shows its external id beside a copy button")
    void shownWithThePermission() throws Exception {
        FileDetailsDTO stored = upload("contract.pdf");

        String page = page(stored.getFileInfoId(), PermissionEnum.FILE_INFO_PAGE, PermissionEnum.VIEW_FILE_EXTERNAL_ID);

        assertThat(page)
                .contains("class=\"revision-external-id\"")
                .contains(">" + stored.getExternalId() + "</code>")
                .contains("data-external-id=\"" + stored.getExternalId() + "\"");
    }

    @Test
    @DisplayName("an administrator sees it without being given it")
    void shownToAnAdministrator() throws Exception {
        FileDetailsDTO stored = upload("policy.pdf");

        assertThat(page(stored.getFileInfoId(), PermissionEnum.ADMIN)).contains(stored.getExternalId());
    }

    @Test
    @DisplayName("without it the page does not carry the external id at all - of the version, or of the file")
    void absentWithoutThePermission() throws Exception {
        FileDetailsDTO stored = upload("memo.pdf");

        String page = page(stored.getFileInfoId(), PermissionEnum.FILE_INFO_PAGE, PermissionEnum.DOWNLOAD_FILE);

        assertThat(page)
                .doesNotContain("revision-external-id")
                .doesNotContain(stored.getExternalId())
                .doesNotContain(stored.getFileInfoExternalId());
    }

    @Test
    @DisplayName("ADMIN holds the permission, USER does not, and the role page offers it in a group of its own")
    void whoHoldsIt() {
        assertThat(FixedRole.ADMIN.permissions()).contains(PermissionEnum.VIEW_FILE_EXTERNAL_ID);
        assertThat(FixedRole.USER.permissions()).doesNotContain(PermissionEnum.VIEW_FILE_EXTERNAL_ID);
        assertThat(PermissionGroup.of(PermissionEnum.VIEW_FILE_EXTERNAL_ID)).contains(PermissionGroup.FILE_EXTERNAL_ID);
        assertThat(PermissionGroup.FILE_EXTERNAL_ID.members()).containsExactly(PermissionEnum.VIEW_FILE_EXTERNAL_ID);
    }

    // ---------------------------------------------------------------- helpers

    private String page(int fileInfoId, PermissionEnum... permissions) throws Exception {
        return mockMvc.perform(get("/files/file-info/{id}", fileInfoId).with(user(principal(permissions))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private FileDetailsDTO upload(String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(tagFolderId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "application/octet-stream",
                TestData.bytesFor(fileName)));
        return fileService.createNewFile(request, principalId, FileService.PRIVATE);
    }

    private UserDetailsImpl principal(PermissionEnum... permissions) {
        UserDetailsImpl userDetails = new UserDetailsImpl();
        userDetails.setId(principalId);
        userDetails.setUsername("reader" + principalId);
        userDetails.setPassword("irrelevant");
        userDetails.setEnabled(1);
        userDetails.setState(0);
        userDetails.setLoginType(0);
        userDetails.setPermissions(List.of(permissions));
        return userDetails;
    }
}
