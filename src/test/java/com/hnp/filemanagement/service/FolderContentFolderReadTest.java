package com.hnp.filemanagement.service;

import com.hnp.filemanagement.dto.FileInfoDTO;
import com.hnp.filemanagement.dto.FolderContentDTO;
import com.hnp.filemanagement.dto.FolderSearchDTO;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.FolderPermission;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.entity.UserFolderGrant;
import com.hnp.filemanagement.repository.FileInfoRepository;
import com.hnp.filemanagement.repository.FolderRepository;
import com.hnp.filemanagement.repository.RoleRepository;
import com.hnp.filemanagement.repository.TagGroupRepository;
import com.hnp.filemanagement.repository.UserRepository;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The explorer read from the folder table alone (Phase 7 step 4): a tag folder's files, the
 * counts on its parent, search placement and folder access.
 *
 * <p>The expected answers are built from the rows themselves - "the files whose {@code folder_id}
 * is this folder" - and compared with what the service returns, so the assertion is about the data
 * and not about the implementation. A file without a folder cannot exist any more
 * ({@code folder_id} is NOT NULL), so the fail-closed case this class used to hold is gone with
 * the column's nullability.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = "filemanagement.folder-access.enabled=true")
class FolderContentFolderReadTest extends MySqlSupport {

    @Autowired
    private FolderContentService underTest;
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
    private FolderFixture.Chain chain;
    private int subAId;
    private String token;
    private int tagA1;
    private int tagA2;
    private int tagB1;

    @BeforeEach
    void setUp() {
        var adminRole = roleRepository.save(TestData.role("ADMIN"));
        User admin = TestData.user();
        admin.getRoles().add(adminRole);
        adminId = userRepository.save(admin).getId();

        chain = FolderFixture.chain(folderRepository, tagGroupRepository, admin);
        subAId = chain.subCategoryId();
        tagA1 = chain.tagId();
        tagA2 = FolderFixture.tag(folderRepository, chain.subCategory(), admin, "TagA2" + TestData.nextSequence()).getId();
        Folder subB = FolderFixture.subCategory(folderRepository, chain.category(), admin, "SubB" + TestData.nextSequence());
        tagB1 = FolderFixture.tag(folderRepository, subB, admin, "TagB1" + TestData.nextSequence()).getId();

        // A token of this run in every name: the administrator's search is unrestricted and would
        // otherwise also find "report" files that committed tests left behind - an order dependency.
        token = "rep" + TestData.nextSequence();
        upload("alpha-" + token + ".txt", tagA1);
        upload("beta-" + token + ".txt", tagA1);
        upload("gamma-" + token + ".txt", tagA2);
        upload("delta-note.txt", tagB1);
        upload("epsilon-" + token + ".txt", tagB1);
        flushAndClear();
    }

    // ---------------------------------------------------------------- what a folder holds

    @Test
    @DisplayName("a tag folder lists exactly the files filed under it")
    void aFolderListsItsFiles() {
        FolderContentDTO content = underTest.contentOf(tagA1, 0, 50, adminId);

        assertThat(names(content)).containsExactlyInAnyOrderElementsOf(expectedNames(tagA1));
        assertThat(names(content)).containsExactlyInAnyOrder("alpha-" + token, "beta-" + token);
        assertThat(content.page().totalElements()).isEqualTo(2);
        assertThat(names(underTest.contentOf(tagA2, 0, 50, adminId)))
                .containsExactlyInAnyOrderElementsOf(expectedNames(tagA2));
    }

    @Test
    @DisplayName("a sub-category's children carry the file count of each tag beneath them")
    void childCountsMatchTheRows() {
        FolderContentDTO content = underTest.contentOf(subAId, 0, 50, adminId);

        Map<String, Long> counts = content.folders().stream()
                .collect(Collectors.toMap(FolderContentDTO.FolderEntry::name, FolderContentDTO.FolderEntry::fileCount));
        assertThat(counts).containsEntry(folderName(tagA1), 2L).containsEntry(folderName(tagA2), 1L);
        // A sub-category cannot hold files, and the response says so by kind rather than by an empty list.
        assertThat(content.files()).isEmpty();
    }

    @Test
    @DisplayName("a category entry under the root carries its tag group's title as its note")
    void aCategoryCarriesItsGroupTitle() {
        FolderContentDTO root = underTest.contentOf(FolderFixture.root(folderRepository).getId(), 0, 50, adminId);

        assertThat(root.folders()).filteredOn(entry -> entry.id() == chain.categoryId()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.kind()).isEqualTo("FOLDER");
                    assertThat(entry.note()).isEqualTo(chain.category().getTagGroup().getTitle());
                    assertThat(entry.folderCount()).isEqualTo(2L);
                });
    }

    @Test
    @DisplayName("a search places every hit in the file's own folder, within the scope asked for")
    void searchPlacesHitsInTheirFolder() {
        FolderSearchDTO everywhere = underTest.search(token, null, 0, 50, adminId);
        FolderSearchDTO withinSubA = underTest.search(token, subAId, 0, 50, adminId);

        assertThat(everywhere.hits()).extracting(hit -> hit.file().name())
                .containsExactlyInAnyOrder("alpha-" + token, "beta-" + token, "gamma-" + token, "epsilon-" + token);
        assertThat(everywhere.hits()).allSatisfy(hit ->
                assertThat(hit.folder().id()).isEqualTo(
                        fileInfoRepository.findById(hit.file().id()).orElseThrow().getFolder().getId()));
        assertThat(withinSubA.hits()).extracting(hit -> hit.file().name())
                .containsExactlyInAnyOrder("alpha-" + token, "beta-" + token, "gamma-" + token);
    }

    @Test
    @DisplayName("a reader granted one folder sees that folder's files, its count, and only its search hits")
    void folderAccessFiltersByTheFilesFolder() {
        User reader = userRepository.save(TestData.user());
        reader.replaceFolderGrants(List.of(new UserFolderGrant(reader,
                folderRepository.findById(tagA2).orElseThrow(), FolderPermission.READ)));
        userRepository.save(reader);
        flushAndClear();

        assertThat(names(underTest.contentOf(tagA2, 0, 50, reader.getId())))
                .containsExactlyInAnyOrderElementsOf(expectedNames(tagA2));
        assertThat(underTest.search(token, null, 0, 50, reader.getId()).hits())
                .extracting(hit -> hit.file().name())
                .containsExactly("gamma-" + token);
    }

    // ---------------------------------------------------------------- the oracle

    private List<String> expectedNames(int folderId) {
        return fileInfoRepository.findAll().stream()
                .filter(f -> f.getFolder().getId().equals(folderId))
                .map(FileInfo::getFileName)
                .toList();
    }

    private static List<String> names(FolderContentDTO content) {
        return content.files().stream().map(FolderContentDTO.FileEntry::name).toList();
    }

    private String folderName(int folderId) {
        return folderRepository.findById(folderId).orElseThrow().getName();
    }

    // ---------------------------------------------------------------- fixtures

    private void upload(String fileName, int folderId) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("description of " + fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain",
                ("content of " + fileName).getBytes(StandardCharsets.UTF_8)));
        fileService.createNewFile(request, adminId, 1);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
