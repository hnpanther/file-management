package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.audit.domain.ActionHistoryService;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.storage.StorageLayout;
import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileUploadDTO;
import com.hnp.filemanagement.audit.domain.EntityEnum;
import com.hnp.filemanagement.file.domain.FileDetails;
import com.hnp.filemanagement.identity.domain.Role;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.shared.exception.DependencyResourceException;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.shared.exception.ResourceNotFoundException;
import com.hnp.filemanagement.file.persistence.FileDetailsRepository;
import com.hnp.filemanagement.file.persistence.FileInfoRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deleting a folder with everything in it (roadmap 10.3): what goes, in what order, what is
 * refused, and what the disk looks like afterwards.
 *
 * <p>Folder access is on, because who may do this is half of the rule; the cap is three files
 * so that "too many" is reachable. The files are real uploads through {@link FileService}, so
 * there are real bytes under {@code base-dir} to see disappear - and one old-layout file with
 * a neighbour, to see the shared directory survive.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = {
        "filemanagement.folder-access.enabled=true",
        "filemanagement.folders.max-delete-files=3"})
class FolderTreeDeleteServiceTest extends DatabaseSupport {

    @Autowired
    private FolderTreeDeleteService underTest;
    @Autowired
    private FileService fileService;
    @Autowired
    private ActionHistoryService actionHistoryService;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Value("${file.management.base-dir}")
    private String baseDir;

    private User admin;
    private int adminId;
    private int restrictedId;
    private FolderFixture.Chain chain;

    @BeforeEach
    void setUp() {
        Role adminRole = roleRepository.save(TestData.role("ADMIN"));
        admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();
        restrictedId = userRepository.save(TestData.user()).getId();
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
    }

