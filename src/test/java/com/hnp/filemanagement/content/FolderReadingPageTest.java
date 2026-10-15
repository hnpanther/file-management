package com.hnp.filemanagement.content;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.FixedRole;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.PermissionGroup;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The page of a folder's setting for reading its files (2.16.0), and where else it shows: what decides it,
 * the choice for whoever holds {@code SET_FOLDER_CONTENT_READING} and no form for anyone else, the change
 * said once it is saved; the status page's list of the folders with a setting of their own, a revision held
 * back on its file's page, the explorer's folder details - each behind its permission.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.content-search.enabled=true"})
class FolderReadingPageTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FileService fileService;
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
    private Folder archive;
    private Folder inside;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.findByRoleNameIgnoreCase(FixedRole.ADMIN.roleName())
                .orElseGet(() -> roleRepository.save(TestData.role(FixedRole.ADMIN.roleName()))));
        admin = userRepository.save(admin);
        adminId = admin.getId();
        Folder top = FolderFixture.chain(folderRepository, tagGroupRepository, admin).subCategory();
        archive = FolderFixture.tag(folderRepository, top, admin, "archive" + TestData.nextSequence());
        inside = FolderFixture.tag(folderRepository, archive, admin, "inside" + TestData.nextSequence());
    }

    @Test
    @DisplayName("switched off from its page: said once saved, the folder below inherits it, the status page lists it")
    void switchOff() throws Exception {
        String before = page(get(pageOf(archive)), PermissionEnum.SET_FOLDER_CONTENT_READING);
        assertThat(before).contains("id=\"reading-form\"", "data-reads=\"true\"", "پیش‌فرض سامانه",
                "value=\"OFF\"", "value=\"ON\"", "value=\"INHERIT\"");

        MvcResult saved = mockMvc.perform(post(pageOf(archive)).param("choice", "off").with(csrf())
                .with(user(person(PermissionEnum.SET_FOLDER_CONTENT_READING)))).andReturn();
        assertThat(saved.getResponse().getRedirectedUrl()).isEqualTo(pageOf(archive));
        assertThat((String) saved.getFlashMap().get("changed")).contains("از این به بعد خوانده نمی‌شود");
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT content_reading FROM folder WHERE id = ?", Boolean.class, archive.getId())).isFalse();

        String after = page(get(pageOf(archive)), PermissionEnum.SET_FOLDER_CONTENT_READING);
        assertThat(after).contains("data-reads=\"false\"", "به تنظیم همین پوشه");
        String below = page(get(pageOf(inside)), PermissionEnum.SET_FOLDER_CONTENT_READING);
        assertThat(below).contains("data-reads=\"false\"", "id=\"decided-by\"", pageOf(archive));

        String status = page(get("/settings/content-extraction"), PermissionEnum.CONTENT_EXTRACTION_PAGE);
        assertThat(status).contains("id=\"folder-reading\"", "data-folder-id=\"" + archive.getId() + "\"");
    }

    @Test
    @DisplayName("the page for CONTENT_EXTRACTION_PAGE without the form; the change refused without its permission; closed to the rest")
    void permissions() throws Exception {
        assertThat(page(get(pageOf(archive)), PermissionEnum.CONTENT_EXTRACTION_PAGE))
                .contains("id=\"reading-read-only\"").doesNotContain("id=\"reading-form\"");
        assertThat(status(get(pageOf(archive)), PermissionEnum.FILE_EXPLORER_PAGE)).isEqualTo(403);
        assertThat(status(post(pageOf(archive)).param("choice", "OFF").with(csrf()), PermissionEnum.CONTENT_EXTRACTION_PAGE))
                .isEqualTo(403);
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT content_reading FROM folder WHERE id = ?", Boolean.class, archive.getId())).isNull();

        MvcResult nothing = mockMvc.perform(post(pageOf(archive)).param("choice", "maybe").with(csrf())
                .with(user(person(PermissionEnum.SET_FOLDER_CONTENT_READING)))).andReturn();
        assertThat((String) nothing.getFlashMap().get("refused")).contains("یکی از گزینه‌ها");

        assertThat(FixedRole.ADMIN.permissions()).contains(PermissionEnum.SET_FOLDER_CONTENT_READING);
        assertThat(FixedRole.USER.permissions()).doesNotContain(PermissionEnum.SET_FOLDER_CONTENT_READING);
        assertThat(PermissionGroup.of(PermissionEnum.SET_FOLDER_CONTENT_READING)).contains(PermissionGroup.FOLDER_CONTENT_READING);
    }

    @Test
    @DisplayName("a revision held back says so on its file's page, with the way to its folder's setting; the explorer's details too")
    void elsewhere() throws Exception {
        mockMvc.perform(post(pageOf(archive)).param("choice", "OFF").with(csrf())
                .with(user(person(PermissionEnum.SET_FOLDER_CONTENT_READING)))).andReturn();
        FileDetailsDTO held = upload("held.txt", inside);

        String file = page(get("/files/file-info/{id}", held.getFileInfoId()), PermissionEnum.FILE_INFO_PAGE,
                PermissionEnum.CONTENT_EXTRACTION_PAGE);
        assertThat(file).contains("data-reading-state=\"EXCLUDED\"", "id=\"reading-excluded\"", "خاموش در پوشه",
                "/files/folders/" + inside.getId() + "/content-reading");

        String details = page(get("/resource/folders/{id}", inside.getId()), PermissionEnum.FILE_EXPLORER_PAGE);
        assertThat(details).contains("\"contentRead\":false");
        assertThat(page(get("/files/explorer"), PermissionEnum.FILE_EXPLORER_PAGE, PermissionEnum.SET_FOLDER_CONTENT_READING))
                .contains("id=\"folder-content-reading\"", "/content-reading'");
        assertThat(page(get("/files/explorer"), PermissionEnum.FILE_EXPLORER_PAGE))
                .as("the state, without the way to its page").contains("id=\"folder-content-reading\"").doesNotContain("/content-reading'");
    }

    // ================================================================ helpers

    private static String pageOf(Folder folder) {
        return "/files/folders/" + folder.getId() + "/content-reading";
    }

    private String page(MockHttpServletRequestBuilder request, PermissionEnum... permissions) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(request.with(user(person(permissions)))).andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as(body.length() > 600 ? body.substring(0, 600) : body).isEqualTo(200);
        return body;
    }

    private int status(MockHttpServletRequestBuilder request, PermissionEnum... permissions) throws Exception {
        return mockMvc.perform(request.with(user(person(permissions)))).andReturn().getResponse().getStatus();
    }

    private FileDetailsDTO upload(String fileName, Folder folder) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription(fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folder.getId());
        String unique = fileName.replace(".txt", "-" + TestData.nextSequence() + ".txt");
        request.setMultipartFile(new MockMultipartFile("file", unique, "text/plain", ("content of " + unique).getBytes(StandardCharsets.UTF_8)));
        FileDetailsDTO uploaded = fileService.createNewFile(request, adminId, FileService.PRIVATE);
        entityManager.flush();
        return uploaded;
    }

    /** The test's administrator - every folder theirs - holding only these permissions. */
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
