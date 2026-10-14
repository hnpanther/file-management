package com.hnp.filemanagement.report;

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

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The reports' pages and their CSV (roadmap 12.9): every report drawn, closed to whoever lacks
 * {@code REPORTS_PAGE}, an unknown one a 404; names escaped on the page; a CSV a spreadsheet opens -
 * a byte-order mark, every cell quoted, a name that begins as a formula never one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.reports.cache-minutes=0"})
class ReportPagesTest extends DatabaseSupport {

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

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        adminId = admin.getId();
        folder = FolderFixture.chain(folderRepository, tagGroupRepository, admin).tag();
    }

    @Test
    @DisplayName("every report is drawn, the menu marks the one open, and the reports are in the side menu")
    void everyReport() throws Exception {
        upload("=SUM(1;2)&x", 120);
        for (Report report : Report.values()) {
            String page = page(mockMvc.perform(get("/reports/" + report.slug()).with(user(person(PermissionEnum.REPORTS_PAGE)))), 200);
            assertThat(page).as(report.slug()).contains("id=\"report-menu\"", "aria-current=\"page\"", "id=\"report-counted-at\"")
                    .doesNotContain("&x-");
        }
        String overview = page(mockMvc.perform(get("/reports").with(user(person(PermissionEnum.REPORTS_PAGE)))), 200);
        assertThat(overview).contains("href=\"/reports\"", "id=\"report-stats\"", "id=\"report-table-folders\"",
                "/reports/overview/csv?table=folders");
        String largest = page(mockMvc.perform(get("/reports/largest").with(user(person(PermissionEnum.REPORTS_PAGE)))), 200);
        assertThat(largest).contains("=SUM(1;2)&amp;x-");
        assertThat(page(mockMvc.perform(get("/reports/growth").param("months", "24")
                .with(user(person(PermissionEnum.REPORTS_PAGE)))), 200)).containsPattern("<option value=\"24\"[^>]*selected");
    }

    @Test
    @DisplayName("closed to whoever lacks the permission; an unknown report and an unknown table are a 404")
    void closed() throws Exception {
        UserDetailsImpl nobody = person(PermissionEnum.FILE_EXPLORER_PAGE);
        assertThat(status(get("/reports"), nobody)).isEqualTo(403);
        assertThat(status(get("/reports/growth"), nobody)).isEqualTo(403);
        assertThat(status(get("/reports/growth/csv").param("table", "months"), nobody)).isEqualTo(403);
        UserDetailsImpl reader = person(PermissionEnum.REPORTS_PAGE);
        assertThat(status(get("/reports/nothing"), reader)).isEqualTo(404);
        assertThat(status(get("/reports/growth/csv").param("table", "nothing"), reader)).isEqualTo(404);
    }

    @Test
    @DisplayName("a CSV: a byte-order mark, the columns' names, every cell quoted, sizes in bytes, a formula never one")
    void csv() throws Exception {
        upload("+cmd(1)", 1234);
        MockHttpServletResponse response = mockMvc.perform(get("/reports/largest/csv").param("table", "revisions")
                .with(user(person(PermissionEnum.REPORTS_PAGE)))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment").contains("report-largest-revisions-");
        byte[] bytes = response.getContentAsByteArray();
        assertThat(bytes).startsWith(0xEF, 0xBB, 0xBF);
        String csv = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = csv.substring(1).split("\r\n");
        assertThat(lines[0]).isEqualTo("\"فایل\",\"نسخه\",\"نوع\",\"حجم\",\"بارگذاری\"");
        assertThat(csv).contains(" / +cmd(1)-").contains("\"1234\"");
        for (String line : lines) {
            assertThat(line).as("no cell begins as a formula").doesNotContainPattern("(^|,)\"[=+@]");
        }
    }

    // ================================================================ helpers

    private void upload(String name, int size) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("d");
        request.setFileNameDescription("d");
        request.setFolderId(folder.getId());
        byte[] content = new byte[size];
        java.util.Arrays.fill(content, (byte) 'a');
        request.setMultipartFile(new MockMultipartFile("file", name + "-" + TestData.nextSequence() + ".txt", "text/plain", content));
        fileService.createNewFile(request, adminId, FileService.PRIVATE);
        entityManager.flush();
    }

    private int status(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, UserDetailsImpl who)
            throws Exception {
        return mockMvc.perform(request.with(user(who))).andReturn().getResponse().getStatus();
    }

    private static String page(ResultActions result, int status) throws Exception {
        MockHttpServletResponse response = result.andReturn().getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as(body.length() > 600 ? body.substring(0, 600) : body).isEqualTo(status);
        return body;
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
