package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.ApiKeyDTO;
import com.hnp.filemanagement.identity.domain.ApiKeyService;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The server's upload cap bounds a v2 PUT as it bounds a form (issue 44): the body is no longer
 * bound as a {@code byte[]}, and one above the cap is a 413 naming the cap - not a whole file on
 * the heap, and not a 500. The cap is set small here so the test sends kilobytes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "filemanagement.folder-access.enabled=true",
        "spring.servlet.multipart.max-file-size=4KB",
        "spring.servlet.multipart.max-request-size=5KB"})
class UploadCapTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;

    private String bucket;
    private String prefix;
    private String credential;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(TestData.user());
        FolderFixture.Chain chain = FolderFixture.chain(folderRepository, tagGroupRepository, owner);
        Folder tag = chain.tag();
        bucket = chain.category().getName();
        prefix = chain.subCategory().getName() + "/" + tag.getName();

        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("cap " + TestData.nextSequence());
        request.setFolderGrants(List.of(tag.getId() + ":WRITE"));
        credential = apiKeyService.create(request, owner.getId()).credential();
    }

    @Test
    @DisplayName("a body at the cap is stored")
    void aBodyAtTheCapIsStored() throws Exception {
        mockMvc.perform(put("/api/v2/" + bucket + "/" + prefix + "/small/small.txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("x".repeat(4 * 1024).getBytes()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.size").value(4 * 1024));
    }

    @Test
    @DisplayName("a body above the cap is a 413 naming the cap, and nothing is stored")
    void aBodyAboveTheCapIs413() throws Exception {
        mockMvc.perform(put("/api/v2/" + bucket + "/" + prefix + "/large/large.txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("x".repeat(4 * 1024 + 1).getBytes()))
                .andExpect(status().isContentTooLarge())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("UploadTooLarge"))
                .andExpect(jsonPath("$.detail").value("the upload is larger than the server's cap of 4 KB"));

        mockMvc.perform(get("/api/v2/" + bucket + "/" + prefix + "/large/v1/large.txt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                .andExpect(status().isNotFound());
    }
}
