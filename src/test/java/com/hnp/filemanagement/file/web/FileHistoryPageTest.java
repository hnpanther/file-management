package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileHistoryService;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.persistence.FileHistoryQuery;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.ApiKeyCreatedDTO;
import com.hnp.filemanagement.identity.domain.ApiKeyDTO;
import com.hnp.filemanagement.identity.domain.ApiKeyService;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.util.SearchKey;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The file history's pages (2.5.0): the history of every file, its section on a file's page, and
 * an API key's activity - each behind its permission, each a fixed number of statements however
 * many rows it shows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "filemanagement.folder-access.enabled=false")
class FileHistoryPageTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FileService fileService;
    @Autowired
    private FileHistoryService fileHistoryService;
    @Autowired
    private ApiKeyService apiKeyService;
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
        token = "page" + TestData.nextSequence();
    }

    @Test
    @DisplayName("the history page needs FILE_HISTORY_PAGE; with it, a deleted file is listed by name and marked, not linked")
    void theHistoryPage() throws Exception {
        FileDetailsDTO kept = upload(token + "-kept.txt");
        FileDetailsDTO gone = upload(token + "-gone.txt");
        fileService.deleteCompleteFileById(gone.getFileInfoId(), adminId);
        entityManager.flush();

        mockMvc.perform(get("/files/history").with(user(person(PermissionEnum.FILE_INFO_PAGE))))
                .andExpect(status().isForbidden());

        String page = mockMvc.perform(get("/files/history").param("q", token)
                        .with(user(person(PermissionEnum.FILE_HISTORY_PAGE))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page)
                .contains("href=\"/files/file-info/" + kept.getFileInfoId() + "\"")
                .doesNotContain("href=\"/files/file-info/" + gone.getFileInfoId() + "\"")
                .contains(token + "-gone")
                .contains("حذف فایل")
                .contains("history-gone")
                .doesNotContain(kept.getFileInfoExternalId())
                .doesNotContain(kept.getExternalId());
    }

    @Test
    @DisplayName("the filters read from the URL, and one it cannot read is ignored rather than refused")
    void theFilters() throws Exception {
        FileDetailsDTO stored = upload(token + ".txt");
        fileService.updateFileInfoDescription(stored.getFileInfoId(), "desc " + token, adminId);
        entityManager.flush();

        String changes = mockMvc.perform(get("/files/history").param("q", token).param("event", "DESCRIPTION_CHANGED")
                        .with(user(person(PermissionEnum.FILE_HISTORY_PAGE))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        // The badges, not the event filter's options, which name every event.
        assertThat(changes).contains("<span>تغییر توضیح</span>").doesNotContain("<span>بارگذاری فایل</span>");

        mockMvc.perform(get("/files/history").param("event", "NOT_AN_EVENT").param("from", "yesterday")
                        .param("file", "abc").param("page", "-4")
                        .with(user(person(PermissionEnum.FILE_HISTORY_PAGE))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/files/history").param("page", "2147483647")
                        .with(user(person(PermissionEnum.FILE_HISTORY_PAGE))))
                .andExpect(status().isOk());

        String oneFile = mockMvc.perform(get("/files/history").param("file", String.valueOf(stored.getFileInfoId()))
                        .with(user(person(PermissionEnum.FILE_HISTORY_PAGE))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(oneFile).contains("فقط رویدادهای یک فایل").contains(token);
    }

    @Test
    @DisplayName("a file's page shows its history only to whoever may read the history, and links to the rest by number")
    void theFilePageSection() throws Exception {
        FileDetailsDTO stored = upload(token + ".pdf");
        entityManager.flush();

        mockMvc.perform(get("/files/file-info/{id}", stored.getFileInfoId())
                        .with(user(person(PermissionEnum.FILE_INFO_PAGE))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("file-history-title"))));

        mockMvc.perform(get("/files/file-info/{id}", stored.getFileInfoId())
                        .with(user(person(PermissionEnum.FILE_INFO_PAGE, PermissionEnum.FILE_HISTORY_PAGE))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("file-history-title")))
                .andExpect(content().string(containsString("بارگذاری فایل")))
                .andExpect(content().string(containsString("/files/history?file=" + stored.getFileInfoId())))
                .andExpect(content().string(not(containsString(stored.getFileInfoExternalId()))));
    }

    @Test
    @DisplayName("an API key's activity page lists what it uploaded through the API, behind API_KEY_ACTIVITY_PAGE")
    void theKeyActivityPage() throws Exception {
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("کلید " + token);
        request.setFolderGrants(List.of(folderId + ":WRITE"));
        ApiKeyCreatedDTO key = apiKeyService.create(request, adminId);
        mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("multipartFile", token + "-api.txt", "text/plain", TestData.bytesFor("api.txt")))
                        .param("description", "by key").param("folderId", String.valueOf(folderId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.credential()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        upload(token + "-hand.txt");
        entityManager.flush();

        mockMvc.perform(get("/api-keys/{id}/activity", key.id()).with(user(person(PermissionEnum.GET_ALL_API_KEY_PAGE))))
                .andExpect(status().isForbidden());
        String page = mockMvc.perform(get("/api-keys/{id}/activity", key.id())
                        .with(user(person(PermissionEnum.API_KEY_ACTIVITY_PAGE))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains(token + "-api").doesNotContain(token + "-hand")
                .contains("از طریق API با کلید «" + request.getTitle() + "»");
        mockMvc.perform(get("/api-keys/{id}/activity", 999_999).with(user(person(PermissionEnum.API_KEY_ACTIVITY_PAGE))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a page of history is a fixed number of statements - the rows with their keys, and which files still exist - for any number of rows")
    void aFixedNumberOfStatements() {
        for (int i = 0; i < 6; i++) {
            upload(token + i + ".txt");
        }
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            var page = fileHistoryService.search(new FileHistoryQuery(SearchKey.forSearch(token), null, null, null, null, null, null),
                    0, 50, adminId);
            assertThat(page.entries()).hasSize(6);
            // Folder access is off here, so asking it costs nothing: a search by name asks for its
            // own plan (FileHistorySearch), then the page and the live files.
            assertThat(statistics.getPrepareStatementCount()).as("statements prepared").isEqualTo(3);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    private FileDetailsDTO upload(String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("page " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain", TestData.bytesFor(fileName)));
        return fileService.createNewFile(request, adminId, FileService.PUBLIC);
    }

    private UserDetailsImpl person(PermissionEnum... permissions) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(adminId);
        principal.setUsername("reader" + adminId);
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(0);
        principal.setPermissions(List.of(permissions));
        return principal;
    }
}
