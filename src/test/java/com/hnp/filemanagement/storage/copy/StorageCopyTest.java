package com.hnp.filemanagement.storage.copy;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.domain.FileUploadDTO;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.shared.config.FileManagementProperties;
import com.hnp.filemanagement.storage.CopyableStore;
import com.hnp.filemanagement.storage.FilesystemBlobStore;
import com.hnp.filemanagement.storage.S3BlobStore;
import com.hnp.filemanagement.storage.StorageKey;
import com.hnp.filemanagement.storage.copy.StorageCopy.Outcome;
import com.hnp.filemanagement.storage.copy.StorageCopySettings.Direction;
import com.hnp.filemanagement.storage.copy.StorageCopySettings.Mode;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.support.TestObjectStores;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import software.amazon.awssdk.core.sync.RequestBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The storage copy of roadmap 4.4 on real rows, a real storage root and a real SeaweedFS: the
 * application uploads through {@code FileService}, the copy moves the bytes, and every scenario
 * of the cut-over and the rollback is played - the first pass, the second, a damaged or missing
 * source, a wrong object in the target, rows deleted meanwhile, the prune and its guards, and the
 * way back.
 *
 * <p>Not {@code @Transactional}: the copy reads the rows on threads of its own, so they are
 * committed. The rows of other test classes are in the same database with their bytes long
 * cleared, so each run starts after the largest id there was before this test's uploads
 * ({@code after-id}) - and each test has a prefix of its own in the bucket.
 */
@SpringBootTest
@TestPropertySource(properties = "filemanagement.folder-access.enabled=false")
class StorageCopyTest extends DatabaseSupport {

    private static final int PART = 5 * 1024 * 1024;

    @Autowired
    private FileService fileService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Value("${filemanagement.base-dir}")
    private String baseDir;

    @TempDir
    Path rollbackRoot;
    @TempDir
    Path reports;

    private int ownerId;
    private int folderId;
    private int afterId;
    private String prefix;
    private FilesystemBlobStore filesystem;
    private S3BlobStore bucket;

    @BeforeEach
    void setUp() {
        User owner = userRepository.save(TestData.user());
        ownerId = owner.getId();
        folderId = FolderFixture.chain(folderRepository, tagGroupRepository, owner).tagId();
        Integer largest = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM file_details", Integer.class);
        afterId = largest == null ? 0 : largest;
        prefix = "storage-copy-" + UUID.randomUUID();
        filesystem = new FilesystemBlobStore(FileManagementProperties.defaults(baseDir));
        bucket = new S3BlobStore(TestObjectStores.client(), TestObjectStores.BUCKET, prefix, PART);
    }

    @Test
    @DisplayName("the first pass copies every revision and verifies it; verify then finds each; a second pass copies nothing")
    void theFirstPassAndTheSecond() throws IOException {
        List<FileDetailsDTO> stored = uploadMix();

        StorageCopy.Report first = run(Direction.TO_S3, Mode.COPY);
        assertThat(first.succeeded()).isTrue();
        assertThat(first.count(Outcome.COPIED)).isEqualTo(stored.size());
        assertThat(first.bytesCopied()).isEqualTo(stored.stream().mapToLong(FileDetailsDTO::getFileSize).sum());
        for (FileDetailsDTO revision : stored) {
            assertThat(read(bucket, revision)).isEqualTo(read(filesystem, revision));
        }

        StorageCopy.Report verify = run(Direction.TO_S3, Mode.VERIFY);
        assertThat(verify.succeeded()).isTrue();
        assertThat(verify.count(Outcome.VERIFIED)).isEqualTo(stored.size());
        assertThat(verify.orphans()).as("counted only from the first row").isEqualTo(-1);

        StorageCopy.Report again = run(Direction.TO_S3, Mode.COPY);
        assertThat(again.succeeded()).isTrue();
        assertThat(again.count(Outcome.COPIED)).isZero();
        assertThat(again.count(Outcome.VERIFIED)).isEqualTo(stored.size());
        assertThat(again.bytesCopied()).isZero();

        FileDetailsDTO added = upload("added-later.txt", 300);
        StorageCopy.Report second = run(Direction.TO_S3, Mode.COPY);
        assertThat(second.count(Outcome.COPIED)).isEqualTo(1);
        assertThat(second.count(Outcome.VERIFIED)).isEqualTo(stored.size());
        assertThat(read(bucket, added)).isEqualTo(read(filesystem, added));

        StorageCopy.Report deep = run(settings(Direction.TO_S3, Mode.VERIFY, 4, true, false, 100));
        assertThat(deep.succeeded()).isTrue();
        assertThat(deep.count(Outcome.VERIFIED)).isEqualTo(stored.size() + 1);
        assertThat(deep.reportFile()).exists();
    }

