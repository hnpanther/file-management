package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.service.FileService;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
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
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Whether a new file is public. Since 1.7.0 it is not, unless the upload says so - on the web form
 * with a box left unticked by default, and on the v1 API with {@code public-file=1} or
 * {@code true}. It used to be the other way round on both: the form always published, and the API
 * published unless told {@code 0}.
 *
 * <p>What "public" does is checked by what it opens, not by the column: the public download answers
 * for a public file and not for a private one. And what it does not do is checked too, because the
 * PL/SQL clients depend on it: a private file still downloads through the API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UploadVisibilityTest extends DatabaseSupport {

    private static final int PUBLIC_STATE = 0;
    private static final int PRIVATE_STATE = -1;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;

    private int uploaderId;
    private int folderId;

    @BeforeEach
    void setUp() {
        User uploader = userRepository.save(TestData.user());
        uploaderId = uploader.getId();
        folderId = FolderFixture.chain(folderRepository, tagGroupRepository, uploader).tagId();
    }

    // ================================================================ the v1 API

    @Test
    @DisplayName("through the API a file is private unless public-file says 1 or true - absent, 0, false and anything else are private, never an error")
    void theApiPublishesOnlyWhenAsked() throws Exception {
        assertThat(apiUpload("absent.txt", null)).isEqualTo(PRIVATE_STATE);
        assertThat(apiUpload("zero.txt", "0")).isEqualTo(PRIVATE_STATE);
        assertThat(apiUpload("false.txt", "false")).isEqualTo(PRIVATE_STATE);
        assertThat(apiUpload("odd.txt", "yes")).as("unclear is private, and still stored").isEqualTo(PRIVATE_STATE);

        assertThat(apiUpload("one.txt", "1")).isEqualTo(PUBLIC_STATE);
        assertThat(apiUpload("true.txt", "true")).isEqualTo(PUBLIC_STATE);
        assertThat(apiUpload("upper.txt", " TRUE ")).isEqualTo(PUBLIC_STATE);
    }

    /**
     * Private is not "not downloadable". The public download refuses it - that is all private
     * means - and the API, which the PL/SQL clients use, serves it as before.
     */
    @Test
    @DisplayName("a private file is refused by the public download and still served by the API; a public one by both")
    void privateOnlyClosesThePublicDownload() throws Exception {
        apiUpload("closed.txt", null);
        apiUpload("open.txt", "1");
        int closed = revisionOf("closed");
        int open = revisionOf("open");

        mockMvc.perform(get("/files/public-download/{id}", closed)).andExpect(status().isNotFound());
        mockMvc.perform(get("/files/public-download/{id}", open)).andExpect(status().isOk());

        for (int revision : new int[]{closed, open}) {
            mockMvc.perform(get("/api/v1/files/file-details/{id}/download", revision)
                            .with(user(principal(PermissionEnum.API_DOWNLOAD_FILE))))
                    .andExpect(status().isOk());
        }
    }

    // ================================================================ the web form

    @Test
    @DisplayName("the upload form offers a public box, unticked; left alone the file is private, ticked it is public")
    void theFormPublishesOnlyWhenTicked() throws Exception {
        String form = mockMvc.perform(get("/files/create").with(user(principal(PermissionEnum.CREATE_FILE_PAGE))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(boxOf(form)).contains("name=\"public-file\"").doesNotContain("checked");

        assertThat(formUpload("unticked.txt", false)).isEqualTo(PRIVATE_STATE);
        assertThat(formUpload("ticked.txt", true)).isEqualTo(PUBLIC_STATE);
    }

    @Test
    @DisplayName("a refused upload comes back with the box as it was sent")
    void aRefusedUploadKeepsTheBox() throws Exception {
        formUpload("taken.txt", false);

        String refused = mockMvc.perform(formRequest("taken.txt", true)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(boxOf(refused)).contains("checked");
    }

    @Test
    @DisplayName("public-file is read the same way everywhere: 1 or true, trimmed, in any case")
    void theParameterIsReadOneWay() {
        for (String yes : List.of("1", "true", "TRUE", " True ")) {
            assertThat(FileService.visibilityOf(yes)).as(yes).isEqualTo(FileService.PUBLIC);
        }
        for (String no : Arrays.asList(null, "", "0", "false", "yes", "on", "2")) {
            assertThat(FileService.visibilityOf(no)).as(String.valueOf(no)).isEqualTo(FileService.PRIVATE);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Uploads through v1 and answers the stored state; {@code publicFile} null sends no parameter. */
    private int apiUpload(String fileName, String publicFile) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/v1/files")
                .file(new MockMultipartFile("multipartFile", fileName, "text/plain",
                        ("content of " + fileName).getBytes(StandardCharsets.UTF_8)));
        request.param("description", "visibility").param("folderId", String.valueOf(folderId));
        if (publicFile != null) {
            request.param("public-file", publicFile);
        }
        mockMvc.perform(request.with(user(principal(PermissionEnum.API_SAVE_NEW_FILE))).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        return stateOf(fileName.substring(0, fileName.lastIndexOf('.')));
    }

    private int formUpload(String fileName, boolean ticked) throws Exception {
        mockMvc.perform(formRequest(fileName, ticked)).andExpect(status().isOk());
        return stateOf(fileName.substring(0, fileName.lastIndexOf('.')));
    }

    private MockMultipartHttpServletRequestBuilder formRequest(String fileName, boolean ticked) {
        MockMultipartHttpServletRequestBuilder request = multipart("/files")
                .file(new MockMultipartFile("multipartFile", fileName, "text/plain",
                        ("content of " + fileName).getBytes(StandardCharsets.UTF_8)));
        request.param("description", "through the form").param("folderId", String.valueOf(folderId));
        if (ticked) {
            request.param("public-file", "1");
        }
        request.with(user(principal(PermissionEnum.SAVE_NEW_FILE))).with(csrf());
        return request;
    }

    private int stateOf(String name) {
        entityManager.flush();
        entityManager.clear();
        FileInfo file = fileInfoRepository.findByFolderIdAndFileName(folderId, name).orElseThrow();
        return file.getState();
    }

    private int revisionOf(String name) {
        int fileId = fileInfoRepository.findByFolderIdAndFileName(folderId, name).orElseThrow().getId();
        return fileDetailsRepository.findAll().stream()
                .filter(details -> details.getFileInfo().getId() == fileId)
                .findFirst().orElseThrow().getId();
    }

    /** The checkbox's own tag, so that "checked" is looked for there and nowhere else on the page. */
    private static String boxOf(String page) {
        int at = page.indexOf("id=\"publicFile\"");
        assertThat(at).as("the page has the public box").isNotNegative();
        int start = page.lastIndexOf('<', at);
        return page.substring(start, page.indexOf('>', at) + 1);
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
