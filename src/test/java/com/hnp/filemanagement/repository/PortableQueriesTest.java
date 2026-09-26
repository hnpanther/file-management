package com.hnp.filemanagement.repository;

import com.hnp.filemanagement.entity.FileDetails;
import com.hnp.filemanagement.entity.FileInfo;
import com.hnp.filemanagement.entity.Folder;
import com.hnp.filemanagement.entity.Role;
import com.hnp.filemanagement.entity.Tag;
import com.hnp.filemanagement.entity.TagGroup;
import com.hnp.filemanagement.entity.User;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.util.SearchKey;
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
 *
 * <p><b>The searches are different since 1.8.0</b> (V2.16): they compare folded key columns
 * ({@link SearchKey}), which are binary on MySQL, so the collation no longer helps them and these
 * tests prove the fold on MySQL too. A search is handed its term folded, as the services do -
 * {@link #key} - and the Persian cases (the half-space, the digits' script, Arabic letters, a
 * space typed where the name has a half-space) are asked for in every form but the stored one.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PortableQueriesTest extends DatabaseSupport {

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
        assertThat(fileInfoRepository.search(key("quarterly report " + n), PAGE).getContent()).extracting(FileInfo::getId).contains(id);
        assertThat(fileInfoRepository.search(key("annual summary " + n), PAGE).getContent()).extracting(FileInfo::getId).contains(id);
        assertThat(fileInfoRepository.search(key(category), PAGE).getContent()).extracting(FileInfo::getId).contains(id);
        assertThat(fileInfoRepository.searchWithinFolders(key("quarterly REPORT " + n), folder, PAGE).getContent()).extracting(FileInfo::getId).containsExactly(id);
        assertThat(fileInfoRepository.searchWithinFolders(key(category.toLowerCase()), folder, PAGE).getContent()).extracting(FileInfo::getId).containsExactly(id);

        // The tree's and the explorer's search.
        assertThat(fileInfoRepository.searchForTree(null, key("quarterly report " + n), PAGE)).extracting(FileInfo::getId).contains(id);
        assertThat(fileInfoRepository.searchFiles(null, key("annual summary " + n), PAGE).getContent()).extracting(FileInfo::getId).contains(id);
        assertThat(fileInfoRepository.searchFilesWithinFolders(null, key("quarterly"), folder, PAGE).getContent()).extracting(FileInfo::getId).containsExactly(id);

        // The public list, matched on the revision's own name and the folders' labels.
        assertThat(fileDetailsRepository.searchPublicFiles(key("quarterly report " + n), PAGE).getContent())
                .extracting(details -> details.getFileInfo().getId()).contains(id);
        assertThat(fileDetailsRepository.searchPublicFiles(key(chain.category().getDisplayName().toLowerCase()), PAGE).getContent())
                .extracting(details -> details.getFileInfo().getId()).contains(id);

        // The raw term in another case finds nothing: the columns are binary, the fold does it all.
        assertThat(fileInfoRepository.search("quarterly report " + n, PAGE).getContent()).extracting(FileInfo::getId).doesNotContain(id);
    }

    @Test
    @DisplayName("the folder search finds a name and a label in another case")
    void folderSearch() {
        String rootPath = FolderFixture.root(folderRepository).getPath();
        flushAndClear();

        assertThat(folderRepository.searchFolders(null, key(chain.subCategory().getName().toLowerCase()), rootPath, PAGE))
                .extracting(Folder::getId).contains(chain.subCategoryId());
        assertThat(folderRepository.searchFolders(null, key(chain.subCategory().getDisplayName().toLowerCase()), rootPath, PAGE))
                .extracting(Folder::getId).contains(chain.subCategoryId());
    }

    // ================================================================ Persian (issue 86, 1.8.0)

    /** {@code گزارش‌های ۱۴۰۳}: a half-space and Persian digits, as a Persian keyboard writes it. */
    private static final String REPORTS_1403 = "\u06af\u0632\u0627\u0631\u0634\u200c\u0647\u0627\u06cc \u06f1\u06f4\u06f0\u06f3";

    @Test
    @DisplayName("a Persian name is found typed without the half-space, with a space for it, with ASCII or Arabic-Indic digits, or with Arabic yeh")
    void persianSearches() {
        FileInfo file = TestData.fileInfo(creator, chain.tag(), REPORTS_1403 + " " + n);
        file.setDescription("\u0628\u0648\u062f\u062c\u0647\u0654 \u0633\u0627\u0644 \u06f1\u06f4\u06f0\u06f3"); // بودجهٔ سال ۱۴۰۳
        TestData.fileDetails(creator, file, 1, "pdf");
        file = fileInfoRepository.save(file);
        int id = file.getId();
        Set<Integer> folder = Set.of(chain.tagId());
        flushAndClear();

        List<String> typed = List.of(
                "\u06af\u0632\u0627\u0631\u0634\u0647\u0627\u06cc",          // گزارشهای - no half-space
                "\u06af\u0632\u0627\u0631\u0634 \u0647\u0627\u06cc",         // گزارش های - a plain space
                "\u06af\u0632\u0627\u0631\u0634\u0647\u0627\u064a 1403",     // گزارشهاي 1403 - Arabic yeh, ASCII digits
                "\u0661\u0664\u0660\u0663",                                   // ١٤٠٣ - Arabic-Indic digits
                "1403 " + n);
        for (String term : typed) {
            assertThat(fileInfoRepository.search(key(term), PAGE).getContent()).as(term).extracting(FileInfo::getId).contains(id);
            assertThat(fileInfoRepository.searchWithinFolders(key(term), folder, PAGE).getContent()).as(term).extracting(FileInfo::getId).contains(id);
            assertThat(fileInfoRepository.searchForTree(null, key(term), PAGE)).as(term).extracting(FileInfo::getId).contains(id);
            assertThat(fileInfoRepository.searchFiles(null, key(term), PAGE).getContent()).as(term).extracting(FileInfo::getId).contains(id);
            assertThat(fileInfoRepository.searchFilesWithinFolders(null, key(term), folder, PAGE).getContent()).as(term).extracting(FileInfo::getId).contains(id);
            assertThat(fileDetailsRepository.searchPublicFiles(key(term), PAGE).getContent()).as(term)
                    .extracting(details -> details.getFileInfo().getId()).contains(id);
        }

        // The description: بودجه without its hamza, and the year in ASCII.
        assertThat(fileInfoRepository.searchFiles(null, key("\u0628\u0648\u062f\u062c\u0647 \u0633\u0627\u0644 1403"), PAGE).getContent())
                .extracting(FileInfo::getId).contains(id);
        // And something the name does not say is still not found.
        assertThat(fileInfoRepository.searchFilesWithinFolders(null, key("1404"), folder, PAGE).getContent()).isEmpty();
    }

    @Test
    @DisplayName("a Persian folder name and label are found in every writing, and so are the files beneath")
    void persianFolderSearches() {
        Folder persian = TestData.folder(creator, chain.tag(), "\u0627\u0633\u0646\u0627\u062f\u06f1\u06f4\u06f0\u06f3x" + n, null); // اسناد۱۴۰۳
        persian.setDisplayName("\u0645\u06cc\u200c\u062e\u0648\u0627\u0647\u0645 " + n);                                    // می‌خواهم
        persian = TestData.placed(folderRepository.save(persian));
        FileInfo file = TestData.fileInfo(creator, persian, "plain" + n);
        TestData.fileDetails(creator, file, 1, "pdf");
        file = fileInfoRepository.save(file);
        String rootPath = FolderFixture.root(folderRepository).getPath();
        flushAndClear();

        assertThat(folderRepository.searchFolders(null, key("\u0627\u0633\u0646\u0627\u062f1403X" + n), rootPath, PAGE))
                .extracting(Folder::getId).containsExactly(persian.getId());
        assertThat(folderRepository.searchFolders(null, key("\u0645\u06cc\u062e\u0648\u0627\u0647\u0645 " + n), rootPath, PAGE))
                .extracting(Folder::getId).containsExactly(persian.getId());
        assertThat(fileInfoRepository.search(key("\u0645\u06cc \u062e\u0648\u0627\u0647\u0645 " + n), PAGE).getContent())
                .as("a file under the folder, by the folder's label").extracting(FileInfo::getId).containsExactly(file.getId());
        assertThat(fileDetailsRepository.searchPublicFiles(key("\u0645\u06cc\u062e\u0648\u0627\u0647\u0645" + n), PAGE).getContent())
                .extracting(details -> details.getFileInfo().getId()).containsExactly(file.getId());
    }

    @Test
    @DisplayName("names that differ only by the half-space, the digits' script, case or Arabic letters are one name in a folder; a space still counts")
    void persianNameUniqueness() {
        fileInfoRepository.save(TestData.fileInfo(creator, chain.tag(), REPORTS_1403 + " " + n));
        Folder child = TestData.placed(folderRepository.save(TestData.folder(creator, chain.tag(), "Arch\u06f1\u06f4\u06f0\u06f3x" + n, null)));
        flushAndClear();

        int folder = chain.tagId();
        assertThat(fileInfoRepository.existsByFolderIdAndSearchName(folder,
                SearchKey.of("\u06af\u0632\u0627\u0631\u0634\u0647\u0627\u064a 1403 " + n))).isTrue();
        assertThat(fileInfoRepository.existsByFolderIdAndSearchName(folder,
                SearchKey.of("\u06af\u0632\u0627\u0631\u0634 \u0647\u0627\u06cc 1403 " + n))).as("a space is not a half-space").isFalse();
        assertThat(fileInfoRepository.existsByFolderIdAndSearchName(chain.subCategoryId(),
                SearchKey.of(REPORTS_1403 + " " + n))).as("per folder").isFalse();

        assertThat(folderRepository.findByParentIdAndSearchName(chain.tagId(), SearchKey.of("ARCH1403X" + n)))
                .extracting(Folder::getId).containsExactly(child.getId());
    }

    /** A term as the services hand it to these queries. */
    private static String key(String term) {
        return SearchKey.forSearch(term);
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
