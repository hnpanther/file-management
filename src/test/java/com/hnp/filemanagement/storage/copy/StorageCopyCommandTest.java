package com.hnp.filemanagement.storage.copy;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.support.TestDatabases;
import com.hnp.filemanagement.support.TestObjectStores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import com.hnp.filemanagement.shared.config.FileManagementProperties;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.support.EnvironmentPostProcessorApplicationListener;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The storage copy as an operator starts it (roadmap 4.4): selected by the command-line argument
 * alone, refusing with exit status 2 and nothing touched whenever its configuration is wrong, and
 * run end to end from the same settings the service reads.
 */
@SpringBootTest
@TestPropertySource(properties = "filemanagement.folder-access.enabled=false")
class StorageCopyCommandTest extends DatabaseSupport {

    @Autowired
    private FileService fileService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Value("${filemanagement.base-dir}")
    private String baseDir;

    @TempDir
    Path reports;

    @Test
    @DisplayName("only the command-line argument selects it - never another profile, never an environment variable")
    void selectedByTheArgumentOnly() {
        assertThat(StorageCopyCommand.isRequested(new String[]{"--spring.profiles.active=storage-copy"})).isTrue();
        assertThat(StorageCopyCommand.isRequested(new String[]{"--spring.profiles.active=prod, storage-copy"})).isTrue();
        assertThat(StorageCopyCommand.isRequested(new String[]{})).isFalse();
        assertThat(StorageCopyCommand.isRequested(new String[]{"--spring.profiles.active=prod"})).isFalse();
        assertThat(StorageCopyCommand.isRequested(new String[]{"--spring.profiles.active=storage-copy-old"})).isFalse();
        assertThat(StorageCopyCommand.isRequested(new String[]{"storage-copy"})).isFalse();
    }

    @Test
    @DisplayName("its log is a directory of its own, never the running service's app_log.log - even when an external application.properties sets the log path")
    void itsOwnLog() {
        // As found on the first run outside the suite: a whole application.properties beside the jar.
        StandardEnvironment configuredByFile = new StandardEnvironment();
        configuredByFile.getPropertySources().addFirst(new MapPropertySource("Config resource 'file [application.properties]'",
                Map.of("filemanagement.log.path", "D:/MyApp/file-management/logs",
                        "logging.level.com.hnp.filemanagement", "debug")));
        StorageCopyCommand.OwnLog.apply(configuredByFile);
        StorageCopyCommand.OwnLog.apply(configuredByFile);
        assertThat(configuredByFile.getProperty("filemanagement.log.path"))
                .as("once, however often applied")
                .isEqualTo(Path.of("D:/MyApp/file-management/logs", "storage-copy").toString());
        assertThat(configuredByFile.getProperty("logging.level.com.hnp.filemanagement")).isEqualTo("info");
        assertThat(configuredByFile.getProperty("logging.register-shutdown-hook")).isEqualTo("false");

        StandardEnvironment nothingSet = new StandardEnvironment();
        StorageCopyCommand.OwnLog.apply(nothingSet);
        assertThat(nothingSet.getProperty("filemanagement.log.path")).isEqualTo(Path.of("./logs", "storage-copy").toString());

        // Set after the files are read, and before logging starts.
        assertThat(new StorageCopyCommand.OwnLog().getOrder())
                .isGreaterThan(EnvironmentPostProcessorApplicationListener.DEFAULT_ORDER)
                .isLessThan(LoggingApplicationListener.DEFAULT_ORDER);
    }

