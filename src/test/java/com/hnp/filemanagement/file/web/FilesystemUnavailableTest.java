package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
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
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The filesystem backend failing to write (2.7.1, issue 100): the same 503 with {@code Retry-After}
 * as an object store that does not answer - a full disk, a share that went away. Here the storage
 * root is a file rather than a directory, so nothing can be created under it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "filemanagement.base-dir=./target/storage-root-that-is-a-file",
        "filemanagement.folder-access.enabled=false"})
class FilesystemUnavailableTest extends DatabaseSupport {

    private static final Path ROOT = Path.of("./target/storage-root-that-is-a-file");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;

    /** StorageRootSupport has just recreated the root, empty: a file takes its place. */
    @BeforeEach
    void aFileWhereTheRootShouldBe() throws IOException {
        Files.deleteIfExists(ROOT);
        Files.writeString(ROOT, "not a directory");
    }

    @Test
    @DisplayName("an upload the disk cannot take is a 503 with Retry-After")
    void anUnwritableRootIsA503() throws Exception {
        User owner = userRepository.save(TestData.user());
        int folderId = FolderFixture.chain(folderRepository, tagGroupRepository, owner).tagId();
        String name = "nowhere" + TestData.nextSequence();

        mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("multipartFile", name + ".txt", "text/plain", TestData.bytesFor(name + ".txt")))
                        .param("description", "onto a broken disk").param("folderId", String.valueOf(folderId))
                        .with(user(principal(owner.getId()))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
                .andExpect(jsonPath("$.title").value("StorageUnavailable"));

        // That the refused upload leaves no row is S3OutageTest's: there the request commits or
        // rolls back on its own, and here it runs in this test's transaction.
    }

    private static UserDetailsImpl principal(int userId) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(userId);
        principal.setUsername("disk" + userId);
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(0);
        principal.setPermissions(List.of(PermissionEnum.API_SAVE_NEW_FILE));
        return principal;
    }
}
