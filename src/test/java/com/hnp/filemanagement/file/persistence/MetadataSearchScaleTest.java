package com.hnp.filemanagement.file.persistence;

import com.hnp.filemanagement.file.domain.MetadataSearchService;
import com.hnp.filemanagement.folder.domain.Folder;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.folder.persistence.TagGroupRepository;
import com.hnp.filemanagement.identity.domain.User;
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

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Search by metadata over 20,000 files, measured (roadmap 12.2): a selective search answers off the
 * GIN index whatever the number of files; a broad one - every file the ERP sent - costs what it
 * matches, a page deep in it included. The times are printed and bounded generously - a shared
 * database in a container, not a benchmark. The rows are the test's transaction's, rolled back.
 */
@ServiceIntegrationTest
class MetadataSearchScaleTest extends DatabaseSupport {

    static final int FILES = 20_000;
    static final long BOUND_MS = 3_000;

    @Autowired
    private MetadataSearchService search;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private TagGroupRepository tagGroupRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a selective search over 20,000 files is a few milliseconds; a broad one a page at a time, deep pages included")
    void atScale() {
        User owner = userRepository.save(TestData.user());
        Folder folder = FolderFixture.chain(folderRepository, tagGroupRepository, owner).tag();
        entityManager.flush();
        String run = "scale-" + TestData.nextSequence();
        jdbc.update("""
                INSERT INTO file_info (external_id, file_name, search_name, code_name, file_name_description, last_version,
                                       folder_id, enabled, state, created_at, created_by)
                SELECT gen_random_uuid()::text, 'm-' || g, 'm-' || g, 'm', 'm', 1, ?, 1, 0, now(), ?
                FROM generate_series(1, ?) g""", folder.getId(), owner.getId(), FILES);
        jdbc.update("""
                INSERT INTO file_details (file_info_id, external_id, file_name, search_name, file_extension, content_type,
                                          version, version_name, description, search_description, storage_key, file_size,
                                          checksum_sha256, metadata, enabled, state, created_at, created_by)
                SELECT fi.id, gen_random_uuid()::text, fi.file_name || '.txt', fi.file_name, 'txt', 'text/plain', 1, 'v1', '', '',
                       'files/00/' || fi.id || '/rev/v1/x.txt', 1, encode(sha256(convert_to(fi.id::text, 'UTF8')), 'hex'),
                       jsonb_build_object('run', ?::text, 'source', 'erp', 'n', fi.id, 'party', jsonb_build_object('code', 'P-' || fi.id)),
                       1, 0, now(), ?
                FROM file_info fi WHERE fi.folder_id = ?""", run, owner.getId(), folder.getId());
        jdbc.execute("ANALYZE file_info");
        jdbc.execute("ANALYZE file_details");
        int any = jdbc.queryForObject("SELECT min(fi.id) + 12345 FROM file_info fi WHERE fi.folder_id = ?", Integer.class, folder.getId());

        var one = timed("selective, a nested key", () -> search.files(
                "{\"run\":\"" + run + "\",\"party\":{\"code\":\"P-" + any + "\"}}", null, 0, 50, owner.getId()));
        assertThat(one.items()).singleElement().satisfies(hit -> assertThat(hit.fileId()).isEqualTo(any));

        var first = timed("broad, the first page", () -> search.files("{\"run\":\"" + run + "\",\"source\":\"erp\"}", null, 0, 200,
                owner.getId()));
        assertThat(first.items()).hasSize(200);
        assertThat(first.hasNext()).isTrue();
        var deep = timed("broad, page 99", () -> search.files("{\"run\":\"" + run + "\",\"source\":\"erp\"}", null, 99, 200,
                owner.getId()));
        assertThat(deep.items()).hasSize(200);
        assertThat(deep.hasNext()).isFalse();
    }

    private <T> T timed(String what, Supplier<T> call) {
        call.get();
        long started = System.nanoTime();
        T result = call.get();
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.printf("[12.2] metadata search, %s: %,d ms%n", what, ms);
        assertThat(ms).as(what).isLessThan(BOUND_MS);
        return result;
    }
}
