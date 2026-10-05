package com.hnp.filemanagement.s3api;

import com.hnp.filemanagement.file.domain.DownloadRecorder;
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
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * API v2, S3-compatible (roadmap 9.10), driven by the AWS SDK for Java v2 itself - the client an
 * integration would use - against the application on a port: Signature V4 as the SDK computes it,
 * streamed bodies as the SDK sends them, pre-signed URLs, and every rule of 9.10.5-9.10.7.
 *
 * <p>Not {@code @Transactional}: the server answers on threads of its own, so what it writes stays.
 * Each test has an owner and a bucket (a top-level folder) of its own, and nothing shared is made -
 * no role, no permission - that another class creates for itself.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3ApiTest extends DatabaseSupport {

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
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DownloadRecorder downloadRecorder;

    private int ownerId;
    private FolderFixture.Chain chain;
    private String bucket;
    private final List<S3Client> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // No role: the upload policy's system-wide default, and nothing committed that another
        // class makes for itself (a role, a permission) - this test commits, as the server does.
        User owner = userRepository.save(TestData.user());
        ownerId = owner.getId();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, owner);
        bucket = S3ObjectService.bucketName(chain.category().getName());
    }

    @AfterEach
    void closeClients() {
        clients.forEach(S3Client::close);
    }

    @Test
    @DisplayName("upload, download, head and delete through the SDK - the bytes back exactly, one ETag for both")
    void theFourOperations() {
        Key key = key(true, true, true, "WRITE");
        S3Client s3 = client(key);
        byte[] bytes = pdf(5000, 1);

        PutObjectResponse put = s3.putObject(r -> r.bucket(bucket).key(keyInChain("report.pdf")), RequestBody.fromBytes(bytes));
        ResponseBytes<GetObjectResponse> got = s3.getObjectAsBytes(r -> r.bucket(bucket).key(keyInChain("report.pdf")));
        HeadObjectResponse head = s3.headObject(r -> r.bucket(bucket).key(keyInChain("report.pdf")));

        assertThat(got.asByteArray()).isEqualTo(bytes);
        assertThat(got.response().eTag()).isEqualTo(put.eTag());
        assertThat(head.contentLength()).isEqualTo(bytes.length);
        assertThat(head.contentType()).isEqualTo("application/pdf");
        assertThat(put.versionId()).isNotBlank();
        // Recorded apart from the old v2, whose retirement this log is to decide (roadmap 9.10.12).
        downloadRecorder.flush();
        assertThat(jdbc.queryForList("SELECT d.channel FROM file_download d JOIN file_info f ON f.id = d.file_info_id"
                + " WHERE f.folder_id = ?", String.class, chain.tag().getId())).isNotEmpty().containsOnly("S3");

        s3.deleteObject(r -> r.bucket(bucket).key(keyInChain("report.pdf")));
        assertThat(code(() -> s3.headObject(r -> r.bucket(bucket).key(keyInChain("report.pdf"))))).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_info WHERE folder_id = ?", Integer.class,
                chain.tag().getId())).isZero();
    }

    @Test
    @DisplayName("a title already in the folder is a new version; versionId reads an older one; If-None-Match: * is a 412")
    void aTitleThereIsANewVersion() {
        S3Client s3 = client(key(false, false, false, "WRITE"));
        byte[] first = pdf(1000, 2);
        byte[] second = pdf(1500, 3);
        PutObjectResponse v1 = s3.putObject(r -> r.bucket(bucket).key(keyInChain("plan.pdf")), RequestBody.fromBytes(first));
        PutObjectResponse v2 = s3.putObject(r -> r.bucket(bucket).key(keyInChain("plan.pdf")), RequestBody.fromBytes(second));

        assertThat(v2.versionId()).isNotEqualTo(v1.versionId());
        assertThat(s3.getObjectAsBytes(r -> r.bucket(bucket).key(keyInChain("plan.pdf"))).asByteArray()).isEqualTo(second);
        assertThat(s3.getObjectAsBytes(r -> r.bucket(bucket).key(keyInChain("plan.pdf")).versionId(v1.versionId()))
                .asByteArray()).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT last_version FROM file_info WHERE folder_id = ?", Integer.class,
                chain.tag().getId())).isEqualTo(2);

        assertThat(code(() -> s3.putObject(r -> r.bucket(bucket).key(keyInChain("plan.pdf")).ifNoneMatch("*"),
                RequestBody.fromBytes(pdf(800, 4))))).isEqualTo(412);
        assertThat(jdbc.queryForObject("SELECT last_version FROM file_info WHERE folder_id = ?", Integer.class,
                chain.tag().getId())).as("nothing stored on a 412").isEqualTo(2);
        s3.putObject(r -> r.bucket(bucket).key(keyInChain("fresh.pdf")).ifNoneMatch("*"), RequestBody.fromBytes(pdf(700, 5)));
    }

    @Test
    @DisplayName("missing folders are created only for a key that may - and a refused file takes the new folders back")
    void foldersCreatedOnlyIfTheKeyMay() {
        int before = folderCount();
        S3Client cannot = client(key(false, false, false, "WRITE"));
        assertThat(code(() -> cannot.putObject(r -> r.bucket(bucket).key("P-1234/contracts/C-1/a.pdf"),
                RequestBody.fromBytes(pdf(600, 6))))).isEqualTo(403);
        assertThat(folderCount()).isEqualTo(before);

        S3Client may = client(key(true, false, false, "WRITE"));
        may.putObject(r -> r.bucket(bucket).key("P-1234/contracts/C-1/a.pdf"), RequestBody.fromBytes(pdf(600, 6)));
        assertThat(folderCount()).isEqualTo(before + 3);
        // Persian names, through the same key.
        may.putObject(r -> r.bucket(bucket).key("شرکت نمونه/قراردادها/گزارش ماهانه.pdf"), RequestBody.fromBytes(pdf(600, 7)));
        assertThat(may.getObjectAsBytes(r -> r.bucket(bucket).key("شرکت نمونه/قراردادها/گزارش ماهانه.pdf")).asByteArray())
                .isEqualTo(pdf(600, 7));

        // Bytes that are not a PDF: refused after the folders were made - and they go with it.
        int now = folderCount();
        assertThat(code(() -> may.putObject(r -> r.bucket(bucket).key("X-Gone/y/not-really.pdf"),
                RequestBody.fromString("plain text, not a pdf")))).isEqualTo(400);
        assertThat(folderCount()).isEqualTo(now);
    }

    @Test
    @DisplayName("a folder created and deleted by its key/; a full one is a 409; each capability is needed")
    void foldersByTheirKey() {
        S3Client may = client(key(true, true, true, "WRITE"));
        may.putObject(r -> r.bucket(bucket).key("Empty-Folder/"), RequestBody.empty());
        assertThat(folderRepository.findByParentIdAndNameIgnoreCase(chain.category().getId(), "Empty-Folder")).isPresent();
        may.putObject(r -> r.bucket(bucket).key("Full-Folder/x.pdf"), RequestBody.fromBytes(pdf(400, 8)));

        assertThat(code(() -> may.deleteObject(r -> r.bucket(bucket).key("Full-Folder/")))).isEqualTo(409);
        may.deleteObject(r -> r.bucket(bucket).key("Empty-Folder/"));
        assertThat(folderRepository.findByParentIdAndNameIgnoreCase(chain.category().getId(), "Empty-Folder")).isEmpty();

        S3Client mayNotDelete = client(key(true, false, false, "WRITE"));
        assertThat(code(() -> mayNotDelete.deleteObject(r -> r.bucket(bucket).key("Full-Folder/x.pdf")))).isEqualTo(403);
        mayNotDelete.putObject(r -> r.bucket(bucket).key("Another/"), RequestBody.empty());
        assertThat(code(() -> mayNotDelete.deleteObject(r -> r.bucket(bucket).key("Another/")))).isEqualTo(403);
        // A key that names nothing is not an error, as in S3.
        may.deleteObject(r -> r.bucket(bucket).key("Nothing/here.pdf"));
    }

    @Test
    @DisplayName("a READ grant reads and cannot write; a key without a grant sees no bucket")
    void grants() {
        S3Client writer = client(key(false, false, false, "WRITE"));
        writer.putObject(r -> r.bucket(bucket).key(keyInChain("doc.pdf")), RequestBody.fromBytes(pdf(300, 9)));

        S3Client reader = client(key(false, false, false, "READ"));
        assertThat(reader.getObjectAsBytes(r -> r.bucket(bucket).key(keyInChain("doc.pdf"))).asByteArray()).isEqualTo(pdf(300, 9));
        assertThat(code(() -> reader.putObject(r -> r.bucket(bucket).key(keyInChain("doc.pdf")),
                RequestBody.fromBytes(pdf(300, 10))))).isEqualTo(403);

        S3Client nobody = client(key(true, true, true, null));
        assertThat(code(() -> nobody.getObjectAsBytes(r -> r.bucket(bucket).key(keyInChain("doc.pdf"))))).isEqualTo(404);
    }

    @Test
    @DisplayName("a body of many chunks, each one's signature checked, arrives whole")
    void aLargeStreamedBody() {
        S3Client s3 = client(key(false, false, false, "WRITE"));
        byte[] bytes = pdf(6 * 1024 * 1024 + 12_345, 11);
        s3.putObject(r -> r.bucket(bucket).key(keyInChain("big.pdf")), RequestBody.fromBytes(bytes));
        assertThat(s3.getObjectAsBytes(r -> r.bucket(bucket).key(keyInChain("big.pdf"))).asByteArray()).isEqualTo(bytes);
    }

    @Test
    @DisplayName("refused: a wrong secret, an unknown key, a V1 key, no signature; an S3 key is no bearer on v1")
    void refusals() throws Exception {
        Key key = key(true, true, true, "WRITE");
        S3Client wrongSecret = client(new Key(key.accessKeyId(), "x".repeat(40)));
        assertThat(errorCode(() -> wrongSecret.headBucket(r -> r.bucket(bucket)), () ->
                wrongSecret.getObject(r -> r.bucket(bucket).key("a.pdf")))).isEqualTo("SignatureDoesNotMatch");

        S3Client unknown = client(new Key("FMNOSUCHKEY000000000", "y".repeat(40)));
        assertThat(errorCode(null, () -> unknown.getObject(r -> r.bucket(bucket).key("a.pdf")))).isEqualTo("InvalidAccessKeyId");

        ApiKeyDTO v1Request = new ApiKeyDTO();
        v1Request.setTitle("v1 " + TestData.nextSequence());
        v1Request.setFolderGrants(List.of(chain.category().getId() + ":WRITE"));
        ApiKeyCreatedDTO v1 = apiKeyService.create(v1Request, ownerId);
        S3Client asS3 = client(new Key(v1.keyId(), v1.credential()));
        assertThat(errorCode(null, () -> asS3.getObject(r -> r.bucket(bucket).key("a.pdf")))).isEqualTo("InvalidAccessKeyId");

        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(URI.create(endpoint() + "/" + bucket + "/a.pdf")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(anonymous.statusCode()).isEqualTo(403);
            assertThat(anonymous.body()).contains("<Code>AccessDenied</Code>");

            HttpResponse<String> bearer = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/files/health-test"))
                    .header("Authorization", "Bearer fmk_" + key.accessKeyId() + "_" + key.secret()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(bearer.statusCode()).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("a pre-signed URL downloads until it expires; one with its signature changed is refused")
    void presignedDownloads() throws Exception {
        Key key = key(false, false, false, "WRITE");
        client(key).putObject(r -> r.bucket(bucket).key(keyInChain("shared.pdf")), RequestBody.fromBytes(pdf(900, 12)));
        try (S3Presigner presigner = S3Presigner.builder().endpointOverride(URI.create(endpoint())).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key.accessKeyId(), key.secret())))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build(); HttpClient http = HttpClient.newHttpClient()) {
            URI url = presigner.presignGetObject(p -> p.signatureDuration(Duration.ofMinutes(10))
                    .getObjectRequest(g -> g.bucket(bucket).key(keyInChain("shared.pdf")))).url().toURI();
            HttpResponse<byte[]> ok = http.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(ok.statusCode()).isEqualTo(200);
            assertThat(ok.body()).isEqualTo(pdf(900, 12));

            String tampered = url.toString().replaceAll("X-Amz-Signature=[0-9a-f]", "X-Amz-Signature=0");
            HttpResponse<String> refused = http.send(HttpRequest.newBuilder(URI.create(tampered)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(refused.statusCode()).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("bucket requests: HeadBucket, GetBucketLocation (n8n asks it first), versioning on, ListBuckets by access; listing a 501")
    void bucketRequests() {
        S3Client s3 = client(key(false, false, false, "READ"));

        s3.headBucket(r -> r.bucket(bucket));
        assertThat(s3.getBucketLocation(r -> r.bucket(bucket)).locationConstraintAsString()).isEqualTo("us-east-1");
        assertThat(s3.getBucketVersioning(r -> r.bucket(bucket)).statusAsString()).isEqualTo("Enabled");
        assertThat(s3.listBuckets().buckets()).extracting(b -> b.name()).contains(bucket);
        assertThat(code(() -> s3.listObjectsV2(r -> r.bucket(bucket)))).isEqualTo(501);
        assertThat(code(() -> s3.createBucket(r -> r.bucket("made-by-a-key")))).isEqualTo(501);
        assertThat(code(() -> s3.deleteBucket(r -> r.bucket(bucket)))).isEqualTo(501);
        assertThat(folderRepository.findById(chain.category().getId())).as("the bucket's folder is still there").isPresent();

        S3Client nobody = client(key(true, true, true, null));
        assertThat(code(() -> nobody.headBucket(r -> r.bucket(bucket)))).isEqualTo(404);
        assertThat(code(() -> nobody.getBucketLocation(r -> r.bucket(bucket)))).isEqualTo(404);
        assertThat(nobody.listBuckets().buckets()).extracting(b -> b.name()).doesNotContain(bucket);
    }

    // ---------------------------------------------------------------- helpers

    record Key(String accessKeyId, String secret) {
    }

    private Key key(boolean createFolders, boolean deleteFiles, boolean deleteFolders, String grant) {
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("s3 " + TestData.nextSequence());
        request.setKind(ApiKeyKind.S3);
        request.setMayCreateFolders(createFolders);
        request.setMayDeleteFiles(deleteFiles);
        request.setMayDeleteFolders(deleteFolders);
        request.setFolderGrants(grant == null ? List.of() : List.of(chain.category().getId() + ":" + grant));
        ApiKeyCreatedDTO created = apiKeyService.create(request, ownerId);
        return new Key(created.keyId(), created.credential());
    }

    private S3Client client(Key key) {
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(endpoint()))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key.accessKeyId(), key.secret())))
                .build();
        clients.add(client);
        return client;
    }

    private String endpoint() {
        return "http://localhost:" + port + S3Controller.MOUNT;
    }

    private String keyInChain(String name) {
        return chain.subCategory().getName() + "/" + chain.tag().getName() + "/" + name;
    }

    private int folderCount() {
        Folder bucketFolder = chain.category();
        Integer count = jdbc.queryForObject("SELECT count(*) FROM folder WHERE path LIKE ?", Integer.class,
                bucketFolder.getPath() + "%");
        return count == null ? 0 : count;
    }

    private static int code(Runnable call) {
        try {
            call.run();
        } catch (S3Exception e) {
            return e.statusCode();
        }
        throw new AssertionError("the call succeeded");
    }

    private static String errorCode(Runnable headCall, Runnable getCall) {
        try {
            getCall.run();
        } catch (S3Exception e) {
            return e.awsErrorDetails().errorCode();
        }
        throw new AssertionError("the call succeeded");
    }

    /** Bytes the content check takes for a PDF, of this size, different for each seed. */
    private static byte[] pdf(int size, long seed) {
        byte[] header = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = new byte[size];
        Random random = new Random(seed);
        for (int i = 0; i < size; i++) {
            bytes[i] = i < header.length ? header[i] : (byte) ('a' + random.nextInt(26));
        }
        return bytes;
    }
}
