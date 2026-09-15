package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.FileDetailsDTO;
import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.repository.GeneralTagRepository;
import com.hnp.filemanagement.repository.MainTagFileRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.service.FileService;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The file page's preview and its dates, and what a dropped connection during a download does.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FilePreviewPageTest.HangsUp.class)
// Rolled back: the taxonomy rows are inserted straight through the repositories, with no folder
// mirror, and must not stay behind for the tree and explorer tests that share the database.
@org.springframework.transaction.annotation.Transactional
class FilePreviewPageTest extends MySqlSupport {

    /** Stands in for Tomcat's client-abort, which MockMvc has no socket to produce. */
    @TestConfiguration
    @RestController
    static class HangsUp {
        @GetMapping("/test-only/client-abort")
        public String abort() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException("ServletOutputStream failed to write");
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FileService fileService;

    @Autowired
    private FileCategoryRepository fileCategoryRepository;
    @Autowired
    private FileSubCategoryRepository fileSubCategoryRepository;
    @Autowired
    private MainTagFileRepository mainTagFileRepository;
    @Autowired
    private GeneralTagRepository generalTagRepository;
    @Autowired
    private UserRepository userRepository;

    @Value("${file.management.base-dir}")
    private String baseDir;

    private int principalId;
    private int categoryId;
    private int subCategoryId;
    private int mainTagId;

    @BeforeEach
    void setUp() throws Exception {
        User creator = userRepository.save(TestData.user());
        principalId = creator.getId();

        GeneralTag generalTag = generalTagRepository.save(TestData.generalTag(creator, "gt" + TestData.nextSequence()));
        FileCategory category = fileCategoryRepository.save(TestData.category(creator, generalTag, "cat" + TestData.nextSequence()));
        categoryId = category.getId();
        FileSubCategory subCategory = fileSubCategoryRepository.save(TestData.subCategory(creator, category, "sub" + TestData.nextSequence()));
        subCategoryId = subCategory.getId();
        mainTagId = mainTagFileRepository.save(TestData.mainTag(creator, subCategory, "tag" + TestData.nextSequence())).getId();

        Files.createDirectories(Paths.get(baseDir, category.getCategoryName(), subCategory.getSubCategoryName()));
    }

    private static UserDetailsImpl administrator() {
        UserDetailsImpl userDetails = new UserDetailsImpl();
        userDetails.setId(1);
        userDetails.setUsername("tester");
        userDetails.setPassword("irrelevant");
        userDetails.setEnabled(1);
        userDetails.setState(0);
        userDetails.setLoginType(0);
        userDetails.setPermissions(List.of(PermissionEnum.ADMIN));
        return userDetails;
    }

    // ---------------------------------------------------------------- preview

    @Test
    @DisplayName("the file page offers a preview for a PDF, and the link opens it inline")
    void aPdfGetsAPreview() throws Exception {
        FileDetailsDTO stored = upload("manual.pdf");
        String downloadPath = "/files/file-info/" + stored.getFileInfoId() + "/file-details/" + stored.getId() + "/download";

        mockMvc.perform(get("/files/file-info/{id}", stored.getFileInfoId()).with(user(administrator())))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(downloadPath + "?inline=1")));

        mockMvc.perform(get(downloadPath).param("inline", "1").with(user(administrator())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("inline;")));

        mockMvc.perform(get(downloadPath).with(user(administrator())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment;")));
    }

    /** A browser cannot show a spreadsheet inline; offering a preview would only download it. */
    @Test
    @DisplayName("a format the browser cannot show gets no preview link")
    void aSpreadsheetGetsNoPreview() throws Exception {
        FileDetailsDTO stored = upload("figures.xlsx");

        mockMvc.perform(get("/files/file-info/{id}", stored.getFileInfoId()).with(user(administrator())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("?inline=1"))));
    }

    // ---------------------------------------------------------------- dates

    @Test
    @DisplayName("the file page shows its dates in the Jalali calendar, with the time")
    void datesAreJalali() throws Exception {
        FileDetailsDTO stored = upload("report.pdf");

        String page = mockMvc.perform(get("/files/file-info/{id}", stored.getFileInfoId()).with(user(administrator())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // A Jalali year in Persian digits, then a time; and no ISO timestamp anywhere on the page.
        org.assertj.core.api.Assertions.assertThat(page)
                .containsPattern("۱[۳-۴]۰[۰-۹]/[۰-۱][۰-۹]/[۰-۳][۰-۹] [۰-۲][۰-۹]:[۰-۵][۰-۹]")
                .doesNotContainPattern("20[0-9]{2}-[0-9]{2}-[0-9]{2}T");
    }

    // ---------------------------------------------------------------- a dropped connection

    /**
     * Before this, a client dropping the connection mid-download produced an ERROR stack trace
     * and then a second exception from trying to render the error page onto a committed response.
     * The advice now answers nothing at all, which is the only correct answer to a client that
     * is no longer there.
     */
    @Test
    @DisplayName("a client that hangs up mid-response is not an error and gets no error page")
    void aClientAbortIsNotAnError() throws Exception {
        mockMvc.perform(get("/test-only/client-abort").with(user(administrator())))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    // ---------------------------------------------------------------- helpers

    private FileDetailsDTO upload(String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setFileCategoryId(categoryId);
        request.setFileSubCategoryId(subCategoryId);
        request.setMainTagFileId(mainTagId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "application/octet-stream",
                ("content of " + fileName).getBytes(StandardCharsets.UTF_8)));
        return fileService.createNewFile(request, principalId, 1);
    }
}
