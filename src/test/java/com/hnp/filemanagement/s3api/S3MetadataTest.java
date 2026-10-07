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
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An object's metadata on the S3 surface (roadmap 12.2 step 4, 2.13.0), through the AWS SDK for Java
 * v2: {@code x-amz-meta-*} stored and answered as S3 does - names lower-cased, a value that is not
 * ASCII in RFC 2047, S3's 2 KB - a new version carrying exactly what it was sent, and
 * {@code x-fm-metadata} for a document S3's flat strings cannot say.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3MetadataTest extends DatabaseSupport {

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

    private int ownerId;
    private Folder bucketFolder;
    private String bucket;
    private S3Client s3;
    private final List<S3Client> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(TestData.user());
        ownerId = owner.getId();
        long n = TestData.nextSequence();
        bucketFolder = FolderFixture.category(folderRepository, owner, "Meta" + n,
                tagGroupRepository.save(TestData.tagGroup(owner, "mg" + n)));
        bucket = S3ObjectService.bucketName(bucketFolder.getName());
        s3 = client(bucketFolder.getId() + ":WRITE");
    }

    @AfterEach
    void closeClients() {
        clients.forEach(S3Client::close);
    }

    @Test
    @DisplayName("x-amz-meta-* stored under lower-cased names and answered by HEAD and GET as sent")
    void flatStrings() {
        Map<String, String> sent = new LinkedHashMap<>();
        sent.put("Contract-No", "C-5678");
        sent.put("source", "erp");
        s3.putObject(r -> r.bucket(bucket).key("deal.txt").metadata(sent), RequestBody.fromString("the deal"));

        assertThat(stored("deal")).isEqualTo("{\"source\": \"erp\", \"contract-no\": \"C-5678\"}");
        HeadObjectResponse head = s3.headObject(r -> r.bucket(bucket).key("deal.txt"));
        assertThat(head.metadata()).containsExactlyInAnyOrderEntriesOf(Map.of("contract-no", "C-5678", "source", "erp"));
        assertThat(head.sdkHttpResponse().firstMatchingHeader("x-amz-missing-meta")).isEmpty();
        assertThat(s3.getObject(r -> r.bucket(bucket).key("deal.txt")).response().metadata())
                .containsEntry("contract-no", "C-5678");
    }

    @Test
    @DisplayName("a value that is not ASCII travels in RFC 2047, as S3 asks - stored decoded, answered encoded")
    void persianInRfc2047() {
        String name = "علی رضایی";
        String bWord = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8)) + "?=";
        s3.putObject(r -> r.bucket(bucket).key("person.txt").metadata(Map.of("full-name", bWord)), RequestBody.fromString("x"));
        assertThat(stored("person")).isEqualTo("{\"full-name\": \"علی رضایی\"}");
        String answered = s3.headObject(r -> r.bucket(bucket).key("person.txt")).metadata().get("full-name");
        assertThat(answered).isEqualTo(bWord);

        s3.putObject(r -> r.bucket(bucket).key("q.txt").metadata(Map.of("city", "=?utf-8?Q?=D8=AA=D9=87=D8=B1=D8=A7=D9=86_city?=")),
                RequestBody.fromString("x"));
        assertThat(stored("q")).isEqualTo("{\"city\": \"تهران city\"}");
    }

    @Test
    @DisplayName("a new version carries what its PUT sent - none included - never the version before, as in S3")
    void eachVersionItsOwn() {
        s3.putObject(r -> r.bucket(bucket).key("plan.txt").metadata(Map.of("stage", "draft")), RequestBody.fromString("v1"));
        s3.putObject(r -> r.bucket(bucket).key("plan.txt"), RequestBody.fromString("v2"));
        assertThat(s3.headObject(r -> r.bucket(bucket).key("plan.txt")).metadata()).isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT d.metadata::text FROM file_details d JOIN file_info f ON f.id = d.file_info_id
                WHERE f.folder_id = ? AND f.file_name = 'plan' ORDER BY d.version""", String.class, bucketFolder.getId()))
                .containsExactly("{\"stage\": \"draft\"}", null);
    }

    @Test
    @DisplayName("above S3's 2 KB is MetadataTooLarge, and nothing is stored")
    void tooLarge() {
        S3Exception refused = refusal(() -> s3.putObject(r -> r.bucket(bucket).key("big.txt").metadata(Map.of("k", "x".repeat(2100))),
                RequestBody.fromString("x")));
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.awsErrorDetails().errorCode()).isEqualTo("MetadataTooLarge");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_info WHERE folder_id = ? AND file_name = 'big'", Integer.class,
                bucketFolder.getId())).isZero();
    }

    @Test
    @DisplayName("x-fm-metadata carries a nested document; the answer gives the flat part as x-amz-meta-*, the count left out, and the whole")
    void aWholeDocument() {
        String document = "{\"contractNo\":\"C-5678\",\"amount\":1500,\"party\":{\"code\":\"P-1\"},\"نام\":\"علی\"}";
        String header = Base64.getEncoder().encodeToString(document.getBytes(StandardCharsets.UTF_8));
        s3.putObject(r -> r.bucket(bucket).key("nested.txt").overrideConfiguration(o -> o.putHeader("x-fm-metadata", header)),
                RequestBody.fromString("x"));

        HeadObjectResponse head = s3.headObject(r -> r.bucket(bucket).key("nested.txt"));
        assertThat(head.metadata()).as("a name as it is stored - here as v1 might have set it")
                .containsExactlyInAnyOrderEntriesOf(Map.of("contractNo", "C-5678", "amount", "1500"));
        assertThat(head.sdkHttpResponse().firstMatchingHeader("x-amz-missing-meta")).contains("2");
        String whole = new String(Base64.getDecoder().decode(head.sdkHttpResponse().firstMatchingHeader("x-fm-metadata").orElseThrow()),
                StandardCharsets.UTF_8);
        assertThat(whole).isEqualTo(stored("nested")).contains("\"party\": {\"code\": \"P-1\"}", "علی");

        assertThat(refusal(() -> s3.putObject(r -> r.bucket(bucket).key("both.txt").metadata(Map.of("a", "1"))
                .overrideConfiguration(o -> o.putHeader("x-fm-metadata", header)), RequestBody.fromString("x"))).statusCode())
                .isEqualTo(400);
        assertThat(refusal(() -> s3.putObject(r -> r.bucket(bucket).key("bad.txt")
                .overrideConfiguration(o -> o.putHeader("x-fm-metadata", Base64.getEncoder().encodeToString("[1]".getBytes(StandardCharsets.UTF_8)))),
                RequestBody.fromString("x"))).statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("a Persian value sent raw - the Java SDK sends it as '?'s - is refused by its signature, as by S3, and nothing stored")
    void theSdkCannotSendPersianRaw() {
        S3Exception refused = refusal(() -> s3.putObject(r -> r.bucket(bucket).key("raw.txt").metadata(Map.of("name", "علی")),
                RequestBody.fromString("x")));
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_info WHERE folder_id = ? AND file_name = 'raw'", Integer.class,
                bucketFolder.getId())).isZero();
    }

    @Test
    @DisplayName("a document of hundreds of short keys - set through v1 - never answers more headers than the server may send")
    void manyKeysStayWithinTheHeaders() {
        s3.putObject(r -> r.bucket(bucket).key("many.txt"), RequestBody.fromString("x"));
        StringBuilder document = new StringBuilder("{");
        for (int i = 0; i < 600; i++) {
            document.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":\"").append("v".repeat(i % 7)).append("\"");
        }
        jdbc.update("""
                UPDATE file_details SET metadata = CAST(? AS jsonb) WHERE file_info_id =
                    (SELECT id FROM file_info WHERE folder_id = ? AND file_name = 'many')""",
                document.append("}").toString(), bucketFolder.getId());

        HeadObjectResponse head = s3.headObject(r -> r.bucket(bucket).key("many.txt"));
        int sent = head.metadata().size();
        assertThat(sent).isPositive().isLessThan(600);
        assertThat(head.sdkHttpResponse().firstMatchingHeader("x-amz-missing-meta")).contains(String.valueOf(600 - sent));
        int headerBytes = head.sdkHttpResponse().headers().entrySet().stream()
                .mapToInt(e -> e.getKey().length() + 4 + String.join(",", e.getValue()).length()).sum();
        assertThat(headerBytes).as("well inside the server's 8 KB of response headers").isLessThan(6 * 1024);
        assertThat(s3.getObjectAsBytes(r -> r.bucket(bucket).key("many.txt")).asUtf8String()).isEqualTo("x");
    }

    // ---------------------------------------------------------------- helpers

    private String stored(String title) {
        return jdbc.queryForObject("""
                SELECT d.metadata::text FROM file_details d JOIN file_info f ON f.id = d.file_info_id
                WHERE f.folder_id = ? AND f.file_name = ? ORDER BY d.version DESC, d.id DESC LIMIT 1""",
                String.class, bucketFolder.getId(), title);
    }

    private static S3Exception refusal(Runnable call) {
        try {
            call.run();
        } catch (S3Exception e) {
            return e;
        }
        throw new AssertionError("accepted");
    }

    private S3Client client(String grant) {
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("s3 metadata " + TestData.nextSequence());
        request.setKind(ApiKeyKind.S3);
        request.setFolderGrants(List.of(grant));
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
