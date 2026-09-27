package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.file.persistence.FileHistoryQuery;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.folder.domain.UserFolderGrant;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.shared.util.SearchKey;
import com.hnp.filemanagement.shared.util.SearchTerms;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The file history, read under folder access (2.5.0): an event is shown to a reader whose grants
 * reach the folder the file was in when it happened; an administrator sees everything, and a
 * reader with no grant at all sees nothing - not the names of files they could never open.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FileHistoryFolderAccessTest extends DatabaseSupport {

    @Autowired
    private FileService fileService;
    @Autowired
    private FileHistoryService underTest;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("a restricted reader sees the events of the folders they may read, and nothing of the others")
    void onlyReadableFolders() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        FolderFixture.Chain chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        Folder other = FolderFixture.tag(folderRepository, chain.subCategory(), admin, "Other" + TestData.nextSequence());

        String token = "acc" + TestData.nextSequence();
        upload(chain.tagId(), token + "here.txt", admin.getId());
        upload(other.getId(), token + "there.txt", admin.getId());

        User reader = userRepository.save(TestData.user());
        grant(reader, chain.tagId(), FolderPermission.READ);
        User stranger = userRepository.save(TestData.user());
        entityManager.flush();
        entityManager.clear();

        FileHistoryQuery query = new FileHistoryQuery(SearchTerms.escapeLike(SearchKey.forSearch(token)),
                null, null, null, null, null, null);
        assertThat(underTest.search(query, 0, 50, admin.getId()).entries()).hasSize(2);
        assertThat(underTest.search(query, 0, 50, reader.getId()).entries())
                .extracting(FileHistoryEntry::fileName).containsExactly(token + "here");
        assertThat(underTest.search(query, 0, 50, stranger.getId()).entries()).isEmpty();
    }

    private void grant(User user, int folderId, FolderPermission permission) {
        List<UserFolderGrant> grants = new ArrayList<>(user.getFolderGrants());
        grants.add(new UserFolderGrant(user, folderRepository.findById(folderId).orElseThrow(), permission));
        user.replaceFolderGrants(grants);
        userRepository.save(user);
    }

    private void upload(int folderId, String fileName, int userId) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("access " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain", TestData.bytesFor(fileName)));
        fileService.createNewFile(request, userId, FileService.PUBLIC);
    }
}
