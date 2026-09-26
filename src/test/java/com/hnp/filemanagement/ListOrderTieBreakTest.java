package com.hnp.filemanagement;

import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.identity.domain.UserService;
import com.hnp.filemanagement.audit.domain.ActionHistoryDTO;
import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.shared.web.PageResponse;
import com.hnp.filemanagement.file.domain.PublicFileDetailsDTO;
import com.hnp.filemanagement.identity.domain.UserDTO;
import com.hnp.filemanagement.audit.domain.ActionEnum;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rows written in the same second, paged (issue 95).
 *
 * <p>{@code created_at} keeps whole seconds, so a burst of uploads - an integration, an import -
 * writes many rows with one value. Ordered by that column alone, their order is whatever the plan
 * produces and need not repeat between the request for page 1 and the one for page 2: a row can
 * appear on both and its neighbour on neither. Every list here is therefore paged one row at a
 * time over rows that tie exactly, and must show each row once, newest id first.
 */
@ServiceIntegrationTest
class ListOrderTieBreakTest extends DatabaseSupport {

    private static final int ROWS = 5;
    private static final Timestamp ONE_SECOND = Timestamp.valueOf(LocalDateTime.of(2026, 9, 26, 10, 0, 0));

    @Autowired
    private FileService fileService;
    @Autowired
    private UserService userService;
    @Autowired
    private ActionHistoryService actionHistoryService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private FolderFixture.Chain chain;
    private String token;

    @BeforeEach
    void setUp() {
        User admin = userRepository.save(TestData.user());
        adminId = admin.getId();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        token = "tie" + TestData.nextSequence();
    }

    @Test
    @DisplayName("the file list pages files of one second each once, newest id first")
    void filesOfOneSecond() {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            ids.add(upload(token + "-" + i + ".txt", 0).getFileInfoId());
        }
        sameSecond("file_info", ids);

        List<Integer> seen = pageThrough(page -> fileService.getPageFileInfo(1, page, token, adminId)
                .content().stream().map(FileInfoDTO::getId).toList());

        assertThat(seen).containsExactlyElementsOf(newestFirst(ids));
    }

    @Test
    @DisplayName("the public files page does the same with revisions of one second")
    void publicFilesOfOneSecond() {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            ids.add(upload(token + "-public-" + i + ".txt", 1).getId());
        }
        sameSecond("file_details", ids);

        List<Integer> seen = pageThrough(page -> {
            PageResponse<PublicFileDetailsDTO> response = fileService.getPagePublicFiles(1, page, token);
            return response.content().stream().map(PublicFileDetailsDTO::getId).toList();
        });

        assertThat(seen).containsExactlyElementsOf(newestFirst(ids));
    }

    @Test
    @DisplayName("the user list does the same with accounts of one second")
    void usersOfOneSecond() {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            User user = TestData.user();
            user.setUsername(token + "user" + i);
            ids.add(userRepository.save(user).getId());
        }
        sameSecond("app_user", ids);

        List<Integer> seen = pageThrough(page -> userService.getUserPage(token + "user", 1, page)
                .getContent().stream().map(UserDTO::getId).toList());

        assertThat(seen).containsExactlyElementsOf(newestFirst(ids));
    }

    @Test
    @DisplayName("a record's history lists entries of one second newest id first, the same every time")
    void historyOfOneSecond() {
        int entity = 900_000 + TestData.nextSequence();
        for (int i = 0; i < ROWS; i++) {
            actionHistoryService.saveActionHistory(EntityEnum.FileInfo, entity, ActionEnum.UPDATE_VALUES, adminId,
                    "change " + i, "change " + i);
        }
        entityManager.flush();
        jdbcTemplate.update("UPDATE action_history SET created_at = ? WHERE entity_id = ?", ONE_SECOND, entity);
        entityManager.clear();

        List<Integer> seen = actionHistoryService.getActionHistoriesOfEntity(entity, EntityEnum.FileInfo)
                .stream().map(ActionHistoryDTO::getId).toList();

        assertThat(seen).hasSize(ROWS).isSortedAccordingTo(Comparator.reverseOrder());
    }

    // ---------------------------------------------------------------- fixture

    private FileDetailsDTO upload(String fileName, int publicFile) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(chain.tagId());
        request.setMultipartFile(new MockMultipartFile(fileName, fileName, "text/plain", TestData.bytesFor(fileName)));
        return fileService.createNewFile(request, adminId, publicFile);
    }

    /** Gives every row the same {@code created_at}, as a burst of writes in one second does. */
    private void sameSecond(String table, List<Integer> ids) {
        entityManager.flush();
        for (Integer id : ids) {
            jdbcTemplate.update("UPDATE " + table + " SET created_at = ? WHERE id = ?", ONE_SECOND, id);
        }
        entityManager.clear();
    }

    /** Every page of size one, in order, until one comes back empty. */
    private static List<Integer> pageThrough(IntFunction<List<Integer>> page) {
        List<Integer> seen = new ArrayList<>();
        for (int number = 0; number <= ROWS + 1; number++) {
            List<Integer> rows = page.apply(number);
            if (rows.isEmpty()) {
                break;
            }
            seen.addAll(rows);
        }
        return seen;
    }

    private static List<Integer> newestFirst(List<Integer> ids) {
        return ids.stream().sorted(Comparator.reverseOrder()).toList();
    }
}
