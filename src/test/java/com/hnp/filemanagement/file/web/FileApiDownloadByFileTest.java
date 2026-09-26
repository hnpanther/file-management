package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.file.domain.FileUploadDTO;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/files/file-info/{fileInfoId}/download} (1.9.0): a file downloaded by the
 * file's own id - the number or the external id - for a client that keeps that and not a
 * revision's. The latest version by default, another with {@code ?version=}, a format with
 * {@code ?format=}; judged on the file's folder like every download; and every v1 download says
 * which revision it served.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FileApiDownloadByFileTest extends DatabaseSupport {

    private static final byte[] V1 = TestData.bytesFor("report.pdf");
    private static final byte[] V2 = concat(TestData.bytesFor("report.pdf"), " second version");
    private static final byte[] V2_DOCX = TestData.bytesFor("report.docx");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FileService fileService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private int folderId;
    private int fileId;
    private String fileExternalId;
    private int v1Id;
    private int v2PdfId;

    /** One file: version 1 as a PDF, version 2 as a PDF and a DOCX. */
    @BeforeEach
    void setUp() throws Exception {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        adminId = userRepository.save(admin).getId();
        folderId = FolderFixture.chain(folderRepository, tagGroupRepository, admin).tagId();

        String body = mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("multipartFile", "report.pdf", "application/pdf", V1))
                        .param("description", "the report")
                        .param("folderId", String.valueOf(folderId))
                        .with(user(principal(adminId, PermissionEnum.API_SAVE_NEW_FILE)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        fileId = JsonPath.read(body, "$.fileId");
        fileExternalId = JsonPath.read(body, "$.fileExternalId");
        v1Id = JsonPath.read(body, "$.fileDetailsId");

        fileService.createNewFileDetails(request("version", 2, "report.pdf", V2, null), adminId);
        entityManager.flush();
        v2PdfId = entityManager.createQuery(
                        "SELECT d.id FROM FileDetails d WHERE d.fileInfo.id = :f AND d.version = 2", Integer.class)
                .setParameter("f", fileId).getSingleResult();
    }

    @Test
    @DisplayName("by the file's number or its external id, in any case: the latest version, and headers saying which revision it was")
    void theLatestVersionByEitherId() throws Exception {
        for (String id : List.of(String.valueOf(fileId), fileExternalId, fileExternalId.toUpperCase(Locale.ROOT))) {
            download(id, "")
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(V2))
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith("application/pdf")))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("filename=\"report.pdf\"")))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-File-Version", "2"))
                    .andExpect(header().string("X-File-Details-Id", String.valueOf(v2PdfId)))
                    .andExpect(header().string("X-File-External-Id", fileExternalId))
                    .andExpect(header().exists("X-File-Details-External-Id"))
                    .andExpect(header().string("X-Checksum-SHA256", sha256(V2)));
        }
    }

    @Test
    @DisplayName("?version= serves an older version")
    void anOlderVersion() throws Exception {
        download(fileExternalId, "?version=1")
                .andExpect(status().isOk())
                .andExpect(content().bytes(V1))
                .andExpect(header().string("X-File-Version", "1"))
                .andExpect(header().string("X-File-Details-Id", String.valueOf(v1Id)));
    }

    @Test
    @DisplayName("a version with two formats needs ?format= - a 400 listing them without it - and takes it in any case, with or without the dot")
    void aVersionWithSeveralFormats() throws Exception {
        fileService.createNewFileDetails(request("format", 2, "report.docx", V2_DOCX, v2PdfId), adminId);
        entityManager.flush();

        download(String.valueOf(fileId), "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("docx, pdf")))
                .andExpect(jsonPath("$.detail").value(containsString("?format=")));

        download(String.valueOf(fileId), "?format=docx").andExpect(status().isOk()).andExpect(content().bytes(V2_DOCX));
        download(fileExternalId, "?format=.PDF").andExpect(status().isOk()).andExpect(content().bytes(V2));
        download(fileExternalId, "?version=1&format=pdf").andExpect(status().isOk()).andExpect(content().bytes(V1));
    }

    @Test
    @DisplayName("a version or format the file does not have is a 404 that says what it has; a bad version is a 400")
    void whatTheFileDoesNotHave() throws Exception {
        download(fileExternalId, "?version=7")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("latest is 2")));
        download(fileExternalId, "?format=xlsx")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("it has: pdf")));
        download(fileExternalId, "?version=0").andExpect(status().isBadRequest());
        download(fileExternalId, "?version=abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("InvalidParameter"));
    }

    @Test
    @DisplayName("an unknown file is a 404 by either id; a malformed id is the usual 400 that does not echo it")
    void unknownAndMalformed() throws Exception {
        download("999999", "").andExpect(status().isNotFound());
        download("3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d", "").andExpect(status().isNotFound());
        download("not-an-id", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("InvalidParameter"))
                .andExpect(jsonPath("$.detail").value(containsString("fileInfoId")));
    }

    @Test
    @DisplayName("judged on the file's folder like every download: a stranger is refused, and learns nothing about the versions")
    void folderAccess() throws Exception {
        int strangerId = userRepository.save(TestData.user()).getId();
        entityManager.flush();

        for (String query : List.of("", "?version=99", "?format=xlsx")) {
            mockMvc.perform(get("/api/v1/files/file-info/" + fileExternalId + "/download" + query)
                            .with(user(principal(strangerId, PermissionEnum.API_DOWNLOAD_FILE)))
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/api/v1/files/file-info/" + fileId + "/download")
                        .with(user(principal(adminId, PermissionEnum.API_SAVE_NEW_FILE)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the revision downloads say which revision they served too; their bytes are unchanged")
    void theOtherDownloadsCarryTheHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/files/file-details/{d}/download", v1Id)
                        .with(user(principal(adminId, PermissionEnum.API_DOWNLOAD_FILE))))
                .andExpect(status().isOk())
                .andExpect(content().bytes(V1))
                .andExpect(header().string("X-File-Version", "1"))
                .andExpect(header().string("X-Checksum-SHA256", sha256(V1)));
        mockMvc.perform(get("/api/v1/files/file-info/{f}/file-details/{d}/download", fileId, v2PdfId)
                        .with(user(principal(adminId, PermissionEnum.API_DOWNLOAD_FILE))))
                .andExpect(status().isOk())
                .andExpect(content().bytes(V2))
                .andExpect(header().string("X-File-Details-Id", String.valueOf(v2PdfId)));
    }

    /**
     * How a client holding only the numbers of what it stored learns their external ids: a HEAD
     * to the download it already makes. {@code FileApi} answers it with no body at all rather than
     * letting Spring run the GET and drop the bytes, so the empty body here is the handler's, not
     * the container's - and the length is the stored file's.
     */
    @Test
    @DisplayName("a HEAD to a download by the old numbers answers both external ids, and the size without the bytes")
    void aHeadRequestTellsTheExternalIds() throws Exception {
        String revisionExternalId = mockMvc.perform(head("/api/v1/files/file-details/{d}/download", v1Id)
                        .with(user(principal(adminId, PermissionEnum.API_DOWNLOAD_FILE))))
                .andExpect(status().isOk())
                .andExpect(header().string("X-File-External-Id", fileExternalId))
                .andExpect(header().string("X-File-Version", "1"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, V1.length))
                .andExpect(content().bytes(new byte[0]))
                .andReturn().getResponse().getHeader("X-File-Details-External-Id");

        mockMvc.perform(get("/api/v1/files/file-details/{d}/download", revisionExternalId)
                        .with(user(principal(adminId, PermissionEnum.API_DOWNLOAD_FILE))))
                .andExpect(status().isOk())
                .andExpect(content().bytes(V1));
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions download(String fileReference, String query) throws Exception {
        return mockMvc.perform(get("/api/v1/files/file-info/" + fileReference + "/download" + query)
                .with(user(principal(adminId, PermissionEnum.API_DOWNLOAD_FILE)))
                .accept(MediaType.APPLICATION_JSON));
    }

    private FileUploadDTO request(String type, int version, String fileName, byte[] bytes, Integer sampleId) {
        FileUploadDTO request = new FileUploadDTO();
        request.setFileId(fileId);
        request.setFileName("report");
        request.setFileNameWithoutExtension("report");
        request.setVersion(version);
        request.setType(type);
        request.setFileDetailsId(sampleId);
        request.setFileDetailsDescription(type + " " + version);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "application/octet-stream", bytes));
        return request;
    }

    private static UserDetailsImpl principal(int userId, PermissionEnum... permissions) {
        UserDetailsImpl userDetails = new UserDetailsImpl();
        userDetails.setId(userId);
        userDetails.setUsername("user" + userId);
        userDetails.setPassword("irrelevant");
        userDetails.setEnabled(1);
        userDetails.setState(0);
        userDetails.setLoginType(0);
        userDetails.setPermissions(List.of(permissions));
        return userDetails;
    }

    private static byte[] concat(byte[] head, String tail) {
        byte[] extra = tail.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + extra.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(extra, 0, all, head.length, extra.length);
        return all;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
