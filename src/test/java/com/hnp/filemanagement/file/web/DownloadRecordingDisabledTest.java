package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.file.domain.DownloadRecorder;
import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code filemanagement.downloads.enabled=false}: downloads work as before and nothing is written;
 * the pages that read the record still open, over what was recorded before it was switched off.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "filemanagement.folder-access.enabled=false",
        "filemanagement.downloads.enabled=false"})
class DownloadRecordingDisabledTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DownloadRecorder downloadRecorder;
    @Autowired
    private FileService fileService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;

    @Test
    @DisplayName("switched off, a download is served and not recorded, and the downloads page still opens")
    void nothingIsRecorded() throws Exception {
        User owner = userRepository.save(TestData.user());
        int folderId = FolderFixture.chain(folderRepository, tagGroupRepository, owner).tagId();
        String name = "unrecorded" + TestData.nextSequence() + ".txt";
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("disabled");
        request.setFileNameDescription(name);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", name, "text/plain", TestData.bytesFor(name)));
        FileDetailsDTO revision = fileService.createNewFile(request, owner.getId(), FileService.PUBLIC);

        mockMvc.perform(get("/files/public-download/{id}", revision.getId())).andExpect(status().isOk());
        downloadRecorder.flush();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM file_download WHERE file_info_id = ?",
                Integer.class, revision.getFileInfoId())).isZero();

        UserDetailsImpl reader = new UserDetailsImpl();
        reader.setId(owner.getId());
        reader.setUsername(owner.getUsername());
        reader.setPassword("irrelevant");
        reader.setEnabled(1);
        reader.setState(0);
        reader.setLoginType(0);
        reader.setPermissions(List.of(PermissionEnum.FILE_DOWNLOADS_PAGE));
        mockMvc.perform(get("/files/downloads").with(user(reader))).andExpect(status().isOk());
    }
}
