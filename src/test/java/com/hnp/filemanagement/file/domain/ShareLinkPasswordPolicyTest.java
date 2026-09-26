package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The installation that insists on a password: a link without one is refused (roadmap 10.5). */
@ServiceIntegrationTest
@TestPropertySource(properties = "filemanagement.share-links.password=REQUIRED")
class ShareLinkPasswordPolicyTest extends DatabaseSupport {

    @Autowired
    private ShareLinkService underTest;
    @Autowired
    private FileService fileService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("under REQUIRED a blank password is a 400 and a real one makes the link")
    void aPasswordIsMandatory() {
        Role adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        int adminId = userRepository.save(admin).getId();
        FolderFixture.Chain chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("d");
        request.setFileNameDescription("guarded.txt");
        request.setFolderId(chain.tagId());
        request.setMultipartFile(new MockMultipartFile("guarded.txt", "guarded.txt", "text/plain", TestData.bytesFor("guarded.txt")));
        FileDetailsDTO revision = fileService.createNewFile(request, adminId, 1);

        assertThat(underTest.passwordRequired()).isTrue();
        assertThatThrownBy(() -> underTest.create(revision.getId(), 5, null, null, adminId))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("password");
        assertThatThrownBy(() -> underTest.create(revision.getId(), 5, "   ", null, adminId))
                .isInstanceOf(InvalidDataException.class);
        assertThat(underTest.create(revision.getId(), 5, "pw", null, adminId).passwordProtected()).isTrue();
    }
}
