package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.folder.domain.UserFolderGrant;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.metadata.MetadataDocument;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Metadata on the pages (roadmap 12.2, 12.3 - 2.13.0): the upload form's field, the new version's
 * inheritance, the file page's table - escaped - and its edit pages, the folder page and the queue;
 * each form conditioned on what it was opened on, refused with a message rather than an error page,
 * and closed to whoever lacks the permission or the folder.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class MetadataPagesTest extends DatabaseSupport {

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

    private User admin;
    private int adminId;
    private Folder folder;

    @BeforeEach
    void setUp() {
        admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        adminId = admin.getId();
        folder = FolderFixture.chain(folderRepository, tagGroupRepository, admin).tag();
    }

    @Test
    @DisplayName("the upload form stores the field's document; a bad one re-renders the form with why, and stores nothing")
    void theUploadForm() throws Exception {
        page(mockMvc.perform(upload("deal.txt", "{\"contractNo\":\"C-5678\"}")), 200);
        entityManager.flush();
        assertThat(stored("deal")).isEqualTo("{\"contractNo\": \"C-5678\"}");

        String refused = page(mockMvc.perform(upload("bad.txt", "{\"a\":1,\"a\":2}")), 200);
        assertThat(refused).contains("متادیتا JSON معتبر نیست");
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_info WHERE folder_id = ? AND file_name = 'bad'", Integer.class,
                folder.getId())).isZero();
        assertThat(page(mockMvc.perform(get("/files/create").with(user(admin()))), 200)).contains("metadata-editor");
    }

    @Test
    @DisplayName("the file page shows the current document, every value escaped, and each revision's own")
    void theFilePage() throws Exception {
        FileDetailsDTO file = uploaded("page.txt", "{\"note\":\"<script>alert(1)</script>\",\"party\":{\"name\":\"علی\"}}");
        String page = page(mockMvc.perform(get("/files/file-info/" + file.getFileInfoId()).with(user(admin()))), 200);
        assertThat(page).contains("&lt;script&gt;alert(1)&lt;/script&gt;").doesNotContain("<script>alert(1)");
        assertThat(page).contains("علی", "edit-file-metadata", "/files/file-details/" + file.getId() + "/metadata");

        String reader = page(mockMvc.perform(get("/files/file-info/" + file.getFileInfoId())
                .with(user(person(PermissionEnum.FILE_INFO_PAGE)))), 200);
        assertThat(reader).contains("علی").doesNotContain("edit-file-metadata");
    }

    @Test
    @DisplayName("the edit page saves over what it was opened on; a change meanwhile is said and kept; a bad document is said")
    void theEditPage() throws Exception {
        FileDetailsDTO file = uploaded("edit.txt", "{\"n\":\"1\"}");
        String form = page(mockMvc.perform(get("/files/file-info/" + file.getFileInfoId() + "/metadata").with(user(admin()))), 200);
        String etag = new MetadataDocument("{\"n\": \"1\"}").etag();
        assertThat(form).contains("name=\"etag\"", etag.replace("\"", "&quot;"));

        mockMvc.perform(save("/files/file-info/" + file.getFileInfoId() + "/metadata", "{\"n\":\"2\"}", etag))
                .andReturn().getResponse();
        entityManager.flush();
        assertThat(stored("edit")).isEqualTo("{\"n\": \"2\"}");

        String stale = page(mockMvc.perform(save("/files/file-info/" + file.getFileInfoId() + "/metadata", "{\"n\":\"3\"}", etag)), 200);
        assertThat(stale).contains("تغییر کرده است");
        entityManager.flush();
        assertThat(stored("edit")).isEqualTo("{\"n\": \"2\"}");

        String bad = page(mockMvc.perform(save("/files/file-details/" + file.getId() + "/metadata", "[1]",
                new MetadataDocument("{\"n\": \"2\"}").etag())), 200);
        assertThat(bad).contains("باید یک شیء JSON باشد").contains("[1]");
    }

    @Test
    @DisplayName("a first change - none before it - is shown on the folder page and in the file's history, before and after")
    void aFirstChangeIsShown() throws Exception {
        mockMvc.perform(save("/files/folders/" + folder.getId() + "/metadata", "{\"fullName\":\"<b>علی</b>\"}", null));
        entityManager.flush();
        String folderPage = page(mockMvc.perform(get("/files/folders/" + folder.getId() + "/metadata").with(user(admin()))), 200);
        assertThat(folderPage).contains("metadata-change", "&lt;b&gt;علی&lt;/b&gt;").doesNotContain("<b>علی</b>");

        FileDetailsDTO file = uploaded("history.txt", null);
        mockMvc.perform(save("/files/file-info/" + file.getFileInfoId() + "/metadata", "{\"stage\":\"signed\"}", null));
        entityManager.flush();
        String filePage = page(mockMvc.perform(get("/files/file-info/" + file.getFileInfoId()).with(user(admin()))), 200);
        assertThat(filePage).contains("history-metadata", "signed");
    }

    @Test
    @DisplayName("saving needs the permission and WRITE on the folder; reading the file needs neither")
    void permissions() throws Exception {
        FileDetailsDTO file = uploaded("guarded.txt", "{\"a\":\"1\"}");
        int status = mockMvc.perform(save("/files/file-info/" + file.getFileInfoId() + "/metadata", "{\"a\":\"2\"}", null)
                .with(user(person(PermissionEnum.FILE_INFO_PAGE)))).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(403);

        User other = userRepository.save(TestData.user());
        grant(other, folder.getId(), FolderPermission.READ);
        int noWrite = mockMvc.perform(save("/files/file-info/" + file.getFileInfoId() + "/metadata", "{\"a\":\"2\"}", null)
                .with(user(person(other.getId(), PermissionEnum.EDIT_FILE_METADATA)))).andReturn().getResponse().getStatus();
        assertThat(noWrite).isEqualTo(403);
        entityManager.flush();
        assertThat(stored("guarded")).isEqualTo("{\"a\": \"1\"}");
    }

    @Test
    @DisplayName("the folder page: the form for whoever may write, the table for a reader, the root refused; the queue lists what is left")
    void theFolderPages() throws Exception {
        Folder parent = folderRepository.findById(folder.getId()).orElseThrow().getParent();
        String editable = page(mockMvc.perform(get("/files/folders/" + folder.getId() + "/metadata").with(user(admin()))), 200);
        assertThat(editable).contains("metadataForm");

        String queue = page(mockMvc.perform(get("/files/folders/" + parent.getId() + "/undescribed").with(user(admin()))), 200);
        assertThat(queue).contains("/files/folders/" + folder.getId() + "/metadata");

        mockMvc.perform(save("/files/folders/" + folder.getId() + "/metadata", "{\"fullName\":\"علی\"}",
                MetadataDocument.etagOf(java.util.Optional.empty())));
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT metadata::text FROM folder WHERE id = ?", String.class, folder.getId()))
                .isEqualTo("{\"fullName\": \"علی\"}");
        assertThat(page(mockMvc.perform(get("/files/folders/" + parent.getId() + "/undescribed").with(user(admin()))), 200))
                .doesNotContain("/files/folders/" + folder.getId() + "/metadata\"");

        User reader = userRepository.save(TestData.user());
        grant(reader, folder.getId(), FolderPermission.READ);
        String readOnly = page(mockMvc.perform(get("/files/folders/" + folder.getId() + "/metadata")
                .with(user(person(reader.getId(), PermissionEnum.FILE_EXPLORER_PAGE)))), 200);
        assertThat(readOnly).contains("metadata-read-only", "علی").doesNotContain("metadataForm");
        assertThat(mockMvc.perform(save("/files/folders/" + folder.getId() + "/metadata", "{\"x\":\"1\"}", null)
                .with(user(person(reader.getId(), PermissionEnum.FILE_EXPLORER_PAGE)))).andReturn().getResponse().getStatus())
                .isEqualTo(403);

        int root = jdbc.queryForObject("SELECT id FROM folder WHERE parent_id IS NULL", Integer.class);
        assertThat(page(mockMvc.perform(save("/files/folders/" + root + "/metadata", "{\"x\":\"1\"}", null)), 200))
                .contains("این پوشه متادیتا نمی‌گیرد");
    }

    // ---------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder upload(String name, String metadata) {
        return (org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder) multipart("/files")
                .file(new MockMultipartFile("multipartFile", name, "text/plain", ("content of " + name).getBytes(StandardCharsets.UTF_8)))
                .param("fileName", name.substring(0, name.indexOf('.')))
                .param("description", "pages")
                .param("folderId", String.valueOf(folder.getId()))
                .param("metadata", metadata)
                .with(user(admin())).with(csrf()).accept(MediaType.TEXT_HTML);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder save(String path, String metadata, String etag) {
        var request = post(path).param("metadata", metadata).with(user(admin())).with(csrf()).accept(MediaType.TEXT_HTML);
        if (etag != null) {
            request.param("etag", etag);
        }
        return request;
    }

    private FileDetailsDTO uploaded(String name, String metadata) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription(name);
        request.setFolderId(folder.getId());
        request.setMetadata(metadata);
        request.setMultipartFile(new MockMultipartFile("file", name, "text/plain", ("content of " + name).getBytes(StandardCharsets.UTF_8)));
        FileDetailsDTO uploaded = fileService.createNewFile(request, adminId, FileService.PRIVATE);
        entityManager.flush();
        entityManager.clear();
        return uploaded;
    }

    private String stored(String title) {
        return jdbc.queryForObject("""
                SELECT d.metadata::text FROM file_details d JOIN file_info f ON f.id = d.file_info_id
                WHERE f.folder_id = ? AND f.file_name = ? ORDER BY d.version DESC, d.id DESC LIMIT 1""",
                String.class, folder.getId(), title);
    }

    private static String page(ResultActions result, int status) throws Exception {
        MockHttpServletResponse response = result.andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as(body.length() > 600 ? body.substring(0, 600) : body).isEqualTo(status);
        return body;
    }

    private void grant(User user, int folderId, FolderPermission permission) {
        User loaded = userRepository.findById(user.getId()).orElseThrow();
        List<UserFolderGrant> grants = new ArrayList<>(loaded.getFolderGrants());
        grants.add(new UserFolderGrant(loaded, folderRepository.findById(folderId).orElseThrow(), permission));
        loaded.replaceFolderGrants(grants);
        userRepository.save(loaded);
        entityManager.flush();
    }

    private UserDetailsImpl admin() {
        return person(adminId, PermissionEnum.ADMIN);
    }

    private UserDetailsImpl person(PermissionEnum... permissions) {
        return person(adminId, permissions);
    }

    private static UserDetailsImpl person(int id, PermissionEnum... permissions) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(id);
        principal.setUsername("person" + id);
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(0);
        principal.setPermissions(List.of(permissions));
        return principal;
    }
}