    @Test
    @DisplayName("a three-level tree with files at each level goes whole: rows, tags, grants, versions, and every byte - the shards stay")
    void deletesTheWholeTree() {
        // Cat / Sub / Tag, a file in each of Sub and Tag, and a second version of one of them.
        FileDetailsDTO inSub = upload("sub-report.txt", chain.subCategoryId());
        FileDetailsDTO inTag = upload("tag-report.txt", chain.tagId());
        fileService.createNewFileDetails(versionRequest(inTag.getFileInfoId(), "tag-report.txt", 2), adminId);
        Folder deeper = FolderFixture.tag(folderRepository, chain.subCategory(), admin, "Empty" + TestData.nextSequence());
        grant(restrictedId, deeper.getId(), FolderPermission.READ);
        entityManager.flush();
        entityManager.clear();

        Path subDir = Paths.get(baseDir).resolve(StorageLayout.directoryFor(inSub.getFileInfoId()));
        Path tagDir = Paths.get(baseDir).resolve(StorageLayout.directoryFor(inTag.getFileInfoId()));
        assertThat(subDir).exists();
        assertThat(tagDir.resolve(Paths.get("tag-report", "v2", "tag-report.txt"))).exists();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM file_tag WHERE file_info_id IN (?, ?)", Integer.class,
                inSub.getFileInfoId(), inTag.getFileInfoId())).isPositive();

        FolderTreeDeleteService.DeletedTree deleted = underTest.deleteTree(chain.subCategoryId(), adminId);
        entityManager.flush();
        entityManager.clear();

        assertThat(deleted.folders()).as("Tag and Empty beneath Sub").isEqualTo(2);
        assertThat(deleted.files()).isEqualTo(2);
        assertThat(folderRepository.findById(chain.subCategoryId())).isEmpty();
        assertThat(folderRepository.findById(chain.tagId())).isEmpty();
        assertThat(folderRepository.findById(deeper.getId())).isEmpty();
        assertThat(folderRepository.findById(chain.categoryId())).as("the parent stays").isPresent();
        assertThat(fileInfoRepository.findById(inSub.getFileInfoId())).isEmpty();
        assertThat(fileInfoRepository.findById(inTag.getFileInfoId())).isEmpty();
        assertThat(fileDetailsRepository.findByFileInfoIdIn(List.of(inSub.getFileInfoId(), inTag.getFileInfoId()))).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM file_tag WHERE file_info_id IN (?, ?)", Integer.class,
                inSub.getFileInfoId(), inTag.getFileInfoId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_folder WHERE folder_id = ?", Integer.class,
                deeper.getId())).as("the grant cascaded").isZero();

        assertThat(subDir).as("each file's own directory").doesNotExist();
        assertThat(tagDir).doesNotExist();
        assertThat(subDir.getParent()).as("the shard directory is shared and stays").exists();

        assertThat(actionHistoryService.getActionHistoriesOfEntity(chain.subCategoryId(), EntityEnum.Folder))
                .extracting(h -> h.getDescription())
                .anySatisfy(d -> assertThat(d).contains("2 folder(s)").contains("2 file(s)"));
        assertThat(actionHistoryService.getActionHistoriesOfEntity(inTag.getFileInfoId(), EntityEnum.FileInfo))
                .as("every file still gets its own audit row").isNotEmpty();
    }

    @Test
    @DisplayName("a file stored under the old shared layout loses its own directory and its neighbour keeps theirs")
    void anOldLayoutFileLeavesItsNeighbour() throws IOException {
        FileDetailsDTO stored = upload("old-report.txt", chain.tagId());
        Path shared = Paths.get(baseDir, "OldCat" + TestData.nextSequence(), "OldSub");
        Path own = shared.resolve(Paths.get("old-report", "v1", "old-report.txt"));
        Path neighbour = shared.resolve(Paths.get("other", "v1", "other.txt"));
        Files.createDirectories(own.getParent());
        Files.createDirectories(neighbour.getParent());
        Files.writeString(own, "old");
        Files.writeString(neighbour, "neighbour");
        FileDetails revision = fileDetailsRepository.findById(stored.getId()).orElseThrow();
        revision.setStorageKey(Paths.get(baseDir).relativize(own).toString().replace('\\', '/'));
        fileDetailsRepository.saveAndFlush(revision);
        entityManager.clear();

        underTest.deleteTree(chain.tagId(), adminId);
        entityManager.flush();

        assertThat(own.getParent().getParent()).doesNotExist();
        assertThat(neighbour).exists();
    }

    /**
     * The bytes never fail the delete. A file whose directory is already gone (lost in an
     * earlier partial failure, a restore without base-dir) is nothing to remove; were it an
     * error, the rows would roll back after the files before it had their bytes erased, and
     * every retry would erase more. So: all rows go, the other files' bytes go, the audit row is
     * written, and the missing one is logged.
     */
    @Test
    @DisplayName("a file whose bytes are already gone does not stop the tree: every row goes, every other file's bytes go")
    void aMissingDirectoryDoesNotStopTheTree() throws IOException {
        FileDetailsDTO kept = upload("kept.txt", chain.tagId());
        FileDetailsDTO lost = upload("lost.txt", chain.tagId());
        FileDetailsDTO alsoKept = upload("also-kept.txt", chain.subCategoryId());
        Path lostDir = Paths.get(baseDir).resolve(StorageLayout.directoryFor(lost.getFileInfoId()));
        Path keptDir = Paths.get(baseDir).resolve(StorageLayout.directoryFor(kept.getFileInfoId()));
        Path alsoKeptDir = Paths.get(baseDir).resolve(StorageLayout.directoryFor(alsoKept.getFileInfoId()));
        deleteRecursively(lostDir);
        assertThat(lostDir).doesNotExist();
        entityManager.flush();
        entityManager.clear();

        FolderTreeDeleteService.DeletedTree deleted = underTest.deleteTree(chain.subCategoryId(), adminId);
        entityManager.flush();
        entityManager.clear();

        assertThat(deleted.files()).isEqualTo(3);
        assertThat(fileInfoRepository.findById(lost.getFileInfoId())).isEmpty();
        assertThat(fileInfoRepository.findById(kept.getFileInfoId())).isEmpty();
        assertThat(fileInfoRepository.findById(alsoKept.getFileInfoId())).isEmpty();
        assertThat(folderRepository.findById(chain.subCategoryId())).isEmpty();
        assertThat(keptDir).doesNotExist();
        assertThat(alsoKeptDir).doesNotExist();
        assertThat(actionHistoryService.getActionHistoriesOfEntity(chain.subCategoryId(), EntityEnum.Folder))
                .extracting(h -> h.getDescription())
                .anySatisfy(d -> assertThat(d).contains("3 file(s)").doesNotContain("could not be removed"));
    }

    @Test
    @DisplayName("the same for a single file: one whose bytes are already gone is still deleted")
    void aSingleFileWithMissingBytesIsStillDeleted() throws IOException {
        FileDetailsDTO lost = upload("single-lost.txt", chain.tagId());
        Path lostDir = Paths.get(baseDir).resolve(StorageLayout.directoryFor(lost.getFileInfoId()));
        deleteRecursively(lostDir);
        entityManager.flush();
        entityManager.clear();

        fileService.deleteCompleteFileById(lost.getFileInfoId(), adminId);
        entityManager.flush();
        entityManager.clear();

        assertThat(fileInfoRepository.findById(lost.getFileInfoId())).isEmpty();
    }

    @Test
    @DisplayName("more files than one call may remove is a 409 that names the count, and nothing goes")
    void refusesATreeAboveTheCap() {
        for (int i = 0; i < 4; i++) {
            upload("many-" + i + ".txt", chain.tagId());
        }
        entityManager.flush();
        entityManager.clear();

        assertThatThrownBy(() -> underTest.deleteTree(chain.subCategoryId(), adminId))
                .isInstanceOf(DependencyResourceException.class)
                .hasMessageContaining("4 file(s)").hasMessageContaining("3");
        assertThat(folderRepository.findById(chain.tagId())).isPresent();
        assertThat(fileInfoRepository.countBySubtree(chain.subCategory().getPath())).isEqualTo(4);
        assertThat(underTest.maxDeleteFiles()).isEqualTo(3);
    }

    @Test
    @DisplayName("the root and a home folder are never deleted this way, and a missing folder is a 404")
    void refusesTheRootAHomeAndAMissingFolder() {
        Folder home = TestData.folder(admin, chain.subCategory(), "home" + TestData.nextSequence(), null);
        home.setKind(FolderKind.USER_HOME);
        home.setOwnerUser(admin);
        int homeId = folderRepository.saveAndFlush(TestData.placed(folderRepository.save(home))).getId();

        assertThatThrownBy(() -> underTest.deleteTree(FolderFixture.root(folderRepository).getId(), adminId))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("ROOT");
        assertThatThrownBy(() -> underTest.deleteTree(homeId, adminId))
                .isInstanceOf(InvalidDataException.class).hasMessageContaining("USER_HOME");
        assertThatThrownBy(() -> underTest.deleteTree(999_999, adminId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("removing a tree is a write into its parent: write on the folder itself is not enough, write on the parent covers everything beneath")
    void needsWriteAccessOnTheParent() {
        upload("guarded.txt", chain.tagId());
        entityManager.flush();
        entityManager.clear();

        grant(restrictedId, chain.subCategoryId(), FolderPermission.WRITE);
        assertThatThrownBy(() -> underTest.deleteTree(chain.subCategoryId(), restrictedId))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(folderRepository.findById(chain.tagId())).isPresent();

        grant(restrictedId, chain.categoryId(), FolderPermission.WRITE);
        underTest.deleteTree(chain.subCategoryId(), restrictedId);
        entityManager.flush();
        entityManager.clear();
        assertThat(folderRepository.findById(chain.subCategoryId())).isEmpty();
        assertThat(folderRepository.findById(chain.tagId())).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private FileDetailsDTO upload(String fileName, int folderId) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile(fileName, fileName, "text/plain", TestData.bytesFor(fileName)));
        return fileService.createNewFile(request, adminId, 1);
    }

    private FileUploadDTO versionRequest(int fileInfoId, String fileName, int version) {
        FileUploadDTO request = new FileUploadDTO();
        request.setFileId(fileInfoId);
        request.setFileName(fileName.substring(0, fileName.lastIndexOf('.')));
        request.setFileNameWithoutExtension(fileName.substring(0, fileName.lastIndexOf('.')));
        request.setVersion(version);
        request.setFileDetailsDescription("version " + version + " of " + fileName);
        request.setType("version");
        request.setMultipartFile(new MockMultipartFile(fileName, fileName, "text/plain", TestData.bytesFor(fileName)));
        return request;
    }

    private void grant(int userId, int folderId, FolderPermission permission) {
        User user = userRepository.findById(userId).orElseThrow();
        List<UserFolderGrant> grants = new ArrayList<>(user.getFolderGrants());
        grants.add(new UserFolderGrant(user, folderRepository.findById(folderId).orElseThrow(), permission));
        user.replaceFolderGrants(grants);
        userRepository.save(user);
        entityManager.flush();
        entityManager.clear();
    }
}
