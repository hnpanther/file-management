package com.hnp.filemanagement.content;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.domain.FileUploadDTO;
import com.hnp.filemanagement.folder.domain.Folder;
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
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * How each version's contents were read, on the file's own page (2.16.0): beside the external id, for
 * whoever looks after the reading ({@code CONTENT_EXTRACTION_PAGE}, as the status page) and absent from
 * the page for everyone else; a failure's reason, a version not yet queued, and a failed reading queued
 * again from the page, which answers with the page.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.content-search.enabled=true"})
class ReadingOnTheFilePageTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FileService fileService;
    @Autowired
    private ContentExtractionService contentExtractionService;
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

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        adminId = admin.getId();
        folder = FolderFixture.chain(folderRepository, tagGroupRepository, admin).tag();
    }

    @Test
    @DisplayName("each version's reading: done with its pages and characters, failed with its reason and a retry, one not queued said so")
    void eachVersion() throws Exception {
        FileDetailsDTO first = upload("scan.txt");
        int second = nextVersion(first);
        int third = nextVersion(first);
        jdbc.update("""
                UPDATE file_content SET state = 'DONE', lane = 'OCR', detected_type = 'application/pdf', pages = 12, ocr_pages = 3,
                                        characters = 4210, read_at = now() WHERE file_details_id = ?""", first.getId());
        jdbc.update("""
                UPDATE file_content SET state = 'FAILED', attempts = 3, reason = 'OCR read nothing: handwriting', read_at = now()
                WHERE file_details_id = ?""", second);
        jdbc.update("DELETE FROM file_content WHERE file_details_id = ?", third);

        String page = page(first.getFileInfoId(), PermissionEnum.FILE_INFO_PAGE, PermissionEnum.CONTENT_EXTRACTION_PAGE,
                PermissionEnum.RETRY_CONTENT_EXTRACTION);

        assertThat(page).contains("data-reading-state=\"DONE\"", "data-reading-state=\"FAILED\"", "data-reading-state=\"NONE\"")
                .contains("خوانده شد", ">4210<", "application/pdf", "ناموفق", "OCR read nothing: handwriting", "در صف نیست")
                .contains("/settings/content-extraction/" + second + "/retry?file=" + first.getFileInfoId())
                .doesNotContain("/settings/content-extraction/" + first.getId() + "/retry");
        assertThat(page(first.getFileInfoId(), PermissionEnum.FILE_INFO_PAGE, PermissionEnum.CONTENT_EXTRACTION_PAGE))
                .as("no retry without its permission").contains("OCR read nothing").doesNotContain("/retry?file=");
    }

    @Test
    @DisplayName("without CONTENT_EXTRACTION_PAGE the page carries nothing of the reading; an administrator sees it")
    void onlyForWhoeverLooksAfterTheReading() throws Exception {
        FileDetailsDTO file = upload("memo.txt");
        jdbc.update("UPDATE file_content SET state = 'FAILED', reason = 'a reason only the status page shows' WHERE file_details_id = ?",
                file.getId());

        assertThat(page(file.getFileInfoId(), PermissionEnum.FILE_INFO_PAGE, PermissionEnum.VIEW_FILE_EXTERNAL_ID,
                PermissionEnum.SEARCH_FILE_CONTENTS))
                .doesNotContain("revision-reading", "data-reading-state", "a reason only the status page shows");
        assertThat(page(file.getFileInfoId(), PermissionEnum.ADMIN)).contains("a reason only the status page shows");
    }

    @Test
    @DisplayName("a failed reading queued again from the file's page lands on the page, said so; one not failed is refused there too")
    void retryFromThePage() throws Exception {
        FileDetailsDTO file = upload("failed.txt");
        jdbc.update("UPDATE file_content SET state = 'FAILED', attempts = 3, lane = 'OCR', reason = 'timed out', read_at = now() "
                + "WHERE file_details_id = ?", file.getId());
        UserDetailsImpl keeper = person(PermissionEnum.FILE_INFO_PAGE, PermissionEnum.CONTENT_EXTRACTION_PAGE,
                PermissionEnum.RETRY_CONTENT_EXTRACTION);

        MockHttpServletResponse queued = mockMvc.perform(post("/settings/content-extraction/{id}/retry", file.getId())
                .param("file", String.valueOf(file.getFileInfoId())).with(csrf()).with(user(keeper))).andReturn().getResponse();
        assertThat(queued.getStatus()).isEqualTo(302);
        assertThat(queued.getRedirectedUrl()).isEqualTo("/files/file-info/" + file.getFileInfoId() + "?readingQueued");
        assertThat(jdbc.queryForObject("SELECT state FROM file_content WHERE file_details_id = ?", String.class, file.getId()))
                .isEqualTo("PENDING");

        MockHttpServletResponse again = mockMvc.perform(post("/settings/content-extraction/{id}/retry", file.getId())
                .param("file", String.valueOf(file.getFileInfoId())).with(csrf()).with(user(keeper))).andReturn().getResponse();
        assertThat(again.getRedirectedUrl()).isEqualTo("/files/file-info/" + file.getFileInfoId() + "?readingNotQueued");

        String shown = body(mockMvc.perform(get("/files/file-info/{id}", file.getFileInfoId()).param("readingQueued", "")
                .with(user(keeper))).andReturn().getResponse());
        assertThat(shown).contains("id=\"reading-queued\"", "دوباره در صف قرار گرفت", "data-reading-state=\"PENDING\"",
                        "نوبت خواندن از")
                .as("the failed reading's facts are not this one's").doesNotContain("timed out", "زمان خواندن");
        String refused = body(mockMvc.perform(get("/files/file-info/{id}", file.getFileInfoId()).param("readingNotQueued", "")
                .with(user(keeper))).andReturn().getResponse());
        assertThat(refused).contains("id=\"reading-not-queued\"", "این نسخه خواندن ناموفقی ندارد");

        assertThat(mockMvc.perform(post("/settings/content-extraction/{id}/retry", file.getId())
                        .param("file", String.valueOf(file.getFileInfoId())).with(csrf())
                        .with(user(person(PermissionEnum.FILE_INFO_PAGE, PermissionEnum.CONTENT_EXTRACTION_PAGE))))
                .andReturn().getResponse().getStatus()).as("the retry still asks its own permission").isEqualTo(403);
    }

    @Test
    @DisplayName("the readings of a file's versions are one statement, by version id; none asked is none read")
    void oneStatement() {
        FileDetailsDTO first = upload("plan.txt");
        int second = nextVersion(first);

        Map<Integer, FileContentRepository.Reading> readings = contentExtractionService.readingsOf(List.of(first.getId(), second));
        assertThat(readings).containsOnlyKeys(first.getId(), second);
        assertThat(readings.get(second).state()).isEqualTo("PENDING");
        assertThat(readings.get(second).queuedAt()).isNotNull();
        assertThat(contentExtractionService.readingsOf(List.of())).isEmpty();
    }

    // ================================================================ helpers

    private String page(int fileInfoId, PermissionEnum... permissions) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/files/file-info/{id}", fileInfoId).with(user(person(permissions))))
                .andReturn().getResponse();
        String body = body(response);
        assertThat(response.getStatus()).as(body.length() > 600 ? body.substring(0, 600) : body).isEqualTo(200);
        return body;
    }

    private static String body(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
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

    /** A new version of the file, queued as every upload is; its revision id. */
    private int nextVersion(FileDetailsDTO of) {
        Integer latest = jdbc.queryForObject("SELECT max(version) FROM file_details WHERE file_info_id = ?", Integer.class,
                of.getFileInfoId());
        String title = of.getFileName().substring(0, of.getFileName().lastIndexOf('.'));
        FileUploadDTO next = new FileUploadDTO();
        next.setFileId(of.getFileInfoId());
        next.setFileName(title);
        next.setFileNameWithoutExtension(title);
        next.setVersion(latest + 1);
        next.setType("version");
        next.setFileDetailsDescription("v" + (latest + 1));
        next.setMultipartFile(new MockMultipartFile("file", of.getFileName(), "text/plain",
                ("v" + (latest + 1)).getBytes(StandardCharsets.UTF_8)));
        fileService.createNewFileDetails(next, adminId);
        entityManager.flush();
        return jdbc.queryForObject("SELECT id FROM file_details WHERE file_info_id = ? AND version = ?", Integer.class,
                of.getFileInfoId(), latest + 1);
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
