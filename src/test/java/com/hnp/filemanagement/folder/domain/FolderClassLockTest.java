package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.ClassFixture;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The race {@link FolderClassLock} closes (2.17.2): a folder's class changed in one transaction while a folder is
 * made beneath it in another - n8n uploading while an administrator classes the tree. Without the lock the new
 * folder took its parent's class path as read before the change committed, and kept it: a class taken away above
 * it still covered it, or a class given did not. With it, the folder waits for the change and is made with it.
 *
 * <p>As {@code FolderReadingConcurrencyTest} races its lock: each side in a transaction of its own on a thread of
 * its own, committed; the change held open by a latch, not a sleep, so that a pause of the whole JVM cannot let the
 * change commit before the folder is asked for; and the lock seen held in {@code pg_locks} before the race, so a
 * failure says which half failed. Not {@code @Transactional}, and nothing committed that another class makes for
 * itself (no role).
 */
@SpringBootTest
@TestPropertySource(properties = "filemanagement.folders.max-depth=8")
class FolderClassLockTest extends DatabaseSupport {

    @Autowired
    private FolderClassService classService;
    @Autowired
    private FolderClassAssignment assignment;
    @Autowired
    private FolderService folderService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a folder made while a class is set above it waits for the change, and is made with it")
    void aFolderMadeDuringAChangeOfClass() throws Exception {
        User owner = userRepository.save(TestData.user());
        int classId = classService.create(ClassFixture.definition("lock" + TestData.nextSequence(), "قفل", false, "NONE",
                null, List.of()), owner.getId());
        int bucket = folderService.create(folderRepository.findFirstByKind(FolderKind.ROOT).orElseThrow().getId(),
                "L" + TestData.nextSequence(), "L", null, "l" + TestData.nextSequence(), owner.getId()).id();
        int top = folderService.create(bucket, "Top", "Top", null, null, owner.getId()).id();
        int middle = folderService.create(top, "Middle", "Middle", null, null, owner.getId()).id();

        CountDownLatch classed = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CompletableFuture<Void> change = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            assignment.set(top, classId, owner.getId());
            classed.countDown();
            await(commit);
        }));
        assertThat(classed.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND mode = 'ExclusiveLock'"
                        + " AND granted AND (classid::bigint << 32 | objid::bigint) = ?", Integer.class, FolderClassLock.LOCK))
                .as("the change holds the lock exclusively while it is open").isEqualTo(1);

        CompletableFuture<Integer> made = CompletableFuture.supplyAsync(() -> transactions.execute(status ->
                folderService.create(middle, "Made", "Made", null, null, owner.getId()).id()));
        Thread.sleep(700);
        assertThat(made).as("the folder waits for the change holding the lock").isNotDone();

        commit.countDown();
        change.get(30, TimeUnit.SECONDS);
        int madeId = made.get(30, TimeUnit.SECONDS);
        assertThat(jdbc.queryForObject("SELECT class_path FROM folder WHERE id = ?", String.class, madeId))
                .isEqualTo("/" + classId + "/");
        assertThat(folderRepository.findRowsWhoseClassPathDisagrees()).isEmpty();
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
