package com.hnp.filemanagement.content;

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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The advanced search on the page and on API v1 (roadmap 12.8): what was asked kept in the form and in
 * the pager's links, each result's trail and metadata shown and escaped, the folder chooser's answer,
 * the explorer's link into a folder, a refusal said in words - and nothing of it logged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.content-search.enabled=true"})
class AdvancedSearchPagesTest extends DatabaseSupport {

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
    private EntityManager entityManager;

    private int adminId;
    private Folder folder;
    private Folder other;
    private String tag;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        adminId = admin.getId();
        Folder root = FolderFixture.chain(folderRepository, tagGroupRepository, admin).subCategory();
        folder = FolderFixture.tag(folderRepository, root, admin, "بایگانی" + TestData.nextSequence());
        other = FolderFixture.tag(folderRepository, root, admin, "دیگری" + TestData.nextSequence());
        tag = "tag" + TestData.nextSequence();
    }

    @Test
    @DisplayName("the page: the form keeps what was asked, open; each result with its trail and its metadata, escaped")
    void thePage() throws Exception {
        String document = "{\"run\":\"" + tag + "\",\"party\":\"<img src=x onerror=alert(1)>\"}";
        FileDetailsDTO file = upload("contract", folder, document);

        String page = page(mockMvc.perform(get("/files/content-search").param("folder", String.valueOf(folder.getId()))
                .param("metadata", document).param("name", "contract").param("sort", "newest")
                .with(user(person(PermissionEnum.SEARCH_FILE_CONTENTS)))), 200);
        assertThat(page).contains("id=\"content-search-results\"", "/files/file-info/" + file.getFileInfoId(),
                        folder.getDisplayName(), "content-result-metadata", "&lt;img src=x onerror=alert(1)&gt;")
                .doesNotContain("<img src=x");
        assertThat(page).as("the advanced part open, the folder chosen, the name and the order kept")
                .containsPattern("<details[^>]*id=\"content-search-advanced\"[^>]*open|<details[^>]*open[^>]*id=\"content-search-advanced\"")
                .contains("value=\"" + folder.getId() + "\"", "value=\"contract\"")
                .containsPattern("<option value=\"newest\"[^>]*selected");
        assertThat(page).as("the document typed is given back to its editor as data, escaped")
                .contains("data-initial=\"{&quot;run&quot;:&quot;" + tag + "&quot;");

        String plain = page(mockMvc.perform(get("/files/content-search").with(user(person(PermissionEnum.SEARCH_FILE_CONTENTS)))), 200);
        assertThat(plain).doesNotContainPattern("<details[^>]*id=\"content-search-advanced\"[^>]*open");
    }

    @Test
    @DisplayName("the pager's links carry every part asked - the document encoded - and the next page holds the rest")
    void thePager() throws Exception {
        String document = "{\"run\":\"" + tag + "\"}";
        for (int i = 0; i < 21; i++) {
            upload("p" + i, folder, document);
        }
        String first = page(mockMvc.perform(get("/files/content-search").param("folder", String.valueOf(folder.getId()))
                .param("metadata", document).param("sort", "oldest")
                .with(user(person(PermissionEnum.SEARCH_FILE_CONTENTS)))), 200);
        String encodedDocument = URLEncoder.encode(document, StandardCharsets.UTF_8);
        assertThat(first).contains("/files/content-search?sort=oldest&amp;metadata=" + encodedDocument + "&amp;folder="
                + folder.getId() + "&amp;page=1");
        assertThat(count(first, "class=\"content-result\"")).isEqualTo(20);

        String second = page(mockMvc.perform(get("/files/content-search").param("folder", String.valueOf(folder.getId()))
                .param("metadata", document).param("sort", "oldest").param("page", "1")
                .with(user(person(PermissionEnum.SEARCH_FILE_CONTENTS)))), 200);
        assertThat(count(second, "class=\"content-result\"")).isEqualTo(1);
        assertThat(second).contains("&amp;page=0");
    }

    @Test
    @DisplayName("a refusal said in words: a kind that is not one, days reversed, a document that is not one")
    void refusals() throws Exception {
        UserDetailsImpl searcher = person(PermissionEnum.SEARCH_FILE_CONTENTS);
        assertThat(page(mockMvc.perform(get("/files/content-search").param("type", "p d f").with(user(searcher))), 200))
                .contains("id=\"content-search-message\"", "نوع فایل را با پسوند بنویسید");
        assertThat(page(mockMvc.perform(get("/files/content-search").param("from", "2026-02-01").param("to", "2026-01-01")
                .with(user(searcher))), 200)).contains("تاریخ شروع بعد از تاریخ پایان است");
        assertThat(page(mockMvc.perform(get("/files/content-search").param("metadata", "{not json").with(user(searcher))), 200))
                .contains("id=\"content-search-message\"").doesNotContain("content-search-results");
    }

    @Test
    @DisplayName("the chooser: the folders the person may walk into, each with its trail, as JSON - closed to whoever may not search")
    void theChooser() throws Exception {
        User reader = userRepository.save(TestData.user());
        reader.replaceFolderGrants(new ArrayList<>(List.of(new UserFolderGrant(reader, folder, FolderPermission.READ))));
        userRepository.save(reader);
        entityManager.flush();

        String json = page(mockMvc.perform(get("/files/content-search/folders").param("q", folder.getDisplayName())
                .with(user(person(reader.getId(), PermissionEnum.SEARCH_FILE_CONTENTS)))), 200);
        assertThat(json).contains("\"id\":" + folder.getId(), folder.getDisplayName());
        String none = page(mockMvc.perform(get("/files/content-search/folders").param("q", other.getDisplayName())
                .with(user(person(reader.getId(), PermissionEnum.SEARCH_FILE_CONTENTS)))), 200);
        assertThat(none).isEqualTo("[]");
        assertThat(mockMvc.perform(get("/files/content-search/folders").param("q", "x")
                .with(user(person(PermissionEnum.FILE_EXPLORER_PAGE)))).andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("the explorer offers a search in the folder open, to whoever may search")
    void theExplorersLink() throws Exception {
        assertThat(page(mockMvc.perform(get("/files/explorer")
                .with(user(person(PermissionEnum.FILE_EXPLORER_PAGE, PermissionEnum.SEARCH_FILE_CONTENTS)))), 200))
                .contains("id=\"folder-search-link\"", "data-base=\"/files/content-search?folder=\"", "جستجو در این پوشه");
        assertThat(page(mockMvc.perform(get("/files/explorer").with(user(person(PermissionEnum.FILE_EXPLORER_PAGE)))), 200))
                .doesNotContain("folder-search-link");
    }

    @Test
    @DisplayName("API v1: a folder, a document, a kind and an order - each file with its trail and its metadata values")
    void theApi() throws Exception {
        String document = "{\"run\":\"" + tag + "\",\"contractNo\":\"C-1\"}";
        FileDetailsDTO file = upload("api", folder, document);
        upload("api-other", other, document);

        String json = page(mockMvc.perform(get("/api/v1/files/content-search").param("folderId", String.valueOf(folder.getId()))
                .param("metadata", document).param("type", "txt").param("sort", "newest")
                .with(user(person(PermissionEnum.API_SEARCH_FILE_CONTENTS)))), 200);
        assertThat(json).contains("\"fileDetailsNumber\":" + file.getId(), "\"folderTrail\":[", folder.getDisplayName(),
                "\"metadata\":{\"run\":\"" + tag + "\",\"contractNo\":\"C-1\"}", "\"matchedPages\":0", "\"hasNext\":false");
        assertThat(count(json, "\"fileDetailsNumber\"")).isEqualTo(1);
        assertThat(page(mockMvc.perform(get("/api/v1/files/content-search").param("from", "1403-07-01")
                .with(user(person(PermissionEnum.API_SEARCH_FILE_CONTENTS)))), 400)).isNotBlank();
        assertThat(page(mockMvc.perform(get("/api/v1/files/content-search").param("folderId", "2147483647")
                .with(user(person(PermissionEnum.API_SEARCH_FILE_CONTENTS)))), 200)).contains("\"items\":[]");
    }

    @Test
    @DisplayName("nothing typed is logged: the name, the documents and the words masked wherever they are")
    void nothingTypedIsLogged() {
        assertThat(GlobalGeneralLogging.maskSecrets("/files/content-search?sort=newest&name=0012345678&metadata=%7B%22a%22%7D"
                + "&folderMetadata=%7B%22b%22%7D&folder=7&q=x"))
                .isEqualTo("/files/content-search?sort=newest&name=***&metadata=***&folderMetadata=***&folder=7&q=***");
    }

    // ================================================================ helpers

    private FileDetailsDTO upload(String name, Folder into, String metadata) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription(name);
        request.setFileNameDescription(name);
        request.setFolderId(into.getId());
        request.setMetadata(metadata);
        String unique = name + "-" + TestData.nextSequence() + ".txt";
        request.setMultipartFile(new MockMultipartFile("file", unique, "text/plain", unique.getBytes(StandardCharsets.UTF_8)));
        FileDetailsDTO uploaded = fileService.createNewFile(request, adminId, FileService.PRIVATE);
        entityManager.flush();
        return uploaded;
    }

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            found++;
        }
        return found;
    }

    private static String page(ResultActions result, int status) throws Exception {
        MockHttpServletResponse response = result.andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as(body.length() > 600 ? body.substring(0, 600) : body).isEqualTo(status);
        return body;
    }

    /** The test's administrator - every folder theirs - holding only these permissions. */
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
