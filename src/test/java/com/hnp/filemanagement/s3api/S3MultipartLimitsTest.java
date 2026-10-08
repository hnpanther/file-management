package com.hnp.filemanagement.s3api;

import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.ApiKeyCreatedDTO;
import com.hnp.filemanagement.identity.domain.ApiKeyDTO;
import com.hnp.filemanagement.identity.domain.ApiKeyKind;
import com.hnp.filemanagement.identity.domain.ApiKeyService;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bounds of a multipart upload (roadmap 9.10 step 5), on a server whose upload cap is 1 MB and
 * which lets a key have three uploads in progress: the parts never hold more than the cap - a part
 * sent again counts once - and a fourth upload waits for one of the three to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.servlet.multipart.max-file-size=1MB",
        "spring.servlet.multipart.max-request-size=2MB",
        "filemanagement.s3-api.multipart.max-open-uploads=3"})
class S3MultipartLimitsTest extends DatabaseSupport {

    private static final int KB = 1024;

    @LocalServerPort
    private int port;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;

    private int ownerId;
    private String bucket;
    private Folder bucketFolder;
    private S3Client s3;
    private final List<S3Client> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(TestData.user());
        ownerId = owner.getId();
        long n = TestData.nextSequence();
        bucketFolder = FolderFixture.category(folderRepository, owner, "Limits" + n,
                tagGroupRepository.save(TestData.tagGroup(owner, "ml" + n)));
        bucket = S3ObjectService.bucketName(bucketFolder.getName());
        s3 = client();
    }

    @AfterEach
    void closeClients() {
        clients.forEach(S3Client::close);
    }

    @Test
    @DisplayName("the parts never hold more than the cap: a part past it is EntityTooLarge and not kept; a part sent again counts once")
    void theCap() {
        String key = "capped.pdf";
        String uploadId = s3.createMultipartUpload(r -> r.bucket(bucket).key(key)).uploadId();
        String one = upload(key, uploadId, 1, S3MultipartTest.pdf(600 * KB, 1));
        assertThat(code(() -> upload(key, uploadId, 2, S3MultipartTest.letters(600 * KB, 2)))).isEqualTo("EntityTooLarge");
        assertThat(s3.listParts(r -> r.bucket(bucket).key(key).uploadId(uploadId)).parts()).hasSize(1);
        // One part declared above the cap is refused before a byte of it is read - as a PUT's is: the
        // server will not read a body it has already refused, so the client may see the connection
        // closed rather than the error. What matters: it fails, and nothing of it is kept.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> upload(key, uploadId, 3, S3MultipartTest.letters(1100 * KB, 3)))
                .isInstanceOf(software.amazon.awssdk.core.exception.SdkException.class);
        assertThat(s3.listParts(r -> r.bucket(bucket).key(key).uploadId(uploadId)).parts())
                .extracting(software.amazon.awssdk.services.s3.model.Part::partNumber).containsExactly(1);

        // Part 1 sent again, smaller: its old bytes no longer count, and part 2 fits.
        one = upload(key, uploadId, 1, S3MultipartTest.pdf(300 * KB, 4));
        String two = upload(key, uploadId, 2, S3MultipartTest.letters(600 * KB, 5));
        String first = one;
        s3.completeMultipartUpload(r -> r.bucket(bucket).key(key).uploadId(uploadId).multipartUpload(m -> m.parts(
                CompletedPart.builder().partNumber(1).eTag(first).build(), CompletedPart.builder().partNumber(2).eTag(two).build())));
        assertThat(s3.headObject(r -> r.bucket(bucket).key(key)).contentLength()).isEqualTo(900L * KB);
    }

    @Test
    @DisplayName("a key has three uploads in progress at most; a fourth waits for one to end")
    void openUploads() {
        List<String> open = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int n = i;
            open.add(s3.createMultipartUpload(r -> r.bucket(bucket).key("open-" + n + ".pdf")).uploadId());
        }
        assertThat(code(() -> s3.createMultipartUpload(r -> r.bucket(bucket).key("fourth.pdf")))).isEqualTo("InvalidArgument");
        assertThat(client().createMultipartUpload(r -> r.bucket(bucket).key("another-key.pdf")).uploadId())
                .as("each key its own three").isNotBlank();
        s3.abortMultipartUpload(r -> r.bucket(bucket).key("open-0.pdf").uploadId(open.getFirst()));
        assertThat(s3.createMultipartUpload(r -> r.bucket(bucket).key("fourth.pdf")).uploadId()).isNotBlank();
    }

    @Test
    @DisplayName("sixteen uploads begun at once by one key: three begin, the rest are refused - the bound on its disk holds under a race")
    void openUploadsAtOnce() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> answers = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                int n = i;
                answers.add(threads.submit(() -> {
                    start.await();
                    try {
                        return s3.createMultipartUpload(r -> r.bucket(bucket).key("race-" + n + ".pdf")).uploadId() == null ? "?" : "begun";
                    } catch (S3Exception e) {
                        return e.awsErrorDetails().errorCode();
                    }
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> answer : answers) {
                results.add(answer.get());
            }
            assertThat(results).filteredOn("begun"::equals).hasSize(3);
            assertThat(results).filteredOn(r -> !"begun".equals(r)).containsOnly("InvalidArgument");
        } finally {
            threads.shutdownNow();
        }
    }

    // ---------------------------------------------------------------- helpers

    private String upload(String key, String uploadId, int number, byte[] bytes) {
        return s3.uploadPart(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(number), RequestBody.fromBytes(bytes)).eTag();
    }

    private static String code(Runnable call) {
        try {
            call.run();
        } catch (S3Exception e) {
            return e.awsErrorDetails().errorCode();
        }
        throw new AssertionError("the call succeeded");
    }

    private S3Client client() {
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("s3 limits " + TestData.nextSequence());
        request.setKind(ApiKeyKind.S3);
        request.setFolderGrants(List.of(bucketFolder.getId() + ":WRITE"));
        ApiKeyCreatedDTO created = apiKeyService.create(request, ownerId);
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create("http://localhost:" + port + S3Controller.MOUNT))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(created.keyId(), created.credential())))
                .build();
        clients.add(client);
        return client;
    }
}
