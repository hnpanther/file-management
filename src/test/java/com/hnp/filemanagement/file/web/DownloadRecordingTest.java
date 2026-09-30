package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.file.domain.DownloadRecorder;
import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.domain.ShareLinkDTO;
import com.hnp.filemanagement.file.domain.ShareLinkService;
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
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.MutableClock;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The record of downloads (2.7.0), end to end: every way a file leaves the application writes one
 * row saying who - the person, the API key, or nobody - from which address, through which channel;
 * what is not a download (a {@code HEAD}, the rest of a ranged read, the same download again within
 * the minute) writes none; and the pages that read it are behind their permission.
 *
 * <p>The recorder writes on its own thread; a test calls {@link DownloadRecorder#flush()} to have
 * the rows now. Called on the test's thread it joins the test's transaction, so what it writes is
 * rolled back with everything else - and what the writer thread got to first is committed, which is
 * why every assertion is about this test's own file ids, never a count of the table.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(MutableClock.Config.class)
@TestPropertySource(properties = "filemanagement.folder-access.enabled=false")
class DownloadRecordingTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FileService fileService;
    @Autowired
    private ShareLinkService shareLinkService;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private DownloadRecorder downloadRecorder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
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
    @Autowired
    private MutableClock clock;

    private int adminId;
    private String adminName;
    private FolderFixture.Chain chain;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        adminId = admin.getId();
        adminName = admin.getUsername();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
    }

    // ---------------------------------------------------------------- what is recorded

    @Test
    @DisplayName("the file page's download is recorded with the person, the address and the revision; a preview is its own channel")
    void aSignedInDownload() throws Exception {
        FileDetailsDTO revision = upload("report" + TestData.nextSequence() + ".pdf", FileService.PRIVATE);

        mockMvc.perform(get(pageDownload(revision)).with(user(person(PermissionEnum.DOWNLOAD_FILE))).with(from("192.168.10.7")))
                .andExpect(status().isOk());
        mockMvc.perform(get(pageDownload(revision)).param("inline", "1")
                        .with(user(person(PermissionEnum.DOWNLOAD_FILE))).with(from("192.168.10.7")))
                .andExpect(status().isOk());

        List<Map<String, Object>> rows = rowsOf(revision.getFileInfoId());
        assertThat(rows).extracting(row -> row.get("channel")).containsExactly("PAGE", "PREVIEW");
        assertThat(rows.getFirst())
                .containsEntry("file_details_id", revision.getId())
                .containsEntry("file_name", revision.getFileName())
                .containsEntry("version", 1)
                .containsEntry("folder_id", chain.tagId())
                .containsEntry("user_id", adminId)
                .containsEntry("username", adminName)
                .containsEntry("client_ip", "192.168.10.7")
                .containsEntry("api_key_id", null)
                .containsEntry("share_link_id", null);
    }

    @Test
    @DisplayName("a visitor who is not signed in is recorded by address alone, through the public files")
    void anAnonymousPublicDownload() throws Exception {
        FileDetailsDTO revision = upload("public" + TestData.nextSequence() + ".txt", FileService.PUBLIC);

        mockMvc.perform(get("/files/public-download/{id}", revision.getId()).with(from("10.1.2.3")))
                .andExpect(status().isOk());

        assertThat(rowsOf(revision.getFileInfoId())).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("channel", "PUBLIC")
                .containsEntry("user_id", null)
                .containsEntry("username", null)
                .containsEntry("client_ip", "10.1.2.3"));
    }

    @Test
    @DisplayName("a share link's download names the link and the address, and nobody")
    void aShareLinkDownload() throws Exception {
        FileDetailsDTO revision = upload("shared" + TestData.nextSequence() + ".txt", FileService.PRIVATE);
        ShareLinkDTO link = shareLinkService.create(revision.getId(), 10, null, null, adminId);

        mockMvc.perform(post("/share/{token}", link.token()).with(csrf()).with(from("172.16.4.4")))
                .andExpect(status().isOk());

        assertThat(rowsOf(revision.getFileInfoId())).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("channel", "SHARE_LINK")
                .containsEntry("share_link_id", link.id())
                .containsEntry("user_id", null)
                .containsEntry("client_ip", "172.16.4.4"));
    }

    @Test
    @DisplayName("an API key's download through v1 names the key and its creator, whichever way the revision is addressed")
    void anApiV1Download() throws Exception {
        FileDetailsDTO revision = upload("api" + TestData.nextSequence() + ".txt", FileService.PRIVATE);
        ApiKeyCreatedDTO key = key(chain.tagId() + ":READ");

        mockMvc.perform(get("/api/v1/files/file-details/{id}/download", revision.getExternalId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.credential()).with(from("10.20.30.40")))
                .andExpect(status().isOk());
        // By the file and its version: the same revision through the same channel - one download.
        mockMvc.perform(get("/api/v1/files/file-info/{id}/download", revision.getFileInfoExternalId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.credential()).with(from("10.20.30.40")))
                .andExpect(status().isOk());

        assertThat(rowsOf(revision.getFileInfoId())).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("channel", "API_V1")
                .containsEntry("api_key_id", key.id())
                .containsEntry("user_id", adminId)
                .containsEntry("client_ip", "10.20.30.40"));
    }

    @Test
    @DisplayName("a v2 GET is recorded as API_V2 with its key; its HEAD and its metadata are not downloads")
    void anApiV2Download() throws Exception {
        ApiKeyCreatedDTO key = key(chain.tagId() + ":WRITE");
        String name = "object" + TestData.nextSequence();
        String prefix = chain.subCategory().getName() + "/" + chain.tag().getName();
        String bucket = chain.category().getName();
        mockMvc.perform(put("/api/v2/" + bucket + "/" + prefix + "/" + name + "/" + name + ".txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.credential())
                        .contentType(MediaType.TEXT_PLAIN).content("v2 bytes".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isCreated());
        String stored = "/api/v2/" + bucket + "/" + prefix + "/" + name + "/v1/" + name + ".txt";

        mockMvc.perform(head(stored).header(HttpHeaders.AUTHORIZATION, "Bearer " + key.credential()))
                .andExpect(status().isOk());
        mockMvc.perform(get(stored).param("metadata", "").header(HttpHeaders.AUTHORIZATION, "Bearer " + key.credential()))
                .andExpect(status().isOk());
        mockMvc.perform(get(stored).header(HttpHeaders.AUTHORIZATION, "Bearer " + key.credential()).with(from("10.0.0.9")))
                .andExpect(status().isOk())
                .andExpect(content().string("v2 bytes"));

        Integer fileInfoId = jdbcTemplate.queryForObject(
                "SELECT file_info_id FROM file_details WHERE file_name = ?", Integer.class, name + ".txt");
        assertThat(rowsOf(fileInfoId)).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("channel", "API_V2")
                .containsEntry("api_key_id", key.id())
                .containsEntry("client_ip", "10.0.0.9"));
    }

    // ---------------------------------------------------------------- what is not

    @Test
    @DisplayName("a HEAD and a range past the first byte are not downloads; a range from byte 0 is one")
    void headersAndLaterRangesAreNotDownloads() throws Exception {
        FileDetailsDTO revision = upload("ranged" + TestData.nextSequence() + ".pdf", FileService.PRIVATE);

        mockMvc.perform(head(pageDownload(revision)).with(user(person(PermissionEnum.DOWNLOAD_FILE))))
                .andExpect(status().isOk());
        mockMvc.perform(get(pageDownload(revision)).header(HttpHeaders.RANGE, "bytes=5-9")
                        .with(user(person(PermissionEnum.DOWNLOAD_FILE))))
                .andExpect(status().isPartialContent());
        mockMvc.perform(get(pageDownload(revision)).header(HttpHeaders.RANGE, "bytes=-4")
                        .with(user(person(PermissionEnum.DOWNLOAD_FILE))))
                .andExpect(status().isPartialContent());
        assertThat(rowsOf(revision.getFileInfoId())).isEmpty();

        mockMvc.perform(get(pageDownload(revision)).header(HttpHeaders.RANGE, "bytes=0-3")
                        .with(user(person(PermissionEnum.DOWNLOAD_FILE))))
                .andExpect(status().isPartialContent());
        assertThat(rowsOf(revision.getFileInfoId())).hasSize(1);
    }

    @Test
    @DisplayName("the same download again within a minute is one record; after the minute, or by somebody else, it is another")
    void repeatsWithinTheWindow() throws Exception {
        FileDetailsDTO revision = upload("twice" + TestData.nextSequence() + ".txt", FileService.PUBLIC);

        mockMvc.perform(get("/files/public-download/{id}", revision.getId()).with(from("10.5.5.5"))).andExpect(status().isOk());
        mockMvc.perform(get("/files/public-download/{id}", revision.getId()).with(from("10.5.5.5"))).andExpect(status().isOk());
        mockMvc.perform(get("/files/public-download/{id}", revision.getId()).with(from("10.5.5.6"))).andExpect(status().isOk());
        assertThat(rowsOf(revision.getFileInfoId())).extracting(row -> row.get("client_ip"))
                .containsExactly("10.5.5.5", "10.5.5.6");

        clock.advance(Duration.ofSeconds(61));
        mockMvc.perform(get("/files/public-download/{id}", revision.getId()).with(from("10.5.5.5"))).andExpect(status().isOk());
        assertThat(rowsOf(revision.getFileInfoId())).extracting(row -> row.get("client_ip"))
                .containsExactly("10.5.5.5", "10.5.5.6", "10.5.5.5");
    }

    @Test
    @DisplayName("a refused download is not a download")
    void aRefusalIsNotRecorded() throws Exception {
        FileDetailsDTO revision = upload("refused" + TestData.nextSequence() + ".txt", FileService.PRIVATE);

        mockMvc.perform(get("/files/public-download/{id}", revision.getId())).andExpect(status().isNotFound());
        mockMvc.perform(get(pageDownload(revision)).with(user(person(PermissionEnum.FILE_INFO_PAGE))))
                .andExpect(status().isForbidden());

        assertThat(rowsOf(revision.getFileInfoId())).isEmpty();
    }

    // ---------------------------------------------------------------- the pages

    @Test
    @DisplayName("the downloads page needs FILE_DOWNLOADS_PAGE, and its filters narrow by file, person, address, channel and key")
    void theDownloadsPage() throws Exception {
        String token = "dl" + TestData.nextSequence();
        FileDetailsDTO revision = upload(token + ".txt", FileService.PUBLIC);
        ApiKeyCreatedDTO key = key(chain.tagId() + ":READ");
        mockMvc.perform(get("/files/public-download/{id}", revision.getId()).with(from("10.7.7.7"))).andExpect(status().isOk());
        mockMvc.perform(get(pageDownload(revision)).with(user(person(PermissionEnum.DOWNLOAD_FILE))).with(from("10.8.8.8")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/files/file-details/{id}/download", revision.getExternalId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.credential()).with(from("10.9.9.9")))
                .andExpect(status().isOk());
        downloadRecorder.flush();
        String file = String.valueOf(revision.getFileInfoId());

        mockMvc.perform(get("/files/downloads").with(user(person(PermissionEnum.FILE_HISTORY_PAGE))))
                .andExpect(status().isForbidden());

        assertThat(page(Map.of("file", file))).contains(token).contains("10.7.7.7", "10.8.8.8", "10.9.9.9")
                .contains("بدون ورود").contains(adminName).contains("از طریق API با کلید «key ");
        assertThat(page(Map.of("file", file, "ip", "10.7.7.7"))).contains("10.7.7.7").doesNotContain("10.8.8.8", "10.9.9.9");
        assertThat(page(Map.of("file", file, "channel", "PAGE"))).contains("10.8.8.8").doesNotContain("10.7.7.7", "10.9.9.9");
        assertThat(page(Map.of("file", file, "user", adminName.toUpperCase()))).contains("10.8.8.8", "10.9.9.9")
                .doesNotContain("10.7.7.7");
        assertThat(page(Map.of("file", file, "user", "nobody-" + token))).contains("دانلودی پیدا نشد.").doesNotContain("10.8.8.8");
        assertThat(page(Map.of("key", String.valueOf(key.id())))).contains("10.9.9.9").doesNotContain("10.8.8.8");
        assertThat(page(Map.of("file", file, "from", "2026-09-23"))).contains("دانلودی پیدا نشد.");
        assertThat(page(Map.of("file", file, "from", "2026-09-22", "to", "2026-09-22"))).contains("10.7.7.7");

        // What a person may have typed into the URL is ignored, not refused.
        mockMvc.perform(get("/files/downloads").param("channel", "NOT_A_CHANNEL").param("from", "yesterday")
                        .param("file", "abc").param("key", "x").param("page", "-3")
                        .with(user(person(PermissionEnum.FILE_DOWNLOADS_PAGE))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/files/downloads").param("page", "2147483647")
                        .with(user(person(PermissionEnum.FILE_DOWNLOADS_PAGE))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a deleted file's downloads are still listed, by name and marked, not linked")
    void aDeletedFileKeepsItsDownloads() throws Exception {
        String token = "gone" + TestData.nextSequence();
        FileDetailsDTO revision = upload(token + ".txt", FileService.PUBLIC);
        mockMvc.perform(get("/files/public-download/{id}", revision.getId())).andExpect(status().isOk());
        downloadRecorder.flush();
        fileService.deleteCompleteFileById(revision.getFileInfoId(), adminId);
        entityManager.flush();

        assertThat(page(Map.of("file", String.valueOf(revision.getFileInfoId()))))
                .contains(token).contains("history-gone")
                .doesNotContain("href=\"/files/file-info/" + revision.getFileInfoId() + "\"");
    }

    @Test
    @DisplayName("a file's page shows its downloads only to whoever may read the downloads")
    void theFilePageSection() throws Exception {
        FileDetailsDTO revision = upload("section" + TestData.nextSequence() + ".txt", FileService.PUBLIC);
        mockMvc.perform(get("/files/public-download/{id}", revision.getId()).with(from("10.3.3.3"))).andExpect(status().isOk());
        downloadRecorder.flush();

        mockMvc.perform(get("/files/file-info/{id}", revision.getFileInfoId()).with(user(person(PermissionEnum.FILE_INFO_PAGE))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("file-downloads-title"))));
        mockMvc.perform(get("/files/file-info/{id}", revision.getFileInfoId())
                        .with(user(person(PermissionEnum.FILE_INFO_PAGE, PermissionEnum.FILE_DOWNLOADS_PAGE))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("file-downloads-title")))
                .andExpect(content().string(containsString("10.3.3.3")))
                .andExpect(content().string(containsString("/files/downloads?file=" + revision.getFileInfoId())));
    }

    // ---------------------------------------------------------------- helpers

    private String page(Map<String, String> filters) throws Exception {
        var request = get("/files/downloads").with(user(person(PermissionEnum.FILE_DOWNLOADS_PAGE)));
        filters.forEach(request::param);
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private List<Map<String, Object>> rowsOf(int fileInfoId) {
        downloadRecorder.flush();
        return jdbcTemplate.queryForList("""
                SELECT channel, file_details_id, file_name, version, folder_id, user_id, username,
                       api_key_id, share_link_id, client_ip
                FROM file_download WHERE file_info_id = ? ORDER BY id""", fileInfoId);
    }

    private static String pageDownload(FileDetailsDTO revision) {
        return "/files/file-info/" + revision.getFileInfoId() + "/file-details/" + revision.getId() + "/download";
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private ApiKeyCreatedDTO key(String... grants) {
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("key " + TestData.nextSequence());
        request.setFolderGrants(List.of(grants));
        return apiKeyService.create(request, adminId);
    }

    private FileDetailsDTO upload(String fileName, int visibility) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("downloads " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(chain.tagId());
        request.setMultipartFile(new MockMultipartFile("file", fileName, "application/octet-stream", TestData.bytesFor(fileName)));
        return fileService.createNewFile(request, adminId, visibility);
    }

    private UserDetailsImpl person(PermissionEnum... permissions) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(adminId);
        principal.setUsername(adminName);
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(0);
        principal.setPermissions(List.of(permissions));
        return principal;
    }
}
