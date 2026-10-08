package com.hnp.filemanagement.content;

import com.hnp.filemanagement.file.domain.FileDetailsDTO;
import com.hnp.filemanagement.file.domain.FileInfoDTO;
import com.hnp.filemanagement.file.domain.FileService;
import com.hnp.filemanagement.file.domain.FileUploadDTO;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.folder.domain.FolderReadScope;
import com.hnp.filemanagement.folder.domain.UserFolderGrant;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.shared.exception.InvalidDataException;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "Search in contents" over what was read (roadmap 11.2): the right file, on the right page, its words
 * however typed; the latest version unless every one is asked for; and above all only what the
 * reader may open - not a result, not a page, not a snippet of anything else. The readings are
 * written as the worker writes them, so no Tika is needed.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.content-search.enabled=true"})
class ContentSearchTest extends DatabaseSupport {

    @Autowired
    private ContentSearchService underTest;
    @Autowired
    private FileService fileService;
    @Autowired
    private FileContentRepository repository;
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
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private NamedParameterJdbcTemplate named;

    private int adminId;
    private Folder folderA;
    private Folder folderB;
    /** A word no other test writes, so each search sees this test's rows only. */
    private String run;

    @BeforeEach
    void setUp() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        adminId = admin.getId();
        Folder root = FolderFixture.chain(folderRepository, tagGroupRepository, admin).subCategory();
        folderA = FolderFixture.tag(folderRepository, root, admin, "A" + TestData.nextSequence());
        folderB = FolderFixture.tag(folderRepository, root, admin, "B" + TestData.nextSequence());
        run = "رمز" + Long.toString(TestData.nextSequence() + 1000, 36).replaceAll("[0-9]", "");
    }

    @Test
    @DisplayName("found on the page it is on: a PDF's page 2 answered as page 2, page 1 not shown")
    void thePage() {
        FileDetailsDTO pdf = upload("report.txt", folderA);
        read(pdf.getId(), page(1, "PAGE", "صفحه اول درباره بودجه " + run), page(2, "PAGE", "صفحه دوم قرارداد مخزن " + run));

        var results = underTest.search(run + " مخزن", false, 0, 10, adminId);
        assertThat(results.items()).singleElement().satisfies(result -> {
            assertThat(result.hit().fileId()).isEqualTo(pdf.getFileInfoId());
            assertThat(result.pages()).extracting(ContentSearchService.PageMatch::pageNumber).containsExactly(2);
            assertThat(result.pages().getFirst().snippet().segments()).filteredOn(Snippets.Segment::match)
                    .extracting(Snippets.Segment::text).containsExactly("مخزن", run);
        });
        assertThat(underTest.search(run, false, 0, 10, adminId).items().getFirst().pages())
                .extracting(ContentSearchService.PageMatch::pageNumber).containsExactly(1, 2);
        assertThat(underTest.search(run + " بودجه مخزن", false, 0, 10, adminId).items())
                .as("every word on one page").isEmpty();
    }

    @Test
    @DisplayName("a word as a prefix, a phrase in order, Arabic letters and Persian digits found by what a person types")
    void howWordsMatch() {
        FileDetailsDTO file = upload("letter.txt", folderA);
        read(file.getId(), page(0, "WHOLE", run + " كتاب‌های قرارداد اجاره سال ۱۴۰۳ تجهیزات PMP-1201"));

        assertThat(found(run + " کتاب")).as("prefix, Arabic kaf").containsExactly(file.getId());
        assertThat(found(run + " کتابها")).as("half-space").containsExactly(file.getId());
        assertThat(found(run + " 1403")).as("Persian digits").containsExactly(file.getId());
        assertThat(found(run + " \"قرارداد اجاره\"")).as("phrase").containsExactly(file.getId());
        assertThat(found(run + " \"اجاره قرارداد\"")).as("phrase out of order").isEmpty();
        assertThat(found(run + " pmp-1201")).as("a code, any case").containsExactly(file.getId());
        assertThat(found(run + " مخزن")).isEmpty();
        assertThatThrownBy(() -> underTest.search("  !! ", false, 0, 10, adminId)).isInstanceOf(InvalidDataException.class);
    }

    @Test
    @DisplayName("every version is read; a search finds the latest unless every version is asked for")
    void versions() {
        FileDetailsDTO v1 = upload("plan.txt", folderA);
        FileUploadDTO next = new FileUploadDTO();
        next.setFileId(v1.getFileInfoId());
        String name = v1.getFileName().substring(0, v1.getFileName().lastIndexOf('.'));
        next.setFileName(name);
        next.setFileNameWithoutExtension(name);
        next.setVersion(2);
        next.setType("version");
        next.setFileDetailsDescription("v2");
        next.setMultipartFile(new MockMultipartFile("file", v1.getFileName(), "text/plain", "v2".getBytes(StandardCharsets.UTF_8)));
        fileService.createNewFileDetails(next, adminId);
        entityManager.flush();
        int v2 = jdbc.queryForObject("SELECT id FROM file_details WHERE file_info_id = ? AND version = 2", Integer.class,
                v1.getFileInfoId());
        read(v1.getId(), page(0, "WHOLE", run + " پیش‌نویس اولیه"));
        read(v2, page(0, "WHOLE", run + " نسخه نهایی"));

        assertThat(found(run)).as("the latest only").containsExactly(v2);
        assertThat(found(run + " پیش‌نویس")).as("an older version's words are not the file's now").isEmpty();
        var every = underTest.search(run + " پیش‌نویس", true, 0, 10, adminId).items();
        assertThat(every).singleElement().satisfies(result -> {
            assertThat(result.hit().fileDetailsId()).isEqualTo(v1.getId());
            assertThat(result.hit().version()).isEqualTo(1);
            assertThat(result.hit().lastVersion()).isEqualTo(2);
        });
        assertThat(underTest.search(run, true, 0, 10, adminId).items()).hasSize(2);
    }

    @Test
    @DisplayName("only what the reader may open: not a result, a page or a snippet of another folder; a move or a revoke is at once")
    void access() {
        FileDetailsDTO inA = upload("a.txt", folderA);
        FileDetailsDTO inB = upload("b.txt", folderB);
        read(inA.getId(), page(0, "WHOLE", run + " محرمانه الف"));
        read(inB.getId(), page(0, "WHOLE", run + " محرمانه ب"));
        User reader = userRepository.save(TestData.user());
        flushAndClear();

        assertThat(underTest.search(run, false, 0, 10, reader.getId()).items()).as("granted nothing").isEmpty();
        grant(reader, folderA.getId());
        var results = underTest.search(run + " محرمانه", false, 0, 10, reader.getId()).items();
        assertThat(results).extracting(r -> r.hit().fileDetailsId()).containsExactly(inA.getId());
        assertThat(results).allSatisfy(r -> assertThat(r.pages()).allSatisfy(p -> assertThat(p.snippet().text()).doesNotContain(" ب")));
        assertThat(underTest.search(run + " ب", false, 0, 10, reader.getId()).items()).as("B's word finds nothing").isEmpty();

        fileService.moveFile(inA.getFileInfoId(), folderB.getId(), adminId);
        flushAndClear();
        assertThat(underTest.search(run, false, 0, 10, reader.getId()).items()).as("moved out of reach").isEmpty();
        assertThat(underTest.search(run, false, 0, 10, adminId).items()).hasSize(2);
    }

    @Test
    @DisplayName("an API key finds only its own folders, never all of its creator's")
    void apiKeyScope() {
        FileDetailsDTO inA = upload("a.txt", folderA);
        FileDetailsDTO inB = upload("b.txt", folderB);
        read(inA.getId(), page(0, "WHOLE", run + " کلید"));
        read(inB.getId(), page(0, "WHOLE", run + " کلید"));
        int keyId = jdbc.queryForObject("""
                INSERT INTO api_key (key_id, secret_hash, title, enabled, created_at, created_by)
                VALUES (?, 'x', 'content search test', 1, now(), ?) RETURNING id""", Integer.class,
                "K" + TestData.nextSequence(), adminId);
        jdbc.update("INSERT INTO api_key_folder (api_key_id, folder_id, permission) VALUES (?, ?, 'READ')", keyId, folderB.getId());
        ContentQuery query = ContentQuery.of(run).orElseThrow();
        List<ContentSearch.Hit> hits = new PostgresContentSearch(named).search(query, FolderReadScope.ofApiKey(keyId, false), false, 0, 10);
        assertThat(hits).extracting(ContentSearch.Hit::fileDetailsId).containsExactly(inB.getId());
        assertThat(new PostgresContentSearch(named).search(query, FolderReadScope.ofApiKey(keyId, true), false, 0, 10)).isEmpty();
    }

    @Test
    @DisplayName("three pages shown with each result, the rest counted; a long page in parts is one page; a page at a time")
    void pagesAndPaging() {
        FileDetailsDTO book = upload("book.txt", folderA);
        List<FileContentRepository.PageRow> rows = new ArrayList<>();
        for (int p = 1; p <= 5; p++) {
            rows.add(page(p, "PAGE", "صفحه " + p + " " + run));
        }
        rows.add(new FileContentRepository.PageRow(1, 1, "PAGE", null, "TEXT", null, "ادامه " + run, ContentFolding.fold("ادامه " + run)));
        read(book.getId(), rows.toArray(FileContentRepository.PageRow[]::new));
        var result = underTest.search(run, false, 0, 10, adminId).items().getFirst();
        assertThat(result.pages()).extracting(ContentSearchService.PageMatch::pageNumber).containsExactly(1, 2);
        assertThat(result.hit().pages()).isEqualTo(5);
        assertThat(result.morePages()).isEqualTo(3);

        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            FileDetailsDTO file = upload("f" + i + ".txt", folderA);
            read(file.getId(), page(0, "WHOLE", run + " صفحه‌بندی"));
            all.add(file.getId());
        }
        List<Integer> seen = new ArrayList<>();
        for (int page = 0; ; page++) {
            var slice = underTest.search(run + " صفحه‌بندی", false, page, 2, adminId);
            assertThat(slice.items()).hasSizeLessThanOrEqualTo(2);
            slice.items().forEach(r -> seen.add(r.hit().fileDetailsId()));
            if (!slice.hasNext()) {
                break;
            }
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(all).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the search is planned on the GIN index of the pages, never by reading them all")
    void plannedOnTheIndex() {
        FolderReadScope restricted = FolderReadScope.ofUser(adminId, false);
        MapSqlParameterSource parameters = FileContentRepository.scoped(restricted)
                .addValue("query", "'قرارداد':*").addValue("cap", PostgresContentSearch.MOST_PAGES_RANKED).addValue("limit", 21).addValue("offset", 0L);
        // As SearchIndexTest plans: only scans with an index condition left, so that a near-empty table
        // cannot be read whole - the question is whether the index can serve the search.
        jdbc.execute("SET LOCAL enable_seqscan = off");
        jdbc.execute("SET LOCAL enable_indexscan = off");
        jdbc.execute("SET LOCAL enable_indexonlyscan = off");
        String plan = String.join("\n", named.queryForList("EXPLAIN " + PostgresContentSearch.searchSql(restricted, false, false),
                parameters, String.class));
        assertThat(plan).contains("ix_file_content_page_search");
        assertThat(String.join("\n", named.queryForList("EXPLAIN " + PostgresContentSearch.searchSql(FolderReadScope.everything(),
                true, false), parameters, String.class))).contains("ix_file_content_page_search");
    }

    @Test
    @DisplayName("a word on more pages than are ranked is answered from the newest of them, and says so; a selective one is not")
    void broadIsBounded() {
        FileDetailsDTO file = upload("probe.txt", folderA);
        read(file.getId(), page(0, "WHOLE", run + " انتخابی"));
        PostgresContentSearch engine = new PostgresContentSearch(named);
        assertThat(engine.isBroad(ContentQuery.of(run).orElseThrow())).isFalse();
        var results = underTest.search(run, false, 0, 10, adminId);
        assertThat(results.limited()).isFalse();
        assertThat(results.items()).singleElement().satisfies(r -> assertThat(r.hit().limited()).isFalse());
    }

    // ================================================================ helpers

    private List<Integer> found(String typed) {
        return underTest.search(typed, false, 0, 50, adminId).items().stream().map(r -> r.hit().fileDetailsId()).toList();
    }

    private static FileContentRepository.PageRow page(int number, String unit, String text) {
        return new FileContentRepository.PageRow(number, 0, unit, null, "TEXT", null, text, ContentFolding.fold(text));
    }

    /** A reading written as the worker writes one: queued, claimed, finished with its pages. */
    private void read(int fileDetailsId, FileContentRepository.PageRow... pages) {
        entityManager.flush();
        repository.enqueue(fileDetailsId, FileContentRepository.PRIORITY_NEW, Instant.now());
        jdbc.update("UPDATE file_content SET state = 'READING', lease_until = now() + interval '1 hour' WHERE file_details_id = ?",
                fileDetailsId);
        long characters = 0;
        for (FileContentRepository.PageRow row : pages) {
            characters += row.text().length();
        }
        assertThat(repository.finish(fileDetailsId, new FileContentRepository.Outcome("DONE", "TEXT", "text/plain", null,
                false, null, null, characters), List.of(pages), Instant.now())).isTrue();
    }

    private FileDetailsDTO upload(String fileName, Folder folder) {
        FileInfoDTO request = new FileInfoDTO();
        request.setDescription(fileName);
        request.setFileNameDescription(fileName);
        request.setFolderId(folder.getId());
        String unique = fileName.replace(".txt", "-" + TestData.nextSequence() + ".txt");
        request.setMultipartFile(new MockMultipartFile("file", unique, "text/plain", ("content of " + unique).getBytes(StandardCharsets.UTF_8)));
        FileDetailsDTO uploaded = fileService.createNewFile(request, adminId, FileService.PRIVATE);
        entityManager.flush();
        return uploaded;
    }

    private void grant(User user, int folder) {
        User loaded = userRepository.findById(user.getId()).orElseThrow();
        List<UserFolderGrant> grants = new ArrayList<>(loaded.getFolderGrants());
        grants.add(new UserFolderGrant(loaded, folderRepository.findById(folder).orElseThrow(), FolderPermission.READ));
        loaded.replaceFolderGrants(grants);
        userRepository.save(loaded);
        flushAndClear();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
