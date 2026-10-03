package com.hnp.filemanagement.storage.copy;

import com.hnp.filemanagement.shared.config.FileManagementProperties;
import com.hnp.filemanagement.storage.BlobStoreConfig;
import com.hnp.filemanagement.storage.CopyableStore;
import com.hnp.filemanagement.storage.FilesystemBlobStore;
import com.hnp.filemanagement.storage.S3BlobStore;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.services.s3.S3Client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@code java -jar file-management.jar --spring.profiles.active=storage-copy
 * --filemanagement.storage-copy.direction=to-s3}: the storage copy of roadmap 4.4 ({@link StorageCopy}),
 * run against the configuration the service runs with, then exit - {@code 0} when every revision
 * it looked at is where it should be and verified, {@code 1} when any is not (the report says
 * which), {@code 2} when it refused to start or to delete.
 *
 * <p><b>It is not the application</b>, as the database copy of 3.5 was not: started as the
 * application it would migrate, reconcile the roles, sweep and backfill against production. This
 * reads the configuration exactly the way the service does - the same {@code application.properties},
 * environment variables and external file - through a Spring context with nothing in it, and talks
 * to the database through one small read-only pool and to the stores through the same classes the
 * application uses, built from the same settings: {@code filemanagement.base-dir} and
 * {@code filemanagement.storage.s3.*}. {@code filemanagement.storage.backend} is not read; the
 * direction says which store is which.
 *
 * <p>Only the command-line argument selects it, never an environment variable: a service definition
 * that carried {@code SPRING_PROFILES_ACTIVE=storage-copy} must not turn the next restart into a copy.
 * Its log goes to a directory of its own ({@code application-storage-copy.properties}), never into
 * the running service's {@code app_log.log}.
 */
public final class StorageCopyCommand {

    private static final Logger logger = LoggerFactory.getLogger(StorageCopyCommand.class);

    public static final int SUCCEEDED = 0;
    public static final int PROBLEMS = 1;
    public static final int REFUSED = 2;

    static final String PROFILE = "storage-copy";

    private StorageCopyCommand() {
    }

    /** Whether the command line asks for the copy: {@code --spring.profiles.active=storage-copy}. */
    public static boolean isRequested(String[] args) {
        return Arrays.stream(args)
                .filter(arg -> arg.startsWith("--spring.profiles.active="))
                .map(arg -> arg.substring("--spring.profiles.active=".length()))
                .flatMap(profiles -> Arrays.stream(profiles.split(",")))
                .anyMatch(profile -> profile.trim().equals(PROFILE));
    }

    /** Runs the copy and answers the process's exit status. */
    public static int run(String[] args) {
        SpringApplication configurationOnly = new SpringApplication(ConfigurationOnly.class);
        configurationOnly.setWebApplicationType(WebApplicationType.NONE);
        configurationOnly.setBannerMode(Banner.Mode.OFF);
        configurationOnly.setRegisterShutdownHook(false);
        // Otherwise the first log line reads "Starting FileManagementApplication".
        configurationOnly.setMainApplicationClass(StorageCopyCommand.class);

        try (ConfigurableApplicationContext context = configurationOnly.run(args)) {
            return run(context.getEnvironment());
        }
    }

