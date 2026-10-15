package com.hnp.filemanagement.content;

import com.hnp.filemanagement.content.FolderReadingService.Choice;
import com.hnp.filemanagement.folder.domain.Folder;
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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A folder of 20,000 personal folders and 100,000 revisions switched off and on again (2.16.0), with fifty
 * other folders holding settings of their own: what a switch settles, measured - and an upload's decision,
 * which every upload pays, measured with the settings there.
 */
@Tag("scale")
@ServiceIntegrationTest
@TestPropertySource(properties = {"filemanagement.folder-access.enabled=true", "filemanagement.content-search.enabled=true"})
class FolderReadingScaleTest extends DatabaseSupport {

    private static final int FOLDERS = 20_000;
    private static final int FILES_EACH = 5;
    private static final long BOUND_MS = 15_000;

    @Autowired
    private FolderReadingService underTest;
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

    @Test
    @DisplayName("20,000 folders and 100,000 revisions: switched off, then on again, each within bounds; an upload decides in milliseconds")
    void switchedOffAndOn() {
        User admin = TestData.user();
        admin.getRoles().add(roleRepository.save(TestData.role("ADMIN")));
        admin = userRepository.save(admin);
        Folder top = FolderFixture.chain(folderRepository, tagGroupRepository, admin).category();
        Folder profiles = FolderFixture.tag(folderRepository, top, admin, "profiles" + TestData.nextSequence());
        Folder other = FolderFixture.tag(folderRepository, top, admin, "other" + TestData.nextSequence());
        entityManager.flush();
        String tag = "s" + TestData.nextSequence();
        jdbc.update("""
                INSERT INTO folder (id, parent_id, name, search_name, display_name, search_display_name, path, key_path,
                                    depth, kind, enabled, state, created_at)
                SELECT n.id, ?, 'P' || ? || '-' || n.g, upper('P' || ? || '-' || n.g), 'P ' || n.g, upper('P ' || n.g),
                       ? || n.id || '/', ? || 'P' || ? || '-' || n.g || '/', ?, 'FOLDER', 1, 0, now()
                FROM (SELECT nextval(pg_get_serial_sequence('folder', 'id')) AS id, g FROM generate_series(1, ?) g) n""",
                profiles.getId(), tag, tag, profiles.getPath(), profiles.getKeyPath(), tag, profiles.getDepth() + 1, FOLDERS);
        jdbc.update("""
                INSERT INTO file_info (external_id, file_name, search_name, code_name, file_name_description, last_version,
                                       folder_id, enabled, state, created_at, created_by)
                SELECT gen_random_uuid()::text, 'f-' || f.id || '-' || k, 'F-' || f.id || '-' || k, 'c', 'c', 1, f.id, 1, 0, now(), ?
                FROM folder f CROSS JOIN generate_series(1, ?) k WHERE f.parent_id = ?""", admin.getId(), FILES_EACH, profiles.getId());
        jdbc.update("""
                INSERT INTO file_details (file_info_id, external_id, file_name, search_name, file_extension, content_type,
                                          version, version_name, description, search_description, storage_key, file_size,
                                          enabled, state, created_at, created_by)
                SELECT fi.id, gen_random_uuid()::text, fi.file_name || '.pdf', fi.search_name, 'pdf', 'application/pdf', 1, 'v1',
                       '', '', 'files/00/' || fi.id || '/rev/v1/x.pdf', 1, 1, 0, now(), ?
                FROM file_info fi JOIN folder f ON f.id = fi.folder_id WHERE f.parent_id = ?""", admin.getId(), profiles.getId());
        jdbc.update("""
                INSERT INTO file_content (file_details_id, state, priority, next_attempt_at, queued_at)
                SELECT d.id, 'PENDING', 1, now(), now()
                FROM file_details d JOIN file_info fi ON fi.id = d.file_info_id JOIN folder f ON f.id = fi.folder_id
                WHERE f.parent_id = ?""", profiles.getId());
        // Fifty settings of their own elsewhere, so the settings are read whole each time as they will be.
        jdbc.update("""
                INSERT INTO folder (id, parent_id, name, search_name, display_name, search_display_name, path, key_path,
                                    depth, kind, enabled, state, created_at, content_reading)
                SELECT n.id, ?, 'O' || ? || '-' || n.g, upper('O' || ? || '-' || n.g), 'O ' || n.g, upper('O ' || n.g),
                       ? || n.id || '/', ? || 'O' || ? || '-' || n.g || '/', ?, 'FOLDER', 1, 0, now(), n.g % 2 = 0
                FROM (SELECT nextval(pg_get_serial_sequence('folder', 'id')) AS id, g FROM generate_series(1, 50) g) n""",
                other.getId(), tag, tag, other.getPath(), other.getKeyPath(), tag, other.getDepth() + 1);
        jdbc.execute("ANALYZE folder");
        jdbc.execute("ANALYZE file_info");
        jdbc.execute("ANALYZE file_details");
        jdbc.execute("ANALYZE file_content");
        int revisions = FOLDERS * FILES_EACH;

        long started = System.nanoTime();
        FolderReadingService.Changed off = underTest.set(profiles.getId(), Choice.OFF, admin.getId());
        long offMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(off.heldBack()).isEqualTo(revisions);

        started = System.nanoTime();
        FolderReadingService.Changed on = underTest.set(profiles.getId(), Choice.INHERIT, admin.getId());
        long onMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(on.queued()).isEqualTo(revisions);

        underTest.set(profiles.getId(), Choice.OFF, admin.getId());
        List<Integer> one = jdbc.queryForList("""
                SELECT d.id FROM file_details d JOIN file_info fi ON fi.id = d.file_info_id JOIN folder f ON f.id = fi.folder_id
                WHERE f.parent_id = ? ORDER BY d.id DESC LIMIT 1""", Integer.class, profiles.getId());
        jdbc.update("DELETE FROM file_content WHERE file_details_id = ?", one.getFirst());
        started = System.nanoTime();
        assertThat(repository.enqueue(one.getFirst(), FileContentRepository.PRIORITY_NEW, Instant.now())).isFalse();
        long enqueueMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(jdbc.queryForObject("SELECT state FROM file_content WHERE file_details_id = ?", String.class, one.getFirst()))
                .isEqualTo("EXCLUDED");

        started = System.nanoTime();
        FolderReadingService.View view = underTest.view(profiles.getId(), admin.getId(), true);
        long viewMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(view.heldBack()).isEqualTo(revisions);

        System.out.printf("[folder reading] %,d folders, %,d revisions: off %d ms, on again %d ms, an upload's decision %d ms, "
                + "the page %d ms%n", FOLDERS, revisions, offMs, onMs, enqueueMs, viewMs);
        assertThat(Math.max(offMs, onMs)).isLessThan(BOUND_MS);
        assertThat(enqueueMs).isLessThan(500);
        assertThat(viewMs).isLessThan(5_000);
    }
}
