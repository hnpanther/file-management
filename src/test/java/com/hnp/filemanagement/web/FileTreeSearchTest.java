package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.FileCategory;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.FileSubCategory;
import com.hnp.filemanagement.entity.GeneralTag;
import com.hnp.filemanagement.entity.MainTagFile;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.repository.FileCategoryRepository;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FileSubCategoryRepository;
import com.hnp.filemanagement.repository.GeneralTagRepository;
import com.hnp.filemanagement.repository.MainTagFileRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The tree's "find a file" search — see {@code docs/issues.md}, issue 73: two nodes at different
 * depths of the same category can carry the identical label, so a label alone cannot say where a
 * file lives. A hit has to carry the full chain down to the file, not just its own name.
 *
 * <p>{@code @ServiceIntegrationTest} rather than plain {@code @SpringBootTest} because, unlike its
 * sibling {@link FileTreeTest}, this needs real fixtures to prove the path a hit reports is the
 * file's actual one — the transaction rolls back afterwards so nothing is left for the next class.
 */
@ServiceIntegrationTest
@AutoConfigureMockMvc
class FileTreeSearchTest extends MySqlSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private GeneralTagRepository generalTagRepository;
    @Autowired
    private FileCategoryRepository fileCategoryRepository;
    @Autowired
    private FileSubCategoryRepository fileSubCategoryRepository;
    @Autowired
    private MainTagFileRepository mainTagFileRepository;
    @Autowired
    private FileInfoRepository fileInfoRepository;

    private FileCategory category;
    private FileSubCategory subCategory;
    private MainTagFile mainTag;
    private FileInfo fileInfo;

    private static UserDetailsImpl principal(PermissionEnum... permissions) {
        UserDetailsImpl userDetails = new UserDetailsImpl();
        userDetails.setId(1);
        userDetails.setUsername("tester");
        userDetails.setPassword("irrelevant");
        userDetails.setEnabled(1);
        userDetails.setState(0);
        userDetails.setLoginType(0);
        userDetails.setPermissions(List.of(permissions));
        return userDetails;
    }

    @BeforeEach
    void setUp() {
        User creator = userRepository.save(TestData.user());
        GeneralTag generalTag = generalTagRepository.save(
                TestData.generalTag(creator, "tag" + TestData.nextSequence()));
        category = fileCategoryRepository.save(
                TestData.category(creator, generalTag, "IMS" + TestData.nextSequence()));
        subCategory = fileSubCategoryRepository.save(
                TestData.subCategory(creator, category, "DocSystem" + TestData.nextSequence()));
        // Named like the reported case: a main tag whose label happens to collide with something
        // else in the tree is exactly what issue 73 is about, but the search has to find it either
        // way - the point of a hit is that it does not depend on the label being unique.
        mainTag = mainTagFileRepository.save(
                TestData.mainTag(creator, subCategory, "HSED" + TestData.nextSequence()));
        fileInfo = fileInfoRepository.save(TestData.fileInfo(creator, mainTag, "WI-HSE-SA" + TestData.nextSequence()));
    }

    @Test
    @DisplayName("a search by exact id finds the file and reports its real path")
    void findsByExactId() throws Exception {
        mockMvc.perform(get("/resource/files/tree/search")
                        .param("query", String.valueOf(fileInfo.getId()))
                        .with(user(principal(PermissionEnum.REST_SEARCH_FILE_TREE)))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].fileId").value(fileInfo.getId()))
                .andExpect(jsonPath("$[0].categoryId").value(category.getId()))
                .andExpect(jsonPath("$[0].subCategoryId").value(subCategory.getId()))
                .andExpect(jsonPath("$[0].mainTagId").value(mainTag.getId()))
                .andExpect(jsonPath("$[0].mainTagTitle").value(mainTag.getTagNameDescription()));
    }

    @Test
    @DisplayName("a search by a fragment of the file name also finds it")
    void findsByNameFragment() throws Exception {
        mockMvc.perform(get("/resource/files/tree/search")
                        .param("query", fileInfo.getFileName().substring(0, 6))
                        .with(user(principal(PermissionEnum.REST_SEARCH_FILE_TREE)))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.fileId == " + fileInfo.getId() + ")]").exists());
    }

    @Test
    @DisplayName("a query matching nothing is an empty list, not an error")
    void noMatchIsAnEmptyList() throws Exception {
        mockMvc.perform(get("/resource/files/tree/search")
                        .param("query", "no-such-file-anywhere-zzz")
                        .with(user(principal(PermissionEnum.REST_SEARCH_FILE_TREE)))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("a Persian-numeral query is treated as text, not as an id that fails to parse")
    void persianDigitsDoNotBlowUpTheSearch() throws Exception {
        mockMvc.perform(get("/resource/files/tree/search")
                        .param("query", "۱۵۷۸")
                        .with(user(principal(PermissionEnum.REST_SEARCH_FILE_TREE)))
                        .header("X-Requested-With", "XMLHttpRequest")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("the endpoint refuses someone without the permission")
    void refusesSomeoneWithoutThePermission() throws Exception {
        mockMvc.perform(get("/resource/files/tree/search")
                        .param("query", "anything")
                        .with(user(principal(PermissionEnum.PUBLIC_FILE_PAGE)))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}