    /** The copy against this configuration - what {@link #run(String[])} does once it has read it. */
    static int run(Environment environment) {
        HikariDataSource dataSource = null;
        S3Client s3Client = null;
        try {
            try {
                BlobStoreConfig.refuseRetiredBaseDir(environment);
            } catch (IllegalStateException e) {
                throw new StorageCopy.Refused(e.getMessage());
            }
            Binder binder = Binder.get(environment);
            FileManagementProperties properties = binder.bind("filemanagement", FileManagementProperties.class)
                    .orElseThrow(() -> new StorageCopy.Refused("no filemanagement.* settings were found"));
            StorageCopySettings settings = settings(environment, properties);

            Path root = Path.of(properties.baseDir()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                throw new StorageCopy.Refused("the storage root " + root + " (filemanagement.base-dir) is not a directory");
            }
            FilesystemBlobStore filesystem = new FilesystemBlobStore(properties);

            FileManagementProperties.S3 s3Settings = properties.storage().s3();
            if (!s3Settings.missing().isEmpty()) {
                throw new StorageCopy.Refused("the object store is not configured; not set: " + String.join(", ", s3Settings.missing()));
            }
            s3Client = BlobStoreConfig.newS3Client(s3Settings);
            S3BlobStore bucket = BlobStoreConfig.newS3BlobStore(s3Client, s3Settings);
            try {
                bucket.requireBucket(s3Settings.endpoint());
            } catch (IllegalStateException e) {
                throw new StorageCopy.Refused(e.getMessage());
            }

            dataSource = dataSource(environment, settings.threads());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            requireSchema(jdbc);

            boolean toS3 = settings.direction() == StorageCopySettings.Direction.TO_S3;
            CopyableStore source = toS3 ? filesystem : bucket;
            CopyableStore target = toS3 ? bucket : filesystem;
            String filesystemSide = "the directory " + root;
            String bucketSide = "the bucket " + s3Settings.bucket() + " at " + s3Settings.endpoint()
                    + (s3Settings.prefix().isBlank() ? "" : " under " + s3Settings.prefix());
            logger.info("storage copy: {} {}, from {} to {}; rows from {}; {} thread(s){}{}{}",
                    settings.mode(), settings.direction(), toS3 ? filesystemSide : bucketSide,
                    toS3 ? bucketSide : filesystemSide, dataSource.getJdbcUrl(), settings.threads(),
                    settings.deepVerify() ? ", every object read back" : "",
                    settings.afterId() > 0 ? ", after file_details id " + settings.afterId() : "",
                    settings.mode() == StorageCopySettings.Mode.PRUNE
                            ? (settings.confirm() ? ", DELETING" : ", listing only (no confirm)") : "");

            StorageCopy copy = new StorageCopy(jdbc, source, target, settings, Clock.systemUTC());
            // Ctrl+C: the revisions in hand are finished and the report written before the JVM goes.
            CountDownLatch reported = new CountDownLatch(1);
            Thread hook = new Thread(() -> {
                logger.warn("storage copy: stopping after the revisions in hand; run it again to carry on");
                copy.stop();
                try {
                    reported.await(10, TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "storage-copy-stop");
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                return summarise(copy.run());
            } finally {
                reported.countDown();
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException alreadyStopping) {
                    // The hook is running and has just been let go.
                }
            }
        } catch (StorageCopy.Refused e) {
            logger.error("storage copy refused, nothing was written or deleted: {}", e.getMessage());
            return REFUSED;
        } catch (RuntimeException e) {
            logger.error("storage copy failed; what it copied stays copied and verified, and a second run carries on", e);
            return PROBLEMS;
        } finally {
            if (dataSource != null) {
                dataSource.close();
            }
            if (s3Client != null) {
                s3Client.close();
            }
        }
    }

    private static int summarise(StorageCopy.Report report) {
        report.counts().forEach((outcome, n) -> logger.info("  {} {}", String.format("%-18s", outcome), n));
        logger.info("  bytes copied       {}", StorageCopy.bytes(report.bytesCopied()));
        if (report.orphans() >= 0) {
            logger.info("  not named by a row {} (in the target, listed in the report)", report.orphans());
        }
        if (report.warnings() > 0) {
            logger.warn("  warnings           {} (listed in the report)", report.warnings());
        }
        report.lines().stream().filter(line -> isProblem(line.what())).limit(20).forEach(line ->
                logger.error("  {} file_details id={} key={} {}", line.what(), line.fileDetailsId(), line.storageKey(),
                        line.detail() == null ? "" : line.detail()));
        logger.info("storage copy {} {} {} in {}; the report: {}", report.mode(), report.direction(),
                report.stopped() ? "STOPPED" : report.succeeded() ? "SUCCEEDED" : "FOUND PROBLEMS",
                report.took(), report.reportFile());
        return report.succeeded() ? SUCCEEDED : PROBLEMS;
    }

    private static boolean isProblem(String what) {
        try {
            return StorageCopy.Outcome.valueOf(what).problem();
        } catch (IllegalArgumentException warningOrOrphan) {
            return false;
        }
    }

    static StorageCopySettings settings(Environment environment, FileManagementProperties properties) {
        String prefix = "filemanagement.storage-copy.";
        String reportDir = environment.getProperty(prefix + "report-dir", "");
        if (reportDir.isBlank()) {
            reportDir = environment.getProperty("filemanagement.log.path", "./logs");
        }
        return new StorageCopySettings(
                StorageCopySettings.Direction.parse(environment.getProperty(prefix + "direction")),
                StorageCopySettings.Mode.parse(environment.getProperty(prefix + "mode")),
                integer(environment, prefix + "threads", 4),
                Boolean.parseBoolean(environment.getProperty(prefix + "deep-verify", "false").trim()),
                integer(environment, prefix + "after-id", 0),
                Boolean.parseBoolean(environment.getProperty(prefix + "confirm", "false").trim()),
                integer(environment, prefix + "max-prune", 100),
                integer(environment, prefix + "quiet-minutes", properties.storage().unfinishedAfterMinutes()),
                Path.of(reportDir).toAbsolutePath().normalize());
    }

    private static int integer(Environment environment, String name, int fallback) {
        String value = environment.getProperty(name, "");
        if (value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new StorageCopy.Refused(name + " must be a whole number; it is '" + value + "'");
        }
    }

    /** One small read-only pool: the copy reads rows and never writes them. */
    private static HikariDataSource dataSource(Environment environment, int threads) {
        String url = environment.getProperty("spring.datasource.url", "");
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new StorageCopy.Refused("spring.datasource.url must name the application's PostgreSQL database; it is '"
                    + url + "'");
        }
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(environment.getProperty("spring.datasource.username"));
        dataSource.setPassword(environment.getProperty("spring.datasource.password"));
        dataSource.setReadOnly(true);
        dataSource.setMaximumPoolSize(threads + 2);
        dataSource.setPoolName("storage-copy");
        return dataSource;
    }

    /** The tables the copy reads must be there - a database this jar has migrated. */
    private static void requireSchema(JdbcTemplate jdbc) {
        try {
            jdbc.queryForObject("SELECT count(*) FROM file_details WHERE checksum_sha256 IS NULL AND storage_key IS NULL",
                    Long.class);
            jdbc.queryForObject("SELECT count(*) FROM file_storage_write", Long.class);
        } catch (RuntimeException e) {
            throw new StorageCopy.Refused("the database does not have the tables this copy reads (file_details with"
                    + " storage_key and checksum_sha256, file_storage_write) - is it the application's, migrated by this"
                    + " release? " + e.getMessage());
        }
    }

    /**
     * The whole of the copy's Spring context: nothing. Not annotated, so the application's component
     * scan never finds it and Spring Boot configures nothing around it.
     */
    static final class ConfigurationOnly {
    }
}
