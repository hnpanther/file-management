package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.shared.config.FileManagementProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Which {@link BlobStore} this installation stores its bytes in, chosen by one setting
 * (roadmap Phase 4):
 *
 * <pre>
 *   filemanagement.storage.backend=filesystem   under base-dir, as always - the default
 *   filemanagement.storage.backend=s3           an S3-compatible object store, filemanagement.storage.s3.*
 * </pre>
 *
 * <p>Exactly one store exists. Nothing else in the application knows which: every read, write and
 * delete goes through the port, with the key the row carries.
 *
 * <p><b>Switching an installation that already has files is a copy, not a setting</b> (roadmap
 * 4.4): the rows keep their keys, and the new backend must hold an object at every one of them
 * before it is switched to - or every download of an old file is a 404. The start with the
 * {@code s3} backend refuses a missing bucket or refused credentials; it cannot know whether the
 * copy was made.
 */
@Configuration
public class BlobStoreConfig {

    private static final Logger logger = LoggerFactory.getLogger(BlobStoreConfig.class);

    private static final String BACKEND = "filemanagement.storage.backend";

    /** The storage root's name until 2.7.0. */
    static final String RETIRED_BASE_DIR = "file.management.base-dir";

    @Bean
    @ConditionalOnProperty(name = BACKEND, havingValue = "filesystem", matchIfMissing = true)
    public BlobStore filesystemBlobStore(FileManagementProperties properties, Environment environment) {
        refuseRetiredBaseDir(environment);
        Path root = Path.of(properties.baseDir()).toAbsolutePath().normalize();
        if (Files.isDirectory(root)) {
            logger.info("files are stored on the filesystem, under {}", root);
        } else {
            // Not created here: on a share that is not mounted, creating it would put new files
            // on the wrong disk. Readiness is DOWN until it exists (issue 104).
            logger.warn("files are stored on the filesystem, under {} - which does not exist: create it;"
                    + " until then /actuator/health/readiness is DOWN", root);
        }
        return new FilesystemBlobStore(properties);
    }

    /**
     * The storage root was named {@code file.management.base-dir} until 2.7.0. An installation that
     * still sets it - in an external {@code application.properties}, say - would otherwise start,
     * ignore it and store every upload under the default directory, beside the real files and not
     * among them; so the start stops and says what to rename. On s3 too: base-dir is still where
     * the copy tool reads from and where a rollback returns to.
     */
    public static void refuseRetiredBaseDir(Environment environment) {
        if (environment.containsProperty(RETIRED_BASE_DIR)) {
            throw new IllegalStateException(RETIRED_BASE_DIR + " is no longer read (2.7.0): set filemanagement.base-dir,"
                    + " or the environment variable FILEMANAGEMENT_BASE_DIR, to the same directory - and remove "
                    + RETIRED_BASE_DIR + ". It is set to " + environment.getProperty(RETIRED_BASE_DIR));
        }
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = BACKEND, havingValue = "s3")
    public S3Client s3Client(FileManagementProperties properties, Environment environment) {
        refuseRetiredBaseDir(environment);
        FileManagementProperties.S3 settings = properties.storage().s3();
        if (!settings.missing().isEmpty()) {
            throw new IllegalStateException("filemanagement.storage.backend is s3, and these are not set: "
                    + String.join(", ", settings.missing()));
        }
        return newS3Client(settings);
    }

    /**
     * The client for these settings - the application's, and the storage copy's (roadmap 4.4),
     * which must talk to the store exactly as the application will.
     */
    public static S3Client newS3Client(FileManagementProperties.S3 settings) {
        return S3Client.builder()
                .endpointOverride(URI.create(settings.endpoint()))
                .region(Region.of(settings.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
                .forcePathStyle(settings.pathStyleAccess())
                // A download streams from its own connection until its last byte, so the pool is
                // as large as the number of downloads that can be in progress (S3ConcurrencyTest).
                .httpClientBuilder(Apache5HttpClient.builder()
                        .maxConnections(settings.maxConnections())
                        // The limits of issue 101: to connect, and of silence on a connection -
                        // the one limit a transfer has, whatever the file's size.
                        .connectionTimeout(Duration.ofSeconds(settings.timeouts().connectSeconds()))
                        .socketTimeout(Duration.ofSeconds(settings.timeouts().readSeconds())))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = BACKEND, havingValue = "s3")
    public BlobStore s3BlobStore(S3Client s3Client, FileManagementProperties properties) {
        FileManagementProperties.S3 settings = properties.storage().s3();
        S3BlobStore store = newS3BlobStore(s3Client, settings);
        store.requireBucket(settings.endpoint());
        logger.info("files are stored in the S3 bucket {} at {}{}", settings.bucket(), settings.endpoint(),
                settings.prefix().isBlank() ? "" : ", under " + settings.prefix());
        return store;
    }

    /** The store over this client, with the bucket, prefix, part size and limits of these settings. */
    public static S3BlobStore newS3BlobStore(S3Client s3Client, FileManagementProperties.S3 settings) {
        FileManagementProperties.Timeouts timeouts = settings.timeouts();
        return new S3BlobStore(s3Client, settings.bucket(), settings.prefix(),
                settings.partSizeMb() * 1024 * 1024,
                new S3BlobStore.Timeouts(Duration.ofSeconds(timeouts.attemptSeconds()),
                        Duration.ofSeconds(timeouts.callSeconds()), Duration.ofSeconds(timeouts.healthSeconds())));
    }

    /**
     * The storage answering is part of being ready: without it no file can be read or written
     * (issue 41). One indicator, {@code blobStore}, whichever the backend - the bucket answering a
     * {@code HeadBucket} within its limit, or the storage root being a directory the service may
     * write to - so {@code management.endpoint.health.group.readiness} names it once, and the
     * start fails if it is ever missing (issue 104: readiness used to check neither this nor the
     * database). Spring Boot's own disk space check is of the working directory, not the root.
     */
    @Bean
    public HealthIndicator blobStoreHealthIndicator(BlobStore blobStore) {
        if (blobStore instanceof S3BlobStore store) {
            return () -> store.bucketReachable() ? Health.up().build() : Health.down().build();
        }
        if (blobStore instanceof FilesystemBlobStore store) {
            return () -> store.rootUsable() ? Health.up().build() : Health.down().build();
        }
        throw new IllegalStateException("no health check for " + blobStore.getClass().getName());
    }
}
