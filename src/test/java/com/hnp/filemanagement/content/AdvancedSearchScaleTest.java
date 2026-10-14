package com.hnp.filemanagement.content;

import com.hnp.filemanagement.content.ContentSearchService.SearchRequest;
import com.hnp.filemanagement.folder.domain.FolderAccessService;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderPermission;
import com.hnp.filemanagement.folder.domain.FolderReadScope;
import com.hnp.filemanagement.folder.domain.UserFolderGrant;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
import com.hnp.filemanagement.identity.persistence.RoleRepository;
import com.hnp.filemanagement.identity.persistence.UserRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.FolderFixture;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestData;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The advanced search over 100,000 pages (roadmap 12.8): ten thousand files of ten pages in a hundred
 * folders under ten, each revision with its metadata, each top-level folder with its own - and every
 * way a search is answered measured, as an administrator and as a reader granted half the archive:
 * the files alone (a metadata value, a folder, a name, a folder's metadata, kinds and days), the words
 * first, the files first, the newest pages. Each asserts the way {@code pathOf} chose, so that a change
 * of the probes is seen here and not in production. Measured 2026-10-13: the files alone 14-22 ms (a
 * reader 14-45), the words first 28-44, the files first 66-239 (a reader 132-203), the newest pages of
 * 9,000 revisions 311 - a broad word's probe about 130 of that. The times are printed and bounded generously - a
 * shared database in a container, not a benchmark. The rows are the test's transaction's, rolled back.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.content-search.enabled=true"})
@Tag("scale")
class AdvancedSearchScaleTest extends DatabaseSupport {

    static final int FILES = 10_000;
    static final int PAGES = 10;
    static final long BOUND_MS = 3_000;

    @Autowired
    private ContentSearchService search;
    @Autowired
    private ContentSearch contentSearch;
    @Autowired
    private FolderAccessService folderAccessService;
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
    @Autowired
    private JdbcTemplate jdbc;

    private String run;
    private String tag;

    @Test
    @DisplayName("100,000 pages, 10,000 described files: every way a search is answered, chosen as planned and within bounds")
    void atScale() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        Folder root = FolderFixture.chain(folderRepository, tagGroupRepository, admin).subCategory();
        List<Folder> tops = new ArrayList<>();
        List<Folder> leaves = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Folder top = FolderFixture.tag(folderRepository, root, admin, "A" + i + "-" + TestData.nextSequence());
            tops.add(top);
            for (int j = 0; j < 10; j++) {
                leaves.add(FolderFixture.tag(folderRepository, top, admin, "L" + i + j + "-" + TestData.nextSequence()));
            }
        }
        entityManager.flush();
        run = "پیشرفته" + Long.toString(TestData.nextSequence() + 1000, 36).replaceAll("[0-9]", "");
        tag = "t" + TestData.nextSequence();
        for (int i = 0; i < tops.size(); i++) {
            jdbc.update("UPDATE folder SET metadata = jsonb_build_object('run', ?::text, 'region', ?::text) WHERE id = ?",
                    tag, "R" + i, tops.get(i).getId());
        }

        long started = System.nanoTime();
        Integer[] leafIds = leaves.stream().map(Folder::getId).toArray(Integer[]::new);
        jdbc.update("""
                INSERT INTO file_info (external_id, file_name, search_name, code_name, file_name_description, last_version,
                                       folder_id, enabled, state, created_at, created_by)
                SELECT gen_random_uuid()::text, ? || '-' || g, upper(?) || '-' || g, 'c', 'c', 1, (?::int[])[1 + g % 100], 1, 0, now(), ?
                FROM generate_series(1, ?) g""", tag, tag, leafIds, admin.getId(), FILES);
        // Each revision described: a number of its own, one city of twenty, an amount of a thousand; a
        // tenth of them Word documents; their days spread over a thousand.
        jdbc.update("""
                INSERT INTO file_details (file_info_id, external_id, file_name, search_name, file_extension, content_type,
                                          version, version_name, description, search_description, storage_key, file_size,
                                          enabled, state, created_at, created_by, metadata)
                SELECT fi.id, gen_random_uuid()::text, fi.file_name || x.ext, fi.search_name, x.kind, 'application/octet-stream', 1, 'v1',
                       '', '', 'files/00/' || fi.id || '/rev/v1/x' || x.ext, 1, 1, 0, now() - make_interval(days => fi.id % 1000), ?,
                       jsonb_build_object('run', ?::text, 'contractNo', 'C-' || fi.id, 'city', 'city' || (fi.id % 20),
                                          'amount', fi.id % 1000)
                FROM file_info fi
                CROSS JOIN LATERAL (SELECT CASE WHEN fi.id % 10 = 0 THEN 'docx' ELSE 'pdf' END AS kind) k
                CROSS JOIN LATERAL (SELECT k.kind, '.' || k.kind AS ext) x
                WHERE fi.folder_id = ANY (?::int[])""", admin.getId(), tag, leafIds);
        jdbc.update("""
                INSERT INTO file_content (file_details_id, state, lane, next_attempt_at, queued_at, read_at)
                SELECT d.id, 'DONE', 'OCR', now(), now(), now()
                FROM file_details d JOIN file_info fi ON fi.id = d.file_info_id WHERE fi.folder_id = ANY (?::int[])""", (Object) leafIds);
        // As ContentSearchScaleTest: the word on every page, a word on 1% of pages, a rare word, and
        // eighty words of a vocabulary of five thousand.
        jdbc.update("""
                INSERT INTO file_content_page (file_details_id, page_number, part, unit, source, text, search_text)
                SELECT c.file_details_id, p, 0, 'PAGE', 'OCR', t.text, t.text
                FROM file_content c
                JOIN file_details d ON d.id = c.file_details_id
                JOIN file_info fi ON fi.id = d.file_info_id
                CROSS JOIN generate_series(1, ?) p
                CROSS JOIN LATERAL (
                    SELECT ? || ' شرکت ' || 'صد' || translate(((c.file_details_id * 10 + p) % 100)::text, '0123456789', 'ابپتثجچحخد')
                           || ' نادر' || translate(((c.file_details_id * 10 + p) % 10000)::text, '0123456789', 'ابپتثجچحخد') || ' '
                           || (SELECT string_agg('و' || translate((floor(random() * 5000))::int::text, '0123456789', 'ابپتثجچحخد'), ' ')
                               FROM generate_series(1, 80) w WHERE w > 0 * p) AS text) t
                WHERE fi.folder_id = ANY (?::int[])""", PAGES, run, leafIds);
        for (String table : List.of("file_content_page", "file_content", "file_details", "file_info", "folder")) {
            jdbc.execute("ANALYZE " + table);
        }
        System.out.printf("[12.8] advanced search scale: %,d files, %,d pages written in %,d ms%n", FILES,
                jdbc.queryForObject("SELECT count(*) FROM file_content_page p JOIN file_details d ON d.id = p.file_details_id "
                        + "JOIN file_info fi ON fi.id = d.file_info_id WHERE fi.folder_id = ANY (?::int[])", Long.class, (Object) leafIds),
                (System.nanoTime() - started) / 1_000_000);

        int adminId = admin.getId();
        int someFile = jdbc.queryForObject("SELECT min(fi.id) + 1234 FROM file_info fi WHERE fi.folder_id = ANY (?::int[])",
                Integer.class, (Object) leafIds);
        String contractNo = "{\"run\":\"" + tag + "\",\"contractNo\":\"C-" + someFile + "\"}";
        String city = "{\"run\":\"" + tag + "\",\"city\":\"city7\"}";
        Folder top = tops.get(3);

        // ---------------------------------------------------------------- the files alone
        var one = measured("a metadata value of one file", adminId, Path.FILES_ONLY, request(null, null, null, contractNo, null));
        assertThat(one.items()).hasSize(1);
        var cities = measured("a metadata value of 500 files, newest", adminId, Path.FILES_ONLY,
                request(null, null, null, city, null));
        assertThat(cities.items()).hasSize(20);
        assertThat(cities.hasNext()).isTrue();
        var amount = measured("a number typed as text, of 10 files", adminId, Path.FILES_ONLY,
                request(null, null, null, "{\"run\":\"" + tag + "\",\"amount\":\"417\"}", null));
        assertThat(amount.items()).hasSize(10);
        var folder = measured("a top-level folder of 1,000 files", adminId, Path.FILES_ONLY, request(null, null, top.getId(), null, null));
        assertThat(folder.items()).hasSize(20)
                .allSatisfy(r -> assertThat(r.hit().folderPath()).startsWith(top.getPath()));
        var deepFolder = timed("a top-level folder, page 40", () -> search.search(request(null, null, top.getId(), null, null), 40, 20, adminId));
        assertThat(deepFolder.items()).hasSize(20);
        var name = measured("a fragment of a name, 11 files", adminId, Path.FILES_ONLY, request(null, tag + "-123", null, null, null));
        assertThat(name.items()).hasSize(11);
        var region = measured("a folder's metadata, 1,000 files below it", adminId, Path.FILES_ONLY,
                request(null, null, null, null, "{\"run\":\"" + tag + "\",\"region\":\"R5\"}"));
        assertThat(region.items()).hasSize(20).allSatisfy(r -> assertThat(r.hit().folderPath()).startsWith(tops.get(5).getPath()));
        var kindAndDays = measured("Word documents of the last 30 days in a folder", adminId, Path.FILES_ONLY,
                new SearchRequest(null, null, top.getId(), null, null, List.of("docx"), java.time.LocalDate.now().minusDays(30).toString(),
                        null, false, "oldest"));
        assertThat(kindAndDays.items()).allSatisfy(r -> assertThat(r.hit().fileExtension()).isEqualTo("docx"));

        // ---------------------------------------------------------------- the words
        var rare = measured("a rare word in a folder", adminId, Path.WORDS_FIRST, request(run + " نادرپپپپ", null, top.getId(), null, null));
        assertThat(rare.limited()).isFalse();
        var hundredth = measured("a word on 1% of pages, of one city", adminId, Path.WORDS_FIRST, request(run + " صدپپ", null, null, city, null));
        assertThat(hundredth.items()).allSatisfy(r -> assertThat(r.metadata()).anySatisfy(v -> assertThat(v.value()).isEqualTo("city7")));
        var everywhereInAFolder = measured("a word on every page, in a folder of 1,000 files", adminId, Path.FILES_FIRST,
                request(run + " شرکت", null, top.getId(), null, null));
        assertThat(everywhereInAFolder.items()).hasSize(20);
        assertThat(everywhereInAFolder.limited()).as("every page of the folder ranked").isFalse();
        var everywhereOfOne = measured("a word on every page, of one file's number", adminId, Path.FILES_FIRST,
                request(run + " شرکت", null, null, contractNo, null));
        assertThat(everywhereOfOne.items()).hasSize(1);
        assertThat(everywhereOfOne.items().getFirst().hit().pages()).isEqualTo(PAGES);
        var everywhereOfAKind = measured("a word on every page, of 9,000 PDFs", adminId, Path.NEWEST_PAGES,
                new SearchRequest(run + " شرکت", null, null, null, null, List.of("pdf"), null, null, false, null));
        assertThat(everywhereOfAKind.items()).hasSize(20);
        assertThat(everywhereOfAKind.limited()).isTrue();

        // ---------------------------------------------------------------- a reader of half the archive
        User reader = userRepository.save(TestData.user());
        List<UserFolderGrant> grants = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            grants.add(new UserFolderGrant(reader, tops.get(i), FolderPermission.READ));
        }
        reader.replaceFolderGrants(grants);
        userRepository.save(reader);
        entityManager.flush();
        int readerId = reader.getId();
        List<String> granted = tops.subList(0, 5).stream().map(Folder::getPath).toList();

        var readerCities = measured("a reader: a metadata value of 500 files", readerId, Path.FILES_ONLY, request(null, null, null, city, null));
        assertThat(readerCities.items()).hasSize(20).allSatisfy(r ->
                assertThat(granted).anySatisfy(path -> assertThat(r.hit().folderPath()).startsWith(path)));
        var readerFolder = measured("a reader: a granted folder", readerId, Path.FILES_ONLY, request(null, null, top.getId(), null, null));
        assertThat(readerFolder.items()).hasSize(20);
        var outside = measured("a reader: a folder outside the grants", readerId, null, request(run + " شرکت", null, tops.get(8).getId(), null, null));
        assertThat(outside.items()).isEmpty();
        var readerRegion = measured("a reader: a folder's metadata outside the grants", readerId, Path.FILES_ONLY,
                request(null, null, null, null, "{\"run\":\"" + tag + "\",\"region\":\"R8\"}"));
        assertThat(readerRegion.items()).isEmpty();
        var readerEverywhere = measured("a reader: a word on every page, in a granted folder", readerId, Path.FILES_FIRST,
                request(run + " شرکت", null, top.getId(), null, null));
        assertThat(readerEverywhere.items()).hasSize(20);
        measured("a reader: a word on every page, of one city", readerId, Path.FILES_FIRST, request(run + " شرکت", null, null, city, null));
        measured("a reader: a rare word, of one city", readerId, Path.WORDS_FIRST, request(run + " نادرپپپپ", null, null, city, null));
    }

    private enum Path { WORDS_FIRST, FILES_FIRST, NEWEST_PAGES, FILES_ONLY }

    private static SearchRequest request(String q, String name, Integer folderId, String metadata, String folderMetadata) {
        return new SearchRequest(q, name, folderId, metadata, folderMetadata, List.of(), null, null, false, null);
    }

    /** The search timed, after the way it is answered is asserted - null where none is (nothing to read). */
    private ContentSearchService.Results measured(String what, int principalId, Path expected, SearchRequest request) {
        SearchCriteria criteria = search.criteriaOf(request).orElse(null);
        if (expected != null) {
            assertThat(criteria).as(what).isNotNull();
            FolderReadScope scope = folderAccessService.readScope(principalId);
            assertThat(((PostgresContentSearch) contentSearch).pathOf(criteria, scope).name()).as(what).isEqualTo(expected.name());
        }
        return timed(what + (expected == null ? "" : " [" + expected + "]"), () -> search.search(request, 0, 20, principalId));
    }

    private <T> T timed(String what, Supplier<T> call) {
        call.get();
        long started = System.nanoTime();
        T result = call.get();
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("[12.8] advanced search, %s: %,d ms%n", what, ms);
        assertThat(ms).as(what).isLessThan(BOUND_MS);
        return result;
    }
}
