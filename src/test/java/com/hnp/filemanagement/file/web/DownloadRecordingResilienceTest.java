package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.file.domain.DownloadRecorder;
import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.shared.config.FileManagementProperties;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A download does not wait for its record and does not fail because of it (2.7.0): here the
 * recorder's database hangs for seconds and then refuses - the worst it can do - and every
 * download still goes out at once, whole, and the application goes on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "filemanagement.folder-access.enabled=false")
class DownloadRecordingResilienceTest extends DatabaseSupport {

    /** How long the recorder's database takes to refuse a connection. */
    private static final Duration HANG = Duration.ofSeconds(4);

    /** Signalled when a writer is inside the hanging database. */
    private static final CountDownLatch WRITER_HANGING = new CountDownLatch(1);

    @TestConfiguration
    static class BrokenDatabase {

        /** The application's recorder, on a database that hangs and then refuses. */
        @Bean
        @Primary
        DownloadRecorder brokenRecorder(Clock clock, FileManagementProperties properties) {
            AbstractDataSource hanging = new AbstractDataSource() {
                @Override
                public Connection getConnection() throws SQLException {
                    WRITER_HANGING.countDown();
                    try {
                        Thread.sleep(HANG.toMillis());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    throw new SQLException("the download records database is down");
                }

                @Override
                public Connection getConnection(String username, String password) throws SQLException {
                    return getConnection();
                }
            };
            return new DownloadRecorder(new JdbcTemplate(hanging), clock, properties);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DownloadRecorder downloadRecorder;
    @Autowired
    private FileService fileService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;

    @Test
    @DisplayName("with the records' database hanging and then down, downloads go out at once and whole, and keep doing so")
    void downloadsDoNotWaitForTheRecord() throws Exception {
        User owner = userRepository.save(TestData.user());
        int folderId = FolderFixture.chain(folderRepository, tagGroupRepository, owner).tagId();
        String name = "resilient" + TestData.nextSequence() + ".txt";
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("resilience");
        request.setFileNameDescription(name);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", name, "text/plain", TestData.bytesFor(name)));
        FileDetailsDTO revision = fileService.createNewFile(request, owner.getId(), FileService.PUBLIC);

        // Queue a record, then have a writer stuck inside the database with the recorder's lock held.
        mockMvc.perform(get("/files/public-download/{id}", revision.getId())).andExpect(status().isOk());
        CompletableFuture<Void> stuck = CompletableFuture.runAsync(downloadRecorder::flush);
        assertThat(WRITER_HANGING.await(10, TimeUnit.SECONDS)).as("a writer reached the database").isTrue();

        // Every download meanwhile - another visitor each time, so each one is a new record.
        long started = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            final String address = "10.66.0." + i;
            mockMvc.perform(get("/files/public-download/{id}", revision.getId())
                            .with(r -> {
                                r.setRemoteAddr(address);
                                return r;
                            }))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(TestData.bytesFor(name)));
        }
        Duration twenty = Duration.ofNanos(System.nanoTime() - started);
        assertThat(twenty).as("twenty downloads while the writer hangs").isLessThan(HANG.dividedBy(2));
        assertThat(stuck).as("the writer is still inside the database").isNotDone();

        // The writer gives up without an exception; the next write is tried, and fails the same quiet way.
        stuck.get(HANG.toSeconds() * 3, TimeUnit.SECONDS);
        mockMvc.perform(get("/files/public-download/{id}", revision.getId())).andExpect(status().isOk());
    }
}
