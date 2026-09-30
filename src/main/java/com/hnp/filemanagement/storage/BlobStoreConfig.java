package com.hnp.filemanagement.storage;

import com.hnp.filemanagement.shared.config.FileManagementProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

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

    @Bean
    @ConditionalOnProperty(name = BACKEND, havingValue = "filesystem", matchIfMissing = true)
    public BlobStore filesystemBlobStore(FileManagementProperties properties) {
        Path root = Path.of(properties.baseDir()).toAbsolutePath().normalize();
        logger.info("files are stored on the filesystem, under {}{}", root,
                Files.isDirectory(root) ? "" : " - which does not exist yet");
        return new FilesystemBlobStore(properties);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = BACKEND, havingValue = "s3")
    public S3Client s3Client(FileManagementProperties properties) {
        FileManagementProperties.S3 settings = properties.storage().s3();
        if (!settings.missing().isEmpty()) {
            throw new IllegalStateException("filemanagement.storage.backend is s3, and these are not set: "
                    + String.join(", ", settings.missing()));
        }
        return S3Client.builder()
                .endpointOverride(URI.create(settings.endpoint()))
                .region(Region.of(settings.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(settings.accessKey(), settings.secretKey())))
                .forcePathStyle(settings.pathStyleAccess())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = BACKEND, havingValue = "s3")
    public BlobStore s3BlobStore(S3Client s3Client, FileManagementProperties properties) {
        FileManagementProperties.S3 settings = properties.storage().s3();
        S3BlobStore store = new S3BlobStore(s3Client, settings.bucket(), settings.prefix(),
                settings.partSizeMb() * 1024 * 1024);
        store.requireBucket(settings.endpoint());
        logger.info("files are stored in the S3 bucket {} at {}{}", settings.bucket(), settings.endpoint(),
                settings.prefix().isBlank() ? "" : ", under " + settings.prefix());
        return store;
    }

    /**
     * The bucket answering is part of being ready: without it no file can be read or written
     * ({@code /actuator/health/readiness}; issue 41). The filesystem needs no indicator of its own -
     * Spring Boot's disk space check covers the working directory's disk.
     */
    @Bean
    @ConditionalOnProperty(name = BACKEND, havingValue = "s3")
    public HealthIndicator blobStoreHealthIndicator(BlobStore blobStore) {
        S3BlobStore store = (S3BlobStore) blobStore;
        return () -> store.bucketReachable() ? Health.up().build() : Health.down().build();
    }
}
