package com.hnp.filemanagement.content;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.web.GlobalGeneralLogging;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Search in contents on the page and on API v1, and the status page (roadmap 11.2): the words found
 * marked and everything a document holds escaped; each closed to whoever lacks its permission; the
 * search term never written to the log.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.content-search.enabled=true"})
class ContentSearchPagesTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FileService fileService;
    @Autowired
    private FileContentRepository repository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private Folder folder;
    private String run;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        adminId = admin.getId();
        folder = FolderFixture.chain(folderRepository, tagGroupRepository, admin).tag();
        run = "واژه" + Long.toString(TestData.nextSequence() + 1000, 36).replaceAll("[0-9]", "");
    }

    @Test
    @DisplayName("the page: the result, its page and its snippet with the word marked; the document's text escaped")
    void thePage() throws Exception {
        FileDetailsDTO file = upload("report.txt");
        read(file.getId(), new FileContentRepository.PageRow(2, 0, "PAGE", null, "OCR", null,
                "<script>alert(1)</script> متن " + run + " پایان", ContentFolding.fold("<script>alert(1)</script> متن " + run + " پایان")));

        String page = page(mockMvc.perform(get("/files/content-search").param("q", run)
                .with(user(person(PermissionEnum.SEARCH_FILE_CONTENTS)))), 200);
        assertThat(page).contains("<mark>" + run + "</mark>", "صفحهٔ 2", "از روی تصویر صفحه", "/files/file-info/" + file.getFileInfoId())
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>alert(1)");

        String empty = page(mockMvc.perform(get("/files/content-search").param("q", "!!!")
                .with(user(person(PermissionEnum.SEARCH_FILE_CONTENTS)))), 200);
        assertThat(empty).contains("یک کلمه برای جستجو بنویسید");
        String none = page(mockMvc.perform(get("/files/content-search").param("q", run + "نیست")
                .with(user(person(PermissionEnum.SEARCH_FILE_CONTENTS)))), 200);
        assertThat(none).contains("content-search-empty");
        assertThat(page(mockMvc.perform(get("/files/content-search").with(user(person(PermissionEnum.SEARCH_FILE_CONTENTS)))), 200))
                .doesNotContain("content-search-results");
    }

    @Test
    @DisplayName("the menu shows the search to whoever may use it")
    void theMenu() throws Exception {
        String page = page(mockMvc.perform(get("/files/content-search").with(user(person(PermissionEnum.SEARCH_FILE_CONTENTS)))), 200);
        assertThat(page).contains("href=\"/files/content-search\"");
    }

    @Test
    @DisplayName("API v1: the results as JSON, each page with its snippet and the offsets of the words found")
    void theApi() throws Exception {
        FileDetailsDTO file = upload("api.txt");
        read(file.getId(), new FileContentRepository.PageRow(0, 0, "WHOLE", null, "TEXT", null, "متن " + run,
                ContentFolding.fold("متن " + run)));
        String json = page(mockMvc.perform(get("/api/v1/files/content-search").param("q", run)
                .with(user(person(PermissionEnum.API_SEARCH_FILE_CONTENTS)))), 200);
        assertThat(json).contains("\"fileDetailsNumber\":" + file.getId(), "\"latestVersion\":true", "\"unit\":\"WHOLE\"",
                "\"snippet\":\"متن " + run + "\"", "\"matches\":[[4," + run.length() + "]]", "\"hasNext\":false");
        assertThat(page(mockMvc.perform(get("/api/v1/files/content-search").param("q", "...")
                .with(user(person(PermissionEnum.API_SEARCH_FILE_CONTENTS)))), 400)).isNotBlank();
    }

    @Test
    @DisplayName("each closed to whoever lacks its permission")
    void closed() throws Exception {
        UserDetailsImpl nobody = person(PermissionEnum.FILE_INFO_PAGE);
        assertThat(mockMvc.perform(get("/files/content-search").param("q", "x").with(user(nobody))).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        assertThat(mockMvc.perform(get("/api/v1/files/content-search").param("q", "x").with(user(nobody))).andReturn().getResponse()
                .getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(get("/settings/content-extraction").with(user(nobody))).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        assertThat(mockMvc.perform(post("/settings/content-extraction/retry-failed").with(csrf())
                .with(user(person(PermissionEnum.CONTENT_EXTRACTION_PAGE)))).andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("the status page: why reading does not run, the counts, the failures with their reason - and a retry")
    void theStatusPage() throws Exception {
        FileDetailsDTO file = upload("failed.txt");
        entityManager.flush();
        jdbc.update("UPDATE file_content SET state = 'FAILED', attempts = 3, reason = 'OCR read nothing', read_at = now() WHERE file_details_id = ?",
                file.getId());
        String page = page(mockMvc.perform(get("/settings/content-extraction")
                .with(user(person(PermissionEnum.CONTENT_EXTRACTION_PAGE, PermissionEnum.RETRY_CONTENT_EXTRACTION)))), 200);
        assertThat(page).contains("reading is switched off", "OCR read nothing", "content-failures",
                "/settings/content-extraction/" + file.getId() + "/retry");

        String retried = page(mockMvc.perform(post("/settings/content-extraction/" + file.getId() + "/retry").with(csrf())
                .with(user(person(PermissionEnum.CONTENT_EXTRACTION_PAGE, PermissionEnum.RETRY_CONTENT_EXTRACTION)))), 200);
        assertThat(retried).contains("دوباره در صف قرار گرفت");
        assertThat(jdbc.queryForObject("SELECT state FROM file_content WHERE file_details_id = ?", String.class, file.getId()))
                .isEqualTo("PENDING");
        String again = page(mockMvc.perform(post("/settings/content-extraction/" + file.getId() + "/retry").with(csrf())
                .with(user(person(PermissionEnum.CONTENT_EXTRACTION_PAGE, PermissionEnum.RETRY_CONTENT_EXTRACTION)))), 200);
        assertThat(again).contains("این نسخه خواندن ناموفقی ندارد");
    }

    @Test
    @DisplayName("the search term is never logged: q is masked wherever it is")
    void theTermIsNotLogged() {
        assertThat(GlobalGeneralLogging.maskSecrets("/files/content-search?q=0012345678&page=1"))
                .isEqualTo("/files/content-search?q=***&page=1");
        assertThat(GlobalGeneralLogging.maskSecrets("/api/v1/files/content-search?size=5&q=%D8%B9%D9%84%DB%8C"))
                .isEqualTo("/api/v1/files/content-search?size=5&q=***");
    }

    // ================================================================ helpers

    private void read(int fileDetailsId, FileContentRepository.PageRow... pages) {
        entityManager.flush();
        jdbc.update("UPDATE file_content SET state = 'READING', lease_until = now() + interval '1 hour' WHERE file_details_id = ?",
                fileDetailsId);
        assertThat(repository.finish(fileDetailsId, new FileContentRepository.Outcome("DONE", "TEXT", "text/plain", null,
                false, null, null, 10), List.of(pages), Instant.now())).isTrue();
    }

    private FileDetailsDTO upload(String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription(fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folder.getId());
        String unique = fileName.replace(".txt", "-" + TestData.nextSequence() + ".txt");
        request.setMultipartFile(new MockMultipartFile("file", unique, "text/plain", ("content " + unique).getBytes(StandardCharsets.UTF_8)));
        FileDetailsDTO uploaded = fileService.createNewFile(request, adminId, FileService.PRIVATE);
        entityManager.flush();
        return uploaded;
    }

    private static String page(ResultActions result, int status) throws Exception {
        MockHttpServletResponse response = result.andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as(body.length() > 600 ? body.substring(0, 600) : body).isEqualTo(status);
        return body;
    }

    /** A person who is the test's administrator - every folder theirs - holding only these permissions. */
    private UserDetailsImpl person(PermissionEnum... permissions) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(adminId);
        principal.setUsername("person" + adminId);
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(0);
        principal.setPermissions(List.of(permissions));
        return principal;
    }
}
