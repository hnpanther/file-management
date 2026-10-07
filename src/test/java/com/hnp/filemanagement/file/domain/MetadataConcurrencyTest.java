package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderMetadataService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.shared.exception.PreconditionFailedException;
import com.hnp.filemanagement.shared.metadata.MetadataPrecondition;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two writers of one document at once (roadmap 12.2, 12.3), in real transactions: a condition is
 * checked on the row the write holds, so the second writer waits for the first and then answers to
 * what it wrote - two integrations filling in what is missing never both write it, and two people
 * who read one version never lose each other's change.
 */
@SpringBootTest
class MetadataConcurrencyTest extends DatabaseSupport {

    @Autowired
    private FileService fileService;
    @Autowired
    private FileMetadataService fileMetadataService;
    @Autowired
    private FolderMetadataService folderMetadataService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcTemplate jdbc;

    private int adminId;
    private Folder folder;

    @BeforeEach
    void setUp() {
        // Committed, so never an ADMIN role: the shared database has one name for it, and a role
        // left behind breaks every later class that makes its own. Folder access is not enforced for
        // a person here (the default), so a plain account reaches the folder.
        transactions.executeWithoutResult(status -> {
            User writer = userRepository.save(TestData.user());
            adminId = writer.getId();
            folder = FolderFixture.chain(folderRepository, tagGroupRepository, writer).tag();
        });
    }

    @Test
    @DisplayName("a file: the second If-None-Match: * waits for the first, then is a 412 - the first's document stays")
    void aFile() throws Exception {
        int fileId = transactions.execute(status -> {
            FileInfoDTO request = new FileInfoDTO();
            request.setDescription("race");
            request.setFolderId(folder.getId());
            request.setMultipartFile(new MockMultipartFile("file", "race" + TestData.nextSequence() + ".txt", "text/plain",
                    "race".getBytes(StandardCharsets.UTF_8)));
            return fileService.createNewFile(request, adminId, FileService.PRIVATE).getFileInfoId();
        });
        MetadataPrecondition onlyIfNone = new MetadataPrecondition(null, "*");
        race(writer -> fileMetadataService.replaceOnFile(fileId, "{\"by\":\"" + writer + "\"}", onlyIfNone, adminId));
        assertThat(jdbc.queryForObject("SELECT metadata::text FROM file_details WHERE file_info_id = ?", String.class, fileId))
                .isEqualTo("{\"by\": \"first\"}");
    }

    @Test
    @DisplayName("a folder: the same")
    void aFolder() throws Exception {
        MetadataPrecondition onlyIfNone = new MetadataPrecondition(null, "*");
        race(writer -> folderMetadataService.replace(folder.getId(), "{\"by\":\"" + writer + "\"}", onlyIfNone, adminId));
        assertThat(jdbc.queryForObject("SELECT metadata::text FROM folder WHERE id = ?", String.class, folder.getId()))
                .isEqualTo("{\"by\": \"first\"}");
    }

    /**
     * The first writer writes and holds its transaction open; the second starts meanwhile; the first
     * then commits. The second must end in a 412.
     */
    private void race(Consumer<String> write) throws Exception {
        CountDownLatch firstWrote = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = threads.submit(() -> transactions.executeWithoutResult(status -> {
                write.accept("first");
                firstWrote.countDown();
                try {
                    secondStarted.await(10, TimeUnit.SECONDS);
                    // Long enough for the second to reach the row and wait on it.
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            Future<Throwable> second = threads.submit(() -> {
                firstWrote.await(10, TimeUnit.SECONDS);
                secondStarted.countDown();
                try {
                    transactions.executeWithoutResult(status -> write.accept("second"));
                    return null;
                } catch (Throwable e) {
                    return e;
                }
            });
            first.get(30, TimeUnit.SECONDS);
            assertThat(second.get(30, TimeUnit.SECONDS)).as("the second writer, after the first committed")
                    .isInstanceOf(PreconditionFailedException.class);
        } finally {
            threads.shutdownNow();
        }
    }
}
