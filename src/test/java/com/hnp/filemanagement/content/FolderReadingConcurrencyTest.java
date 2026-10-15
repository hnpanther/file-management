package com.hnp.filemanagement.content;

import com.hnp.filemanagement.content.FolderReadingService.Choice;
import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.folder.domain.UserFolderGrant;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An upload and a change of its folder's setting at the same moment, each in a transaction of its own and
 * committed (2.16.0): whichever goes first, the revision ends as its folder says when both have committed -
 * never held back in a folder that reads, never waiting in one that does not. The settings' advisory lock
 * is what makes it so: without it, a folder switched on would not see an upload still in its transaction,
 * and that upload would stay held back for good.
 */
@SpringBootTest
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.content-search.enabled=true"})
class FolderReadingConcurrencyTest extends DatabaseSupport {

    @Autowired
    private FolderReadingService folderReadingService;
    @Autowired
    private FileService fileService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcTemplate jdbc;

    private int writerId;
    private Folder folder;

    /**
     * Committed, as the race needs - so nothing another test makes too: no role (a committed ADMIN would
     * collide with every test that makes its own), only a person with a WRITE grant on a folder of their own.
     */
    @BeforeEach
    void setUp() {
        transactions.executeWithoutResult(status -> {
            User user = userRepository.save(TestData.user());
            writerId = user.getId();
            folder = FolderFixture.chain(folderRepository, tagGroupRepository, user).tag();
            user.replaceFolderGrants(List.of(new UserFolderGrant(user, folder, FolderPermission.WRITE)));
            userRepository.save(user);
        });
    }

    @AfterEach
    void tearDown() {
        jdbc.update("UPDATE folder SET content_reading = NULL WHERE id = ?", folder.getId());
    }

    @Test
    @DisplayName("an upload still in its transaction when its folder is switched on again: the switch waits for it, and queues it")
    void switchedOnWhileUploading() throws Exception {
        transactions.executeWithoutResult(status -> folderReadingService.set(folder.getId(), Choice.OFF, writerId));
        CountDownLatch queued = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicReference<Integer> uploaded = new AtomicReference<>();

        CompletableFuture<Void> upload = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            uploaded.set(upload("racing.txt").getId());
            queued.countDown();
            await(commit);
        }));
        assertThat(queued.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(stateOf(uploaded.get())).as("not committed yet: no row to see").isNull();

        CompletableFuture<Void> switchOn = CompletableFuture.runAsync(() ->
                transactions.executeWithoutResult(status -> folderReadingService.set(folder.getId(), Choice.ON, writerId)));
        Thread.sleep(700);
        assertThat(switchOn).as("the switch waits for the upload holding the lock").isNotDone();

        commit.countDown();
        upload.get(30, TimeUnit.SECONDS);
        switchOn.get(30, TimeUnit.SECONDS);
        assertThat(stateOf(uploaded.get())).as("queued, not left held back").isEqualTo("PENDING");
    }

    @Test
    @DisplayName("an upload while its folder is being switched off: the upload waits for the switch, and is held back")
    void uploadedWhileSwitchingOff() throws Exception {
        CountDownLatch switched = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);

        CompletableFuture<Void> switchOff = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            folderReadingService.set(folder.getId(), Choice.OFF, writerId);
            switched.countDown();
            await(commit);
        }));
        assertThat(switched.await(30, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Integer> upload = CompletableFuture.supplyAsync(() ->
                transactions.execute(status -> upload("racing.txt").getId()));
        Thread.sleep(700);
        assertThat(upload).as("the upload waits for the switch holding the lock").isNotDone();

        commit.countDown();
        switchOff.get(30, TimeUnit.SECONDS);
        assertThat(stateOf(upload.get(30, TimeUnit.SECONDS))).as("held back, not waiting to be read").isEqualTo("EXCLUDED");
    }

    private FileDetailsDTO upload(String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription(fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folder.getId());
        String unique = fileName.replace(".txt", "-" + TestData.nextSequence() + ".txt");
        request.setMultipartFile(new MockMultipartFile("file", unique, "text/plain", ("content of " + unique).getBytes(StandardCharsets.UTF_8)));
        return fileService.createNewFile(request, writerId, FileService.PRIVATE);
    }

    private String stateOf(int fileDetailsId) {
        return jdbc.queryForList("SELECT state FROM file_content WHERE file_details_id = ?", String.class, fileDetailsId)
                .stream().findFirst().orElse(null);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
