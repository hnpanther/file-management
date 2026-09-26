package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.FileDetailsDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.service.FileService;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An upload that is refused says why - on the page, in Persian, in words the person can act on.
 *
 * <p>It used to be one sentence for everything: "enter the information correctly". A Visio file
 * nobody may upload, a name with a colon, a missing folder and a PDF that is not a PDF all read
 * the same, and the reason - which the application knew - reached only the log. The first test is
 * that case exactly, a {@code .vsdx} with a Persian name. Each test also checks the generic
 * sentence is gone, so a regression cannot hide behind a message that merely contains a keyword.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UploadErrorMessagesTest extends DatabaseSupport {

    private static final String GENERIC = "لطفا اطلاعات را بطور صحیح وارد نمایید";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FileService fileService;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private UserRepository userRepository;

    private int uploaderId;
    private int folderId;

    @BeforeEach
    void setUp() {
        User uploader = userRepository.save(TestData.user());
        uploaderId = uploader.getId();
        folderId = FolderFixture.chain(folderRepository, tagGroupRepository, uploader).tagId();
    }

    // ================================================================ the new-file form

    /** The case that was reported: a Visio drawing, a ZIP container like the Office formats. */
    @Test
    @DisplayName("a .vsdx is refused with its type named and the types this person may upload listed")
    void aTypeNobodyMayUploadIsNamed() throws Exception {
        String page = upload("فایل راهنمای 1.vsdx",
                new byte[]{'P', 'K', 3, 4, 20, 0, 6, 0}, folderId, "visio drawing");

        assertThat(page).contains("نوع فایل .vsdx")   // "نوع فایل .vsdx"
                .contains("pdf").contains("docx")
                .doesNotContain(GENERIC);
        assertThat(fileInfoRepository.findAll()).noneMatch(f -> f.getFileName().startsWith("فایل راهنمای"));
    }

    @Test
    @DisplayName("a file whose bytes do not match its extension is named, with what to suspect")
    void contentThatDoesNotMatchIsNamed() throws Exception {
        String page = upload("report.pdf", "not a pdf at all".getBytes(StandardCharsets.UTF_8), folderId, "fake");

        assertThat(page).contains("«report.pdf»").contains(".pdf").doesNotContain(GENERIC);
    }

    @Test
    @DisplayName("a name that cannot be stored is shown, with the rule it broke")
    void anUnstorableNameIsShown() throws Exception {
        String colon = upload("minutes: final.txt", "text".getBytes(StandardCharsets.UTF_8), folderId, "colon");
        assertThat(colon).contains("«minutes: final.txt»").doesNotContain(GENERIC);

        String noExtension = upload("README", "text".getBytes(StandardCharsets.UTF_8), folderId, "bare");
        assertThat(noExtension).contains("«README»").doesNotContain(GENERIC);
    }

    @Test
    @DisplayName("no folder chosen, and a folder that holds only folders, each say so")
    void theFolderIsExplained() throws Exception {
        String none = upload("a.txt", "text".getBytes(StandardCharsets.UTF_8), null, "no folder");
        assertThat(none).contains("پوشهٔ مقصد را انتخاب کنید")   // "پوشهٔ مقصد را انتخاب کنید"
                .doesNotContain(GENERIC);

        int root = FolderFixture.root(folderRepository).getId();
        String rootPage = upload("a.txt", "text".getBytes(StandardCharsets.UTF_8), root, "at the root");
        assertThat(rootPage).contains("نمی‌شود فایل گذاشت")   // "نمی‌شود فایل گذاشت"
                .doesNotContain(GENERIC);
    }

    @Test
    @DisplayName("a missing description names the field")
    void aMissingFieldIsNamed() throws Exception {
        String page = upload("a.txt", "text".getBytes(StandardCharsets.UTF_8), folderId, null);

        assertThat(page).contains("این موارد را کامل کنید")   // "این موارد را کامل کنید"
                .contains("توضیحات")   // "توضیحات"
                .doesNotContain(GENERIC);
    }

    @Test
    @DisplayName("two missing fields are named together, separated as Persian is written")
    void twoMissingFieldsAreListed() throws Exception {
        String page = mockMvc.perform(multipart("/files").param("folderId", String.valueOf(folderId))
                        .with(user(principal(PermissionEnum.SAVE_NEW_FILE))).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page)
                .contains("فایل موردنظر")   // "فایل موردنظر"
                .contains("توضیحات")                              // "توضیحات"
                .containsPattern("ت، ف|ر، ت")                            // "توضیحات، فایل…" or "…موردنظر، توضیحات"
                .doesNotContain(GENERIC);
    }

    // ================================================================ the new-version form

    @Test
    @DisplayName("a new version under another name says which name it must carry")
    void aVersionUnderAnotherNameSaysWhichName() throws Exception {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("the original");
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("multipartFile", "budget.pdf", "application/pdf",
                TestData.bytesFor("budget.pdf")));
        FileDetailsDTO first = fileService.createNewFile(request, uploaderId, FileService.PRIVATE);

        String page = mockMvc.perform(multipart("/files/file-info/{id}/file-details", first.getFileInfoId())
                        .file(new MockMultipartFile("multipartFile", "forecast.pdf", "application/pdf",
                                TestData.bytesFor("forecast.pdf")))
                        .param("fileId", String.valueOf(first.getFileInfoId()))
                        .param("fileDetailsId", String.valueOf(first.getId()))
                        .param("fileName", "budget")
                        .param("fileDetailsDescription", "v2")
                        .param("version", "2")
                        .param("type", "version")
                        .with(user(principal(PermissionEnum.SAVE_NEW_FILE_DETAILS))).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("«forecast.pdf»").contains("«budget»").doesNotContain(GENERIC);
    }

    // ================================================================ the API keeps its English

    /** The machines' answer is unchanged in kind: a 400 whose detail names the rule, in English. */
    @Test
    @DisplayName("through the API the same .vsdx is a 400 whose English detail names the type")
    void theApiStillAnswersInEnglish() throws Exception {
        mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("multipartFile", "drawing.vsdx", "application/octet-stream",
                                new byte[]{'P', 'K', 3, 4, 20, 0, 6, 0}))
                        .param("description", "visio").param("folderId", String.valueOf(folderId))
                        .with(user(principal(PermissionEnum.API_SAVE_NEW_FILE))).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("file type .vsdx is not allowed")));
    }

    // ---------------------------------------------------------------- helpers

    private String upload(String fileName, byte[] bytes, Integer folder, String description) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/files")
                .file(new MockMultipartFile("multipartFile", fileName, "application/octet-stream", bytes));
        if (description != null) {
            request.param("description", description);
        }
        if (folder != null) {
            request.param("folderId", String.valueOf(folder));
        }
        return mockMvc.perform(request.with(user(principal(PermissionEnum.SAVE_NEW_FILE))).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private UserDetailsImpl principal(PermissionEnum... permissions) {
        UserDetailsImpl userDetails = new UserDetailsImpl();
        userDetails.setId(uploaderId);
        userDetails.setUsername("uploader");
        userDetails.setPassword("irrelevant");
        userDetails.setEnabled(1);
        userDetails.setState(0);
        userDetails.setLoginType(0);
        userDetails.setPermissions(List.of(permissions));
        return userDetails;
    }
}
