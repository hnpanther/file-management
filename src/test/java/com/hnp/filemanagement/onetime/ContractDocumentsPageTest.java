package com.hnp.filemanagement.onetime;

import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.domain.FolderKind;
import com.hnp.filemanagement.folder.domain.FolderService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The page of the one-off move of 2.16.1: an administrator's alone, its preview rendered with every message it
 * names. The run itself is {@link ContractDocumentsMoveTest}'s. Removed with {@link ContractDocumentsMove}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ContractDocumentsPageTest extends DatabaseSupport {

    private static final String PAGE = "/settings/contract-documents";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FolderService folderService;
    @Autowired
    private FileService fileService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;

    private User admin;
    private String rootPath;

    @BeforeEach
    void setUp() {
        User user = TestData.user();
        user.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(user);

        int n = TestData.nextSequence();
        int top = folderService.create(folderRepository.findFirstByKind(FolderKind.ROOT).orElseThrow().getId(),
                "ERP" + n, "ERP" + n, null, "erp" + n, admin.getId()).id();
        int companies = folderService.create(top, "PartyCompany", "PartyCompany", null, null, admin.getId()).id();
        int contracts = folderService.create(companies, "1234", "1234", null, null, admin.getId()).id();
        int container = folderService.create(contracts, "Contract", "Contract", null, null, admin.getId()).id();
        int contract = folderService.create(container, "98-001", "98-001", null, null, admin.getId()).id();
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("a contract");
        request.setFileNameDescription("contract.pdf");
        request.setFolderId(contract);
        request.setMultipartFile(new MockMultipartFile("file", "contract.pdf", "application/pdf", TestData.bytesFor("contract.pdf")));
        fileService.createNewFile(request, admin.getId(), FileService.PUBLIC);
        rootPath = "ERP" + n + "/PartyCompany";
    }

    @Test
    @DisplayName("the page and its preview are an administrator's, and render")
    void administratorsOnly() throws Exception {
        mockMvc.perform(get(PAGE).with(user(principal(admin, PermissionEnum.GENERAL_SETTINGS_PAGE,
                PermissionEnum.SAVE_GENERAL_SETTINGS, PermissionEnum.REST_MOVE_FILE_INFO))))
                .andExpect(status().isForbidden());

        String page = mockMvc.perform(get(PAGE).with(user(principal(admin, PermissionEnum.ADMIN))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("ERP/PartyCompany").contains("Documents");

        String preview = mockMvc.perform(post(PAGE + "/preview").param("root", rootPath).with(csrf())
                        .with(user(principal(admin, PermissionEnum.ADMIN))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(preview).contains("contract-documents-plan").doesNotContain("warning_message")
                .doesNotContain("??").contains("/settings/contract-documents/run")
                .contains("1 فایل منتقل می‌شود و 1 فولدر Documents ساخته می‌شود");

        String refused = mockMvc.perform(post(PAGE + "/preview").param("root", rootPath + "/Nothing").with(csrf())
                        .with(user(principal(admin, PermissionEnum.ADMIN))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(refused).contains("warning_message").contains(rootPath + "/Nothing");
    }

    private static UserDetailsImpl principal(User user, PermissionEnum... permissions) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(user.getId());
        principal.setUsername(user.getUsername());
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(1);
        principal.setPermissions(List.of(permissions));
        return principal;
    }
}
