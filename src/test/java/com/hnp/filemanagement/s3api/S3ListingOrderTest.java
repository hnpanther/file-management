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
import software.amazon.awssdk.services.s3.model.EncodingType;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The listing's order, proved against an oracle on a tree built to break it: names that sort
 * between a folder's key path and its files ({@code x/} before {@code x-.pdf}, {@code x/b/} before
 * {@code x/b-.pdf} and {@code x/b0/}), dots in titles, empty folders and folders of folders, a
 * character beyond U+FFFF whose UTF-16 sorts before one it follows in bytes - listed whole and by
 * level, a page of 1, 2, 3, 5 and 1000 keys, and after every key there is. Small pages make
 * {@link S3ListingRepository#subtree} read its folders a few at a time, so every bound it draws and
 * every ancestor it goes back to is crossed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3ListingOrderTest extends DatabaseSupport {

    /** Objects, put through the API. */
    private static final List<String> OBJECTS = List.of(
            "x.pdf", "x-.pdf", "x.y.pdf", "x0.pdf", "y.pdf",
            "x/a.pdf", "x/b.pdf", "x/b-.pdf", "x/b.c.pdf", "x/b/c.pdf", "x/b/c/d.pdf", "x/b0/e.pdf", "x/b0.pdf",
            "x-y/f.pdf", "x0/g.pdf",
            "deep/1/2/3/4/h.pdf", "deep/1/2/3/i.pdf", "deep/1/j.pdf",
            "ﭏ.pdf", "😀.pdf", "z/ﭏ/k.pdf", "z/😀/l.pdf", "z/ﭏ.pdf");

    /** Folders with nothing in them, made through the API. */
    private static final List<String> EMPTY_FOLDERS = List.of("e/", "x/b/empty/", "deep/1/2/void/", "x/b1/");

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
        bucketFolder = FolderFixture.category(folderRepository, owner, "Order" + n,
                tagGroupRepository.save(TestData.tagGroup(owner, "og" + n)));
        bucket = S3ObjectService.bucketName(bucketFolder.getName());
        S3Client writer = client(true, bucketFolder.getId() + ":WRITE");
        for (String key : OBJECTS) {
            writer.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromBytes(pdf(key)));
        }
        for (String folder : EMPTY_FOLDERS) {
            writer.putObject(r -> r.bucket(bucket).key(folder), RequestBody.empty());
        }
        assertThat(jdbc.queryForList("""
                SELECT f.id FROM folder f WHERE f.path LIKE ? AND f.key_path <> ''""", Integer.class, bucketFolder.getPath() + "%"))
                .as("every folder made").hasSize(18);
    }

    @AfterEach
    void closeClients() {
        clients.forEach(S3Client::close);
    }

    @Test
    @DisplayName("the whole bucket and every prefix of it: S3's order, at every page size, after every key")
    void wholeAtEveryPageSize() {
        S3Client s3 = client(false, bucketFolder.getId() + ":READ");
        List<String> expected = sorted(OBJECTS);
        assertThat(expected.indexOf("ﭏ.pdf")).as("U+FB4F before U+1F600, in bytes").isLessThan(expected.indexOf("😀.pdf"));

        for (int size : new int[]{1, 2, 3, 5, 1000}) {
            assertThat(pageThrough(s3, "", null, size, null)).as("a page of " + size).containsExactlyElementsOf(expected);
        }
        for (String after : startingPoints(expected)) {
            assertThat(pageThrough(s3, "", null, 2, after)).as("after " + after)
                    .containsExactlyElementsOf(expected.stream().filter(k -> S3Keys.compare(k, after) > 0).toList());
        }
        for (String prefix : List.of("x", "x/", "x/b", "x/b/", "x-", "deep/1/", "deep/1/2/3/4/", "z/", "Z/", "X/B", "nope", "x/b/empty/")) {
            for (int size : new int[]{1, 3}) {
                assertThat(pageThrough(s3, prefix, null, size, null)).as("under " + prefix + ", a page of " + size)
                        .containsExactlyElementsOf(expected.stream()
                                .filter(k -> k.toUpperCase().startsWith(prefix.toUpperCase())).toList());
            }
        }
    }

    @Test
    @DisplayName("by level, empty folders with the rest: S3's order, at every page size, after every entry")
    void levelAtEveryPageSize() {
        S3Client s3 = client(false, bucketFolder.getId() + ":READ");
        for (String prefix : List.of("", "x/", "x/b/", "deep/1/2/", "z/", "x/b", "x")) {
            List<String> expected = level(prefix);
            for (int size : new int[]{1, 2, 1000}) {
                assertThat(pageThrough(s3, prefix, "/", size, null)).as("level " + prefix + ", a page of " + size)
                        .containsExactlyElementsOf(expected);
            }
            for (String after : startingPoints(expected)) {
                assertThat(pageThrough(s3, prefix, "/", 1, after)).as("level " + prefix + " after " + after)
                        .containsExactlyElementsOf(expected.stream().filter(k -> S3Keys.compare(k, after) > 0).toList());
            }
        }
    }

    @Test
    @DisplayName("a key granted one folder deep in the tree: its subtree alone, whole and paged, and the way to it by level")
    void grantedOneFolder() {
        int b = jdbc.queryForObject("SELECT id FROM folder WHERE path LIKE ? AND key_path = 'x/b/'", Integer.class,
                bucketFolder.getPath() + "%");
        S3Client s3 = client(false, b + ":READ");
        List<String> expected = sorted(OBJECTS.stream().filter(k -> k.startsWith("x/b/")).toList());
        assertThat(expected).containsExactly("x/b/c.pdf", "x/b/c/d.pdf");
        for (int size : new int[]{1, 2, 1000}) {
            assertThat(pageThrough(s3, "", null, size, null)).containsExactlyElementsOf(expected);
            assertThat(pageThrough(s3, "x/", null, size, null)).containsExactlyElementsOf(expected);
        }
        assertThat(pageThrough(s3, "", "/", 1, null)).containsExactly("x/");
        assertThat(pageThrough(s3, "x/", "/", 1, null)).containsExactly("x/b/");
        assertThat(pageThrough(s3, "x/b/", "/", 1, null)).containsExactly("x/b/c.pdf", "x/b/c/", "x/b/empty/");
        assertThat(pageThrough(s3, "y", null, 1, null)).isEmpty();
    }

    @Test
    @DisplayName("a prefix XML cannot carry is answered url-encoded, or refused - never an answer the client cannot parse")
    void whatXmlCannotCarry() {
        S3Client s3 = client(false, bucketFolder.getId() + ":READ");
        assertThat(code(() -> s3.listObjectsV2(r -> r.bucket(bucket).startAfter("￿")))).isEqualTo(400);
        assertThat(code(() -> s3.listObjectsV2(r -> r.bucket(bucket).prefix("x￾")))).isEqualTo(400);
        assertThat(s3.listObjectsV2(r -> r.bucket(bucket).startAfter("￿").encodingType(EncodingType.URL)).contents())
                .extracting(o -> o.key()).as("U+1F600 is after U+FFFF in bytes").containsExactly("😀.pdf");
        assertThat(s3.listObjectsV2(r -> r.bucket(bucket).startAfter("z/ﭏ").encodingType(EncodingType.URL)).contents())
                .extracting(o -> o.key()).containsExactly("z/ﭏ.pdf", "z/ﭏ/k.pdf", "z/😀/l.pdf", "ﭏ.pdf", "😀.pdf");
    }

    private static int code(Runnable call) {
        try {
            call.run();
        } catch (software.amazon.awssdk.services.s3.model.S3Exception e) {
            return e.statusCode();
        }
        throw new AssertionError("the call succeeded");
    }

    // ---------------------------------------------------------------- the oracle

    private static List<String> sorted(List<String> keys) {
        List<String> copy = new ArrayList<>(keys);
        copy.sort(S3Keys::compare);
        return copy;
    }

    /** What {@code delimiter=/} lists under a prefix: the next name below it, a folder's with its slash. */
    private static List<String> level(String prefix) {
        TreeSet<String> entries = new TreeSet<>(S3Keys::compare);
        List<String> all = new ArrayList<>(OBJECTS);
        all.addAll(EMPTY_FOLDERS);
        for (String key : all) {
            if (!key.toUpperCase().startsWith(prefix.toUpperCase())) {
                continue;
            }
            int base = prefix.lastIndexOf('/') + 1;
            int slash = key.indexOf('/', base);
            if (slash >= 0) {
                entries.add(key.substring(0, slash + 1));
            } else {
                entries.add(key);
            }
        }
        // Every folder on the way to an object, too.
        for (String key : all) {
            for (int i = key.indexOf('/'); i >= 0; i = key.indexOf('/', i + 1)) {
                String folder = key.substring(0, i + 1);
                int base = prefix.lastIndexOf('/') + 1;
                if (folder.toUpperCase().startsWith(prefix.toUpperCase()) && folder.indexOf('/', base) == folder.length() - 1) {
                    entries.add(folder);
                }
            }
        }
        return new ArrayList<>(entries);
    }

    /** Every listed key, and points between and around them. */
    private static List<String> startingPoints(List<String> keys) {
        List<String> points = new ArrayList<>(keys);
        points.addAll(List.of("", "x", "x/", "x/b", "x/b/", "x/b/c", "x/b0", "deep/", "deep/1/2/3/", "z/", "~", "ﭐ", "😀", "😁"));
        return points;
    }

    // ---------------------------------------------------------------- helpers

    /** Every page to the end, by continuation token, each merged back into key order. */
    private List<String> pageThrough(S3Client s3, String prefix, String delimiter, int size, String startAfter) {
        List<String> entries = new ArrayList<>();
        String token = null;
        for (int guard = 0; guard < 500; guard++) {
            String from = token;
            ListObjectsV2Response page = s3.listObjectsV2(r -> r.bucket(bucket).prefix(prefix).delimiter(delimiter)
                    .maxKeys(size).startAfter(startAfter).continuationToken(from));
            List<String> merged = new ArrayList<>();
            page.commonPrefixes().forEach(p -> merged.add(p.prefix()));
            page.contents().forEach(o -> merged.add(o.key()));
            assertThat(merged.size()).as("a page is never larger than asked").isLessThanOrEqualTo(size);
            merged.sort(S3Keys::compare);
            entries.addAll(merged);
            if (!page.isTruncated()) {
                return entries;
            }
            assertThat(merged).as("a truncated page is never empty").isNotEmpty();
            token = page.nextContinuationToken();
        }
        throw new AssertionError("the listing did not end");
    }

    private S3Client client(boolean createFolders, String grant) {
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("s3 order " + TestData.nextSequence());
        request.setKind(ApiKeyKind.S3);
        request.setMayCreateFolders(createFolders);
        request.setFolderGrants(List.of(grant));
        ApiKeyCreatedDTO created = apiKeyService.create(request, ownerId);
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create("http://localhost:" + port + S3Controller.MOUNT))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(created.keyId(), created.credential())))
                .build();
        clients.add(client);
        return client;
    }

    private static byte[] pdf(String key) {
        return ("%PDF-1.4\n" + key).getBytes(StandardCharsets.UTF_8);
    }
}
