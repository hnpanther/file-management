package com.hnp.filemanagement;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.folder.domain.FileTreeService;
import com.hnp.filemanagement.folder.domain.FolderContentService;
import com.hnp.filemanagement.folder.domain.FolderSearchDTO;
import com.hnp.filemanagement.folder.domain.TreeSearchHitDTO;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.domain.UserDTO;
import com.hnp.filemanagement.identity.domain.UserService;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.shared.util.SearchTerms;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code %} and {@code _} typed into a search box stand for themselves (issue 96).
 *
 * <p>Every search binds its term into {@code LIKE CONCAT('%', :term, '%')}; until 2.5.0 the term
 * went in as typed, so {@code %} matched every file and {@code _} any one character -
 * {@code CM_EDU} also found {@code CMXEDU}. Each search is asked here, through its service, for a
 * name with an underscore that has a look-alike without one, and for a bare {@code %}.
 */
@ServiceIntegrationTest
class SearchWildcardTest extends DatabaseSupport {

    @Autowired
    private FileService fileService;
    @Autowired
    private FolderContentService folderContentService;
    @Autowired
    private FileTreeService fileTreeService;
    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private EntityManager entityManager;

    private int adminId;
    private String token;
    private String underscored;
    private String lookalike;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        adminId = userRepository.save(admin).getId();
        int folderId = FolderFixture.chain(folderRepository, tagGroupRepository, admin).tagId();

        token = "wild" + TestData.nextSequence();
        underscored = token + "_edu";
        lookalike = token + "Xedu";
        upload(folderId, underscored + ".txt");
        upload(folderId, lookalike + ".txt");
        entityManager.flush();
    }

    @Test
    @DisplayName("the escape leaves letters alone and escapes %, _ and the escape character itself")
    void theEscape() {
        assertThat(SearchTerms.escapeLike("ABC")).isEqualTo("ABC");
        assertThat(SearchTerms.escapeLike("CM_EDU")).isEqualTo("CM\\_EDU");
        assertThat(SearchTerms.escapeLike("50%")).isEqualTo("50\\%");
        assertThat(SearchTerms.escapeLike("a\\b")).isEqualTo("a\\\\b");
        assertThat(SearchTerms.escapeLike("")).isEmpty();
        assertThat(SearchTerms.escapeLike(null)).isNull();
    }

    @Test
    @DisplayName("an underscore matches an underscore only - in the file list, the public list, the explorer and the tree")
    void anUnderscoreIsAnUnderscore() {
        assertThat(fileService.getPageFileInfo(50, 0, underscored, adminId).getContent())
                .extracting(FileInfoDTO::getFileName).containsExactly(underscored);
        assertThat(fileService.getPagePublicFiles(50, 0, underscored).getContent())
                .extracting(d -> d.getFileName()).containsExactly(underscored + ".txt");
        assertThat(folderContentService.search(underscored, null, 0, 50, adminId).hits())
                .extracting(hit -> hit.file().name()).containsExactly(underscored);
        assertThat(fileTreeService.search(underscored, adminId))
                .extracting(TreeSearchHitDTO::getFileName).containsExactly(underscored);
    }

    @Test
    @DisplayName("a bare % finds what holds a %, which is nothing here - not everything")
    void aPercentIsAPercent() {
        assertThat(fileService.getPageFileInfo(50, 0, "%", adminId).getContent()).isEmpty();
        assertThat(fileService.getPagePublicFiles(50, 0, "%").getContent()).isEmpty();
        FolderSearchDTO explorer = folderContentService.search("%", null, 0, 50, adminId);
        assertThat(explorer.hits()).isEmpty();
        assertThat(explorer.folders()).isEmpty();
        assertThat(fileTreeService.search("%", adminId)).isEmpty();
        assertThat(userService.getUserPage("%", 50, 0).getContent()).isEmpty();
        assertThat(userService.getUserPage("_", 50, 0).getContent()).extracting(UserDTO::getUsername)
                .allMatch(name -> name.contains("_"));
    }

    private FileDetailsDTO upload(int folderId, String fileName) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription("search wildcard");
        request.setFileNameDescription(fileName);
        request.setFolderId(folderId);
        request.setMultipartFile(new MockMultipartFile("file", fileName, "text/plain", TestData.bytesFor(fileName)));
        return fileService.createNewFile(request, adminId, FileService.PUBLIC);
    }
}
