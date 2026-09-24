package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.Tag;
import com.hnp.filemanagement.entity.TagGroup;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.MySqlSupport;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What PostgreSQL release A promises of the queries (roadmap 3.3, issue 86): every name compares
 * without case, every search finds a term in any case, and a size is 64 bits.
 *
 * <p><b>On MySQL these pass with or without the {@code UPPER} the queries now carry</b>, because the
 * {@code utf8mb4_unicode_ci} collation already compares that way - which is exactly the trap:
 * nothing on MySQL shows that a query depends on the collation. They are written for release B,
 * which runs the suite on both databases; there, a bare {@code =} or {@code LIKE} fails them.
 * Each name is stored in one case and asked for in another, never the same.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PortableQueriesTest extends MySqlSupport {

    private static final Pageable PAGE = PageRequest.of(0, 20);
    private static final long THREE_GIB = 3L * 1024 * 1024 * 1024;

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private EntityManager entityManager;

    private int n;
    private User creator;
    private FolderFixture.Chain chain;

    @BeforeEach
    void setUp() {
        n = TestData.nextSequence();
        creator = TestData.user();
        creator.setUsername("MixedCase" + n);
        creator.setFirstName("Firstname" + n);
        creator.setLastName("Lastname" + n);
        creator = userRepository.save(creator);
        chain = FolderFixture.chain(folderRepository, tagGroupRepository, creator);
    }

    // ================================================================ names looked up by equality

    @Test
    @DisplayName("a username is found in any case - the three lookups, sign-in among them")
    void usernames() {
        flushAndClear();

        assertThat(userRepository.existsByUsernameIgnoreCase("MIXEDCASE" + n)).isTrue();
        assertThat(userRepository.findByUsernameIgnoreCase("mixedcase" + n)).get()
                .extracting(User::getId).isEqualTo(creator.getId());
        assertThat(userRepository.findByUsernameWithRolesAndPermissions("mIXEDcASE" + n)).get()
                .extracting(User::getUsername).as("the stored spelling comes back").isEqualTo("MixedCase" + n);
        assertThat(userRepository.existsByUsernameIgnoreCase("MixedCase" + n + "x")).isFalse();
    }

    @Test
    @DisplayName("a role is found in any case")
    void roles() {
        roleRepository.save(TestData.role("Auditors" + n));
        flushAndClear();

        assertThat(roleRepository.findByRoleNameIgnoreCase("AUDITORS" + n)).isPresent();
        assertThat(roleRepository.existsByRoleNameIgnoreCase("auditors" + n)).isTrue();
        assertThat(roleRepository.findByRoleNameWithPermissions("aUDITORS" + n)).get()
                .extracting(Role::getRoleName).isEqualTo("Auditors" + n);
        assertThat(roleRepository.existsByRoleNameIgnoreCase("auditor" + n)).isFalse();
    }

    @Test
    @DisplayName("a tag group, and a tag within its group, are found in any case")
    void tags() {
        TagGroup group = tagGroupRepository.save(TestData.tagGroup(creator, "Projects" + n));
        tagRepository.save(tag(group, "Budget" + n));
        flushAndClear();

        assertThat(tagGroupRepository.findByNameIgnoreCase("PROJECTS" + n)).get()
                .extracting(TagGroup::getId).isEqualTo(group.getId());
        assertThat(tagRepository.findByGroupIdAndNameIgnoreCase(group.getId(), "budget" + n)).isPresent();
        assertThat(tagRepository.findByGroupIdAndNameIgnoreCase(chain.category().getTagGroup().getId(), "budget" + n))
                .as("only within its own group").isEmpty();
    }

    @Test
    @DisplayName("a file is found by its name in any case, in its own folder only - the duplicate check on upload")
    void fileNames() {
        FileInfo file = fileInfoRepository.save(TestData.fileInfo(creator, chain.tag(), "Quarterly Report " + n));
        flushAndClear();

        assertThat(fileInfoRepository.findByFolderIdAndFileName(chain.tagId(), "QUARTERLY REPORT " + n)).get()
                .extracting(FileInfo::getId).isEqualTo(file.getId());
        assertThat(fileInfoRepository.findByFolderIdAndFileNameWithDetails(chain.tagId(), "quarterly report " + n)).isPresent();
        assertThat(fileInfoRepository.findByFolderIdAndFileName(chain.subCategoryId(), "quarterly report " + n))
                .as("a name is unique per folder, not across them").isEmpty();
    }

    /**
     * The one of these that is about bytes as well as rows: {@code report.PDF} and
     * {@code report.pdf} at one version would be two keys naming one file on Windows.
     */
    @Test
    @DisplayName("a format is a duplicate of the same extension at the same version in any case")
    void formats() {
        FileInfo file = TestData.fileInfo(creator, chain.tag(), "report" + n);
        TestData.fileDetails(creator, file, 1, "pdf");
        file = fileInfoRepository.save(file);
        flushAndClear();

        assertThat(fileDetailsRepository.existsByFileInfoAndVersionAndFormat(file.getId(), 1, "PDF")).isTrue();
        assertThat(fileDetailsRepository.existsByFileInfoAndVersionAndFormat(file.getId(), 1, "Pdf")).isTrue();
        assertThat(fileDetailsRepository.existsByFileInfoAndVersionAndFormat(file.getId(), 2, "PDF")).as("another version").isFalse();
        assertThat(fileDetailsRepository.existsByFileInfoAndVersionAndFormat(file.getId(), 1, "DOCX")).as("another format").isFalse();
    }

    // ================================================================ searches

    @Test
    @DisplayName("every file search finds a term in another case - by name, by description, and by a folder above")
    void fileSearches() {
        FileInfo file = TestData.fileInfo(creator, chain.tag(), "Quarterly Report " + n);
        file.setDescription("Annual Summary " + n);
        TestData.fileDetails(creator, file, 1, "pdf");
        file = fileInfoRepository.save(file);
        int id = file.getId();
        Set<Integer> folder = Set.of(chain.tagId());
        String category = chain.category().getName().toUpperCase();
        flushAndClear();

        // The list page, whole and within folders: name, description, and a folder's name above.
        assertThat(fileInfoRepository.search("QUARTERLY report " + n, PAGE).getContent()).extracting(FileInfo::getId).contains(id);
        assertThat(fileInfoRepository.search("annual SUMMARY " + n, PAGE).getContent()).extracting(FileInfo::getId).contains(id);
        assertThat(fileInfoRepository.search(category, PAGE).getContent()).extracting(FileInfo::getId).contains(id);
        assertThat(fileInfoRepository.searchWithinFolders("quarterly REPORT " + n, folder, PAGE).getContent()).extracting(FileInfo::getId).containsExactly(id);
        assertThat(fileInfoRepository.searchWithinFolders(category.toLowerCase(), folder, PAGE).getContent()).extracting(FileInfo::getId).containsExactly(id);

        // The tree's and the explorer's search.
        assertThat(fileInfoRepository.searchForTree(null, "QUARTERLY REPORT " + n, PAGE)).extracting(FileInfo::getId).contains(id);
        assertThat(fileInfoRepository.searchFiles(null, "annual summary " + n, PAGE).getContent()).extracting(FileInfo::getId).contains(id);
        assertThat(fileInfoRepository.searchFilesWithinFolders(null, "QUARTERLY", folder, PAGE).getContent()).extracting(FileInfo::getId).containsExactly(id);

        // The public list, matched on the revision's own name and the folders' labels.
        assertThat(fileDetailsRepository.searchPublicFiles("QUARTERLY REPORT " + n, PAGE).getContent())
                .extracting(details -> details.getFileInfo().getId()).contains(id);
        assertThat(fileDetailsRepository.searchPublicFiles(chain.category().getDisplayName().toUpperCase(), PAGE).getContent())
                .extracting(details -> details.getFileInfo().getId()).contains(id);
    }

    @Test
    @DisplayName("the folder search finds a name and a label in another case")
    void folderSearch() {
        String rootPath = FolderFixture.root(folderRepository).getPath();
        flushAndClear();

        assertThat(folderRepository.searchFolders(null, chain.subCategory().getName().toUpperCase(), rootPath, PAGE))
                .extracting(Folder::getId).contains(chain.subCategoryId());
        assertThat(folderRepository.searchFolders(null, chain.subCategory().getDisplayName().toUpperCase(), rootPath, PAGE))
                .extracting(Folder::getId).contains(chain.subCategoryId());
    }

    @Test
    @DisplayName("the user search finds a username and a full name in another case")
    void userSearch() {
        flushAndClear();

        assertThat(userRepository.search(null, "mixedCASE" + n, PAGE).getContent()).extracting(User::getId).contains(creator.getId());
        assertThat(userRepository.search(null, "FIRSTNAME" + n + " lastname" + n, PAGE).getContent())
                .extracting(User::getId).contains(creator.getId());
    }

    /**
     * An empty box. The four list queries take the empty string for "everything"; they took
     * {@code null}, which PostgreSQL cannot type inside {@code LIKE CONCAT(...)} and refused on
     * every list page with nothing typed in it (issue 87). MySQL accepted either, so this is the
     * one that release B has to see pass there.
     */
    @Test
    @DisplayName("an empty term matches everything on the four list queries, and a number searches alone")
    void emptyTermsMatchEverything() {
        FileInfo file = TestData.fileInfo(creator, chain.tag(), "anything" + n);
        TestData.fileDetails(creator, file, 1, "pdf");
        int id = fileInfoRepository.save(file).getId();
        Pageable all = PageRequest.of(0, Integer.MAX_VALUE);
        flushAndClear();

        assertThat(fileInfoRepository.search("", all).getContent()).extracting(FileInfo::getId).contains(id);
        assertThat(fileInfoRepository.searchWithinFolders("", Set.of(chain.tagId()), all).getContent())
                .extracting(FileInfo::getId).containsExactly(id);
        assertThat(fileDetailsRepository.searchPublicFiles("", all).getContent())
                .extracting(details -> details.getFileInfo().getId()).contains(id);
        assertThat(userRepository.search(null, "", all).getContent()).extracting(User::getId).contains(creator.getId());
        assertThat(userRepository.search(creator.getId(), "", all).getContent())
                .as("the number alone").extracting(User::getId).containsExactly(creator.getId());
    }

    // ================================================================ the native query

    /**
     * The check that tags and the tree agree compares names without case, as the tags are looked
     * up. A file whose tags spell its folders in other cases agrees; a file missing one does not -
     * the second half is what shows the query can fail at all.
     */
    @Test
    @DisplayName("tags that spell the folders in another case agree with them; a missing tag still disagrees")
    void tagsAgreeWithTheFoldersWithoutCase() {
        TagGroup group = chain.category().getTagGroup();
        Tag category = tagRepository.save(tag(group, chain.category().getName().toUpperCase()));
        Tag subCategory = tagRepository.save(tag(group, chain.subCategory().getName().toLowerCase()));
        Tag leaf = tagRepository.save(tag(group, chain.tag().getName().toUpperCase()));

        FileInfo agrees = TestData.fileInfo(creator, chain.tag(), "agrees" + n);
        agrees.getTags().addAll(List.of(category, subCategory, leaf));
        agrees = fileInfoRepository.save(agrees);

        FileInfo missesOne = TestData.fileInfo(creator, chain.tag(), "misses" + n);
        missesOne.getTags().addAll(List.of(category, subCategory));
        missesOne = fileInfoRepository.save(missesOne);
        flushAndClear();

        List<Integer> disagreeing = fileInfoRepository.findIdsWhoseTagsDisagreeWithTheFolders();
        assertThat(disagreeing).doesNotContain(agrees.getId());
        assertThat(disagreeing).contains(missesOne.getId());
    }

    // ================================================================ sizes

    @Test
    @DisplayName("a size past what 32 bits hold is stored, read back and summed exactly")
    void sizesAreSixtyFourBits() {
        FileInfo file = TestData.fileInfo(creator, chain.tag(), "large" + n);
        FileDetails details = TestData.fileDetails(creator, file, 1, "zip");
        details.setFileSize(THREE_GIB);
        file = fileInfoRepository.save(file);
        int detailsId = details.getId();
        flushAndClear();

        assertThat(fileDetailsRepository.findById(detailsId)).get()
                .extracting(FileDetails::getFileSize).isEqualTo(THREE_GIB);
        assertThat(fileDetailsRepository.sumSizeOf(file.getId())).isEqualTo(THREE_GIB);
        assertThat(fileDetailsRepository.sumSizeUnder(chain.tag().getPath())).isEqualTo(THREE_GIB);
    }

    // ---------------------------------------------------------------- helpers

    private Tag tag(TagGroup group, String name) {
        Tag tag = new Tag();
        tag.setGroup(group);
        tag.setName(name);
        tag.setTitle(name);
        tag.setEnabled(1);
        tag.setCreatedBy(creator);
        return tag;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