    @Test
    @DisplayName("a source whose bytes are not the row's is reported and not copied; a missing one is reported")
    void aDamagedOrMissingSource() throws IOException {
        FileDetailsDTO damaged = upload("damaged.pdf", 2 * PART + 77);
        FileDetailsDTO missing = upload("missing.txt", 500);
        FileDetailsDTO fine = upload("fine.txt", 500);
        Path damagedFile = Path.of(baseDir).resolve(storageKeyOf(damaged));
        byte[] bytes = Files.readAllBytes(damagedFile);
        bytes[bytes.length / 2] ^= 1;
        Files.write(damagedFile, bytes);
        Files.delete(Path.of(baseDir).resolve(storageKeyOf(missing)));

        StorageCopy.Report report = run(Direction.TO_S3, Mode.COPY);

        assertThat(report.succeeded()).isFalse();
        assertThat(report.count(Outcome.SOURCE_CORRUPT)).isEqualTo(1);
        assertThat(report.count(Outcome.MISSING_AT_SOURCE)).isEqualTo(1);
        assertThat(report.count(Outcome.COPIED)).isEqualTo(1);
        assertThat(bucket.exists(StorageKey.of(storageKeyOf(damaged)))).as("nothing written for a damaged source").isFalse();
        assertThat(bucket.exists(StorageKey.of(storageKeyOf(fine)))).isTrue();
        assertThat(report.lines()).anySatisfy(line -> {
            assertThat(line.what()).isEqualTo("SOURCE_CORRUPT");
            assertThat(line.fileDetailsId()).isEqualTo(damaged.getId());
        });
        assertThat(TestObjectStores.client().listMultipartUploads(request -> request.bucket(TestObjectStores.BUCKET)
                .prefix(prefix + "/")).uploads()).as("the damaged file's parts aborted").isEmpty();
        assertThat(Files.readString(report.reportFile())).contains("SOURCE_CORRUPT").contains("MISSING_AT_SOURCE");
    }

    @Test
    @DisplayName("something else at a key in the target is reported and left as it is, small or in parts")
    void aWrongObjectInTheTarget() throws IOException {
        FileDetailsDTO small = upload("small.txt", 400);
        FileDetailsDTO large = upload("large.pdf", 2 * PART + 5);
        // Not the row's bytes: one written by hand, one by the application's own put (no record of a copy).
        TestObjectStores.client().putObject(request -> request.bucket(TestObjectStores.BUCKET)
                .key(prefix + "/" + storageKeyOf(small)), RequestBody.fromString("something else"));
        bucket.put(StorageKey.of(storageKeyOf(large)), new java.io.ByteArrayInputStream(random(2 * PART + 5, 99)));

        StorageCopy.Report report = run(Direction.TO_S3, Mode.COPY);

        assertThat(report.count(Outcome.MISMATCH_AT_TARGET)).isEqualTo(2);
        assertThat(report.succeeded()).isFalse();
        try (InputStream in = bucket.open(StorageKey.of(storageKeyOf(small))).getInputStream()) {
            assertThat(new String(in.readAllBytes())).isEqualTo("something else");
        }
    }

    @Test
    @DisplayName("a row without a recorded checksum is copied against the source's own bytes, with a warning")
    void aRowWithoutAChecksum() throws IOException {
        FileDetailsDTO revision = upload("unchecked.txt", 600);
        String checksum = jdbc.queryForObject("SELECT checksum_sha256 FROM file_details WHERE id = ?", String.class,
                revision.getId());
        jdbc.update("UPDATE file_details SET checksum_sha256 = NULL WHERE id = ?", revision.getId());
        try {
            StorageCopy.Report report = run(Direction.TO_S3, Mode.COPY);
            assertThat(report.succeeded()).isTrue();
            assertThat(report.count(Outcome.COPIED)).isEqualTo(1);
            assertThat(report.warnings()).isEqualTo(1);
            assertThat(report.lines()).singleElement().satisfies(line ->
                    assertThat(line.what()).isEqualTo("NO_RECORDED_CHECKSUM"));
            assertThat(read(bucket, revision)).isEqualTo(read(filesystem, revision));
        } finally {
            jdbc.update("UPDATE file_details SET checksum_sha256 = ? WHERE id = ?", checksum, revision.getId());
        }
    }

