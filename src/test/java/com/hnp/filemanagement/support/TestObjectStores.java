package com.hnp.filemanagement.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The object store the S3 tests run against: one SeaweedFS container per JVM - the store and the
 * version deploy/seaweedfs runs - started the first time a test asks for it, with one bucket.
 *
 * <p>{@code weed server} is master, volume, filer and S3 gateway in one process: the layout of
 * production is not what these tests are about, the S3 protocol as the store speaks it is. Its
 * one key may do anything, like deploy/seaweedfs's {@code operator}.
 */
public final class TestObjectStores {

    public static final String BUCKET = "file-management-test";
    public static final String ACCESS_KEY = "suite-access-key";
    public static final String SECRET_KEY = "suite-secret-key-not-a-real-one";

    private static final String S3_CONFIG = """
            {"identities": [{"name": "suite",
              "credentials": [{"accessKey": "%s", "secretKey": "%s"}],
              "actions": ["Admin", "Read", "Write", "List", "Tagging"]}]}
            """.formatted(ACCESS_KEY, SECRET_KEY);

    private TestObjectStores() {
    }

    /** {@code http://host:port} of the store's S3 gateway. */
    public static String endpoint() {
        return endpointOf(SeaweedFs.CONTAINER);
    }

    /**
     * The application on this store: the s3 backend, the suite's bucket and key, and a prefix of the
     * test's own, so that what one test class writes is never another's to find.
     */
    public static void useAsBackend(DynamicPropertyRegistry registry, String prefix) {
        registry.add("filemanagement.storage.backend", () -> "s3");
        registry.add("filemanagement.storage.s3.endpoint", TestObjectStores::endpoint);
        registry.add("filemanagement.storage.s3.bucket", () -> BUCKET);
        registry.add("filemanagement.storage.s3.access-key", () -> ACCESS_KEY);
        registry.add("filemanagement.storage.s3.secret-key", () -> SECRET_KEY);
        registry.add("filemanagement.storage.s3.prefix", () -> prefix);
        registry.add("filemanagement.storage.s3.part-size-mb", () -> "5");
    }

    /** Every key in the bucket under this prefix, without the prefix - what the store holds for the application. */
    public static List<String> keysUnder(String prefix) {
        List<String> keys = new ArrayList<>();
        try (S3Client s3 = client()) {
            s3.listObjectsV2Paginator(request -> request.bucket(BUCKET).prefix(prefix + "/"))
                    .contents().forEach(object -> keys.add(object.key().substring(prefix.length() + 1)));
        }
        return keys;
    }

    /** Removes everything under this prefix - between tests, what clearing the storage root is on disk. */
    public static void clear(String prefix) {
        try (S3Client s3 = client()) {
            s3.listObjectsV2Paginator(request -> request.bucket(BUCKET).prefix(prefix + "/"))
                    .contents().forEach(object -> s3.deleteObject(request -> request.bucket(BUCKET).key(object.key())));
        }
    }

    /** A client with the suite's key, as the application builds one. */
    public static S3Client client() {
        return client(ACCESS_KEY, SECRET_KEY);
    }

    public static S3Client client(String accessKey, String secretKey) {
        return client(endpoint(), accessKey, secretKey);
    }

    private static S3Client client(String endpoint, String accessKey, String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                .build();
    }

    // A holder: the container starts when this class is first touched, and never otherwise.
    private static final class SeaweedFs {
        static final GenericContainer<?> CONTAINER = started(container());
    }

    /**
     * A store of the test's own, started with the suite's bucket and key - for a test that stops it
     * halfway, which the shared one must never be. The caller stops it.
     */
    public static GenericContainer<?> startPrivateStore() {
        return started(container());
    }

    private static GenericContainer<?> container() {
        return new GenericContainer<>("chrislusf/seaweedfs:4.48")
                .withCommand("server", "-dir=/data", "-ip.bind=0.0.0.0",
                        "-master.volumeSizeLimitMB=64", "-volume.max=100",
                        "-s3", "-s3.port=8333", "-s3.config=/etc/seaweedfs/s3.json")
                .withCopyToContainer(Transferable.of(S3_CONFIG), "/etc/seaweedfs/s3.json")
                .withExposedPorts(8333)
                .waitingFor(Wait.forLogMessage(".*Start Seaweed S3 API Server.*\\n", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
    }

    private static GenericContainer<?> started(GenericContainer<?> container) {
        container.start();
        createBucket(endpointOf(container));
        return container;
    }

    /** {@code http://host:port} of this store's S3 gateway. */
    public static String endpointOf(GenericContainer<?> container) {
        return "http://" + container.getHost() + ":" + container.getMappedPort(8333);
    }

    /** The gateway answers before the filer behind it is ready; the bucket is retried until it is. */
    private static void createBucket(String endpoint) {
        RuntimeException last = null;
        try (S3Client s3 = client(endpoint, ACCESS_KEY, SECRET_KEY)) {
            for (int attempt = 0; attempt < 60; attempt++) {
                try {
                    s3.createBucket(request -> request.bucket(BUCKET));
                    return;
                } catch (RuntimeException e) {
                    last = e;
                    sleep();
                }
            }
        }
        throw new IllegalStateException("the test bucket could not be created", last);
    }

    private static void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
