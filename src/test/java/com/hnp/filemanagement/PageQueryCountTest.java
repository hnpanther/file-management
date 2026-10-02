package com.hnp.filemanagement;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.domain.FileUploadDTO;
import com.hnp.filemanagement.file.domain.ShareLinkService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The busiest pages cost the same number of statements whatever their rows (2.7.4) - the file
 * list, the public files, the explorer's folder content, a file's page and the share links. Each
 * is counted with a few rows and again with several times as many; a difference is a query per
 * row (N+1), which on a page of a hundred files is a hundred round trips.
 * {@code ListQueryCountTest} does the same for the user, tag group and API key lists.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "filemanagement.folder-access.enabled=false")
class PageQueryCountTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FileService fileService;
    @Autowired
    private ShareLinkService shareLinkService;
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

    private int adminId;
    private int folderId;
    private String token;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        adminId = userRepository.save(admin).getId();
        folderId = FolderFixture.chain(folderRepository, tagGroupRepository, admin).tagId();
        token = "count" + TestData.nextSequence();
    }

    @Test
    @DisplayName("the file list, the public files and the explorer's folder content: as many statements for 9 files as for 3")
    void theLists() throws Exception {
        upload(3);
        long[] few = {
                statementsOf(get("/files/file-info").param("search", token).with(user(person(PermissionEnum.GET_ALL_FILE_INFO_PAGE)))),
                statementsOf(get("/files/public-files").param("search", token)),
                statementsOf(get("/resource/folders/children").param("folderId", String.valueOf(folderId))
                        .with(user(person(PermissionEnum.FILE_EXPLORER_PAGE))).accept(MediaType.APPLICATION_JSON))};
        upload(6);
        long[] many = {
                statementsOf(get("/files/file-info").param("search", token).with(user(person(PermissionEnum.GET_ALL_FILE_INFO_PAGE)))),
                statementsOf(get("/files/public-files").param("search", token)),
                statementsOf(get("/resource/folders/children").param("folderId", String.valueOf(folderId))
                        .with(user(person(PermissionEnum.FILE_EXPLORER_PAGE))).accept(MediaType.APPLICATION_JSON))};

        assertThat(many).as("file list, public files, folder content").containsExactly(few);
    }

    @Test
    @DisplayName("a file's page, its history and downloads included: as many statements for 6 revisions as for 2")
    void theFilePage() throws Exception {
        FileDetailsDTO first = upload(token + ".pdf");
        addVersion(first, 2);
        long few = statementsOf(filePage(first));
        for (int version = 3; version <= 6; version++) {
            addVersion(first, version);
        }
        long many = statementsOf(filePage(first));

        assertThat(many).isEqualTo(few);
    }

    @Test
    @DisplayName("the share links page: as many statements for 12 links as for 3")
    void theShareLinks() throws Exception {
        FileDetailsDTO revision = upload(token + ".txt");
        for (int i = 0; i < 3; i++) {
            shareLinkService.create(revision.getId(), 5, null, null, adminId);
        }
        long few = statementsOf(get("/files/share-links").with(user(person(PermissionEnum.SHARE_LINKS_PAGE, PermissionEnum.REVOKE_SHARE_LINK))));
        for (int i = 0; i < 9; i++) {
            shareLinkService.create(revision.getId(), 5, null, null, adminId);
        }
        long many = statementsOf(get("/files/share-links").with(user(person(PermissionEnum.SHARE_LINKS_PAGE, PermissionEnum.REVOKE_SHARE_LINK))));

        assertThat(many).isEqualTo(few);
    }

    // ---------------------------------------------------------------- helpers

    private MockHttpServletRequestBuilder filePage(FileDetailsDTO revision) {
        return get("/files/file-info/{id}", revision.getFileInfoId())
                .with(user(person(PermissionEnum.FILE_INFO_PAGE, PermissionEnum.DOWNLOAD_FILE,
                        PermissionEnum.FILE_HISTORY_PAGE, PermissionEnum.FILE_DOWNLOADS_PAGE)));
    }

    private long statementsOf(MockHttpServletRequestBuilder request) throws Exception {
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            mockMvc.perform(request).andExpect(status().isOk());
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    private void upload(int count) {
        for (int i = 0; i < count; i++) {
            upload(token + TestData.nextSequence() + ".txt");
        }
    }

    private FileDetailsDTO upload(String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("count " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain", TestData.bytesFor(fileName)));
        return fileService.createNewFile(request, adminId, FileService.PUBLIC);
    }

    private void addVersion(FileDetailsDTO sample, int version) {
        String name = sample.getFileName().substring(0, sample.getFileName().lastIndexOf('.'));
        FileUploadDTO request = new FileUploadDTO();
        request.setFileId(sample.getFileInfoId());
        request.setFileDetailsId(sample.getId());
        request.setFileName(name);
        request.setFileDetailsDescription("v" + version);
        request.setVersion(version);
        request.setType("version");
        request.setMultipartFile(new MockMultipartFile("file", name + ".pdf", "application/pdf", TestData.bytesFor(name + ".pdf")));
        fileService.createNewFileDetails(request, adminId);
    }

    private UserDetailsImpl person(PermissionEnum... permissions) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(adminId);
        principal.setUsername("counter" + adminId);
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(0);
        principal.setPermissions(List.of(permissions));
        return principal;
    }
}
