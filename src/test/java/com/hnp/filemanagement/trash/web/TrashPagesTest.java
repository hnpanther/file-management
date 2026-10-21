package com.hnp.filemanagement.trash.web;

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
import com.hnp.filemanagement.trash.TrashEntryRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The recycle bin's pages (roadmap 13.1, 2.18.0): each behind its permission, each rendered with every message it
 * names; a restore and a purge, and a restore refused back to its form. What the bin does is {@code TrashTest}'s.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TrashPagesTest extends DatabaseSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private FileService fileService;
    @Autowired
    private TrashEntryRepository entries;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    private User person;
    private int folderId;
    private FileDetailsDTO trashed;
    private int entryId;

    @BeforeEach
    void setUp() {
        person = userRepository.save(TestData.user());
        folderId = FolderFixture.chain(folderRepository, tagGroupRepository, person).tagId();
        trashed = upload("trashed" + TestData.nextSequence() + ".txt");
        fileService.deleteCompleteFileById(trashed.getFileInfoId(), person.getId());
        flush();
        entryId = entries.findByFileInfoId(trashed.getFileInfoId()).orElseThrow().getId();
    }

    @Test
    @DisplayName("the bin page: its permission's; the entry with its restore, its purge only with PURGE_TRASH; the preview")
    void theBinPage() throws Exception {
        mockMvc.perform(get("/files/trash").with(user(principal(PermissionEnum.FILE_EXPLORER_PAGE))))
                .andExpect(status().isForbidden());

        String page = mockMvc.perform(get("/files/trash").with(user(principal(PermissionEnum.TRASH_PAGE, PermissionEnum.RESTORE_FROM_TRASH))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("سطل زباله").contains(trashed.getFileName().replace(".txt", ""))
                .contains("/files/trash/" + entryId + "/restore")
                .doesNotContain("/files/trash/" + entryId + "/purge")
                .doesNotContain("??");

        String withPurge = mockMvc.perform(get("/files/trash").param("olderThan", "7")
                        .with(user(principal(PermissionEnum.TRASH_PAGE, PermissionEnum.PURGE_TRASH))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(withPurge).contains("/files/trash/" + entryId + "/purge").contains("purge-older-found").doesNotContain("??");

        mockMvc.perform(get("/files/trash").param("kind", "FOLDER").param("name", "x").param("mine", "true")
                        .param("before", "not-a-cursor").with(user(principal(PermissionEnum.TRASH_PAGE))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a restore: its permission's, back where it was; one that cannot go back opens its form, saying why")
    void restore() throws Exception {
        mockMvc.perform(post("/files/trash/{id}/restore", entryId).with(csrf()).with(user(principal(PermissionEnum.TRASH_PAGE))))
                .andExpect(status().isForbidden());

        String form = mockMvc.perform(get("/files/trash/{id}/restore", entryId)
                        .with(user(principal(PermissionEnum.RESTORE_FROM_TRASH))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(form).contains("trash-restore-form").contains("folderChooser").doesNotContain("??");

        // Its name taken meanwhile: refused, back to the form.
        upload(trashed.getFileName());
        flush();
        mockMvc.perform(post("/files/trash/{id}/restore", entryId).with(csrf()).with(user(principal(PermissionEnum.RESTORE_FROM_TRASH))))
                .andExpect(redirectedUrl("/files/trash/" + entryId + "/restore"))
                .andExpect(flash().attributeExists("refused"));

        mockMvc.perform(post("/files/trash/{id}/restore", entryId).param("name", "under-another")
                        .with(csrf()).with(user(principal(PermissionEnum.RESTORE_FROM_TRASH))))
                .andExpect(redirectedUrl("/files/trash"))
                .andExpect(flash().attributeExists("saved"));
        flush();
        assertThat(jdbc.queryForObject("SELECT folder_id FROM file_info WHERE id = ?", Integer.class, trashed.getFileInfoId()))
                .isEqualTo(folderId);

        mockMvc.perform(get("/files/trash/{id}/restore", entryId).with(user(principal(PermissionEnum.RESTORE_FROM_TRASH))))
                .andExpect(redirectedUrl("/files/trash"))
                .andExpect(flash().attributeExists("refused"));
    }

    @Test
    @DisplayName("a purge: PURGE_TRASH's alone; one entry, then every one older than an age")
    void purge() throws Exception {
        mockMvc.perform(post("/files/trash/{id}/purge", entryId).with(csrf())
                        .with(user(principal(PermissionEnum.TRASH_PAGE, PermissionEnum.RESTORE_FROM_TRASH))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/files/trash/{id}/purge", entryId).with(csrf()).with(user(principal(PermissionEnum.PURGE_TRASH))))
                .andExpect(redirectedUrl("/files/trash"))
                .andExpect(flash().attributeExists("saved"));
        flush();
        assertThat(entries.findById(entryId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_info WHERE id = ?", Integer.class, trashed.getFileInfoId())).isZero();

        FileDetailsDTO old = upload("old" + TestData.nextSequence() + ".txt");
        fileService.deleteCompleteFileById(old.getFileInfoId(), person.getId());
        flush();
        jdbc.update("UPDATE trash_entry SET deleted_at = now() - interval '8 days' WHERE file_info_id = ?", old.getFileInfoId());
        mockMvc.perform(post("/files/trash/purge-older").param("olderThan", "7").with(csrf())
                        .with(user(principal(PermissionEnum.PURGE_TRASH))))
                .andExpect(redirectedUrl("/files/trash"))
                .andExpect(flash().attributeExists("saved"));
        flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_info WHERE id = ?", Integer.class, old.getFileInfoId())).isZero();
        mockMvc.perform(post("/files/trash/purge-older").param("olderThan", "3").with(csrf())
                        .with(user(principal(PermissionEnum.PURGE_TRASH))))
                .andExpect(flash().attributeExists("refused"));
    }

    private UserDetailsImpl principal(PermissionEnum... permissions) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(person.getId());
        principal.setUsername(person.getUsername());
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(1);
        principal.setPermissions(List.of(permissions));
        return principal;
    }

    private FileDetailsDTO upload(String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription(fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain",
                ("content of " + fileName).getBytes(StandardCharsets.UTF_8)));
        return fileService.createNewFile(request, person.getId(), FileService.PUBLIC);
    }

    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }
}
