package com.hnp.filemanagement.trash;

import com.hnp.filemanagement.file.domain.FileDetails;
import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileEvent;
import com.hnp.filemanagement.file.domain.FileHistoryEntry;
import com.hnp.filemanagement.file.domain.FileHistoryService;
import com.hnp.filemanagement.file.domain.FileInfo;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.domain.FileUploadDTO;
import com.hnp.filemanagement.file.persistence.FileDetailsRepository;
import com.hnp.filemanagement.file.persistence.FileInfoRepository;
import com.hnp.filemanagement.folder.domain.Bin;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderAccess;
import com.hnp.filemanagement.folder.domain.FolderAccessService;
import com.hnp.filemanagement.folder.domain.FolderContentService;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.folder.domain.FolderQuotaService;
import com.hnp.filemanagement.folder.domain.FolderTreeDeleteService;
import com.hnp.filemanagement.folder.domain.UserFolderGrant;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.report.ReportRepository;
import com.hnp.filemanagement.shared.exception.DependencyResourceException;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.storage.BlobStore;
import com.hnp.filemanagement.storage.StorageKey;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The recycle bin (roadmap 13.1, 2.18.0): every way something is deleted puts it in the bin and removes nothing; it is
 * then found by nobody - an administrator included - and restored as it was, or elsewhere, or under another name, or
 * purged. Each refusal of a restore, by its message code. Folder access on: a writer, a reader and a stranger beside
 * an administrator. Each test rolls back; what only a commit shows - the bytes a purge removes - is
 * {@code TrashPurgeBytesTest}'s.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class TrashTest extends DatabaseSupport {

    @Autowired
    private FileService fileService;
    @Autowired
    private FolderTreeDeleteService folderDeleteService;
    @Autowired
    private TrashRestore trashRestore;
    @Autowired
    private TrashPurge trashPurge;
    @Autowired
    private TrashListing trashListing;
    @Autowired
    private TrashEntryRepository entries;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private FolderAccessService folderAccessService;
    @Autowired
    private FolderContentService folderContentService;
    @Autowired
    private FolderQuotaService folderQuotaService;
    @Autowired
    private FileHistoryService fileHistoryService;
    @Autowired
    private ReportRepository reportRepository;
    @Autowired
    private BlobStore blobStore;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private int writerId;
    private int readerId;
    private int strangerId;
    private FolderFixture.Chain chain;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        adminId = userRepository.save(admin).getId();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        writerId = userWith(chain.categoryId(), FolderPermission.WRITE);
        readerId = userWith(chain.categoryId(), FolderPermission.READ);
        strangerId = userRepository.save(TestData.user()).getId();
        flushAndClear();
    }

    // ------------------------------------------------------------------ a file

    @Test
    @DisplayName("a file deleted goes to the bin whole - rows, revisions, bytes - and nobody finds it, an administrator included")
    void aFileDeletedIsInTheBinAndFoundByNobody() {
        FileDetailsDTO uploaded = upload("contract.txt");
        String key = fileDetailsRepository.findById(uploaded.getId()).orElseThrow().getStorageKey();

        fileService.deleteCompleteFileById(uploaded.getFileInfoId(), writerId);
        flushAndClear();

        FileInfo inBin = fileInfoRepository.findById(uploaded.getFileInfoId()).orElseThrow();
        assertThat(inBin.getFolder().getPath()).startsWith(Bin.PATH);
        assertThat(fileDetailsRepository.findById(uploaded.getId())).isPresent();
        assertThat(blobStore.exists(StorageKey.of(key))).isTrue();

        TrashEntry entry = entries.findByFileInfoId(uploaded.getFileInfoId()).orElseThrow();
        assertThat(entry.getKind()).isEqualTo(TrashKind.FILE);
        assertThat(entry.getOriginFolderId()).isEqualTo(chain.tagId());
        assertThat(entry.getOriginPath()).isEqualTo(chain.tag().getPath());
        assertThat(entry.getDeletedBy()).isEqualTo(writerId);
        assertThat(entry.getOriginalName()).isEqualTo("contract");
        assertThat(entry.getBytes()).isPositive();
        assertThat(entry.getFiles()).isEqualTo(1);

        for (int principal : List.of(adminId, writerId, readerId)) {
            assertThatThrownBy(() -> fileService.getFileInfoDtoWithFileDetails(uploaded.getFileInfoId(), principal))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> fileService.downloadFile(uploaded.getId(), principal))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThat(fileService.getPageFileInfo(50, 0, "", principal).getContent())
                    .extracting(FileInfoDTO::getId).doesNotContain(uploaded.getFileInfoId());
            assertThat(fileService.getPageFileInfo(50, 0, "contract", principal).getContent())
                    .extracting(FileInfoDTO::getId).doesNotContain(uploaded.getFileInfoId());
            assertThat(folderContentService.search("contract", null, 0, 50, principal).hits())
                    .extracting(hit -> hit.file().id()).doesNotContain(uploaded.getFileInfoId());
        }
        assertThat(fileService.getPagePublicFiles(50, 0, "").getContent())
                .extracting(dto -> dto.getFileInfoId()).doesNotContain(uploaded.getFileInfoId());
        // A second delete finds it gone.
        assertThatThrownBy(() -> fileService.deleteCompleteFileById(uploaded.getFileInfoId(), adminId))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(eventsOf(inBin.getExternalId())).contains(FileEvent.FILE_DELETED);
    }

    @Test
    @DisplayName("restored to where it was: the same file, the same ids, the entry and its folder in the bin gone")
    void aFileRestoredToWhereItWas() {
        FileDetailsDTO uploaded = upload("restored.txt");
        String externalId = fileInfoRepository.findById(uploaded.getFileInfoId()).orElseThrow().getExternalId();
        fileService.deleteCompleteFileById(uploaded.getFileInfoId(), writerId);
        flushAndClear();
        TrashEntry entry = entries.findByFileInfoId(uploaded.getFileInfoId()).orElseThrow();

        TrashRestore.Restored restored = trashRestore.restore(entry.getId(), null, null, writerId);
        flushAndClear();

        assertThat(restored.folderId()).isEqualTo(chain.tagId());
        FileInfo back = fileInfoRepository.findById(uploaded.getFileInfoId()).orElseThrow();
        assertThat(back.getFolder().getId()).isEqualTo(chain.tagId());
        assertThat(back.getExternalId()).isEqualTo(externalId);
        assertThat(entries.findById(entry.getId())).isEmpty();
        assertThat(folderRepository.findById(entry.getHolderFolderId())).isEmpty();
        assertThat(fileService.getFileInfoDtoWithFileDetails(uploaded.getFileInfoId(), readerId).getFileName())
                .isEqualTo("restored");
        assertThat(eventsOf(externalId)).contains(FileEvent.FILE_DELETED, FileEvent.FILE_RESTORED);
        assertThat(fileInfoRepository.findIdsWhoseTagsDisagreeWithTheFolders()).isEmpty();
    }

    @Test
    @DisplayName("a name taken meanwhile is refused, and restored under another name - the file's and every revision's")
    void aNameTakenMeanwhile() {
        FileDetailsDTO first = upload("taken.txt");
        fileService.deleteCompleteFileById(first.getFileInfoId(), writerId);
        flushAndClear();
        // The name is free once the file is in the bin: a new file of it is a new file.
        FileDetailsDTO second = upload("taken.txt");
        assertThat(second.getFileInfoId()).isNotEqualTo(first.getFileInfoId());
        flushAndClear();
        int entryId = entries.findByFileInfoId(first.getFileInfoId()).orElseThrow().getId();

        assertThatThrownBy(() -> trashRestore.restore(entryId, null, null, writerId))
                .isInstanceOfSatisfying(InvalidDataException.class,
                        e -> assertThat(e.getMessageCode()).contains("trash.restore.nameTaken"));
        assertThatThrownBy(() -> trashRestore.restore(entryId, null, "bad/name", writerId))
                .isInstanceOfSatisfying(InvalidDataException.class,
                        e -> assertThat(e.getMessageCode()).contains("trash.restore.badName"));

        trashRestore.restore(entryId, null, "taken-old", writerId);
        flushAndClear();
        FileInfo back = fileInfoRepository.findByIdAndFetchFileDetails(first.getFileInfoId()).orElseThrow();
        assertThat(back.getFileName()).isEqualTo("taken-old");
        assertThat(back.getFileDetailsList()).extracting(FileDetails::getFileName).containsOnly("taken-old.txt");
        assertThat(back.getFolder().getId()).isEqualTo(chain.tagId());
    }

    @Test
    @DisplayName("restored elsewhere: a folder chosen; not into one that takes no files, nor without write there")
    void aFileRestoredElsewhere() {
        FileDetailsDTO uploaded = upload("moved.txt");
        Folder elsewhere = FolderFixture.tag(folderRepository, chain.subCategory(), userRepository.getReferenceById(adminId),
                "Else" + TestData.nextSequence());
        Folder outside = FolderFixture.chain(folderRepository, tagGroupRepository,
                userRepository.findById(adminId).orElseThrow()).tag();
        fileService.deleteCompleteFileById(uploaded.getFileInfoId(), writerId);
        flushAndClear();
        int entryId = entries.findByFileInfoId(uploaded.getFileInfoId()).orElseThrow().getId();

        int rootId = FolderFixture.root(folderRepository).getId();
        assertThatThrownBy(() -> trashRestore.restore(entryId, rootId, null, adminId))
                .isInstanceOfSatisfying(InvalidDataException.class,
                        e -> assertThat(e.getMessageCode()).contains("trash.restore.takesNoFiles"));
        assertThatThrownBy(() -> trashRestore.restore(entryId, outside.getId(), null, writerId))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> trashRestore.restore(entryId, Bin.ID, null, adminId))
                .isInstanceOf(ResourceNotFoundException.class);

        trashRestore.restore(entryId, elsewhere.getId(), null, writerId);
        flushAndClear();
        assertThat(fileInfoRepository.findById(uploaded.getFileInfoId()).orElseThrow().getFolder().getId())
                .isEqualTo(elsewhere.getId());
        assertThat(fileInfoRepository.findIdsWhoseTagsDisagreeWithTheFolders()).isEmpty();
    }

    @Test
    @DisplayName("who may restore: whoever may write where it was, and an administrator - not a reader, not a stranger")
    void whoMayRestore() {
        FileDetailsDTO uploaded = upload("guarded.txt");
        fileService.deleteCompleteFileById(uploaded.getFileInfoId(), adminId);
        flushAndClear();
        int entryId = entries.findByFileInfoId(uploaded.getFileInfoId()).orElseThrow().getId();

        assertThatThrownBy(() -> trashRestore.restore(entryId, null, null, readerId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> trashRestore.restore(entryId, null, null, strangerId)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> trashPurge.purge(entryId, readerId)).isInstanceOf(AccessDeniedException.class);

        assertThat(trashListing.page(writerId, TrashListing.Filter.ALL, "", 0, null).rows())
                .extracting(TrashListing.Row::id).contains(entryId);
        assertThat(trashListing.page(adminId, TrashListing.Filter.ALL, "", 0, null).rows())
                .extracting(TrashListing.Row::id).contains(entryId);
        assertThat(trashListing.page(readerId, TrashListing.Filter.ALL, "", 0, null).rows()).isEmpty();
        assertThat(trashListing.page(strangerId, TrashListing.Filter.ALL, "", 0, null).rows()).isEmpty();
        assertThat(trashListing.one(writerId, entryId)).isPresent();
        assertThat(trashListing.one(readerId, entryId)).isEmpty();
        assertThat(trashListing.page(writerId, TrashListing.Filter.FOLDER, "", 0, null).rows())
                .extracting(TrashListing.Row::id).doesNotContain(entryId);
        assertThat(trashListing.page(writerId, TrashListing.Filter.FILE, "guard", 0, null).rows())
                .extracting(TrashListing.Row::id).contains(entryId);
        assertThat(trashListing.page(writerId, TrashListing.Filter.ALL, "", writerId, null).rows())
                .extracting(TrashListing.Row::id).doesNotContain(entryId);

        trashRestore.restore(entryId, null, null, writerId);
        flushAndClear();
        assertThatThrownBy(() -> trashRestore.restore(entryId, null, null, writerId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------ a revision

    @Test
    @DisplayName("a revision deleted alone: its file keeps the others, its latest is the one before, and it comes back to its number")
    void aRevisionDeletedAndRestored() {
        FileDetailsDTO first = upload("versioned.txt");
        int fileId = first.getFileInfoId();
        int secondId = addVersion(fileId, "versioned.txt", 2);

        fileService.deleteFileDetails(secondId, writerId);
        flushAndClear();

        FileInfo file = fileInfoRepository.findByIdAndFetchFileDetails(fileId).orElseThrow();
        assertThat(file.getLastVersion()).isEqualTo(1);
        assertThat(file.getFileDetailsList()).extracting(FileDetails::getId).containsExactly(first.getId());
        TrashEntry entry = entries.findById(entries.findRevisionEntriesOf(List.of(fileId)).getFirst()).orElseThrow();
        assertThat(entry.getKind()).isEqualTo(TrashKind.REVISION);
        assertThat(entry.getFileDetailsId()).isEqualTo(secondId);
        assertThat(fileDetailsRepository.findByIdWithFileInfo(secondId).orElseThrow().getFileInfo().getFolder().getPath())
                .startsWith(Bin.PATH);
        assertThatThrownBy(() -> fileService.downloadFile(secondId, adminId)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(fileService.downloadFileRevision(fileId, null, null, readerId).getVersion()).isEqualTo(1);

        assertThatThrownBy(() -> trashRestore.restore(entry.getId(), chain.tagId(), null, writerId))
                .isInstanceOfSatisfying(InvalidDataException.class,
                        e -> assertThat(e.getMessageCode()).contains("trash.restore.revisionToItsFile"));
        TrashRestore.Restored restored = trashRestore.restore(entry.getId(), null, null, writerId);
        flushAndClear();

        assertThat(restored.version()).isEqualTo(2);
        file = fileInfoRepository.findByIdAndFetchFileDetails(fileId).orElseThrow();
        assertThat(file.getLastVersion()).isEqualTo(2);
        assertThat(file.getFileDetailsList()).extracting(FileDetails::getId).containsExactlyInAnyOrder(first.getId(), secondId);
        assertThat(fileInfoRepository.findById(entry.getFileInfoId())).isEmpty();
        assertThat(eventsOf(file.getExternalId())).contains(FileEvent.REVISION_DELETED, FileEvent.REVISION_RESTORED);
    }

    @Test
    @DisplayName("a revision whose number its file has taken meanwhile comes back as the file's next version")
    void aRevisionWhoseNumberWasTaken() {
        FileDetailsDTO first = upload("renumbered.txt");
        int fileId = first.getFileInfoId();
        int secondId = addVersion(fileId, "renumbered.txt", 2);
        fileService.deleteFileDetails(secondId, writerId);
        flushAndClear();
        int newSecondId = addVersion(fileId, "renumbered.txt", 2);
        flushAndClear();
        int entryId = entries.findRevisionEntriesOf(List.of(fileId)).getFirst();

        TrashRestore.Restored restored = trashRestore.restore(entryId, null, null, writerId);
        flushAndClear();

        assertThat(restored.version()).isEqualTo(3);
        FileDetails back = fileDetailsRepository.findById(secondId).orElseThrow();
        assertThat(back.getVersion()).isEqualTo(3);
        assertThat(back.getVersionName()).isEqualTo("V3");
        assertThat(fileDetailsRepository.findById(newSecondId).orElseThrow().getVersion()).isEqualTo(2);
        assertThat(fileInfoRepository.findById(fileId).orElseThrow().getLastVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("a revision whose file is in the bin waits for it; purging the file takes the revision with it")
    void aRevisionAndItsFileInTheBin() {
        FileDetailsDTO first = upload("together.txt");
        int fileId = first.getFileInfoId();
        int secondId = addVersion(fileId, "together.txt", 2);
        fileService.deleteFileDetails(secondId, writerId);
        fileService.deleteCompleteFileById(fileId, writerId);
        flushAndClear();
        int revisionEntryId = entries.findRevisionEntriesOf(List.of(fileId)).getFirst();
        TrashEntry fileEntry = entries.findByFileInfoId(fileId).orElseThrow();

        assertThatThrownBy(() -> trashRestore.restore(revisionEntryId, null, null, writerId))
                .isInstanceOfSatisfying(InvalidDataException.class,
                        e -> assertThat(e.getMessageCode()).contains("trash.restore.revisionFileInBin"));

        TrashPurge.Purged purged = trashPurge.purge(fileEntry.getId(), adminId);
        flushAndClear();

        assertThat(purged.entries()).isEqualTo(2);
        assertThat(entries.findById(revisionEntryId)).isEmpty();
        assertThat(entries.findById(fileEntry.getId())).isEmpty();
        assertThat(fileInfoRepository.findById(fileId)).isEmpty();
        assertThat(fileDetailsRepository.findById(secondId)).isEmpty();
        assertThat(fileDetailsRepository.findById(first.getId())).isEmpty();
        assertThat(folderRepository.findById(fileEntry.getHolderFolderId())).isEmpty();
    }

    // ------------------------------------------------------------------ a folder

    @Test
    @DisplayName("a folder deleted with everything: its subtree in the bin, its grants covering nothing; restored, all as it was")
    void aFolderTreeDeletedAndRestored() {
        FileDetailsDTO inTag = upload("deep.txt");
        Folder category = chain.category();
        String categoryPath = category.getPath();

        folderDeleteService.deleteTree(chain.categoryId(), adminId);
        flushAndClear();

        Folder inBin = folderRepository.findById(chain.categoryId()).orElseThrow();
        assertThat(inBin.getPath()).startsWith(Bin.PATH);
        assertThat(folderRepository.findById(chain.tagId()).orElseThrow().getPath()).startsWith(inBin.getPath());
        TrashEntry entry = entries.findByFolderId(chain.categoryId()).orElseThrow();
        assertThat(entry.getKind()).isEqualTo(TrashKind.FOLDER);
        assertThat(entry.getFolders()).isEqualTo(3);
        assertThat(entry.getFiles()).isEqualTo(1);
        assertThat(entry.getOriginFolderId()).isEqualTo(FolderFixture.root(folderRepository).getId());
        // The writer's only grant is on the deleted folder: it covers nothing now.
        FolderAccess writer = folderAccessService.accessFor(writerId);
        assertThat(writer.isEmpty()).isTrue();
        FolderAccess admin = folderAccessService.accessFor(adminId);
        assertThat(admin.canRead(inBin.getPath())).isFalse();
        assertThat(admin.visible(inBin.getPath())).isFalse();
        assertThatThrownBy(() -> folderContentService.contentOf(chain.tagId(), 0, 50, adminId))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> fileService.getFileInfoDtoWithFileDetails(inTag.getFileInfoId(), adminId))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(folderContentService.contentOf(null, 0, 200, adminId).folders())
                .extracting(f -> f.id()).doesNotContain(chain.categoryId(), Bin.ID);
        assertDerivedColumnsHold();
        // Only an administrator, or who may write in the root, may restore a top-level folder.
        assertThatThrownBy(() -> trashRestore.restore(entry.getId(), null, null, writerId)).isInstanceOf(AccessDeniedException.class);

        trashRestore.restore(entry.getId(), null, null, adminId);
        flushAndClear();

        Folder back = folderRepository.findById(chain.categoryId()).orElseThrow();
        assertThat(back.getPath()).isEqualTo(categoryPath);
        assertThat(back.getTagGroup()).isNotNull();
        assertThat(folderAccessService.accessFor(writerId).canWrite(chain.tag().getPath())).isTrue();
        assertThat(fileService.getFileInfoDtoWithFileDetails(inTag.getFileInfoId(), readerId).getFileName()).isEqualTo("deep");
        assertThat(folderRepository.findById(entry.getHolderFolderId())).isEmpty();
        assertDerivedColumnsHold();
        assertThat(eventsOf(fileInfoRepository.findById(inTag.getFileInfoId()).orElseThrow().getExternalId()))
                .contains(FileEvent.FILE_DELETED, FileEvent.FILE_RESTORED);
    }

    @Test
    @DisplayName("a folder restored where its name is taken, under another; where it was when that is in the bin, refused")
    void aFolderRestoredUnderAnotherName() {
        Folder sub = chain.subCategory();
        Folder emptyOne = FolderFixture.tag(folderRepository, sub, userRepository.getReferenceById(adminId), "Empty");
        String name = emptyOne.getName();
        folderDeleteService.deleteEmpty(emptyOne.getId(), writerId);
        flushAndClear();
        FolderFixture.tag(folderRepository, folderRepository.findById(sub.getId()).orElseThrow(),
                userRepository.getReferenceById(adminId), name);
        flushAndClear();
        int entryId = entries.findByFolderId(emptyOne.getId()).orElseThrow().getId();

        assertThatThrownBy(() -> trashRestore.restore(entryId, null, null, writerId))
                .isInstanceOfSatisfying(InvalidDataException.class,
                        e -> assertThat(e.getMessageCode()).contains("trash.restore.nameTaken"));
        trashRestore.restore(entryId, null, "Empty-old", writerId);
        flushAndClear();
        Folder back = folderRepository.findById(emptyOne.getId()).orElseThrow();
        assertThat(back.getName()).isEqualTo("Empty-old");
        assertThat(back.getParent().getId()).isEqualTo(sub.getId());
        assertDerivedColumnsHold();

        // A file deleted from a folder that is then deleted too: where it was is in the bin.
        FileDetailsDTO file = upload("orphan.txt");
        fileService.deleteCompleteFileById(file.getFileInfoId(), writerId);
        folderDeleteService.deleteTree(chain.subCategoryId(), writerId);
        flushAndClear();
        int fileEntry = entries.findByFileInfoId(file.getFileInfoId()).orElseThrow().getId();
        assertThatThrownBy(() -> trashRestore.restore(fileEntry, null, null, writerId))
                .isInstanceOfSatisfying(InvalidDataException.class,
                        e -> assertThat(e.getMessageCode()).contains("trash.restore.originInBin"));
        // Who could write where it was still may restore it - and sees it.
        assertThat(trashListing.page(writerId, TrashListing.Filter.FILE, "", 0, null).rows())
                .extracting(TrashListing.Row::id).contains(fileEntry);
    }

    @Test
    @DisplayName("the empty delete refuses a folder that holds anything; the root, Profiles and the bin are never deleted")
    void whatIsNotDeleted() {
        upload("keeps.txt");
        assertThatThrownBy(() -> folderDeleteService.deleteEmpty(chain.tagId(), adminId))
                .isInstanceOf(DependencyResourceException.class);
        assertThatThrownBy(() -> folderDeleteService.deleteEmpty(chain.categoryId(), adminId))
                .isInstanceOf(DependencyResourceException.class);
        int rootId = FolderFixture.root(folderRepository).getId();
        assertThatThrownBy(() -> folderDeleteService.deleteTree(rootId, adminId)).isInstanceOf(InvalidDataException.class);
        assertThatThrownBy(() -> folderDeleteService.deleteTree(Bin.ID, adminId)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> folderDeleteService.deleteTree(chain.categoryId(), readerId)).isInstanceOf(AccessDeniedException.class);
        assertThat(entries.findByFolderId(chain.tagId())).isEmpty();
    }

    // ------------------------------------------------------------------ quotas and reports

    @Test
    @DisplayName("what is in the bin counts against the quota of the folder it came from until it is purged")
    void theBinCountsAgainstTheQuota() {
        FileDetailsDTO uploaded = upload("heavy.txt");
        Folder category = folderRepository.findById(chain.categoryId()).orElseThrow();
        long before = folderQuotaService.usageOf(category);
        long totalsBefore = reportRepository.totals(com.hnp.filemanagement.folder.domain.FolderReadScope.everything()).files();

        fileService.deleteCompleteFileById(uploaded.getFileInfoId(), writerId);
        flushAndClear();
        category = folderRepository.findById(chain.categoryId()).orElseThrow();
        assertThat(folderQuotaService.usageOf(category)).isEqualTo(before);
        assertThat(folderQuotaService.inBin(category)).isEqualTo(before);
        assertThat(reportRepository.totals(com.hnp.filemanagement.folder.domain.FolderReadScope.everything()).files())
                .isEqualTo(totalsBefore - 1);

        trashPurge.purge(entries.findByFileInfoId(uploaded.getFileInfoId()).orElseThrow().getId(), writerId);
        flushAndClear();
        category = folderRepository.findById(chain.categoryId()).orElseThrow();
        assertThat(folderQuotaService.usageOf(category)).isZero();
    }

    // ------------------------------------------------------------------ pieces

    private void assertDerivedColumnsHold() {
        assertThat(folderRepository.findRowsWhoseDerivedColumnsDisagree()).isEmpty();
        assertThat(folderRepository.findRowsWhoseKeyPathDisagrees()).isEmpty();
        assertThat(folderRepository.findRowsWhoseClassPathDisagrees()).isEmpty();
        assertThat(fileInfoRepository.findIdsWhoseTagsDisagreeWithTheFolders()).isEmpty();
    }

    private List<FileEvent> eventsOf(String fileExternalId) {
        return fileHistoryService.ofFile(fileExternalId).stream().map(FileHistoryEntry::event).toList();
    }

    private int userWith(int folderId, FolderPermission permission) {
        User user = userRepository.save(TestData.user());
        List<UserFolderGrant> grants = new ArrayList<>(user.getFolderGrants());
        grants.add(new UserFolderGrant(user, folderRepository.findById(folderId).orElseThrow(), permission));
        user.replaceFolderGrants(grants);
        userRepository.save(user);
        return user.getId();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private FileDetailsDTO upload(String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(chain.tagId());
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain",
                ("content of " + fileName).getBytes(StandardCharsets.UTF_8)));
        FileDetailsDTO uploaded = fileService.createNewFile(request, adminId, FileService.PUBLIC);
        flushAndClear();
        return uploaded;
    }

    private int addVersion(int fileId, String fileName, int version) {
        FileUploadDTO request = new FileUploadDTO();
        request.setFileId(fileId);
        String name = fileName.substring(0, fileName.lastIndexOf('.'));
        request.setFileName(name);
        request.setFileNameWithoutExtension(name);
        request.setVersion(version);
        request.setType("version");
        request.setFileDetailsDescription("version " + version);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain",
                ("version " + version + " of " + fileName).getBytes(StandardCharsets.UTF_8)));
        fileService.createNewFileDetails(request, adminId);
        flushAndClear();
        int id = fileInfoRepository.findByIdAndFetchFileDetails(fileId).orElseThrow().getFileDetailsList().stream()
                .filter(d -> d.getVersion() == version)
                .mapToInt(FileDetails::getId).max().orElseThrow();
        flushAndClear();
        return id;
    }
}