    @Test
    @DisplayName("the report goes beside its log unless report-dir says otherwise")
    void theReportBesideTheLog() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("settings", Map.of(
                "filemanagement.log.path", "D:/MyApp/file-management/logs",
                "filemanagement.storage-copy.direction", "to-s3")));
        StorageCopyCommand.OwnLog.apply(environment);
        FileManagementProperties properties = FileManagementProperties.defaults("unused");

        assertThat(StorageCopyCommand.settings(environment, properties).reportDir())
                .isEqualTo(Path.of("D:/MyApp/file-management/logs", "storage-copy").toAbsolutePath().normalize());
        environment.getPropertySources().addFirst(new MapPropertySource("command line",
                Map.of("filemanagement.storage-copy.report-dir", "E:/reports")));
        assertThat(StorageCopyCommand.settings(environment, properties).reportDir())
                .isEqualTo(Path.of("E:/reports").toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("refused, with exit status 2, whenever what it is asked or where it would read or write is wrong")
    void refusals() throws IOException {
        assertRefused(environment().withProperty("filemanagement.storage-copy.direction", ""));
        assertRefused(environment().withProperty("filemanagement.storage-copy.direction", "to-the-moon"));
        assertRefused(environment().withProperty("filemanagement.storage-copy.mode", "delete-everything"));
        assertRefused(environment().withProperty("filemanagement.storage-copy.threads", "0"));
        assertRefused(environment().withProperty("filemanagement.storage-copy.threads", "four"));
        assertRefused(environment().withProperty("spring.datasource.url", "jdbc:mysql://localhost/file_management"));
        assertRefused(environment().withProperty("filemanagement.storage.s3.endpoint", ""));
        assertRefused(environment().withProperty("filemanagement.storage.s3.bucket", "no-such-bucket-" + UUID.randomUUID()));
        assertRefused(environment().withProperty("filemanagement.base-dir", reports.resolve("not-there").toString()));
        assertRefused(environment().withProperty("file.management.base-dir", baseDir));
        // A database that is not the application's: no file_details.
        PostgreSQLContainer<?> container = TestDatabases.postgresql();
        assertRefused(environment().withProperty("spring.datasource.url", TestDatabases.postgresqlUrlFor("postgres"))
                .withProperty("spring.datasource.username", container.getUsername())
                .withProperty("spring.datasource.password", container.getPassword()));
        try (Stream<Path> written = Files.list(reports)) {
            assertThat(written).as("no report: nothing ran").isEmpty();
        }
    }

    @Test
    @DisplayName("run end to end from the service's settings: copied, verified, exit 0, the report beside the log")
    void endToEnd() throws IOException {
        Integer largest = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM file_details", Integer.class);
        FileDetailsDTO stored = upload("end-to-end.txt");
        String prefix = "command-" + UUID.randomUUID();

        int copied = StorageCopyCommand.run(environment()
                .withProperty("filemanagement.storage.s3.prefix", prefix)
                .withProperty("filemanagement.storage-copy.after-id", String.valueOf(largest)));
        int verified = StorageCopyCommand.run(environment()
                .withProperty("filemanagement.storage.s3.prefix", prefix)
                .withProperty("filemanagement.storage-copy.mode", "verify")
                .withProperty("filemanagement.storage-copy.after-id", String.valueOf(largest)));

        assertThat(copied).isEqualTo(StorageCopyCommand.SUCCEEDED);
        assertThat(verified).isEqualTo(StorageCopyCommand.SUCCEEDED);
        String key = jdbc.queryForObject("SELECT storage_key FROM file_details WHERE id = ?", String.class, stored.getId());
        assertThat(TestObjectStores.keysUnder(prefix)).containsExactly(key);
        try (Stream<Path> written = Files.list(reports)) {
            assertThat(written.map(path -> path.getFileName().toString()))
                    .anyMatch(name -> name.startsWith("storage-copy-to-s3-copy-"))
                    .anyMatch(name -> name.startsWith("storage-copy-to-s3-verify-"));
        }

        // A revision whose bytes are gone: the run finishes, and says so with exit status 1.
        Files.delete(Path.of(baseDir).resolve(key));
        String other = "command-" + UUID.randomUUID();
        assertThat(StorageCopyCommand.run(environment()
                .withProperty("filemanagement.storage.s3.prefix", other)
                .withProperty("filemanagement.storage-copy.after-id", String.valueOf(largest))))
                .isEqualTo(StorageCopyCommand.PROBLEMS);
    }

    private void assertRefused(MockEnvironment environment) {
        assertThat(StorageCopyCommand.run(environment)).isEqualTo(StorageCopyCommand.REFUSED);
    }

    /** The settings of a service on this suite's database, storage root and object store. */
    private MockEnvironment environment() {
        PostgreSQLContainer<?> container = TestDatabases.postgresql();
        return new MockEnvironment()
                .withProperty("spring.datasource.url", container.getJdbcUrl())
                .withProperty("spring.datasource.username", container.getUsername())
                .withProperty("spring.datasource.password", container.getPassword())
                .withProperty("filemanagement.base-dir", baseDir)
                .withProperty("filemanagement.storage.s3.endpoint", TestObjectStores.endpoint())
                .withProperty("filemanagement.storage.s3.bucket", TestObjectStores.BUCKET)
                .withProperty("filemanagement.storage.s3.access-key", TestObjectStores.ACCESS_KEY)
                .withProperty("filemanagement.storage.s3.secret-key", TestObjectStores.SECRET_KEY)
                .withProperty("filemanagement.storage.s3.part-size-mb", "5")
                .withProperty("filemanagement.storage-copy.direction", "to-s3")
                .withProperty("filemanagement.storage-copy.report-dir", reports.toString());
    }

    private FileDetailsDTO upload(String fileName) {
        User owner = userRepository.save(TestData.user());
        int folderId = FolderFixture.chain(folderRepository, tagGroupRepository, owner).tagId();
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("command " + fileName);
        request.setFileNameDescription("command-" + UUID.randomUUID());
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain",
                ("the bytes of " + fileName).getBytes(StandardCharsets.UTF_8)));
        return fileService.createNewFile(request, owner.getId(), FileService.PUBLIC);
    }
}
