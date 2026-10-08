package com.hnp.filemanagement.content;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.folder.domain.UserFolderGrant;
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
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Search in contents attacked (roadmap 11.2, the review before production, 2026-10-08): a real API key
 * kept to its own folders, a person to theirs on the status page and its retry, every name and text a
 * document carries escaped, no form posted without its token, and whatever is typed into {@code q},
 * {@code page} or {@code size} answered without an error.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.content-search.enabled=true"})
class ContentSecurityTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FileService fileService;
    @Autowired
    private FileContentRepository repository;
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
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private Folder folderA;
    private Folder folderB;
    private String run;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        adminId = admin.getId();
        Folder root = FolderFixture.chain(folderRepository, tagGroupRepository, admin).subCategory();
        folderA = FolderFixture.tag(folderRepository, root, admin, "A" + TestData.nextSequence());
        folderB = FolderFixture.tag(folderRepository, root, admin, "<b>B" + TestData.nextSequence() + "</b>");
        run = "امنیت" + Long.toString(TestData.nextSequence() + 1000, 36).replaceAll("[0-9]", "");
    }

    @Test
    @DisplayName("a real API key (Bearer) finds only what its own grants reach - not its creator's, an administrator's")
    void anApiKeyIsKeptToItsFolders() throws Exception {
        FileDetailsDTO inA = upload("a.txt", folderA);
        FileDetailsDTO inB = upload("b.txt", folderB);
        read(inA.getId(), page(0, "WHOLE", run + " کلید"));
        read(inB.getId(), page(0, "WHOLE", run + " کلید"));
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("content search " + TestData.nextSequence());
        request.setFolderGrants(List.of(folderA.getId() + ":READ"));
        ApiKeyCreatedDTO key = apiKeyService.create(request, adminId);
        entityManager.flush();

        String json = body(mockMvc.perform(get("/api/v1/files/content-search").param("q", run)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + key.credential())), 200);
        assertThat(json).contains("\"fileDetailsNumber\":" + inA.getId()).doesNotContain("\"fileDetailsNumber\":" + inB.getId());
        assertThat(mockMvc.perform(get("/api/v1/files/content-search").param("q", run)
                .header(HttpHeaders.AUTHORIZATION, "Bearer fmk_NOSUCHKEY_" + "x".repeat(40))).andReturn().getResponse().getStatus())
                .as("a key that is not one").isEqualTo(401);
        assertThat(mockMvc.perform(get("/api/v1/files/content-search").param("q", run)).andReturn().getResponse().getStatus())
                .as("nobody").isIn(401, 403);
    }

    @Test
    @DisplayName("a person sees only the failures of files they may open, and cannot queue another's again")
    void theStatusPageIsScoped() throws Exception {
        FileDetailsDTO mine = upload("mine.txt", folderA);
        FileDetailsDTO theirs = upload("theirs.txt", folderB);
        entityManager.flush();
        jdbc.update("UPDATE file_content SET state = 'FAILED', reason = 'one', read_at = now() WHERE file_details_id IN (?, ?)",
                mine.getId(), theirs.getId());
        User reader = userRepository.save(TestData.user());
        grant(reader, folderA.getId());
        UserDetailsImpl person = person(reader.getId(), PermissionEnum.CONTENT_EXTRACTION_PAGE, PermissionEnum.RETRY_CONTENT_EXTRACTION);

        String page = body(mockMvc.perform(get("/settings/content-extraction").with(user(person))), 200);
        assertThat(page).contains("/settings/content-extraction/" + mine.getId() + "/retry")
                .doesNotContain("/settings/content-extraction/" + theirs.getId() + "/retry")
                .doesNotContain(theirs.getFileName().substring(0, theirs.getFileName().lastIndexOf('.')));

        body(mockMvc.perform(post("/settings/content-extraction/" + theirs.getId() + "/retry").with(csrf()).with(user(person))), 200);
        assertThat(state(theirs.getId())).as("another's failure is not queued again").isEqualTo("FAILED");
        body(mockMvc.perform(post("/settings/content-extraction/" + mine.getId() + "/retry").with(csrf()).with(user(person))), 200);
        assertThat(state(mine.getId())).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("no form posted without its CSRF token")
    void formsNeedTheirToken() throws Exception {
        UserDetailsImpl admin = person(adminId, PermissionEnum.ADMIN);
        assertThat(mockMvc.perform(post("/settings/content-extraction/retry-failed").with(user(admin))).andReturn().getResponse()
                .getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(post("/settings/content-extraction/1/retry").with(user(admin))).andReturn().getResponse()
                .getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("a file's name, a sheet's name, a folder's name and a page's text are text on the page, never markup")
    void everythingIsEscaped() throws Exception {
        FileDetailsDTO file = upload("x.txt", folderB);
        entityManager.flush();
        jdbc.update("UPDATE file_info SET file_name = '<img src=x onerror=alert(1)>' WHERE id = ?", file.getFileInfoId());
        read(file.getId(), new FileContentRepository.PageRow(1, 0, "SHEET", "<svg onload=alert(2)>", "TEXT", null,
                run + " <iframe src=javascript:alert(3)>", ContentFolding.fold(run + " <iframe src=javascript:alert(3)>")));
        String page = body(mockMvc.perform(get("/files/content-search").param("q", run).with(user(person(adminId, PermissionEnum.ADMIN)))), 200);
        assertThat(page).doesNotContain("<img src=x", "<svg onload", "<iframe src", "<b>B")
                .contains("&lt;img src=x onerror=alert(1)&gt;", "&lt;svg onload=alert(2)&gt;", "&lt;iframe src=javascript:alert(3)&gt;");
    }

    @Test
    @DisplayName("whatever is typed - quotes, operators, SQL, control characters, a megabyte - is answered, never an error")
    void anythingTyped() throws Exception {
        FileDetailsDTO file = upload("q.txt", folderA);
        read(file.getId(), page(0, "WHOLE", run + " متن"));
        UserDetailsImpl admin = person(adminId, PermissionEnum.ADMIN);
        List<String> typed = new ArrayList<>(List.of("'; DROP TABLE file_content; --", "a' | b & !c:* (d) <-> e", "\"unclosed", "\\",
                "\u0000\u0001‮‏", "🙂🙂", "%", "*", ":*", "()", "!", "&&&", "ـــ", "ك".repeat(1_000_000)));
        for (String q : typed) {
            int status = mockMvc.perform(get("/files/content-search").param("q", q).with(user(admin))).andReturn().getResponse().getStatus();
            assertThat(status).as(q.length() > 30 ? q.substring(0, 30) : q).isEqualTo(200);
            int api = mockMvc.perform(get("/api/v1/files/content-search").param("q", q).with(user(admin))).andReturn().getResponse().getStatus();
            assertThat(api).as(q.length() > 30 ? q.substring(0, 30) : q).isIn(200, 400);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_content WHERE file_details_id = ?", Integer.class, file.getId()))
                .isOne();
        for (String[] paging : new String[][]{{"-5", "-1"}, {"2147483647", "200"}, {"x", "y"}, {"0", "100000"}}) {
            int status = mockMvc.perform(get("/api/v1/files/content-search").param("q", run).param("page", paging[0])
                    .param("size", paging[1]).with(user(admin))).andReturn().getResponse().getStatus();
            assertThat(status).as(String.join(",", paging)).isIn(200, 400);
        }
        String json = body(mockMvc.perform(get("/api/v1/files/content-search").param("q", run).param("size", "100000")
                .with(user(admin))), 200);
        assertThat(json).contains("\"size\":200");
    }

    // ================================================================ helpers

    private static FileContentRepository.PageRow page(int number, String unit, String text) {
        return new FileContentRepository.PageRow(number, 0, unit, null, "TEXT", null, text, ContentFolding.fold(text));
    }

    private void read(int fileDetailsId, FileContentRepository.PageRow... pages) {
        entityManager.flush();
        jdbc.update("UPDATE file_content SET state = 'READING', lease_until = now() + interval '1 hour' WHERE file_details_id = ?",
                fileDetailsId);
        assertThat(repository.finish(fileDetailsId, new FileContentRepository.Outcome("DONE", "TEXT", "text/plain", null,
                false, null, null, 10), List.of(pages), Instant.now())).isTrue();
    }

    private FileDetailsDTO upload(String fileName, Folder folder) {
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

    private String state(int id) {
        return jdbc.queryForObject("SELECT state FROM file_content WHERE file_details_id = ?", String.class, id);
    }

    private void grant(User user, int folder) {
        User loaded = userRepository.findById(user.getId()).orElseThrow();
        List<UserFolderGrant> grants = new ArrayList<>(loaded.getFolderGrants());
        grants.add(new UserFolderGrant(loaded, folderRepository.findById(folder).orElseThrow(), FolderPermission.READ));
        loaded.replaceFolderGrants(grants);
        userRepository.save(loaded);
        entityManager.flush();
        entityManager.clear();
    }

    private static String body(ResultActions result, int status) throws Exception {
        MockHttpServletResponse response = result.andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as(body.length() > 600 ? body.substring(0, 600) : body).isEqualTo(status);
        return body;
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
