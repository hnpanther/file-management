package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FileDetailsRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.support.FolderFixture;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The v1 delete, end to end and committed: upload through the API, delete through the API, and
 * then look at the database and the disk rather than at the response.
 *
 * <p>Not rolled back, on purpose. The service tests cover the same code inside one transaction
 * that is never committed, which cannot see a foreign key that only bites at commit - and since
 * Phase 7 steps 1 and 2 a file has a folder and tags, both referenced by foreign keys, so the
 * whole-file delete is the path with the most ways to fail outside a test. What is asserted is
 * what an integration that calls this endpoint relies on: the rows are gone, the bytes are gone,
 * and a second delete of the same thing is a 404, not a 500.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FileApiDeleteTest extends MySqlSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private com.hnp.filemanagement.service.FileService fileService;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;

    @Value("${file.management.base-dir}")
    private String baseDir;

    private int principalId;
    private int tagFolderId;
    private String categoryName;
    private String subCategoryName;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(TestData.user());
        principalId = owner.getId();
        FolderFixture.Chain chain = FolderFixture.chain(folderRepository, tagGroupRepository, owner);
        categoryName = chain.category().getName();
        subCategoryName = chain.subCategory().getName();
        tagFolderId = chain.tagId();
    }

    @Test
    @DisplayName("deleting the only version through v1 removes the file, its folder link, its tags and its bytes")
    void deletingTheOnlyVersionRemovesEverything() throws Exception {
        int[] ids = uploadThroughV1("report.txt");
        int fileInfoId = ids[0];
        int fileDetailsId = ids[1];
        Path fileDirectory = Paths.get(baseDir, categoryName, subCategoryName, "report");
        assertThat(fileDirectory).exists();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM file_tag WHERE file_info_id = ?", Integer.class, fileInfoId))
                .as("the upload tagged it").isEqualTo(3);

        mockMvc.perform(delete("/api/v1/files/file-info/{f}/file-details/{d}", fileInfoId, fileDetailsId)
                        .with(user(machine()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DELETED"))
                .andExpect(jsonPath("$.id").value(fileDetailsId));

        assertThat(fileInfoRepository.findById(fileInfoId)).isEmpty();
        assertThat(fileDetailsRepository.findById(fileDetailsId)).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM file_tag WHERE file_info_id = ?", Integer.class, fileInfoId))
                .isZero();
        assertThat(fileDirectory).as("the file's directory on disk is gone with it").doesNotExist();

        mockMvc.perform(delete("/api/v1/files/file-info/{f}/file-details/{d}", fileInfoId, fileDetailsId)
                        .with(user(machine()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("deleting one of two versions through v1 leaves the other, on disk and in the database")
    void deletingOneVersionLeavesTheOther() throws Exception {
        int[] first = uploadThroughV1("manual.txt");
        int fileInfoId = first[0];
        // A second version through the service: the v1 API has no "new version" call.
        com.hnp.filemanagement.dto.FileUploadDTO second = new com.hnp.filemanagement.dto.FileUploadDTO();
        second.setFileId(fileInfoId);
        second.setFileName("manual");
        second.setFileNameWithoutExtension("manual");
        second.setVersion(2);
        second.setType("version");
        second.setFileDetailsDescription("second");
        second.setMultipartFile(new MockMultipartFile("file", "manual.txt", "text/plain",
                "v2".getBytes(StandardCharsets.UTF_8)));
        fileService.createNewFileDetails(second, principalId);
        int v2DetailsId = jdbcTemplate.queryForObject(
                "SELECT id FROM file_details WHERE file_info_id = ? AND version = 2", Integer.class, fileInfoId);

        mockMvc.perform(delete("/api/v1/files/file-info/{f}/file-details/{d}", fileInfoId, v2DetailsId)
                        .with(user(machine()))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DELETED"));

        assertThat(fileInfoRepository.findById(fileInfoId)).isPresent();
        assertThat(fileDetailsRepository.findById(first[1])).isPresent();
        assertThat(fileDetailsRepository.findById(v2DetailsId)).isEmpty();
        assertThat(Paths.get(baseDir, categoryName, subCategoryName, "manual", "v1", "manual.txt")).exists();
        assertThat(Paths.get(baseDir, categoryName, subCategoryName, "manual", "v2")).doesNotExist();
        assertThat(fileInfoRepository.findById(fileInfoId).orElseThrow().getLastVersion()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- helpers

    /** {@code {fileInfoId, fileDetailsId}} of a file uploaded through {@code POST /api/v1/files}. */
    private int[] uploadThroughV1(String fileName) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("multipartFile", fileName, "text/plain",
                                ("content of " + fileName).getBytes(StandardCharsets.UTF_8)))
                        .param("description", "uploaded through v1")
                        .param("folderId", String.valueOf(tagFolderId))
                        .with(user(machine())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new int[]{JsonPath.read(body, "$.fileId"), JsonPath.read(body, "$.fileDetailsId")};
    }

    private UserDetailsImpl machine() {
        return principal(PermissionEnum.API_SAVE_NEW_FILE, PermissionEnum.API_DELETE_FILE_DETAILS);
    }

    private UserDetailsImpl principal(PermissionEnum... permissions) {
        UserDetailsImpl userDetails = new UserDetailsImpl();
        userDetails.setId(principalId);
        userDetails.setUsername("machine");
        userDetails.setPassword("irrelevant");
        userDetails.setEnabled(1);
        userDetails.setState(0);
        userDetails.setLoginType(0);
        userDetails.setPermissions(List.of(permissions));
        return userDetails;
    }
}
