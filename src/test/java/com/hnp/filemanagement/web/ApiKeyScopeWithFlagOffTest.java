package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.dto.ApiKeyDTO;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.service.ApiKeyService;
import com.hnp.filemanagement.service.FolderAccessService;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.support.FolderFixture;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code filemanagement.folder-access.enabled} governs people. An API key is created with its
 * grants and reaches those and nothing else, whether the flag is on or off - here it is off (the
 * shipped default), a person with no grant at all reaches everything, and a key does not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiKeyScopeWithFlagOffTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FolderAccessService folderAccessService;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;

    private int ownerId;
    private int tagFolderId;
    private int otherTagFolderId;
    private String bucket;
    private String prefix;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(TestData.user());
        ownerId = owner.getId();
        FolderFixture.Chain chain = FolderFixture.chain(folderRepository, tagGroupRepository, owner);
        bucket = chain.category().getName();
        tagFolderId = chain.tagId();
        otherTagFolderId = FolderFixture.tag(folderRepository, chain.subCategory(), owner, "Other" + TestData.nextSequence()).getId();
        prefix = chain.subCategory().getName() + "/" + chain.tag().getName();
    }

    @Test
    @DisplayName("with the flag off a person is unrestricted, but a key reaches only its grants - on v1 by id and on v2 alike")
    void aKeyIsScopedWhateverTheFlagSays() throws Exception {
        assertThat(folderAccessService.isEnforced()).as("the shipped default").isFalse();

        // A person with no grant at all: unrestricted, as the flag promises.
        String body = mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("multipartFile", "open.txt", null, TestData.bytesFor("open.txt")))
                        .param("description", "d").param("folderId", String.valueOf(tagFolderId))
                        .with(user(principal(ownerId, PermissionEnum.API_SAVE_NEW_FILE))).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int detailsId = JsonPath.read(body, "$.fileDetailsId");

        // A key granted the *other* folder: refused on this one, on every route.
        String elsewhere = apiKey(otherTagFolderId + ":WRITE");
        mockMvc.perform(get("/api/v1/files/file-details/{d}/download", detailsId).header(HttpHeaders.AUTHORIZATION, "Bearer " + elsewhere))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/files/file-details/{d}", detailsId).header(HttpHeaders.AUTHORIZATION, "Bearer " + elsewhere).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("multipartFile", "bykey.txt", null, TestData.bytesFor("bykey.txt")))
                        .param("description", "d").param("folderId", String.valueOf(tagFolderId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + elsewhere).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v2/" + bucket + "/" + prefix + "/open/v1/open.txt").header(HttpHeaders.AUTHORIZATION, "Bearer " + elsewhere))
                .andExpect(status().isForbidden());

        // A key granted this folder: allowed.
        String here = apiKey(tagFolderId + ":WRITE");
        mockMvc.perform(get("/api/v1/files/file-details/{d}/download", detailsId).header(HttpHeaders.AUTHORIZATION, "Bearer " + here))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v2/" + bucket + "/" + prefix + "/open/v1/open.txt").header(HttpHeaders.AUTHORIZATION, "Bearer " + here))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/files/file-details/{d}", detailsId).header(HttpHeaders.AUTHORIZATION, "Bearer " + here).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- helpers

    private String apiKey(String... grants) {
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("scope " + TestData.nextSequence());
        request.setFolderGrants(List.of(grants));
        return apiKeyService.create(request, ownerId).credential();
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
}
