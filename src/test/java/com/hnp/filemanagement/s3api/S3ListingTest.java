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
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.EncodingType;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Listing and deleting many on the S3 surface (roadmap 9.10, 2.12.0), through the AWS SDK for Java
 * v2 against the application: {@code ListObjectsV2} and {@code ListObjects} in S3's order - the
 * bytes of the key, which {@code aws s3 sync} relies on - by level and whole, paged, url-encoded,
 * as far as the key may read; and {@code DeleteObjects}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3ListingTest extends DatabaseSupport {

    /** Every key the tests put, in S3's order: "a/b/" sorts before "a/b0.pdf" ('/' is 0x2F, '0' 0x30). */
    private static final List<String> KEYS = List.of(
            "a/B-upper.pdf", "a/b/deep/y.pdf", "a/b/x.pdf", "a/b0.pdf", "a/with space.pdf", "a/زرد.pdf", "c.pdf");

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
    private final List<S3Client> clients = new ArrayList<>();

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(TestData.user());
        ownerId = owner.getId();
        long n = TestData.nextSequence();
        bucketFolder = FolderFixture.category(folderRepository, owner, "List" + n,
                tagGroupRepository.save(TestData.tagGroup(owner, "lg" + n)));
        bucket = S3ObjectService.bucketName(bucketFolder.getName());
        S3Client writer = client(key(true, true, true, bucketFolder.getId() + ":WRITE"));
        long seed = 1;
        for (String key : KEYS) {
            byte[] bytes = pdf(300 + (int) seed, seed++);
            writer.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromBytes(bytes));
        }
    }

    @AfterEach
    void closeClients() {
        clients.forEach(S3Client::close);
    }

    @Test
    @DisplayName("the whole bucket in S3's byte order, every key exactly once, a page of two at a time")
    void theWholeBucketPaged() {
        S3Client s3 = client(key(false, false, false, bucketFolder.getId() + ":READ"));
        List<String> listed = new ArrayList<>();
        int pages = 0;
        for (ListObjectsV2Response page : s3.listObjectsV2Paginator(r -> r.bucket(bucket).maxKeys(2))) {
            page.contents().forEach(object -> listed.add(object.key()));
            pages++;
            assertThat(page.keyCount()).isLessThanOrEqualTo(2);
        }
        assertThat(listed).containsExactlyElementsOf(KEYS);
        assertThat(KEYS).isSortedAccordingTo(Comparator.naturalOrder());
        assertThat(pages).isEqualTo(4);

        S3Object first = s3.listObjectsV2(r -> r.bucket(bucket)).contents().getFirst();
        assertThat(first.size()).isEqualTo(301);
        assertThat(first.eTag()).as("the ETag a HEAD answers").isEqualTo(s3.headObject(r -> r.bucket(bucket).key(first.key())).eTag());
        assertThat(first.lastModified()).isNotNull();
    }

    @Test
    @DisplayName("by level: folders as common prefixes and files as objects, merged in key order, below any prefix")
    void byLevel() {
        S3Client s3 = client(key(false, false, false, bucketFolder.getId() + ":READ"));

        ListObjectsV2Response top = s3.listObjectsV2(r -> r.bucket(bucket).delimiter("/"));
        assertThat(top.commonPrefixes()).extracting(CommonPrefix::prefix).containsExactly("a/");
        assertThat(top.contents()).extracting(S3Object::key).containsExactly("c.pdf");

        ListObjectsV2Response a = s3.listObjectsV2(r -> r.bucket(bucket).delimiter("/").prefix("a/"));
        assertThat(a.commonPrefixes()).extracting(CommonPrefix::prefix).containsExactly("a/b/");
        assertThat(a.contents()).extracting(S3Object::key)
                .containsExactly("a/B-upper.pdf", "a/b0.pdf", "a/with space.pdf", "a/زرد.pdf");

        // A prefix part of the way into a name - without case, as a key is resolved.
        ListObjectsV2Response b = s3.listObjectsV2(r -> r.bucket(bucket).delimiter("/").prefix("A/b"));
        assertThat(b.commonPrefixes()).extracting(CommonPrefix::prefix).containsExactly("a/b/");
        assertThat(b.contents()).extracting(S3Object::key).containsExactly("a/B-upper.pdf", "a/b0.pdf");

        // Paged across folders and files together: a page answers its prefixes and its objects in two
        // lists, so each page is merged back into key order before the pages are joined.
        List<String> levelInPages = new ArrayList<>();
        for (ListObjectsV2Response page : s3.listObjectsV2Paginator(r -> r.bucket(bucket).delimiter("/").prefix("a/").maxKeys(2))) {
            List<String> entries = new ArrayList<>();
            page.commonPrefixes().forEach(p -> entries.add(p.prefix()));
            page.contents().forEach(o -> entries.add(o.key()));
            assertThat(entries).hasSizeLessThanOrEqualTo(2);
            entries.sort(Comparator.naturalOrder());
            levelInPages.addAll(entries);
        }
        assertThat(levelInPages).containsExactly("a/B-upper.pdf", "a/b/", "a/b0.pdf", "a/with space.pdf", "a/زرد.pdf");

        assertThat(s3.listObjectsV2(r -> r.bucket(bucket).prefix("nowhere/")).contents()).isEmpty();
        assertThat(s3.listObjectsV2(r -> r.bucket(bucket).prefix("a//")).contents()).isEmpty();
    }

    @Test
    @DisplayName("url-encoded keys come back whole; version 1 pages by marker; max-keys 0 lists nothing")
    void encodingAndVersionOne() {
        S3Client s3 = client(key(false, false, false, bucketFolder.getId() + ":READ"));
        ListObjectsV2Response encoded = s3.listObjectsV2(r -> r.bucket(bucket).prefix("a/").encodingType(EncodingType.URL));
        assertThat(encoded.contents()).extracting(S3Object::key).contains("a/with space.pdf", "a/زرد.pdf");

        List<String> v1 = new ArrayList<>();
        String marker = null;
        for (int i = 0; i < 10; i++) {
            String from = marker;
            ListObjectsResponse page = s3.listObjects(r -> r.bucket(bucket).maxKeys(3).marker(from));
            page.contents().forEach(o -> v1.add(o.key()));
            if (!page.isTruncated()) {
                break;
            }
            marker = page.contents().getLast().key();
        }
        assertThat(v1).containsExactlyElementsOf(KEYS);

        ListObjectsV2Response none = s3.listObjectsV2(r -> r.bucket(bucket).maxKeys(0));
        assertThat(none.contents()).isEmpty();
        assertThat(none.isTruncated()).isFalse();
    }

    @Test
    @DisplayName("a key granted one folder lists that folder and the way to it - nothing beside it; a key granted nothing sees no bucket")
    void onlyWhatTheKeyMayRead() {
        int b = jdbc.queryForObject("SELECT id FROM folder WHERE path LIKE ? AND key_path = 'a/b/'", Integer.class,
                bucketFolder.getPath() + "%");
        S3Client s3 = client(key(false, false, false, b + ":READ"));

        ListObjectsV2Response top = s3.listObjectsV2(r -> r.bucket(bucket).delimiter("/"));
        assertThat(top.commonPrefixes()).extracting(CommonPrefix::prefix).containsExactly("a/");
        assertThat(top.contents()).as("the bucket's own files are not the key's").isEmpty();
        ListObjectsV2Response a = s3.listObjectsV2(r -> r.bucket(bucket).delimiter("/").prefix("a/"));
        assertThat(a.commonPrefixes()).extracting(CommonPrefix::prefix).containsExactly("a/b/");
        assertThat(a.contents()).isEmpty();
        assertThat(s3.listObjectsV2(r -> r.bucket(bucket)).contents()).extracting(S3Object::key)
                .containsExactly("a/b/deep/y.pdf", "a/b/x.pdf");

        S3Client nobody = client(key(false, false, false, null));
        assertThat(code(() -> nobody.listObjectsV2(r -> r.bucket(bucket)))).isEqualTo(404);
    }

    @Test
    @DisplayName("DeleteObjects: each key deleted or refused on its own, a key naming nothing deleted, Quiet answering only failures")
    void deleteObjects() {
        S3Client s3 = client(key(false, true, true, bucketFolder.getId() + ":WRITE"));
        DeleteObjectsResponse answer = s3.deleteObjects(r -> r.bucket(bucket).delete(d -> d.objects(
                ObjectIdentifier.builder().key("c.pdf").build(),
                ObjectIdentifier.builder().key("a/b0.pdf").build(),
                ObjectIdentifier.builder().key("nothing/here.pdf").build(),
                ObjectIdentifier.builder().key("a/b/").build())));
        assertThat(answer.deleted()).extracting(d -> d.key()).containsExactly("c.pdf", "a/b0.pdf", "nothing/here.pdf");
        assertThat(answer.errors()).singleElement().satisfies(error -> {
            assertThat(error.key()).isEqualTo("a/b/");
            assertThat(error.code()).isEqualTo("FolderNotEmpty");
        });
        assertThat(keys(s3)).containsExactly("a/B-upper.pdf", "a/b/deep/y.pdf", "a/b/x.pdf", "a/with space.pdf", "a/زرد.pdf");

        DeleteObjectsResponse quiet = s3.deleteObjects(r -> r.bucket(bucket).delete(d -> d.quiet(true).objects(
                ObjectIdentifier.builder().key("a/with space.pdf").build())));
        assertThat(quiet.deleted()).isEmpty();
        assertThat(quiet.errors()).isEmpty();
        assertThat(keys(s3)).doesNotContain("a/with space.pdf");

        S3Client mayNot = client(key(false, false, false, bucketFolder.getId() + ":WRITE"));
        DeleteObjectsResponse refused = mayNot.deleteObjects(r -> r.bucket(bucket).delete(d -> d.objects(
                ObjectIdentifier.builder().key("a/زرد.pdf").build())));
        assertThat(refused.errors()).singleElement().satisfies(error -> assertThat(error.code()).isEqualTo("AccessDenied"));
        assertThat(keys(s3)).contains("a/زرد.pdf");
    }

    @Test
    @DisplayName("a key is listed once, as its newest revision of that format; another format is another key")
    void theNewestRevisionOfEachFormat() {
        S3Client s3 = client(key(false, true, false, bucketFolder.getId() + ":WRITE"));
        String first = s3.listObjectsV2(r -> r.bucket(bucket).prefix("c.")).contents().getFirst().eTag();
        s3.putObject(r -> r.bucket(bucket).key("c.pdf"), RequestBody.fromBytes(pdf(999, 77)));
        s3.putObject(r -> r.bucket(bucket).key("c.txt"), RequestBody.fromString("c, as text"));

        List<S3Object> c = s3.listObjectsV2(r -> r.bucket(bucket).prefix("c.")).contents();
        assertThat(c).extracting(S3Object::key).containsExactly("c.pdf", "c.txt");
        assertThat(c.getFirst().size()).isEqualTo(999);
        assertThat(c.getFirst().eTag()).isNotEqualTo(first);
        assertThat(c.get(1).size()).isEqualTo("c, as text".length());

        // The newer pdf deleted by its version: the older one is listed again, the text untouched.
        String newest = jdbc.queryForObject("""
                SELECT d.external_id FROM file_details d JOIN file_info f ON f.id = d.file_info_id
                WHERE f.folder_id = ? AND d.file_extension = 'pdf' ORDER BY d.version DESC LIMIT 1""",
                String.class, bucketFolder.getId());
        DeleteObjectsResponse byVersion = s3.deleteObjects(r -> r.bucket(bucket).delete(d -> d.objects(
                ObjectIdentifier.builder().key("c.pdf").versionId(newest).build())));
        assertThat(byVersion.deleted()).singleElement().satisfies(deleted -> {
            assertThat(deleted.key()).isEqualTo("c.pdf");
            assertThat(deleted.versionId()).isEqualTo(newest);
        });
        List<S3Object> after = s3.listObjectsV2(r -> r.bucket(bucket).prefix("c.")).contents();
        assertThat(after).extracting(S3Object::key).containsExactly("c.pdf", "c.txt");
        assertThat(after.getFirst().eTag()).isEqualTo(first);
    }

    @Test
    @DisplayName("a key that may delete files but holds only READ on the folder deletes nothing")
    void deleteNeedsWrite() {
        S3Client readOnly = client(key(false, true, true, bucketFolder.getId() + ":READ"));
        DeleteObjectsResponse refused = readOnly.deleteObjects(r -> r.bucket(bucket).delete(d -> d.objects(
                ObjectIdentifier.builder().key("c.pdf").build(),
                ObjectIdentifier.builder().key("a/b/x.pdf").build())));
        assertThat(refused.deleted()).isEmpty();
        assertThat(refused.errors()).extracting(e -> e.code()).containsExactly("AccessDenied", "AccessDenied");
        assertThat(keys(readOnly)).containsExactlyElementsOf(KEYS);

        S3Client elsewhere = client(key(false, true, true, null));
        assertThat(code(() -> elsewhere.deleteObjects(r -> r.bucket(bucket).delete(d -> d.objects(
                ObjectIdentifier.builder().key("c.pdf").build()))))).as("a bucket the key cannot see").isEqualTo(404);
        assertThat(keys(readOnly)).containsExactlyElementsOf(KEYS);
    }

    @Test
    @DisplayName("start-after starts after any key, listed or not; a token this did not write is refused")
    void startAfterAndTokens() {
        S3Client s3 = client(key(false, false, false, bucketFolder.getId() + ":READ"));
        assertThat(s3.listObjectsV2(r -> r.bucket(bucket).startAfter("a/b0.pdf")).contents()).extracting(S3Object::key)
                .containsExactly("a/with space.pdf", "a/زرد.pdf", "c.pdf");
        assertThat(s3.listObjectsV2(r -> r.bucket(bucket).startAfter("a/b")).contents()).extracting(S3Object::key)
                .startsWith("a/b/deep/y.pdf");
        assertThat(s3.listObjectsV2(r -> r.bucket(bucket).startAfter("z")).contents()).isEmpty();
        assertThat(code(() -> s3.listObjectsV2(r -> r.bucket(bucket).continuationToken("not*base64!")))).isEqualTo(400);
    }

    @Test
    @DisplayName("what is not served here says so: versions, uploads, a delimiter other than /")
    void notServed() {
        S3Client s3 = client(key(false, false, false, bucketFolder.getId() + ":READ"));
        assertThat(code(() -> s3.listObjectVersions(r -> r.bucket(bucket)))).isEqualTo(501);
        assertThat(code(() -> s3.listMultipartUploads(r -> r.bucket(bucket).delimiter("/")))).as("served since 2.14.0, not by level")
                .isEqualTo(501);
        assertThat(code(() -> s3.listObjectsV2(r -> r.bucket(bucket).delimiter("|")))).isEqualTo(501);
    }

    @Test
    @DisplayName("a sub-resource of an object is never taken for the object: tagging, acl, copy, parts - a 501, the file untouched")
    void subResourcesLeaveTheObjectAlone() {
        S3Client s3 = client(key(true, true, true, bucketFolder.getId() + ":WRITE"));
        s3.putObject(r -> r.bucket(bucket).key("notes.txt"), RequestBody.fromString("the notes"));
        List<String> before = revisionsIn(bucketFolder.getId());

        assertThat(code(() -> s3.deleteObjectTagging(r -> r.bucket(bucket).key("notes.txt")))).as("delete tagging").isEqualTo(501);
        assertThat(code(() -> s3.putObjectTagging(r -> r.bucket(bucket).key("notes.txt")
                .tagging(t -> t.tagSet(s -> s.key("k").value("v")))))).as("put tagging").isEqualTo(501);
        assertThat(code(() -> s3.getObjectTagging(r -> r.bucket(bucket).key("notes.txt")))).as("get tagging").isEqualTo(501);
        assertThat(code(() -> s3.getObjectAcl(r -> r.bucket(bucket).key("notes.txt")))).as("get acl").isEqualTo(501);
        assertThat(code(() -> s3.putObjectAcl(r -> r.bucket(bucket).key("notes.txt").acl("private")))).as("put acl").isEqualTo(501);
        assertThat(code(() -> s3.copyObject(r -> r.sourceBucket(bucket).sourceKey("notes.txt")
                .destinationBucket(bucket).destinationKey("copy.txt")))).as("copy").isEqualTo(501);
        assertThat(code(() -> s3.copyObject(r -> r.sourceBucket(bucket).sourceKey("c.pdf")
                .destinationBucket(bucket).destinationKey("notes.txt")))).as("copy over").isEqualTo(501);
        // A multipart upload's requests (2.14.0) name an upload: one that is not there is NoSuchUpload,
        // never a write or a delete of the object of that key.
        assertThat(code(() -> s3.uploadPart(r -> r.bucket(bucket).key("notes.txt").uploadId("u").partNumber(1),
                RequestBody.fromString("a part")))).as("upload part").isEqualTo(404);
        assertThat(code(() -> s3.abortMultipartUpload(r -> r.bucket(bucket).key("notes.txt").uploadId("u"))))
                .as("abort upload").isEqualTo(404);
        assertThat(code(() -> s3.completeMultipartUpload(r -> r.bucket(bucket).key("notes.txt").uploadId("u")
                .multipartUpload(m -> m.parts(p -> p.partNumber(1).eTag("e")))))).as("complete upload").isEqualTo(404);
        String begun = s3.createMultipartUpload(r -> r.bucket(bucket).key("notes.txt")).uploadId();
        s3.abortMultipartUpload(r -> r.bucket(bucket).key("notes.txt").uploadId(begun));
        assertThat(code(() -> s3.uploadPartCopy(r -> r.sourceBucket(bucket).sourceKey("c.pdf").destinationBucket(bucket)
                .destinationKey("notes.txt").uploadId("u").partNumber(1)))).as("upload part copy").isEqualTo(501);

        assertThat(revisionsIn(bucketFolder.getId())).as("every revision as it was").isEqualTo(before);
        assertThat(s3.getObjectAsBytes(r -> r.bucket(bucket).key("notes.txt")).asUtf8String()).isEqualTo("the notes");
        assertThat(keys(s3)).doesNotContain("copy.txt");
    }

    /** The revisions of the files directly in a folder: "title.extension:version:checksum", in order. */
    private List<String> revisionsIn(int folderId) {
        return jdbc.queryForList("""
                SELECT fi.file_name || '.' || d.file_extension || ':' || d.version || ':' || d.checksum_sha256
                FROM file_details d JOIN file_info fi ON fi.id = d.file_info_id
                WHERE fi.folder_id = ? ORDER BY d.id""", String.class, folderId);
    }

    // ---------------------------------------------------------------- helpers

    private List<String> keys(S3Client s3) {
        List<String> keys = new ArrayList<>();
        s3.listObjectsV2Paginator(r -> r.bucket(bucket)).forEach(page -> page.contents().forEach(o -> keys.add(o.key())));
        return keys;
    }

    record Key(String accessKeyId, String secret) {
    }

    private Key key(boolean createFolders, boolean deleteFiles, boolean deleteFolders, String grant) {
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("s3 list " + TestData.nextSequence());
        request.setKind(ApiKeyKind.S3);
        request.setMayCreateFolders(createFolders);
        request.setMayDeleteFiles(deleteFiles);
        request.setMayDeleteFolders(deleteFolders);
        request.setFolderGrants(grant == null ? List.of() : List.of(grant));
        ApiKeyCreatedDTO created = apiKeyService.create(request, ownerId);
        return new Key(created.keyId(), created.credential());
    }

    private S3Client client(Key key) {
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create("http://localhost:" + port + S3Controller.MOUNT))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key.accessKeyId(), key.secret())))
                .build();
        clients.add(client);
        return client;
    }

    private static int code(Runnable call) {
        try {
            call.run();
        } catch (S3Exception e) {
            return e.statusCode();
        }
        throw new AssertionError("the call succeeded");
    }

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
