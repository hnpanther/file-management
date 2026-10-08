package com.hnp.filemanagement.s3api;

import com.hnp.filemanagement.file.web.UploadTempDirectory;
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
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multipart upload on the S3 surface (roadmap 9.10 step 5, 2.14.0), through the AWS SDK for Java v2
 * against the application - the calls {@code aws s3 cp} makes above 8 MB: begun, parts sent (in
 * parallel, out of order, one sent again), completed into one file that reads back byte for byte;
 * aborted; listed; refused where a {@code PUT} would be, before a part is sent; another key's upload
 * no upload at all; and the abandoned ones swept.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3MultipartTest extends DatabaseSupport {

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
    private UploadTempDirectory uploadTempDirectory;
    @Autowired
    private S3MultipartService multipartService;

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
        bucketFolder = FolderFixture.category(folderRepository, owner, "Multi" + n,
                tagGroupRepository.save(TestData.tagGroup(owner, "mp" + n)));
        bucket = S3ObjectService.bucketName(bucketFolder.getName());
        s3 = client(true, bucketFolder.getId() + ":WRITE");
    }

    @AfterEach
    void closeClients() {
        clients.forEach(S3Client::close);
    }

    @Test
    @DisplayName("three parts of 5 MB, 5 MB and 1 MB become one file that reads back byte for byte, its tags as a PUT's, nothing left behind")
    void aWholeUpload() throws Exception {
        byte[] whole = pdf(11 * 1024 * 1024 + 123, 1);
        String uploadId = s3.createMultipartUpload(r -> r.bucket(bucket).key("reports/annual.pdf")
                .metadata(Map.of("source", "erp"))).uploadId();
        assertThat(uploadId).matches("[A-Za-z0-9_-]{43}");
        assertThat(directory(uploadId)).as("made with the first part, not before").doesNotExist();

        List<CompletedPart> parts = new ArrayList<>();
        int[] cuts = {0, 5 * 1024 * 1024, 10 * 1024 * 1024, whole.length};
        for (int i = 0; i < 3; i++) {
            byte[] part = java.util.Arrays.copyOfRange(whole, cuts[i], cuts[i + 1]);
            int number = i + 1;
            String etag = s3.uploadPart(r -> r.bucket(bucket).key("reports/annual.pdf").uploadId(uploadId).partNumber(number),
                    RequestBody.fromBytes(part)).eTag();
            assertThat(etag).as("a part's tag is its MD5, as S3's").isEqualTo("\"" + md5(part) + "\"");
            assertThat(directory(uploadId)).isDirectory();
            parts.add(CompletedPart.builder().partNumber(number).eTag(etag).build());
        }
        CompleteMultipartUploadResponse done = s3.completeMultipartUpload(r -> r.bucket(bucket).key("reports/annual.pdf")
                .uploadId(uploadId).multipartUpload(m -> m.parts(parts)));

        byte[] read = s3.getObjectAsBytes(r -> r.bucket(bucket).key("reports/annual.pdf")).asByteArray();
        assertThat(read).isEqualTo(whole);
        var head = s3.headObject(r -> r.bucket(bucket).key("reports/annual.pdf"));
        assertThat(done.eTag()).isEqualTo(head.eTag());
        assertThat(done.versionId()).isEqualTo(head.versionId());
        assertThat(head.metadata()).containsEntry("source", "erp");
        assertThat(jdbc.queryForObject("SELECT checksum_sha256 FROM file_details WHERE external_id = ?", String.class, head.versionId()))
                .isEqualTo(sha256(whole));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_history WHERE file_details_external_id = ? AND event = 'FILE_UPLOADED'",
                Integer.class, head.versionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM s3_multipart_upload WHERE upload_id = ?", Integer.class, uploadId)).isZero();
        assertThat(directory(uploadId)).doesNotExist();
    }

    @Test
    @DisplayName("parts sent at once, out of order, one sent twice: the file is the last of each, in number order")
    void partsInParallel() throws Exception {
        String key = "parallel.pdf";
        String uploadId = s3.createMultipartUpload(r -> r.bucket(bucket).key(key)).uploadId();
        byte[][] parts = new byte[8][];
        for (int i = 0; i < 8; i++) {
            parts[i] = i == 0 ? pdf(300_000, 100) : letters(250_000 + i, 100 + i);
        }
        ExecutorService threads = Executors.newFixedThreadPool(8);
        try {
            // Part 3 first sent with other bytes, then again: the second is the part.
            byte[] stale = letters(250_003, 999);
            s3.uploadPart(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(3), RequestBody.fromBytes(stale));
            List<Future<String>> etags = new ArrayList<>();
            for (int i = 7; i >= 0; i--) {
                int number = i + 1;
                byte[] bytes = parts[i];
                etags.add(0, threads.submit(() -> s3.uploadPart(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(number),
                        RequestBody.fromBytes(bytes)).eTag()));
            }
            List<CompletedPart> completed = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                completed.add(CompletedPart.builder().partNumber(i + 1).eTag(etags.get(i).get()).build());
            }
            s3.completeMultipartUpload(r -> r.bucket(bucket).key(key).uploadId(uploadId).multipartUpload(m -> m.parts(completed)));
        } finally {
            threads.shutdownNow();
        }
        java.io.ByteArrayOutputStream expected = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) {
            expected.writeBytes(part);
        }
        assertThat(s3.getObjectAsBytes(r -> r.bucket(bucket).key(key)).asByteArray()).isEqualTo(expected.toByteArray());
    }

    @Test
    @DisplayName("a completion naming a part with another tag, a part not sent, or parts out of order is refused - and the upload is still there to complete")
    void refusedCompletions() {
        String key = "careful.pdf";
        String uploadId = s3.createMultipartUpload(r -> r.bucket(bucket).key(key)).uploadId();
        String one = s3.uploadPart(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(1),
                RequestBody.fromBytes(pdf(100_000, 7))).eTag();
        String two = s3.uploadPart(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(2),
                RequestBody.fromBytes(letters(100_000, 8))).eTag();

        assertThat(code(() -> complete(key, uploadId, part(1, one), part(2, "\"00000000000000000000000000000000\""))))
                .isEqualTo("InvalidPart");
        assertThat(code(() -> complete(key, uploadId, part(1, one), part(3, two)))).isEqualTo("InvalidPart");
        assertThat(code(() -> complete(key, uploadId, part(2, two), part(1, one)))).isEqualTo("InvalidPartOrder");
        assertThat(code(() -> complete(key, uploadId, part(1, one), part(1, one)))).isEqualTo("InvalidPartOrder");
        assertThat(code(() -> complete("other.pdf", uploadId, part(1, one)))).as("the upload is of its own key").isEqualTo("NoSuchUpload");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM file_info WHERE folder_id = ?", Integer.class, bucketFolder.getId())).isZero();

        complete(key, uploadId, part(1, one), part(2, two));
        assertThat(s3.headObject(r -> r.bucket(bucket).key(key)).contentLength()).isEqualTo(200_000);
        assertThat(code(() -> complete(key, uploadId, part(1, one), part(2, two)))).as("completed once").isEqualTo("NoSuchUpload");
    }

    @Test
    @DisplayName("only parts named are used; a part number past 10,000, or none, is refused")
    void onlyNamedParts() {
        String key = "named.pdf";
        String uploadId = s3.createMultipartUpload(r -> r.bucket(bucket).key(key)).uploadId();
        String one = s3.uploadPart(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(1),
                RequestBody.fromBytes(pdf(1000, 9))).eTag();
        s3.uploadPart(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(2), RequestBody.fromBytes(letters(1000, 10)));
        assertThat(code(() -> s3.uploadPart(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(10_001),
                RequestBody.fromBytes(letters(10, 1))))).isEqualTo("InvalidArgument");
        assertThat(code(() -> s3.uploadPart(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(0),
                RequestBody.fromBytes(letters(10, 1))))).isEqualTo("InvalidArgument");
        complete(key, uploadId, part(1, one));
        assertThat(s3.headObject(r -> r.bucket(bucket).key(key)).contentLength()).as("part 2, not named, is not in it").isEqualTo(1000);
        assertThat(directory(uploadId)).doesNotExist();
    }

    @Test
    @DisplayName("an abort removes the upload and its parts; a part or a completion after it is NoSuchUpload")
    void abort() {
        String key = "dropped.pdf";
        String uploadId = s3.createMultipartUpload(r -> r.bucket(bucket).key(key)).uploadId();
        String one = s3.uploadPart(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(1),
                RequestBody.fromBytes(pdf(5000, 11))).eTag();
        s3.abortMultipartUpload(r -> r.bucket(bucket).key(key).uploadId(uploadId));
        assertThat(directory(uploadId)).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM s3_multipart_part p JOIN s3_multipart_upload u ON u.id = p.upload_id"
                + " WHERE u.upload_id = ?", Integer.class, uploadId)).isZero();
        assertThat(code(() -> s3.uploadPart(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(2),
                RequestBody.fromBytes(letters(10, 1))))).isEqualTo("NoSuchUpload");
        assertThat(code(() -> complete(key, uploadId, part(1, one)))).isEqualTo("NoSuchUpload");
        assertThat(code(() -> s3.abortMultipartUpload(r -> r.bucket(bucket).key(key).uploadId(uploadId)))).isEqualTo("NoSuchUpload");
        assertThat(directory(uploadId)).as("a part refused leaves no file").doesNotExist();
    }

    @Test
    @DisplayName("another key's upload is no upload: not its parts, not its completion, not its abort, not in its listing - even on the same bucket")
    void anotherKeys() {
        String key = "mine.pdf";
        String uploadId = s3.createMultipartUpload(r -> r.bucket(bucket).key(key)).uploadId();
        String one = s3.uploadPart(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(1),
                RequestBody.fromBytes(pdf(5000, 12))).eTag();
        S3Client other = client(true, bucketFolder.getId() + ":WRITE");

        assertThat(code(() -> other.uploadPart(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(2),
                RequestBody.fromBytes(letters(10, 1))))).isEqualTo("NoSuchUpload");
        assertThat(code(() -> other.listParts(r -> r.bucket(bucket).key(key).uploadId(uploadId)))).isEqualTo("NoSuchUpload");
        assertThat(code(() -> other.abortMultipartUpload(r -> r.bucket(bucket).key(key).uploadId(uploadId)))).isEqualTo("NoSuchUpload");
        assertThat(code(() -> other.completeMultipartUpload(r -> r.bucket(bucket).key(key).uploadId(uploadId)
                .multipartUpload(m -> m.parts(part(1, one)))))).isEqualTo("NoSuchUpload");
        assertThat(other.listMultipartUploads(r -> r.bucket(bucket)).uploads()).isEmpty();
        assertThat(s3.listMultipartUploads(r -> r.bucket(bucket)).uploads()).extracting(MultipartUpload::uploadId).containsExactly(uploadId);

        // An id that is not one - a path, a guess - is no upload, and reaches no file.
        assertThat(code(() -> s3.uploadPart(r -> r.bucket(bucket).key(key).uploadId("../../../evil").partNumber(1),
                RequestBody.fromBytes(letters(10, 1))))).isEqualTo("NoSuchUpload");
        assertThat(code(() -> s3.abortMultipartUpload(r -> r.bucket(bucket).key(key).uploadId("x".repeat(43))))).isEqualTo("NoSuchUpload");
        complete(key, uploadId, part(1, one));
    }

    @Test
    @DisplayName("refused when it begins, as its PUT would be: no WRITE, no folder capability, a folder's key, an extension not allowed")
    void refusedAtTheStart() {
        S3Client readOnly = client(true, bucketFolder.getId() + ":READ");
        assertThat(status(() -> readOnly.createMultipartUpload(r -> r.bucket(bucket).key("x.pdf")))).isEqualTo(403);
        S3Client noFolders = client(false, bucketFolder.getId() + ":WRITE");
        assertThat(status(() -> noFolders.createMultipartUpload(r -> r.bucket(bucket).key("new-folder/x.pdf")))).isEqualTo(403);
        assertThat(status(() -> s3.createMultipartUpload(r -> r.bucket(bucket).key("a-folder/")))).isEqualTo(501);
        assertThat(status(() -> s3.createMultipartUpload(r -> r.bucket(bucket).key("no-extension")))).isEqualTo(400);
        assertThat(status(() -> s3.createMultipartUpload(r -> r.bucket(bucket).key("tool.exe")))).isEqualTo(400);
        S3Client nobody = client(true, null);
        assertThat(status(() -> nobody.createMultipartUpload(r -> r.bucket(bucket).key("x.pdf")))).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM s3_multipart_upload WHERE bucket = ?", Integer.class, bucket)).isZero();
    }

    @Test
    @DisplayName("the completed bytes are checked as any upload's: a .pdf that is not a PDF is refused, and the upload stays")
    void theBytesAreChecked() {
        String key = "fake.pdf";
        String uploadId = s3.createMultipartUpload(r -> r.bucket(bucket).key(key)).uploadId();
        String one = s3.uploadPart(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(1),
                RequestBody.fromBytes(letters(5000, 13))).eTag();
        assertThat(status(() -> complete(key, uploadId, part(1, one)))).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM s3_multipart_upload WHERE upload_id = ?", Integer.class, uploadId)).isOne();
        s3.abortMultipartUpload(r -> r.bucket(bucket).key(key).uploadId(uploadId));
    }

    @Test
    @DisplayName("If-None-Match: * on the completion refuses a title already there - 412 - and the upload stays")
    void ifNoneMatch() {
        s3.putObject(r -> r.bucket(bucket).key("taken.pdf"), RequestBody.fromBytes(pdf(1000, 14)));
        String uploadId = s3.createMultipartUpload(r -> r.bucket(bucket).key("taken.pdf")).uploadId();
        String one = s3.uploadPart(r -> r.bucket(bucket).key("taken.pdf").uploadId(uploadId).partNumber(1),
                RequestBody.fromBytes(pdf(2000, 15))).eTag();
        assertThat(status(() -> s3.completeMultipartUpload(r -> r.bucket(bucket).key("taken.pdf").uploadId(uploadId)
                .ifNoneMatch("*").multipartUpload(m -> m.parts(part(1, one)))))).isEqualTo(412);
        complete("taken.pdf", uploadId, part(1, one));
        assertThat(s3.headObject(r -> r.bucket(bucket).key("taken.pdf")).contentLength()).as("a new version").isEqualTo(2000);
    }

    @Test
    @DisplayName("a part whose Content-MD5 is not its bytes' is BadDigest, and is not kept")
    void contentMd5() throws Exception {
        String key = "digest.pdf";
        String uploadId = s3.createMultipartUpload(r -> r.bucket(bucket).key(key)).uploadId();
        byte[] bytes = pdf(4000, 16);
        assertThat(code(() -> s3.uploadPart(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(1)
                .overrideConfiguration(o -> o.putHeader("Content-MD5", "1B2M2Y8AsgTpgAmY7PhCfg==")), RequestBody.fromBytes(bytes))))
                .isEqualTo("BadDigest");
        assertThat(s3.listParts(r -> r.bucket(bucket).key(key).uploadId(uploadId)).parts()).isEmpty();
        String right = java.util.Base64.getEncoder().encodeToString(HexFormat.of().parseHex(md5(bytes)));
        s3.uploadPart(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(1)
                .overrideConfiguration(o -> o.putHeader("Content-MD5", right)), RequestBody.fromBytes(bytes));
        assertThat(s3.listParts(r -> r.bucket(bucket).key(key).uploadId(uploadId)).parts()).hasSize(1);
        s3.abortMultipartUpload(r -> r.bucket(bucket).key(key).uploadId(uploadId));
    }

    @Test
    @DisplayName("ListParts and ListMultipartUploads page as S3's do")
    void listings() {
        String key = "listed.pdf";
        String uploadId = s3.createMultipartUpload(r -> r.bucket(bucket).key(key)).uploadId();
        for (int i = 1; i <= 5; i++) {
            int number = i;
            s3.uploadPart(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(number),
                    RequestBody.fromBytes(letters(100 + number, number)));
        }
        List<Integer> seen = new ArrayList<>();
        Integer marker = null;
        for (int page = 0; page < 5; page++) {
            Integer from = marker;
            ListPartsResponse parts = s3.listParts(r -> r.bucket(bucket).key(key).uploadId(uploadId).maxParts(2).partNumberMarker(from));
            parts.parts().forEach(p -> seen.add(p.partNumber()));
            assertThat(parts.parts()).allSatisfy(p -> assertThat(p.size()).isEqualTo(100L + p.partNumber()));
            if (!parts.isTruncated()) {
                break;
            }
            marker = parts.nextPartNumberMarker();
        }
        assertThat(seen).containsExactly(1, 2, 3, 4, 5);

        String second = s3.createMultipartUpload(r -> r.bucket(bucket).key("a/second.pdf")).uploadId();
        String third = s3.createMultipartUpload(r -> r.bucket(bucket).key(key)).uploadId();
        ListMultipartUploadsResponse first = s3.listMultipartUploads(r -> r.bucket(bucket).maxUploads(2));
        assertThat(first.uploads()).extracting(MultipartUpload::key).containsExactly("a/second.pdf", key);
        assertThat(first.isTruncated()).isTrue();
        ListMultipartUploadsResponse rest = s3.listMultipartUploads(r -> r.bucket(bucket).maxUploads(2)
                .keyMarker(first.nextKeyMarker()).uploadIdMarker(first.nextUploadIdMarker()));
        assertThat(rest.uploads()).extracting(MultipartUpload::uploadId).containsExactly(third);
        assertThat(s3.listMultipartUploads(r -> r.bucket(bucket).prefix("a/")).uploads()).extracting(MultipartUpload::uploadId)
                .containsExactly(second);
        for (String each : List.of(uploadId, second, third)) {
            String k = each.equals(second) ? "a/second.pdf" : key;
            s3.abortMultipartUpload(r -> r.bucket(bucket).key(k).uploadId(each));
        }
    }

    @Test
    @DisplayName("two completions of one upload at once: one file, one version - the other is NoSuchUpload")
    void completedOnce() throws Exception {
        String key = "once.pdf";
        String uploadId = s3.createMultipartUpload(r -> r.bucket(bucket).key(key)).uploadId();
        String one = s3.uploadPart(r -> r.bucket(bucket).key(key).uploadId(uploadId).partNumber(1),
                RequestBody.fromBytes(pdf(2_000_000, 17))).eTag();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Callable<String> completion = () -> {
                try {
                    complete(key, uploadId, part(1, one));
                    return "done";
                } catch (S3Exception e) {
                    return e.awsErrorDetails().errorCode();
                }
            };
            Future<String> a = threads.submit(completion);
            Future<String> b = threads.submit(completion);
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder("done", "NoSuchUpload");
        } finally {
            threads.shutdownNow();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM file_details d JOIN file_info f ON f.id = d.file_info_id
                WHERE f.folder_id = ? AND f.file_name = 'once'""", Integer.class, bucketFolder.getId())).isOne();
    }

    @Test
    @DisplayName("the sweep removes an upload begun before the expiry, its parts, and a directory no upload names - and nothing newer")
    void theSweep() throws Exception {
        String old = s3.createMultipartUpload(r -> r.bucket(bucket).key("old.pdf")).uploadId();
        s3.uploadPart(r -> r.bucket(bucket).key("old.pdf").uploadId(old).partNumber(1), RequestBody.fromBytes(pdf(1000, 18)));
        String fresh = s3.createMultipartUpload(r -> r.bucket(bucket).key("fresh.pdf")).uploadId();
        s3.uploadPart(r -> r.bucket(bucket).key("fresh.pdf").uploadId(fresh).partNumber(1), RequestBody.fromBytes(pdf(1000, 19)));
        jdbc.update("UPDATE s3_multipart_upload SET created_at = now() - interval '25 hours' WHERE upload_id = ?", old);
        Path orphan = directory("o".repeat(43));
        Files.createDirectories(orphan);
        Files.writeString(orphan.resolve("1-left.part"), "left by a crash");
        Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minus(2, ChronoUnit.DAYS)));

        assertThat(multipartService.sweep()).isGreaterThanOrEqualTo(1);
        assertThat(directory(old)).doesNotExist();
        assertThat(orphan).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM s3_multipart_upload WHERE upload_id = ?", Integer.class, old)).isZero();
        assertThat(code(() -> s3.listParts(r -> r.bucket(bucket).key("old.pdf").uploadId(old)))).isEqualTo("NoSuchUpload");
        assertThat(directory(fresh)).isDirectory();
        assertThat(s3.listParts(r -> r.bucket(bucket).key("fresh.pdf").uploadId(fresh)).parts()).hasSize(1);
        s3.abortMultipartUpload(r -> r.bucket(bucket).key("fresh.pdf").uploadId(fresh));
    }

    // ---------------------------------------------------------------- helpers

    private CompleteMultipartUploadResponse complete(String key, String uploadId, CompletedPart... parts) {
        return s3.completeMultipartUpload(r -> r.bucket(bucket).key(key).uploadId(uploadId).multipartUpload(m -> m.parts(parts)));
    }

    private static CompletedPart part(int number, String etag) {
        return CompletedPart.builder().partNumber(number).eTag(etag).build();
    }

    private Path directory(String uploadId) {
        return uploadTempDirectory.path().resolve(S3MultipartService.DIRECTORY).resolve(uploadId);
    }

    private static String code(Runnable call) {
        try {
            call.run();
        } catch (S3Exception e) {
            return e.awsErrorDetails().errorCode();
        }
        throw new AssertionError("the call succeeded");
    }

    private static int status(Runnable call) {
        try {
            call.run();
        } catch (S3Exception e) {
            return e.statusCode();
        }
        throw new AssertionError("the call succeeded");
    }

    private S3Client client(boolean createFolders, String grant) {
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("s3 multipart " + TestData.nextSequence());
        request.setKind(ApiKeyKind.S3);
        request.setMayCreateFolders(createFolders);
        request.setFolderGrants(grant == null ? List.of() : List.of(grant));
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

    /** A PDF's header, then letters. */
    static byte[] pdf(int size, long seed) {
        byte[] bytes = letters(size, seed);
        byte[] header = "%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, bytes, 0, header.length);
        return bytes;
    }

    static byte[] letters(int size, long seed) {
        byte[] bytes = new byte[size];
        Random random = new Random(seed);
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) ('a' + random.nextInt(26));
        }
        return bytes;
    }

    private static String md5(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes));
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
