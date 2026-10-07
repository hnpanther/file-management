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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The S3 listing over a bucket the ERP workflow would make (roadmap 9.10 with 12.4): {@value #WIDE}
 * folders, one per person, each with {@value #FILES_EACH} files of two revisions, and
 * {@value #TOP_FILES} files beside them - {@value #OBJECTS} objects, twice as many revisions. Every
 * listing here is paged through to its end, as {@code aws s3 sync} pages, and checked whole: every
 * key once, in S3's order. The time of the slowest page is printed and bounded generously - a shared
 * database in a container, not a benchmark.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class S3ListingScaleTest extends DatabaseSupport {

    static final int WIDE = 20_000;
    static final int FILES_EACH = 2;
    static final int TOP_FILES = 1_000;
    static final int OBJECTS = WIDE * FILES_EACH + TOP_FILES;
    /** What any one page may take here. */
    static final long BOUND_MS = 3_000;

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
        bucketFolder = FolderFixture.category(folderRepository, owner, "Scale" + n,
                tagGroupRepository.save(TestData.tagGroup(owner, "sg" + n)));
        bucket = S3ObjectService.bucketName(bucketFolder.getName());
        long started = System.nanoTime();
        generate();
        System.out.printf("[9.10] generated %,d folders and %,d objects in %,d ms%n", WIDE, OBJECTS,
                (System.nanoTime() - started) / 1_000_000);
    }

    /**
     * The generated rows removed: the database is shared by every test class, and forty thousand files
     * without bytes left in it would be work for whatever reads every revision after this (the
     * checksum backfill, the storage sweeper).
     */
    @AfterEach
    void removeTheBucket() {
        clients.forEach(S3Client::close);
        String below = bucketFolder.getPath() + "%";
        jdbc.update("""
                DELETE FROM file_details d USING file_info fi, folder fo
                WHERE d.file_info_id = fi.id AND fi.folder_id = fo.id AND fo.path LIKE ?""", below);
        jdbc.update("DELETE FROM file_info fi USING folder fo WHERE fi.folder_id = fo.id AND fo.path LIKE ?", below);
        jdbc.update("DELETE FROM folder WHERE path LIKE ? AND id <> ?", below, bucketFolder.getId());
    }

    @Test
    @DisplayName("the bucket's level: twenty thousand folders and a thousand files, every one once, in order, page by page")
    void theWideLevel() {
        S3Client s3 = client(bucketFolder.getId() + ":READ");
        List<String> entries = pageThrough(s3, r -> r.bucket(bucket).delimiter("/"), "level");
        assertThat(entries).hasSize(WIDE + TOP_FILES);
        assertThat(entries.getFirst()).isEqualTo("P-000001/");
        assertThat(entries.get(WIDE - 1)).isEqualTo("P-020000/");
        assertThat(entries.getLast()).isEqualTo("top-1000.pdf");
        assertSortedOnce(entries);

        ListObjectsV2Response narrowed = s3.listObjectsV2(r -> r.bucket(bucket).delimiter("/").prefix("p-01500"));
        assertThat(narrowed.commonPrefixes()).extracting(CommonPrefix::prefix)
                .as("a prefix without case, ten of twenty thousand").hasSize(10).first().isEqualTo("P-015000/");
    }

    @Test
    @DisplayName("the whole bucket: forty-one thousand objects at any depth, the newest revision of each, page by page")
    void theWholeBucket() {
        S3Client s3 = client(bucketFolder.getId() + ":READ");
        List<String> keys = pageThrough(s3, r -> r.bucket(bucket), "whole");
        assertThat(keys).hasSize(OBJECTS);
        assertSortedOnce(keys);
        assertThat(keys).startsWith("P-000001/doc-1.pdf", "P-000001/doc-2.pdf", "P-000002/doc-1.pdf");

        List<S3Object> one = s3.listObjectsV2(r -> r.bucket(bucket).prefix("P-015000/")).contents();
        assertThat(one).extracting(S3Object::key).containsExactly("P-015000/doc-1.pdf", "P-015000/doc-2.pdf");
        assertThat(one).extracting(S3Object::size).as("the second revision's").containsOnly(200L);
    }

    @Test
    @DisplayName("a key granted one folder of twenty thousand lists that folder alone, as fast")
    void oneFolderGranted() {
        int person = jdbc.queryForObject("SELECT id FROM folder WHERE parent_id = ? AND name = 'P-015000'", Integer.class,
                bucketFolder.getId());
        S3Client s3 = client(person + ":READ");
        assertThat(pageThrough(s3, r -> r.bucket(bucket), "whole, one folder granted"))
                .containsExactly("P-015000/doc-1.pdf", "P-015000/doc-2.pdf");
        assertThat(pageThrough(s3, r -> r.bucket(bucket).delimiter("/"), "level, one folder granted"))
                .containsExactly("P-015000/");
    }

    // ---------------------------------------------------------------- helpers

    /** Every page to the end, at S3's default of a thousand keys a page: the entries, joined in key order. */
    private List<String> pageThrough(S3Client s3, Consumer<ListObjectsV2Request.Builder> request, String what) {
        List<String> entries = new ArrayList<>();
        long slowest = 0;
        long started = System.nanoTime();
        int pages = 0;
        String token = null;
        do {
            String from = token;
            long pageStarted = System.nanoTime();
            ListObjectsV2Response page = s3.listObjectsV2(r -> {
                request.accept(r);
                r.continuationToken(from);
            });
            slowest = Math.max(slowest, (System.nanoTime() - pageStarted) / 1_000_000);
            List<String> merged = new ArrayList<>();
            page.commonPrefixes().forEach(p -> merged.add(p.prefix()));
            page.contents().forEach(o -> merged.add(o.key()));
            merged.sort(String::compareTo);
            entries.addAll(merged);
            pages++;
            token = page.isTruncated() ? page.nextContinuationToken() : null;
        } while (token != null);
        long total = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("[9.10] %s: %,d entries in %d pages, %,d ms, the slowest page %,d ms%n",
                what, entries.size(), pages, total, slowest);
        assertThat(slowest).as("the slowest page of " + what + ", ms").isLessThan(BOUND_MS);
        return entries;
    }

    /** In S3's order - ASCII keys here, where Java's and the bytes' agree - and each once. */
    private static void assertSortedOnce(List<String> keys) {
        for (int i = 1; i < keys.size(); i++) {
            assertThat(keys.get(i - 1).compareTo(keys.get(i))).as("%s before %s", keys.get(i - 1), keys.get(i)).isNegative();
        }
        assertThat(new HashSet<>(keys)).hasSameSizeAs(keys);
    }

    private S3Client client(String grant) {
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("s3 scale " + TestData.nextSequence());
        request.setKind(ApiKeyKind.S3);
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
        // One request untimed: the first of a context pays for the SDK, the filter chain and the JIT,
        // which is not what a page costs on a running server.
        client.listObjectsV2(r -> r.bucket(bucket).maxKeys(1));
        return client;
    }

    /**
     * The folders, the files and their revisions, each written once in a statement of its own - the
     * rows the application would have written, without their bytes, which a listing never reads.
     */
    private void generate() {
        jdbc.update("""
                INSERT INTO folder (id, parent_id, name, search_name, display_name, search_display_name, path, key_path,
                                    depth, kind, enabled, state, created_at)
                SELECT n.id, ?, n.name, n.name, 'شخص ' || n.g, 'شخص' || n.g, ? || n.id || '/', n.name || '/', ?, 'FOLDER',
                       1, 0, now()
                FROM (SELECT nextval(pg_get_serial_sequence('folder', 'id')) AS id, g, 'P-' || lpad(g::text, 6, '0') AS name
                      FROM generate_series(1, ?) g) n
                """, bucketFolder.getId(), bucketFolder.getPath(), bucketFolder.getDepth() + 1, WIDE);
        jdbc.update("""
                INSERT INTO file_info (external_id, file_name, search_name, code_name, file_name_description, last_version,
                                       folder_id, enabled, state, created_at, created_by)
                SELECT gen_random_uuid()::text, 'doc-' || s, 'doc-' || s, 'doc', 'doc', 2, f.id, 1, 0, now(), ?
                FROM folder f, generate_series(1, ?) s
                WHERE f.parent_id = ?
                """, ownerId, FILES_EACH, bucketFolder.getId());
        jdbc.update("""
                INSERT INTO file_info (external_id, file_name, search_name, code_name, file_name_description, last_version,
                                       folder_id, enabled, state, created_at, created_by)
                SELECT gen_random_uuid()::text, 'top-' || lpad(g::text, 4, '0'), 'top-' || lpad(g::text, 4, '0'), 'top', 'top',
                       2, ?, 1, 0, now(), ?
                FROM generate_series(1, ?) g
                """, bucketFolder.getId(), ownerId, TOP_FILES);
        jdbc.update("""
                INSERT INTO file_details (file_info_id, external_id, file_name, search_name, file_extension, content_type,
                                          version, version_name, description, search_description, storage_key, file_size,
                                          checksum_sha256, enabled, state, created_at, created_by)
                SELECT fi.id, gen_random_uuid()::text, fi.file_name, fi.file_name, 'pdf', 'application/pdf', v, 'v' || v,
                       '', '', 'files/00/' || fi.id || '/rev/v' || v || '/x.pdf', 100 * v,
                       encode(sha256(convert_to(fi.id || '/' || v, 'UTF8')), 'hex'), 1, 0, now(), ?
                FROM file_info fi JOIN folder fo ON fo.id = fi.folder_id, generate_series(1, 2) v
                WHERE fo.path LIKE ? || '%'
                """, ownerId, bucketFolder.getPath());
        jdbc.execute("ANALYZE folder");
        jdbc.execute("ANALYZE file_info");
        jdbc.execute("ANALYZE file_details");
    }
}
