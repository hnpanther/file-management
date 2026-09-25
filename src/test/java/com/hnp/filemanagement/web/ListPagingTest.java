package com.hnp.filemanagement.web;

import com.hnp.filemanagement.config.FileManagementProperties;
import com.hnp.filemanagement.config.security.UserDetailsImpl;
import com.hnp.filemanagement.entity.PermissionEnum;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.util.PageRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code page-size} and {@code page-number} are in the URL, and a URL is anybody's to edit. A size
 * of zero or a negative page used to be a 500 from {@code PageRequest.of}, and a size of a million
 * a query for a million rows; now the three paged lists fall back to the default and clamp to
 * {@link PageRequests#MAX_PAGE_SIZE}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ListPagingTest extends MySqlSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FileManagementProperties properties;

    private int userId;

    @BeforeEach
    void setUp() {
        userId = userRepository.save(TestData.user()).getId();
    }

    @Test
    @DisplayName("a size below one and a negative page are the default size and the first page, not a 500")
    void aNonsensePageIsTheFirstPage() throws Exception {
        int defaultSize = Math.min(properties.defaults().pageSize(), PageRequests.MAX_PAGE_SIZE);
        for (String list : List.of("/users", "/files/file-info", "/files/public-files")) {
            mockMvc.perform(get(list).param("page-size", "0").param("page-number", "-3")
                            .with(user(principal())).accept(MediaType.TEXT_HTML))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("pageSize", defaultSize))
                    .andExpect(model().attribute("pageNumber", 1));
        }
    }

    @Test
    @DisplayName("a size past the cap is the cap")
    void aHugePageIsClamped() throws Exception {
        for (String list : List.of("/users", "/files/file-info", "/files/public-files")) {
            mockMvc.perform(get(list).param("page-size", "1000000")
                            .with(user(principal())).accept(MediaType.TEXT_HTML))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("pageSize", PageRequests.MAX_PAGE_SIZE));
        }
    }

    private UserDetailsImpl principal() {
        UserDetailsImpl userDetails = new UserDetailsImpl();
        userDetails.setId(userId);
        userDetails.setUsername("tester" + userId);
        userDetails.setPassword("irrelevant");
        userDetails.setEnabled(1);
        userDetails.setState(0);
        userDetails.setLoginType(0);
        userDetails.setPermissions(List.of(PermissionEnum.GET_ALL_USER_PAGE, PermissionEnum.GET_ALL_FILE_INFO_PAGE));
        return userDetails;
    }
}
