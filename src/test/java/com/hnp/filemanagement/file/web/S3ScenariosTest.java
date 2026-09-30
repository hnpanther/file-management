package com.hnp.filemanagement.file.web;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileDownloadDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.domain.FileUploadDTO;
import com.hnp.filemanagement.file.domain.ShareLinkDTO;
import com.hnp.filemanagement.file.domain.ShareLinkService;
import com.hnp.filemanagement.file.persistence.FileDetailsRepository;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderService;
import com.hnp.filemanagement.folder.domain.FolderTreeDeleteService;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.ApiKeyDTO;
import com.hnp.filemanagement.identity.domain.ApiKeyService;
import com.hnp.filemanagement.identity.domain.PermissionEnum;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.identity.security.UserDetailsImpl;
import com.hnp.filemanagement.shared.exception.DuplicateResourceException;
import com.hnp.filemanagement.storage.BlobStore;
import com.hnp.filemanagement.storage.S3BlobStore;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.support.TestObjectStores;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Everything the application does with a file, on the s3 backend (2.7.0, before the copy of
 * roadmap 4.4): what each operation leaves in the bucket, counted object by object - a duplicate
 * stores nothing, a version and a format are objects of their own, a delete removes exactly what it
 * should, a move and a rename touch no object, and every way of downloading reads from the bucket.
 *
 * <p>{@code @Transactional}: a write in a transaction that rolls back is removed by
 * {@code StorageWriter}, so each test leaves the bucket as it found it - which is itself under test
 * here. The two cases that need real commits - concurrent uploads, and a rollback the test drives -
 * opt out and clean up after themselves.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class S3ScenariosTest extends DatabaseSupport {

    private static final String PREFIX = "scenarios-" + UUID.randomUUID();

    @DynamicPropertySource
    static void objectStore(DynamicPropertyRegistry registry) {
        TestObjectStores.useAsBackend(registry, PREFIX);
        registry.add("filemanagement.folder-access.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BlobStore blobStore;
    @Autowired
    private FileService fileService;
    @Autowired
    private FolderService folderService;
    @Autowired
    private FolderTreeDeleteService folderTreeDeleteService;
    @Autowired
    private ShareLinkService shareLinkService;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private int userId;
    private User owner;
    private FolderFixture.Chain chain;

    @BeforeEach
    void setUp() {
        owner = userRepository.save(TestData.user());
        userId = owner.getId();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, owner);
    }

    @AfterEach
    void clearTheBucket() {
        TestObjectStores.clear(PREFIX);
    }

    @Test
    @DisplayName("the store is the object store")
    void theStoreIsTheObjectStore() {
        assertThat(blobStore).isInstanceOf(S3BlobStore.class);
        assertThat(objects()).isEmpty();
    }

    // ---------------------------------------------------------------- duplicates

    @Test
    @DisplayName("a second file of the same name in a folder - in any case - is refused and stores nothing")
    void aDuplicateNameStoresNothing() {
        String name = "dup" + TestData.nextSequence();
        FileDetailsDTO first = upload(chain.tagId(), name + ".pdf", bytes("the first"));
        assertThat(objects()).containsExactly(storageKeyOf(first));

        assertThatThrownBy(() -> upload(chain.tagId(), name + ".pdf", bytes("a second, different")))
                .isInstanceOf(DuplicateResourceException.class);
        assertThatThrownBy(() -> upload(chain.tagId(), name.toUpperCase() + ".docx", bytes("another format, same name")))
                .isInstanceOf(DuplicateResourceException.class);

        assertThat(objects()).containsExactly(storageKeyOf(first));
        assertThat(read(fileService.downloadFile(first.getId(), userId))).isEqualTo(withSignature(name + ".pdf", bytes("the first")));
    }

    @Test
    @DisplayName("through API v1 a duplicate is a 409, and the bucket holds what it held")
    void aDuplicateThroughTheApi() throws Exception {
        String name = "apidup" + TestData.nextSequence() + ".txt";
        mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("multipartFile", name, "text/plain", bytes("one")))
                        .param("description", "first").param("folderId", String.valueOf(chain.tagId()))
                        .with(user(principal(PermissionEnum.API_SAVE_NEW_FILE))))
                .andExpect(status().isOk());
        List<String> before = objects();

        mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("multipartFile", name, "text/plain", bytes("two")))
                        .param("description", "again").param("folderId", String.valueOf(chain.tagId()))
                        .with(user(principal(PermissionEnum.API_SAVE_NEW_FILE))))
                .andExpect(status().isConflict());

        assertThat(before).hasSize(1);
        assertThat(objects()).isEqualTo(before);
    }

    @Test
    @DisplayName("the same bytes under two names, or in two folders, are two files and two objects - nothing is shared")
    void theSameBytesTwice() {
        Folder other = FolderFixture.tag(folderRepository, chain.subCategory(), owner, "Other" + TestData.nextSequence());
        String name = "same" + TestData.nextSequence();
        FileDetailsDTO a = upload(chain.tagId(), name + "a.txt", bytes("identical"));
        FileDetailsDTO b = upload(chain.tagId(), name + "b.txt", bytes("identical"));
        FileDetailsDTO c = upload(other.getId(), name + "a.txt", bytes("identical"));

        assertThat(objects()).containsExactlyInAnyOrder(storageKeyOf(a), storageKeyOf(b), storageKeyOf(c));
        assertThat(a.getChecksumSha256()).as("the same bytes where the names match").isEqualTo(c.getChecksumSha256());

        fileService.deleteCompleteFileById(a.getFileInfoId(), userId);
        assertThat(read(fileService.downloadFile(b.getId(), userId))).isEqualTo(withSignature(name + "b.txt", bytes("identical")));
        assertThat(objects()).containsExactlyInAnyOrder(storageKeyOf(b), storageKeyOf(c));
    }

    @Test
    @DisplayName("concurrent uploads of one name: one file, one object, and nothing left of the others")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentUploadsOfOneName() throws Exception {
        String name = "race" + TestData.nextSequence() + ".txt";
        int racers = 4;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        List<Future<FileDetailsDTO>> results = new ArrayList<>();
        try {
            for (int i = 0; i < racers; i++) {
                byte[] content = bytes("racer " + i);
                Callable<FileDetailsDTO> racer = () -> {
                    go.await();
                    return upload(chain.tagId(), name, content);
                };
                results.add(pool.submit(racer));
            }
            go.countDown();
            List<FileDetailsDTO> stored = new ArrayList<>();
            int refused = 0;
            for (Future<FileDetailsDTO> result : results) {
                try {
                    stored.add(result.get(60, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException e) {
                    refused++;
                }
            }

            assertThat(stored).as("exactly one upload wins").hasSize(1);
            assertThat(refused).isEqualTo(racers - 1);
            assertThat(objects()).as("and only its bytes are in the bucket").containsExactly(storageKeyOf(stored.getFirst()));

            fileService.deleteCompleteFileById(stored.getFirst().getFileInfoId(), userId);
            assertThat(objects()).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("an upload whose transaction rolls back after the bytes went up leaves no object")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aRolledBackUploadLeavesNoObject() {
        String name = "rolledback" + TestData.nextSequence() + ".pdf";
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            upload(chain.tagId(), name, bytes("never committed"));
            assertThat(objects()).as("the bytes are up while the transaction is open").hasSize(1);
            throw new IllegalStateException("a later step refused");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(objects()).isEmpty();
        assertThat(fileService.isDuplicate(name.substring(0, name.indexOf('.')), chain.tagId())).isFalse();
    }

    // ---------------------------------------------------------------- versions and formats

    @Test
    @DisplayName("each version and each format is an object of its own; a duplicate format is refused and stores nothing")
    void versionsAndFormats() throws Exception {
        String name = "multi" + TestData.nextSequence();
        FileDetailsDTO v1pdf = upload(chain.tagId(), name + ".pdf", bytes("v1 pdf"));
        addRevision(v1pdf, name + ".docx", 1, "format", bytes("v1 docx"));
        addRevision(v1pdf, name + ".pdf", 2, "version", bytes("v2 pdf"));

        assertThat(objects()).hasSize(3).doesNotHaveDuplicates();
        assertThat(read(fileService.downloadFileRevision(v1pdf.getFileInfoId(), 1, "pdf", userId)))
                .isEqualTo(withSignature(name + ".pdf", bytes("v1 pdf")));
        assertThat(read(fileService.downloadFileRevision(v1pdf.getFileInfoId(), 1, "docx", userId)))
                .isEqualTo(withSignature(name + ".docx", bytes("v1 docx")));
        assertThat(read(fileService.downloadFileRevision(v1pdf.getFileInfoId(), 2, "pdf", userId)))
                .isEqualTo(withSignature(name + ".pdf", bytes("v2 pdf")));
        assertThat(read(fileService.downloadFileRevision(v1pdf.getFileInfoId(), null, null, userId)))
                .as("the latest").isEqualTo(withSignature(name + ".pdf", bytes("v2 pdf")));

        assertThatThrownBy(() -> addRevision(v1pdf, name + ".docx", 1, "format", bytes("v1 docx again")))
                .isInstanceOf(DuplicateResourceException.class);
        assertThat(objects()).hasSize(3);
    }

    @Test
    @DisplayName("deleting a format, a version, then the file removes exactly their objects, and the rest still downloads")
    void deletingRevisions() throws Exception {
        String name = "del" + TestData.nextSequence();
        FileDetailsDTO v1pdf = upload(chain.tagId(), name + ".pdf", bytes("v1 pdf"));
        int fileInfoId = v1pdf.getFileInfoId();
        addRevision(v1pdf, name + ".docx", 1, "format", bytes("v1 docx"));
        addRevision(v1pdf, name + ".pdf", 2, "version", bytes("v2 pdf"));
        int v1docx = revisionId(fileInfoId, 1, "docx");
        int v2pdf = revisionId(fileInfoId, 2, "pdf");
        String v1docxKey = fileDetailsRepository.findById(v1docx).orElseThrow().getStorageKey();
        String v2pdfKey = fileDetailsRepository.findById(v2pdf).orElseThrow().getStorageKey();

        fileService.deleteFileDetails(v1docx, userId);
        assertThat(objects()).hasSize(2).doesNotContain(v1docxKey);

        fileService.deleteFileDetails(v2pdf, userId);
        assertThat(objects()).containsExactly(storageKeyOf(v1pdf)).doesNotContain(v2pdfKey);
        assertThat(read(fileService.downloadFile(v1pdf.getId(), userId))).isEqualTo(withSignature(name + ".pdf", bytes("v1 pdf")));

        fileService.deleteFileDetails(v1pdf.getId(), userId);
        assertThat(objects()).as("the last revision takes the file with it").isEmpty();
    }

    @Test
    @DisplayName("deleting a folder tree removes the objects of every file beneath it, and none beside it")
    void aTreeDelete() {
        Folder inside = FolderFixture.tag(folderRepository, chain.subCategory(), owner, "In" + TestData.nextSequence());
        FolderFixture.Chain beside = FolderFixture.chain(folderRepository, tagGroupRepository, owner);
        String name = "tree" + TestData.nextSequence();
        upload(chain.tagId(), name + "1.txt", bytes("one"));
        upload(inside.getId(), name + "2.txt", bytes("two"));
        FileDetailsDTO kept = upload(beside.tagId(), name + "3.txt", bytes("three"));
        assertThat(objects()).hasSize(3);

        folderTreeDeleteService.deleteTree(chain.subCategoryId(), userId);

        assertThat(objects()).containsExactly(storageKeyOf(kept));
    }

    // ---------------------------------------------------------------- moves and renames

    @Test
    @DisplayName("moving a file and renaming its folder move no object: the keys stay, and the file still downloads")
    void movesAndRenamesTouchNoObject() {
        Folder target = FolderFixture.tag(folderRepository, chain.subCategory(), owner, "Target" + TestData.nextSequence());
        String name = "moved" + TestData.nextSequence() + ".pdf";
        FileDetailsDTO stored = upload(chain.tagId(), name, bytes("where am i"));
        List<String> before = objects();

        fileService.moveFile(stored.getFileInfoId(), target.getId(), userId);
        folderService.rename(target.getId(), "Renamed" + TestData.nextSequence(), "renamed", null, userId);

        assertThat(objects()).isEqualTo(before);
        assertThat(read(fileService.downloadFile(stored.getId(), userId))).isEqualTo(withSignature(name, bytes("where am i")));
    }

    // ---------------------------------------------------------------- every way out

    @Test
    @DisplayName("from the bucket: the page's download and preview with ranges, the public files, a share link, and API v1")
    void everyDownloadReadsTheBucket() throws Exception {
        String name = "served" + TestData.nextSequence() + ".pdf";
        byte[] pdf = withSignature(name, bytes("served from the bucket"));
        FileDetailsDTO stored = upload(chain.tagId(), name, bytes("served from the bucket"), FileService.PUBLIC);
        String page = "/files/file-info/" + stored.getFileInfoId() + "/file-details/" + stored.getId() + "/download";

        mockMvc.perform(get(page).with(user(principal(PermissionEnum.DOWNLOAD_FILE))))
                .andExpect(status().isOk()).andExpect(content().bytes(pdf));
        mockMvc.perform(get(page).param("inline", "1").header(HttpHeaders.RANGE, "bytes=2-6")
                        .with(user(principal(PermissionEnum.DOWNLOAD_FILE))))
                .andExpect(status().isPartialContent())
                .andExpect(content().bytes(Arrays.copyOfRange(pdf, 2, 7)));
        mockMvc.perform(get(page).header(HttpHeaders.RANGE, "bytes=-3")
                        .with(user(principal(PermissionEnum.DOWNLOAD_FILE))))
                .andExpect(status().isPartialContent())
                .andExpect(content().bytes(Arrays.copyOfRange(pdf, pdf.length - 3, pdf.length)));
        mockMvc.perform(head(page).with(user(principal(PermissionEnum.DOWNLOAD_FILE))))
                .andExpect(status().isOk())
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, pdf.length));

        mockMvc.perform(get("/files/public-download/{id}", stored.getId()))
                .andExpect(status().isOk()).andExpect(content().bytes(pdf));

        ShareLinkDTO link = shareLinkService.create(stored.getId(), 10, null, null, userId);
        mockMvc.perform(post("/share/{token}", link.token()).with(csrf()))
                .andExpect(status().isOk()).andExpect(content().bytes(pdf));

        mockMvc.perform(get("/api/v1/files/file-details/{id}/download", stored.getExternalId())
                        .with(user(principal(PermissionEnum.API_DOWNLOAD_FILE))))
                .andExpect(status().isOk()).andExpect(content().bytes(pdf));
    }

    @Test
    @DisplayName("API v2 on the bucket: PUT, GET, HEAD and DELETE, and a PUT at a taken version is a 409 that writes nothing")
    void theObjectApi() throws Exception {
        ApiKeyDTO request = new ApiKeyDTO();
        request.setTitle("s3 " + TestData.nextSequence());
        request.setFolderGrants(List.of(chain.tagId() + ":WRITE"));
        String credential = apiKeyService.create(request, userId).credential();
        String bucket = "/api/v2/" + chain.category().getName() + "/" + chain.subCategory().getName() + "/" + chain.tag().getName();
        String name = "obj" + TestData.nextSequence();

        mockMvc.perform(put(bucket + "/" + name + "/" + name + ".txt").header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .contentType(MediaType.TEXT_PLAIN).content(bytes("first")))
                .andExpect(status().isCreated());
        mockMvc.perform(put(bucket + "/" + name + "/" + name + ".txt").header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .contentType(MediaType.TEXT_PLAIN).content(bytes("second")))
                .andExpect(status().isCreated());
        assertThat(objects()).hasSize(2);

        mockMvc.perform(put(bucket + "/" + name + "/v1/" + name + ".txt").header(HttpHeaders.AUTHORIZATION, "Bearer " + credential)
                        .contentType(MediaType.TEXT_PLAIN).content(bytes("over the first")))
                .andExpect(status().isConflict());
        assertThat(objects()).hasSize(2);

        mockMvc.perform(get(bucket + "/" + name + "/v1/" + name + ".txt").header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes("first")));
        mockMvc.perform(head(bucket + "/" + name + "/v2/" + name + ".txt").header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                .andExpect(status().isOk()).andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 6));
        mockMvc.perform(delete(bucket + "/" + name + "/v2/" + name + ".txt").header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                .andExpect(status().isNoContent());
        assertThat(objects()).hasSize(1);
        mockMvc.perform(get(bucket + "/" + name + "/v2/" + name + ".txt").header(HttpHeaders.AUTHORIZATION, "Bearer " + credential))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- helpers

    /** What the bucket holds for this class - the storage keys of the objects under its prefix. */
    private static List<String> objects() {
        return TestObjectStores.keysUnder(PREFIX);
    }

    private String storageKeyOf(FileDetailsDTO revision) {
        return fileDetailsRepository.findById(revision.getId()).orElseThrow().getStorageKey();
    }

    private int revisionId(int fileInfoId, int version, String format) {
        return fileDetailsRepository.findAll().stream()
                .filter(d -> d.getFileInfo().getId() == fileInfoId && d.getVersion() == version
                        && d.getFileName().endsWith("." + format))
                .findFirst().orElseThrow().getId();
    }

    private FileDetailsDTO upload(int folderId, String fileName, byte[] content) {
        return upload(folderId, fileName, content, FileService.PRIVATE);
    }

    private FileDetailsDTO upload(int folderId, String fileName, byte[] content, int visibility) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("s3 " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "application/octet-stream", withSignature(fileName, content)));
        return fileService.createNewFile(request, userId, visibility);
    }

    private void addRevision(FileDetailsDTO sample, String fileName, int version, String type, byte[] content) {
        FileUploadDTO request = new FileUploadDTO();
        request.setFileId(sample.getFileInfoId());
        request.setFileDetailsId(sample.getId());
        request.setFileName(fileName.substring(0, fileName.lastIndexOf('.')));
        request.setFileDetailsDescription(type + " " + version);
        request.setVersion(version);
        request.setType(type);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "application/octet-stream", withSignature(fileName, content)));
        fileService.createNewFileDetails(request, userId);
    }

    /** The content, behind the signature its extension calls for - what the upload checks. */
    private static byte[] withSignature(String fileName, byte[] content) {
        byte[] signed = TestData.bytesFor(fileName);
        byte[] all = Arrays.copyOf(signed, signed.length + content.length);
        System.arraycopy(content, 0, all, signed.length, content.length);
        return all;
    }

    private static byte[] read(FileDownloadDTO download) {
        try (InputStream in = download.getResource().getInputStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private UserDetailsImpl principal(PermissionEnum... permissions) {
        UserDetailsImpl principal = new UserDetailsImpl();
        principal.setId(userId);
        principal.setUsername("s3tester" + userId);
        principal.setPassword("irrelevant");
        principal.setEnabled(1);
        principal.setState(0);
        principal.setLoginType(0);
        principal.setPermissions(List.of(permissions));
        return principal;
    }
}
