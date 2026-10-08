package com.hnp.filemanagement.content;

import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.domain.FolderPermission;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Search in contents over 100,000 pages, measured (roadmap 11: ten thousand files of ten pages, the
 * archive's size): a rare word, a word on one page in a hundred, a word on every page - the worst
 * case, answered from the newest pages it is on - as an administrator and as a reader granted half the
 * folders. Measured 2026-10-08: 30, 51 and 335 ms; 43 and 684 ms for the reader. The times
 * are printed and bounded generously - a shared database in a container, not a benchmark. The rows
 * are the test's transaction's, rolled back.
 */
@ServiceIntegrationTest
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.content-search.enabled=true"})
class ContentSearchScaleTest extends DatabaseSupport {

    static final int FILES = 10_000;
    static final int PAGES = 10;
    static final long BOUND_MS = 3_000;

    @Autowired
    private ContentSearchService search;
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

    @Test
    @DisplayName("100,000 pages: a rare word, a common one and one on every page answered within bounds, all and in part")
    void atScale() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        Folder root = FolderFixture.chain(folderRepository, tagGroupRepository, admin).subCategory();
        List<Folder> folders = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            folders.add(FolderFixture.tag(folderRepository, root, admin, "S" + i + "-" + TestData.nextSequence()));
        }
        entityManager.flush();
        String run = "مقیاس" + Long.toString(TestData.nextSequence() + 1000, 36).replaceAll("[0-9]", "");

        long started = System.nanoTime();
        Integer[] folderIds = folders.stream().map(Folder::getId).toArray(Integer[]::new);
        jdbc.update("""
                INSERT INTO file_info (external_id, file_name, search_name, code_name, file_name_description, last_version,
                                       folder_id, enabled, state, created_at, created_by)
                SELECT gen_random_uuid()::text, 'c-' || g, 'C-' || g, 'c', 'c', 1, (?::int[])[1 + g % 10], 1, 0, now(), ?
                FROM generate_series(1, ?) g""", folderIds, admin.getId(), FILES);
        jdbc.update("""
                INSERT INTO file_details (file_info_id, external_id, file_name, search_name, file_extension, content_type,
                                          version, version_name, description, search_description, storage_key, file_size,
                                          enabled, state, created_at, created_by)
                SELECT fi.id, gen_random_uuid()::text, fi.file_name || '.pdf', fi.search_name, 'pdf', 'application/pdf', 1, 'v1',
                       '', '', 'files/00/' || fi.id || '/rev/v1/x.pdf', 1, 1, 0, now(), ?
                FROM file_info fi WHERE fi.folder_id = ANY (?::int[])""", admin.getId(), folderIds);
        jdbc.update("""
                INSERT INTO file_content (file_details_id, state, lane, next_attempt_at, queued_at, read_at)
                SELECT d.id, 'DONE', 'OCR', now(), now(), now()
                FROM file_details d JOIN file_info fi ON fi.id = d.file_info_id WHERE fi.folder_id = ANY (?::int[])""", (Object) folderIds);
        // Each page: the word on every page, a word of a hundred (on 1% of pages), a rare word (a page in
        // ten thousand), and eighty words of a vocabulary of five thousand - letters, as Persian words are.
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
                WHERE fi.folder_id = ANY (?::int[])""", PAGES, run, folderIds);
        jdbc.execute("ANALYZE file_content_page");
        jdbc.execute("ANALYZE file_content");
        jdbc.execute("ANALYZE file_details");
        jdbc.execute("ANALYZE file_info");
        System.out.printf("[11] content search scale: %,d pages written in %,d ms%n",
                jdbc.queryForObject("SELECT count(*) FROM file_content_page p JOIN file_details d ON d.id = p.file_details_id "
                        + "JOIN file_info fi ON fi.id = d.file_info_id WHERE fi.folder_id = ANY (?::int[])", Long.class, (Object) folderIds),
                (System.nanoTime() - started) / 1_000_000);

        int adminId = admin.getId();
        var rare = timed("a rare word, administrator", () -> search.search(run + " نادرپپپپ", false, 0, 20, adminId));
        assertThat(rare.items()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(rare.limited()).as("a rare word is ranked whole").isFalse();
        var hundredth = timed("a word on 1% of the pages, administrator", () -> search.search(run + " صدپپ", false, 0, 20, adminId));
        assertThat(hundredth.items()).hasSize(20);
        assertThat(hundredth.hasNext()).isTrue();
        assertThat(hundredth.limited()).isFalse();
        var everywhere = timed("a word on every page, administrator", () -> search.search(run + " شرکت", false, 0, 20, adminId));
        assertThat(everywhere.items()).hasSize(20);
        assertThat(everywhere.limited()).as("a word on 100,000 pages: answered from the newest 20,000, said so").isTrue();
        var deep = timed("a word on every page, page 50", () -> search.search(run + " شرکت", false, 50, 20, adminId));
        assertThat(deep.items()).hasSize(20);
        var phrase = timed("a phrase on every page", () -> search.search("\"" + run + " شرکت\"", false, 0, 20, adminId));
        assertThat(phrase.items()).hasSize(20);

        User reader = userRepository.save(TestData.user());
        List<UserFolderGrant> grants = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            grants.add(new UserFolderGrant(reader, folders.get(i), FolderPermission.READ));
        }
        reader.replaceFolderGrants(grants);
        userRepository.save(reader);
        entityManager.flush();
        int readerId = reader.getId();
        var granted = timed("a word on every page, a reader of half the folders", () -> search.search(run + " شرکت", false, 0, 20, readerId));
        assertThat(granted.items()).hasSize(20).allSatisfy(result ->
                assertThat(folders.subList(0, 5).stream().map(Folder::getId)).contains(result.hit().folderId()));
        timed("a rare word, a reader of half the folders", () -> search.search(run + " نادرپپپپ", false, 0, 20, readerId));
    }

    private <T> T timed(String what, Supplier<T> call) {
        call.get();
        long started = System.nanoTime();
        T result = call.get();
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("[11] content search, %s: %,d ms%n", what, ms);
        assertThat(ms).as(what).isLessThan(BOUND_MS);
        return result;
    }
}
