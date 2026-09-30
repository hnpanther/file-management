package com.hnp.filemanagement.file.domain;

import com.hnp.filemanagement.file.persistence.FileDownloadQuery;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.folder.domain.UserFolderGrant;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.shared.config.FileManagementProperties;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.MutableClock;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading the record of downloads (2.7.0) - who may see which rows, what a page costs - and
 * removing it by age. The rows are written here directly, as {@link DownloadRecorder} writes them;
 * the way they get there is {@code DownloadRecordingTest}'s.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FileDownloadServiceTest extends DatabaseSupport {

    @Autowired
    private FileDownloadService underTest;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("a restricted reader sees the downloads of the folders they may read; an administrator all; a stranger none")
    void onlyReadableFolders() {
        User admin = admin();
        FolderFixture.Chain chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        Folder other = FolderFixture.tag(folderRepository, chain.subCategory(), admin, "Other" + TestData.nextSequence());
        int file = TestData.nextSequence() + 900_000;
        insert(file, Instant.parse("2026-09-01T10:00:00Z"), chain.tagId(), "here.txt");
        insert(file, Instant.parse("2026-09-01T11:00:00Z"), other.getId(), "there.txt");
        insert(file, Instant.parse("2026-09-01T12:00:00Z"), null, "nowhere.txt");

        User reader = userRepository.save(TestData.user());
        grant(reader, chain.tagId());
        User stranger = userRepository.save(TestData.user());
        entityManager.flush();
        entityManager.clear();

        FileDownloadQuery query = FileDownloadQuery.everything().ofFile(file);
        assertThat(underTest.search(query, 0, 50, admin.getId()).entries())
                .extracting(FileDownloadService.DownloadEntry::fileName)
                .containsExactly("nowhere.txt", "there.txt", "here.txt");
        assertThat(underTest.search(query, 0, 50, reader.getId()).entries())
                .extracting(FileDownloadService.DownloadEntry::fileName).containsExactly("here.txt");
        assertThat(underTest.search(query, 0, 50, stranger.getId()).entries()).isEmpty();
        assertThat(underTest.ofFile(file, reader.getId())).hasSize(1);
    }

    @Test
    @DisplayName("a page is newest first, a slice that knows whether another follows, and its file is not a link once gone")
    void paging() {
        User admin = admin();
        int file = TestData.nextSequence() + 900_000;
        Instant first = Instant.parse("2026-09-01T10:00:00Z");
        for (int i = 0; i < 5; i++) {
            insert(file, first.plus(Duration.ofMinutes(i)), null, "f" + i + ".txt");
        }

        FileDownloadQuery query = FileDownloadQuery.everything().ofFile(file);
        var page0 = underTest.search(query, 0, 2, admin.getId());
        var page2 = underTest.search(query, 2, 2, admin.getId());
        assertThat(page0.entries()).extracting(FileDownloadService.DownloadEntry::fileName).containsExactly("f4.txt", "f3.txt");
        assertThat(page0.hasNext()).isTrue();
        assertThat(page0.hasPrevious()).isFalse();
        assertThat(page2.entries()).extracting(FileDownloadService.DownloadEntry::fileName).containsExactly("f0.txt");
        assertThat(page2.hasNext()).isFalse();
        assertThat(page2.entries().getFirst().liveFileId()).as("no such file").isNull();

        var between = underTest.search(new FileDownloadQuery(file, null, null, null, null,
                first.plus(Duration.ofMinutes(1)), first.plus(Duration.ofMinutes(3)), null), 0, 50, admin.getId());
        assertThat(between.entries()).extracting(FileDownloadService.DownloadEntry::fileName)
                .as("from inclusive, until exclusive").containsExactly("f2.txt", "f1.txt");
    }

    @Test
    @DisplayName("a page of downloads is a fixed number of statements, however many rows it shows")
    void aFixedNumberOfStatements() {
        User admin = admin();
        int few = TestData.nextSequence() + 900_000;
        int many = TestData.nextSequence() + 900_000;
        insert(few, Instant.parse("2026-09-01T10:00:00Z"), null, "one.txt");
        for (int i = 0; i < 30; i++) {
            insert(many, Instant.parse("2026-09-01T10:00:00Z").plusSeconds(i), null, "many" + i + ".txt");
        }
        entityManager.flush();
        entityManager.clear();

        assertThat(statementsOf(() -> underTest.search(FileDownloadQuery.everything().ofFile(many), 0, 50, admin.getId())))
                .isEqualTo(statementsOf(() -> underTest.search(FileDownloadQuery.everything().ofFile(few), 0, 50, admin.getId())));
    }

    // ---------------------------------------------------------------- retention

    @Test
    @DisplayName("the nightly run removes what is older than the retention, in batches, and keeps the rest")
    void retention() {
        // Far before any other test's rows, so the count is this test's alone.
        Instant now = Instant.parse("2020-06-01T00:00:00Z");
        int file = TestData.nextSequence() + 900_000;
        insert(file, now.minus(Duration.ofDays(31)), null, "old.txt");
        insert(file, now.minus(Duration.ofDays(29)), null, "recent.txt");
        jdbcTemplate.update("""
                INSERT INTO file_download (occurred_at, channel, file_info_id, file_details_id, file_name)
                SELECT ?, 'PAGE', ?, 1, 'bulk.txt' FROM generate_series(1, ?)""",
                Timestamp.from(now.minus(Duration.ofDays(40))), file, DownloadRetention.BATCH + 5);

        long removed = retention(30, now).removeExpired();

        assertThat(removed).isEqualTo(DownloadRetention.BATCH + 6);
        assertThat(jdbcTemplate.queryForList("SELECT file_name FROM file_download WHERE file_info_id = ?", String.class, file))
                .containsExactly("recent.txt");
    }

    @Test
    @DisplayName("a retention of 0 keeps everything")
    void retentionZeroKeepsEverything() {
        Instant now = Instant.parse("2020-06-01T00:00:00Z");
        int file = TestData.nextSequence() + 900_000;
        insert(file, now.minus(Duration.ofDays(3_000)), null, "ancient.txt");

        assertThat(retention(0, now).removeExpired()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM file_download WHERE file_info_id = ?", Integer.class, file))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the retention's delete finds its rows through the time index, not by reading the table")
    void retentionUsesTheTimeIndex() {
        jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
        List<String> plan = jdbcTemplate.queryForList("EXPLAIN " + DownloadRetention.DELETE_BATCH, String.class,
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")), DownloadRetention.BATCH);

        assertThat(String.join("\n", plan)).contains("ix_file_download_occurred_at");
    }

    // ---------------------------------------------------------------- helpers

    private DownloadRetention retention(int days, Instant now) {
        FileManagementProperties properties = new FileManagementProperties("./target/unused", null, null, null, null,
                null, null, null, null, null, new FileManagementProperties.Downloads(true, days));
        return new DownloadRetention(jdbcTemplate, new MutableClock(now, ZoneId.of("Asia/Tehran")), properties);
    }

    private long statementsOf(Runnable call) {
        Statistics statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            call.run();
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    private void insert(int fileInfoId, Instant at, Integer folderId, String fileName) {
        jdbcTemplate.update("""
                INSERT INTO file_download (occurred_at, channel, file_info_id, file_details_id, file_name, version,
                                           folder_id, client_ip)
                VALUES (?, 'PUBLIC', ?, ?, ?, 1, ?, '10.0.0.1')""",
                Timestamp.from(at), fileInfoId, fileInfoId, fileName, folderId);
    }

    private User admin() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        return userRepository.save(admin);
    }

    private void grant(User user, int folderId) {
        List<UserFolderGrant> grants = new ArrayList<>(user.getFolderGrants());
        grants.add(new UserFolderGrant(user, folderRepository.findById(folderId).orElseThrow(), FolderPermission.READ));
        user.replaceFolderGrants(grants);
        userRepository.save(user);
    }
}
