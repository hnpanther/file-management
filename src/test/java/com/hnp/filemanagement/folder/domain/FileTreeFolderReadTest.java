package com.hnp.filemanagement.folder.domain;

import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.folder.domain.TreeNodeDTO.NodeType;
import com.hnp.filemanagement.file.domain.FileInfo;
import com.hnp.filemanagement.identity.domain.User;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tree read from the folder table alone: a folder node's children (folders and files
 * together, since {@code V2.9}) and its count, opening a file, and placing a search hit on the
 * branch down to it.
 *
 * <p>The oracle is the rows: the files whose {@code folder_id} is the folder, the folder's own
 * ancestors read off its path, and those rows' display names.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FileTreeFolderReadTest extends DatabaseSupport {

    @Autowired
    private FileTreeService underTest;
    @Autowired
    private FileService fileService;

    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private int readerId;
    private FolderFixture.Chain chain;
    private int subAId;
    private int tagA1;
    private int tagA2;
    private String token;
    private FileDetailsDTO alpha;
    private FileDetailsDTO beta;
    private FileDetailsDTO gamma;

    @BeforeEach
    void setUp() {
        var adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();
        readerId = userRepository.save(TestData.user()).getId();

        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        subAId = chain.subCategoryId();
        tagA1 = chain.tagId();
        tagA2 = FolderFixture.tag(folderRepository, chain.subCategory(), admin, "TagA2" + TestData.nextSequence()).getId();

        // A token of this run in every name: the search below is unrestricted for the
        // administrator and would otherwise also find "report" files that committed tests left
        // behind, which depends on the order the classes ran in.
        token = "rep" + TestData.nextSequence();
        alpha = upload("alpha-" + token + ".txt", tagA1);
        beta = upload("beta-" + token + ".txt", tagA1);
        gamma = upload("gamma-" + token + ".txt", tagA2);
        flushAndClear();
    }

    // ---------------------------------------------------------------- a tag node

    @Test
    @DisplayName("a folder node's children are its folders and then its files, and a child's count is both together")
    void aFolderNodeListsAndCountsItsContents() {
        List<TreeNodeDTO> children = underTest.getChildren(NodeType.FOLDER, tagA1, adminId);

        assertThat(children).extracting(TreeNodeDTO::getName)
                .containsExactlyElementsOf(expectedNamesUnder(tagA1).stream().sorted().toList());
        assertThat(children).extracting(TreeNodeDTO::getName)
                .containsExactly("alpha-" + token, "beta-" + token);

        List<TreeNodeDTO> tags = underTest.getChildren(NodeType.FOLDER, subAId, adminId);
        assertThat(tags).filteredOn(node -> node.getId() == tagA1).singleElement()
                .extracting(TreeNodeDTO::getChildCount).isEqualTo(2);
        assertThat(tags).filteredOn(node -> node.getId() == tagA2).singleElement()
                .extracting(TreeNodeDTO::getChildCount).isEqualTo(1);

        // Since V2.9 a folder holds folders and files together: a folder under tagA1 is listed
        // before its files, and counted with them on the parent.
        Folder deeper = FolderFixture.tag(folderRepository, chain.tag(), adminUser(), "Deeper" + TestData.nextSequence());
        flushAndClear();
        assertThat(underTest.getChildren(NodeType.FOLDER, tagA1, adminId))
                .extracting(TreeNodeDTO::getType)
                .containsExactly(NodeType.FOLDER, NodeType.FILE, NodeType.FILE);
        assertThat(underTest.getChildren(NodeType.FOLDER, subAId, adminId))
                .filteredOn(node -> node.getId() == tagA1).singleElement()
                .extracting(TreeNodeDTO::getChildCount).isEqualTo(3);
        assertThat(deeper.getDepth()).isEqualTo(4);
    }

    @Test
    @DisplayName("a top-level folder carries its group's title as its note")
    void aTopLevelFolderCarriesItsGroupTitle() {
        assertThat(underTest.getRoots(adminId))
                .filteredOn(node -> node.getId() == chain.categoryId()).singleElement()
                .satisfies(node -> {
                    assertThat(node.getType()).isEqualTo(NodeType.FOLDER);
                    assertThat(node.getNote()).isEqualTo(chain.category().getTagGroup().getTitle());
                    assertThat(node.getChildCount()).isEqualTo(1);
                });
    }

    // ---------------------------------------------------------------- opening a file

    @Test
    @DisplayName("opening a file is authorised by the file's own folder: refused without a grant on it, allowed with one")
    void openingAFileIsAuthorisedByItsFolder() {
        assertThatThrownBy(() -> underTest.getChildren(NodeType.FILE, alpha.getFileInfoId(), readerId))
                .isInstanceOf(AccessDeniedException.class);

        grant(readerId, tagA1, FolderPermission.READ);

        assertThat(underTest.getChildren(NodeType.FILE, alpha.getFileInfoId(), readerId))
                .as("the versions of a file in the granted folder").isNotEmpty();
        assertThatThrownBy(() -> underTest.getChildren(NodeType.FILE, gamma.getFileInfoId(), readerId))
                .as("a file under the sibling tag is still refused")
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------- search

    @Test
    @DisplayName("a search hit carries the folder ids and display names of the branch the file sits on")
    void aSearchHitIsPlacedOnItsBranch() {
        List<TreeSearchHitDTO> hits = underTest.search("gamma-" + token, adminId);

        assertThat(hits).hasSize(1);
        TreeSearchHitDTO hit = hits.getFirst();
        FileInfo file = fileInfoRepository.findById(gamma.getFileInfoId()).orElseThrow();
        Folder tag = file.getFolder();
        Folder subCategory = tag.getParent();
        Folder category = subCategory.getParent();
        assertThat(tag.getId()).isEqualTo(tagA2);
        assertThat(hit.getFolderIds()).containsExactly(category.getId(), subCategory.getId(), tag.getId());
        assertThat(hit.getFolderTitles()).containsExactly(
                category.getDisplayName(), subCategory.getDisplayName(), tag.getDisplayName());
    }

    @Test
    @DisplayName("search offers only the hits whose folder the person may read")
    void searchIsBoundedByTheFilesFolder() {
        assertThat(underTest.search(token, readerId)).as("no grant, no hits").isEmpty();

        grant(readerId, tagA2, FolderPermission.READ);

        assertThat(underTest.search(token, readerId)).extracting(TreeSearchHitDTO::getFileName)
                .containsExactly("gamma-" + token);
        assertThat(underTest.search(token, adminId)).extracting(TreeSearchHitDTO::getFileName)
                .containsExactlyInAnyOrder("alpha-" + token, "beta-" + token, "gamma-" + token);
    }

    // ---------------------------------------------------------------- the oracle

    private List<String> expectedNamesUnder(int folderId) {
        return fileInfoRepository.findAll().stream()
                .filter(f -> f.getFolder().getId().equals(folderId))
                .map(FileInfo::getFileName)
                .toList();
    }

    // ---------------------------------------------------------------- fixtures

    private void grant(int userId, int folderId, FolderPermission permission) {
        User user = userRepository.findById(userId).orElseThrow();
        user.replaceFolderGrants(List.of(new UserFolderGrant(user,
                folderRepository.findById(folderId).orElseThrow(), permission)));
        userRepository.save(user);
        flushAndClear();
    }

    private FileDetailsDTO upload(String fileName, int folderId) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain",
                ("content of " + fileName).getBytes(StandardCharsets.UTF_8)));
        return fileService.createNewFile(request, adminId, 1);
    }

    private User adminUser() {
        return userRepository.findById(adminId).orElseThrow();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
