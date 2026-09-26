package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.ShareLinkDTO;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.settings.domain.AppSettingService;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.domain.ShareLinkService;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.MutableClock;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Temporary share links over HTTP (roadmap 10.5): who may make and revoke one, what a visitor
 * with the link gets - and does not get - without signing in, and that the pages render the
 * controls to the right people. The clock is the test's, so a link can be aged.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(MutableClock.Config.class)
@TestPropertySource(properties = {
        "filemanagement.folder-access.enabled=true",
        "filemanagement.share-links.max-minutes=30",
        "filemanagement.share-links.max-failed-attempts=2"})
class ShareLinkWebTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FileService fileService;
    @Autowired
    private ShareLinkService shareLinkService;
    @Autowired
    private AppSettingService appSettingService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MutableClock clock;

    private int adminId;
    private int otherId;
    private FolderFixture.Chain chain;
    private FileDetailsDTO revision;

    @BeforeEach
    void setUp() {
        Role adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();
        otherId = userRepository.save(TestData.user()).getId();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        revision = upload("shared.txt");
    }

    @Test
    @DisplayName("a link is made by one POST under its own permission and answers with the URL once; a visitor downloads it with no sign-in, and a dead or unknown token is one 404")
    void makeAndDownload() throws Exception {
        mockMvc.perform(post("/resource/files/file-details/{id}/share-links", revision.getId())
                        .with(user(principal(adminId, PermissionEnum.DOWNLOAD_FILE))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"minutes\":10}"))
                .andExpect(status().isForbidden());

        String created = mockMvc.perform(post("/resource/files/file-details/{id}/share-links", revision.getId())
                        .with(user(principal(adminId, PermissionEnum.CREATE_SHARE_LINK))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"minutes\":10000,\"maxDownloads\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value(Matchers.startsWith("http://localhost/share/")))
                .andExpect(jsonPath("$.link.token").isString())
                .andExpect(jsonPath("$.link.status").value("ACTIVE"))
                .andExpect(jsonPath("$.link.maxDownloads").value(1))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(created, "$.link.token");
        // Clamped to the cap: 30 minutes, not 10000.
        assertThat(shareLinkService.usable(token)).isPresent();
        clock.advance(Duration.ofMinutes(29));
        assertThat(shareLinkService.usable(token)).isPresent();

        // Nobody signed in: the landing page, which hands out nothing by itself.
        mockMvc.perform(get("/share/{token}", token).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("shared.txt")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("content of shared.txt"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("name=\"password\""))));
        assertThat(shareLinkService.listMine(adminId).getFirst().downloadCount()).as("a GET spends nothing").isZero();

        // The POST is the download, with the same headers as the public download.
        mockMvc.perform(post("/share/{token}", token).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"shared.txt\"; filename*=UTF-8''shared.txt"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string("content of shared.txt"));

        // One download was all it allowed; and a forged token is the same answer.
        mockMvc.perform(get("/share/{token}", token).accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound());
        mockMvc.perform(post("/share/{token}", token).with(csrf())).andExpect(status().isNotFound());
        mockMvc.perform(get("/share/{token}", "not-a-token").accept(MediaType.TEXT_HTML)).andExpect(status().isNotFound());

        // A POST without the CSRF token is refused before anything is counted.
        ShareLinkDTO another = shareLinkService.create(revision.getId(), 5, null, null, adminId);
        mockMvc.perform(post("/share/{token}", another.token())).andExpect(status().isForbidden());
        assertThat(shareLinkService.listMine(adminId)).filteredOn(l -> l.id() == another.id()).singleElement()
                .satisfies(l -> assertThat(l.downloadCount()).isZero());
    }

    @Test
    @DisplayName("a link with a password shows the field, refuses a wrong one, locks after too many, and downloads for the right one")
    void aPasswordLink() throws Exception {
        ShareLinkDTO link = shareLinkService.create(revision.getId(), 30, "open sesame", null, adminId);

        mockMvc.perform(get("/share/{token}", link.token()).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("name=\"password\"")));

        mockMvc.perform(post("/share/{token}", link.token()).with(csrf()).param("password", "wrong"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION))
                .andExpect(content().string(Matchers.containsString("name=\"password\"")));
        mockMvc.perform(post("/share/{token}", link.token()).with(csrf()).param("password", "wrong twice"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("disabled=\"disabled\"")));
        mockMvc.perform(post("/share/{token}", link.token()).with(csrf()).param("password", "open sesame"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.CONTENT_DISPOSITION));

        clock.advance(Duration.ofMinutes(15));
        mockMvc.perform(post("/share/{token}", link.token()).with(csrf()).param("password", "open sesame"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"shared.txt\"; filename*=UTF-8''shared.txt"))
                .andExpect(content().string("content of shared.txt"));
    }

    @Test
    @DisplayName("the link is the access: it works with the public files closed to visitors, and for a private file in a folder the visitor could never read")
    void theLinkIsTheAccess() throws Exception {
        appSettingService.setPublicFilesAnonymous(false, adminId);
        // The revision was stored private (public-file 0); a visitor cannot reach it any other way.
        mockMvc.perform(get("/files/public-download/{id}", revision.getId())).andExpect(status().is3xxRedirection());

        ShareLinkDTO link = shareLinkService.create(revision.getId(), 5, null, null, adminId);
        mockMvc.perform(post("/share/{token}", link.token()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string("content of shared.txt"));
    }

    @Test
    @DisplayName("revoking: one's own under CREATE_SHARE_LINK, anyone's under REVOKE_SHARE_LINK, someone else's otherwise 403; a revoked link is dead at once")
    void revoking() throws Exception {
        ShareLinkDTO mine = shareLinkService.create(revision.getId(), 5, null, null, adminId);
        ShareLinkDTO alsoMine = shareLinkService.create(revision.getId(), 5, null, null, adminId);

        mockMvc.perform(delete("/resource/share-links/{id}", mine.id())
                        .with(user(principal(otherId, PermissionEnum.CREATE_SHARE_LINK))).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/resource/share-links/{id}", mine.id())
                        .with(user(principal(otherId, PermissionEnum.SHARE_LINKS_PAGE))).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(shareLinkService.usable(mine.token())).isPresent();

        mockMvc.perform(delete("/resource/share-links/{id}", mine.id())
                        .with(user(principal(adminId, PermissionEnum.CREATE_SHARE_LINK))).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DELETED"));
        assertThat(shareLinkService.usable(mine.token())).isEmpty();
        mockMvc.perform(post("/share/{token}", mine.token()).with(csrf())).andExpect(status().isNotFound());

        mockMvc.perform(delete("/resource/share-links/{id}", alsoMine.id())
                        .with(user(principal(otherId, PermissionEnum.REVOKE_SHARE_LINK))).with(csrf()))
                .andExpect(status().isOk());
        assertThat(shareLinkService.usable(alsoMine.token())).isEmpty();
    }

    @Test
    @DisplayName("the pages: the share-links page lists one's own links - everyone's for REVOKE_SHARE_LINK - and the explorer and the file page offer the panel to CREATE_SHARE_LINK only")
    void thePages() throws Exception {
        ShareLinkDTO mine = shareLinkService.create(revision.getId(), 5, "pw", 3, adminId);

        mockMvc.perform(get("/files/share-links").with(user(principal(adminId, PermissionEnum.SHARE_LINKS_PAGE))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("share-link-" + mine.id())))
                .andExpect(content().string(Matchers.containsString("0 / 3")))
                .andExpect(content().string(Matchers.not(Matchers.containsString(mine.token()))));
        mockMvc.perform(get("/files/share-links").with(user(principal(otherId, PermissionEnum.SHARE_LINKS_PAGE))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("share-link-" + mine.id()))));
        mockMvc.perform(get("/files/share-links").with(user(principal(otherId, PermissionEnum.SHARE_LINKS_PAGE, PermissionEnum.REVOKE_SHARE_LINK))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("share-link-" + mine.id())));
        mockMvc.perform(get("/files/share-links").with(user(principal(otherId, PermissionEnum.DOWNLOAD_FILE))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/files/explorer").with(user(principal(adminId, PermissionEnum.FILE_EXPLORER_PAGE, PermissionEnum.CREATE_SHARE_LINK))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("shareLinkPanel(SHARE_LINK)")))
                .andExpect(content().string(Matchers.containsString("maxMinutes:       30")));
        mockMvc.perform(get("/files/explorer").with(user(principal(adminId, PermissionEnum.FILE_EXPLORER_PAGE))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("shareLinkPanel(SHARE_LINK)"))));

        mockMvc.perform(get("/files/file-info/{id}", revision.getFileInfoId())
                        // The revision row's actions cell is the downloader's; the share button sits in it.
                        .with(user(principal(adminId, PermissionEnum.FILE_INFO_PAGE, PermissionEnum.DOWNLOAD_FILE, PermissionEnum.CREATE_SHARE_LINK))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("data-file-details-id=\"" + revision.getId() + "\"")))
                .andExpect(content().string(Matchers.containsString("shareLinkPanel(SHARE_LINK)")));
        mockMvc.perform(get("/files/file-info/{id}", revision.getFileInfoId())
                        .with(user(principal(adminId, PermissionEnum.FILE_INFO_PAGE, PermissionEnum.DOWNLOAD_FILE))).accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(Matchers.containsString("data-file-details-id="))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("shareLinkPanel(SHARE_LINK)"))));
    }

    // ---------------------------------------------------------------- helpers

    private FileDetailsDTO upload(String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(chain.tagId());
        request.setMultipartFile(new MockMultipartFile(fileName, fileName, "text/plain", TestData.bytesFor(fileName)));
        return fileService.createNewFile(request, adminId, 0);
    }

    private static UserDetailsImpl principal(int userId, PermissionEnum... permissions) {
        UserDetailsImpl userDetails = new UserDetailsImpl();
        userDetails.setId(userId);
        userDetails.setUsername("tester" + userId);
        userDetails.setPassword("irrelevant");
        userDetails.setEnabled(1);
        userDetails.setState(0);
        userDetails.setLoginType(0);
        userDetails.setPermissions(List.of(permissions));
        return userDetails;
    }
}