    @Test
    @DisplayName("prune deletes what no row names - a deleted file's objects - only with confirm, never what a row or a write in flight names, never anything recent")
    void prune() {
        FileDetailsDTO kept = upload("kept.txt", 100);
        FileDetailsDTO deleted = upload("deleted.txt", 100);
        assertThat(run(Direction.TO_S3, Mode.COPY).count(Outcome.COPIED)).isEqualTo(2);
        String deletedKey = storageKeyOf(deleted);
        fileService.deleteCompleteFileById(deleted.getFileInfoId(), ownerId);
        String inFlight = "files/s999/999999/inflight-" + UUID.randomUUID() + "/v1/inflight.txt";
        bucket.put(StorageKey.of(inFlight), new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}));
        jdbc.update("INSERT INTO file_storage_write (storage_key, created_at) VALUES (?, now())", inFlight);
        try {
            // Just written: kept whatever the rows say.
            StorageCopy.Report now = run(settings(Direction.TO_S3, Mode.PRUNE, 1, false, true, 100));
            assertThat(now.count(Outcome.KEPT_RECENT)).isEqualTo(1);
            assertThat(now.count(Outcome.DELETED)).isZero();

            Clock later = Clock.offset(Clock.systemUTC(), Duration.ofHours(2));
            StorageCopy.Report dryRun = run(settings(Direction.TO_S3, Mode.PRUNE, 1, false, false, 100), later);
            assertThat(dryRun.count(Outcome.WOULD_DELETE)).isEqualTo(1);
            assertThat(dryRun.lines()).singleElement().satisfies(line ->
                    assertThat(line.storageKey()).isEqualTo(deletedKey));
            assertThat(bucket.exists(StorageKey.of(deletedKey))).as("nothing deleted without confirm").isTrue();

            StorageCopy.Report confirmed = run(settings(Direction.TO_S3, Mode.PRUNE, 1, false, true, 100), later);
            assertThat(confirmed.count(Outcome.DELETED)).isEqualTo(1);
            assertThat(confirmed.succeeded()).isTrue();
            assertThat(bucket.exists(StorageKey.of(deletedKey))).isFalse();
            assertThat(bucket.exists(StorageKey.of(storageKeyOf(kept)))).isTrue();
            assertThat(bucket.exists(StorageKey.of(inFlight))).as("named by a write in flight").isTrue();

            assertThat(run(settings(Direction.TO_S3, Mode.PRUNE, 1, false, true, 100), later).count(Outcome.DELETED)).isZero();
        } finally {
            jdbc.update("DELETE FROM file_storage_write WHERE storage_key = ?", inFlight);
        }
    }

    @Test
    @DisplayName("prune refuses to delete more than max-prune, and deletes nothing then")
    void pruneHasALimit() {
        for (int i = 0; i < 3; i++) {
            bucket.put(StorageKey.of("files/s999/99999" + i + "/stray/v1/stray.txt"),
                    new java.io.ByteArrayInputStream(new byte[]{(byte) i}));
        }
        Clock later = Clock.offset(Clock.systemUTC(), Duration.ofHours(2));

        assertThatThrownBy(() -> run(settings(Direction.TO_S3, Mode.PRUNE, 1, false, true, 2), later))
                .isInstanceOf(StorageCopy.Refused.class)
                .hasMessageContaining("3 objects would be deleted");
        assertThat(TestObjectStores.keysUnder(prefix)).hasSize(3);
    }

    @Test
    @DisplayName("the way back: the bucket copied to an empty directory whole, verified by reading, a damaged file found")
    void theWayBack() throws IOException {
        List<FileDetailsDTO> stored = uploadMix();
        assertThat(run(Direction.TO_S3, Mode.COPY).succeeded()).isTrue();
        FilesystemBlobStore emptyRoot = new FilesystemBlobStore(FileManagementProperties.defaults(rollbackRoot.toString()));

        StorageCopy.Report back = new StorageCopy(jdbc, bucket, emptyRoot,
                settings(Direction.TO_FILESYSTEM, Mode.COPY, 4, false, false, 100), Clock.systemUTC()).run();
        assertThat(back.succeeded()).isTrue();
        assertThat(back.count(Outcome.COPIED)).isEqualTo(stored.size());
        for (FileDetailsDTO revision : stored) {
            assertThat(read(emptyRoot, revision)).isEqualTo(read(filesystem, revision));
        }

        StorageCopy.Report again = new StorageCopy(jdbc, bucket, emptyRoot,
                settings(Direction.TO_FILESYSTEM, Mode.COPY, 4, false, false, 100), Clock.systemUTC()).run();
        assertThat(again.count(Outcome.SAME_SIZE)).isEqualTo(stored.size());
        assertThat(again.count(Outcome.COPIED)).isZero();

        Path one = rollbackRoot.resolve(storageKeyOf(stored.getFirst()));
        byte[] bytes = Files.readAllBytes(one);
        bytes[0] ^= 1;
        Files.write(one, bytes);
        StorageCopy.Report verify = new StorageCopy(jdbc, bucket, emptyRoot,
                settings(Direction.TO_FILESYSTEM, Mode.VERIFY, 4, false, false, 100), Clock.systemUTC()).run();
        assertThat(verify.count(Outcome.MISMATCH_AT_TARGET)).isEqualTo(1);
        assertThat(verify.count(Outcome.VERIFIED)).isEqualTo(stored.size() - 1);
        assertThat(verify.succeeded()).isFalse();
    }

    @Test
    @DisplayName("stopped half-way, a run says so and fails; the next carries on from what is verified")
    void aStopAndAResume() {
        List<FileDetailsDTO> stored = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            stored.add(upload("many-" + i + ".txt", 50 + i));
        }
        StorageCopy[] first = new StorageCopy[1];
        // Asked to stop after its fifth copy - as Ctrl+C would.
        HookedStore stopsAfterFive = new HookedStore(bucket, () -> { }, 5, () -> first[0].stop());
        first[0] = new StorageCopy(jdbc, filesystem, stopsAfterFive,
                settings(Direction.TO_S3, Mode.COPY, 1, false, false, 100), Clock.systemUTC());
        StorageCopy.Report stoppedRun = first[0].run();
        assertThat(stoppedRun.stopped()).isTrue();
        assertThat(stoppedRun.count(Outcome.COPIED)).isEqualTo(5);
        assertThat(stoppedRun.succeeded()).isFalse();
        assertThat(stoppedRun.count(Outcome.COPIED)).isLessThan(stored.size());

        StorageCopy.Report resumed = run(Direction.TO_S3, Mode.COPY);
        assertThat(resumed.succeeded()).isTrue();
        assertThat(resumed.count(Outcome.COPIED) + resumed.count(Outcome.VERIFIED)).isEqualTo(stored.size());
        assertThat(resumed.count(Outcome.COPIED)).isEqualTo(stored.size() - stoppedRun.count(Outcome.COPIED));
    }

    @Test
    @DisplayName("a file deleted after the rows were read is gone, not a problem")
    void aRowDeletedMeanwhile() {
        FileDetailsDTO revision = upload("soon-gone.txt", 100);
        // Deleted by the service between the copy reading the row and looking at the stores.
        HookedStore deletesFirst = new HookedStore(bucket,
                () -> fileService.deleteCompleteFileById(revision.getFileInfoId(), ownerId), Integer.MAX_VALUE, () -> { });
        StorageCopy copy = new StorageCopy(jdbc, filesystem, deletesFirst,
                settings(Direction.TO_S3, Mode.COPY, 1, false, false, 100), Clock.systemUTC());

        StorageCopy.Report report = copy.run();

        assertThat(report.count(Outcome.GONE)).isEqualTo(1);
        assertThat(report.succeeded()).isTrue();
    }

    // ---------------------------------------------------------------- helpers

    /** A mix: one byte, a Persian name with spaces, a file in three parts, a second version, a second format. */
    private List<FileDetailsDTO> uploadMix() {
        List<FileDetailsDTO> stored = new ArrayList<>();
        stored.add(upload("one-byte.txt", 1));
        stored.add(upload("گزارش ماهانه ۱۴۰۵.pdf", 3000));
        FileDetailsDTO large = upload("large.pdf", 2 * PART + 12_345);
        stored.add(large);
        stored.add(revision(large.getFileInfoId(), "large.pdf", 2, "version", null, 7000));
        stored.add(revision(large.getFileInfoId(), "large.txt", 2, "format", stored.getLast().getId(), 800));
        return stored;
    }

    private FileDetailsDTO upload(String fileName, int size) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("copy " + fileName);
        request.setFileNameDescription(fileName.substring(0, fileName.lastIndexOf('.')) + "-" + UUID.randomUUID());
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "application/octet-stream",
                content(fileName, size, fileName.hashCode())));
        return fileService.createNewFile(request, ownerId, FileService.PUBLIC);
    }

    private FileDetailsDTO revision(int fileInfoId, String fileName, int version, String type, Integer sampleId, int size) {
        FileUploadDTO request = new FileUploadDTO();
        request.setFileId(fileInfoId);
        request.setFileName(fileName.substring(0, fileName.lastIndexOf('.')));
        request.setFileNameWithoutExtension(fileName.substring(0, fileName.lastIndexOf('.')));
        request.setVersion(version);
        request.setType(type);
        request.setFileDetailsId(sampleId);
        request.setFileDetailsDescription(type + " " + version);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "application/octet-stream",
                content(fileName, size, fileName.hashCode() + version)));
        fileService.createNewFileDetails(request, ownerId);
        Integer id = jdbc.queryForObject("SELECT MAX(id) FROM file_details WHERE file_info_id = ?", Integer.class, fileInfoId);
        FileDetailsDTO dto = new FileDetailsDTO();
        dto.setId(id);
        dto.setFileInfoId(fileInfoId);
        dto.setFileSize((long) size);
        return dto;
    }

    private StorageCopy.Report run(Direction direction, Mode mode) {
        return run(settings(direction, mode, 4, false, false, 100));
    }

    private StorageCopy.Report run(StorageCopySettings settings) {
        return run(settings, Clock.systemUTC());
    }

    private StorageCopy.Report run(StorageCopySettings settings, Clock clock) {
        boolean toS3 = settings.direction() == Direction.TO_S3;
        return new StorageCopy(jdbc, toS3 ? filesystem : bucket, toS3 ? bucket : filesystem, settings, clock).run();
    }

    private StorageCopySettings settings(Direction direction, Mode mode, int threads, boolean deep, boolean confirm,
                                         int maxPrune) {
        return new StorageCopySettings(direction, mode, threads, deep, mode == Mode.PRUNE ? 0 : afterId, confirm,
                maxPrune, 60, reports);
    }

    private String storageKeyOf(FileDetailsDTO revision) {
        return jdbc.queryForObject("SELECT storage_key FROM file_details WHERE id = ?", String.class, revision.getId());
    }

    private byte[] read(FilesystemBlobStore store, FileDetailsDTO revision) throws IOException {
        return readKey(store, storageKeyOf(revision));
    }

    private byte[] read(S3BlobStore store, FileDetailsDTO revision) throws IOException {
        return readKey(store, storageKeyOf(revision));
    }

    /** A store that runs something before its first {@code facts}, and after its n-th {@code copyIn}. */
    private static final class HookedStore implements CopyableStore {

        private final CopyableStore delegate;
        private final Runnable beforeFirstFacts;
        private final int copies;
        private final Runnable afterCopies;
        private final java.util.concurrent.atomic.AtomicInteger copied = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicBoolean factsSeen = new java.util.concurrent.atomic.AtomicBoolean();

        HookedStore(CopyableStore delegate, Runnable beforeFirstFacts, int copies, Runnable afterCopies) {
            this.delegate = delegate;
            this.beforeFirstFacts = beforeFirstFacts;
            this.copies = copies;
            this.afterCopies = afterCopies;
        }

        @Override
        public java.util.Optional<ObjectFacts> facts(StorageKey key) {
            if (factsSeen.compareAndSet(false, true)) {
                beforeFirstFacts.run();
            }
            return delegate.facts(key);
        }

        @Override
        public CopiedObject copyIn(StorageKey key, InputStream data, String expectedSha256) {
            CopiedObject result = delegate.copyIn(key, data, expectedSha256);
            if (copied.incrementAndGet() == copies) {
                afterCopies.run();
            }
            return result;
        }

        @Override
        public void forEachObject(java.util.function.Consumer<ListedObject> consumer) {
            delegate.forEachObject(consumer);
        }

        @Override
        public com.hnp.filemanagement.storage.StoredBlob put(StorageKey key, InputStream data) {
            return delegate.put(key, data);
        }

        @Override
        public org.springframework.core.io.Resource open(StorageKey key) {
            return delegate.open(key);
        }

        @Override
        public boolean exists(StorageKey key) {
            return delegate.exists(key);
        }

        @Override
        public void delete(StorageKey key) {
            delegate.delete(key);
        }

        @Override
        public void deleteDirectory(String prefix) {
            delegate.deleteDirectory(prefix);
        }
    }

    private static byte[] readKey(CopyableStore store, String key) throws IOException {
        try (InputStream in = store.open(StorageKey.of(key)).getInputStream()) {
            return in.readAllBytes();
        }
    }

    /**
     * Bytes the upload's content check takes for the name's kind - a PDF's signature, then letters -
     * of exactly this size, different for every seed.
     */
    private static byte[] content(String fileName, int size, long seed) {
        byte[] signature = fileName.endsWith(".pdf") ? "%PDF-1.4 ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
                : new byte[0];
        byte[] bytes = new byte[size];
        Random random = new Random(seed);
        for (int i = 0; i < size; i++) {
            bytes[i] = i < signature.length ? signature[i] : (byte) ('a' + random.nextInt(26));
        }
        return bytes;
    }

    private static byte[] random(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }
}
