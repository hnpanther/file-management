package com.hnp.filemanagement;

import com.hnp.filemanagement.support.TestData;
import com.hnp.filemanagement.support.TestDatabases;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migrations after the baseline, run as production runs them: on a database that already
 * holds rows, and as an account that owns its database and is no superuser.
 *
 * <p>The rest of the suite migrates an empty database as the container's superuser, which proves
 * neither what {@code V3.1} does to the times already written nor that {@code V3.2} may create
 * its extension as the account {@code docs/deployment.md} creates.
 */
class MigrationTest {

    /**
     * {@code V3.1} (issue 24): a time written before it was the wall clock of a server on Tehran
     * time, and comes out as the instant that was - with the summer time Iran kept until 1401.
     */
    @Test
    @DisplayName("V3.1 reads the times already written as Tehran time, summer time included, and leaves no column without a zone")
    void timesWrittenBeforeBecomeTheirInstants() {
        String database = "migration_tz_" + TestData.nextSequence();
        JdbcTemplate admin = superuser("postgres");
        admin.execute("CREATE DATABASE " + database);
        try {
            DriverManagerDataSource dataSource = dataSource(database, superuserName(), superuserPassword());
            flyway(dataSource).target("3.0").load().migrate();

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.update("""
                    INSERT INTO file_storage_write (storage_key, created_at) VALUES
                        ('after-1401', '2026-09-19 16:33:55'),
                        ('summer-1400', '2021-06-01 16:30:00'),
                        ('winter-1400', '2021-12-01 15:30:00')
                    """);
            jdbc.update("INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES ('migration.test', 'x', NULL)");

            flyway(dataSource).target("3.1").load().migrate();

            assertThat(instantOf(jdbc, "after-1401")).isEqualTo(Instant.parse("2026-09-19T13:03:55Z"));
            assertThat(instantOf(jdbc, "summer-1400")).as("+04:30 then").isEqualTo(Instant.parse("2021-06-01T12:00:00Z"));
            assertThat(instantOf(jdbc, "winter-1400")).isEqualTo(Instant.parse("2021-12-01T12:00:00Z"));
            assertThat(jdbc.queryForObject(
                    "SELECT updated_at FROM app_setting WHERE setting_key = 'migration.test'", OffsetDateTime.class))
                    .as("a missing time stays missing").isNull();

            List<Map<String, Object>> columns = jdbc.queryForList("""
                    SELECT table_name, column_name, data_type, datetime_precision
                    FROM information_schema.columns
                    WHERE table_schema = 'public' AND data_type LIKE 'timestamp%'
                      AND table_name <> 'flyway_schema_history'
                    """);
            assertThat(columns).hasSize(28).allSatisfy(column -> {
                assertThat(column.get("data_type")).as(column.toString()).isEqualTo("timestamp with time zone");
                assertThat(column.get("datetime_precision")).as(column.toString()).isEqualTo(0);
            });
        } finally {
            admin.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    /**
     * {@code V3.4} (issue 98): a sequence that stands at or below an id the audit trail names - a
     * row deleted before the cut-over copied the rest - is moved past it, so that id is never
     * handed out again; one already past everything, and one of an empty table with no history,
     * are left as they are.
     */
    @Test
    @DisplayName("V3.4 moves a sequence past every id the audit trail names, and only forward")
    void sequencesPassEveryIdEverUsed() {
        String database = "migration_seq_" + TestData.nextSequence();
        JdbcTemplate admin = superuser("postgres");
        admin.execute("CREATE DATABASE " + database);
        try {
            DriverManagerDataSource dataSource = dataSource(database, superuserName(), superuserPassword());
            flyway(dataSource).target("3.3").load().migrate();

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Integer userId = jdbc.queryForObject("""
                    INSERT INTO app_user (username, personel_code, national_code, password, first_name, last_name,
                                          created_at, enabled, state)
                    VALUES ('migration', 1, '0000000000', 'x', 'm', 'm', now(), 1, 0) RETURNING id
                    """, Integer.class);
            // A file 5000 created and deleted before the cut-over: gone from file_info, named by the trail.
            for (String action : List.of("CREATE", "DELETE")) {
                jdbc.update("""
                        INSERT INTO action_history (entity_name, table_name, entity_id, action, enabled, state, created_at, user_id)
                        VALUES ('FileInfo', 'file_info', 5000, ?, 1, 0, now(), ?)
                        """, action, userId);
            }
            // A sequence already past everything its trail names.
            jdbc.execute("SELECT setval('file_details_id_seq', 9000, true)");
            jdbc.update("""
                    INSERT INTO action_history (entity_name, table_name, entity_id, action, enabled, state, created_at, user_id)
                    VALUES ('FileDetails', 'file_details', 42, 'DELETE', 1, 0, now(), ?)
                    """, userId);

            flyway(dataSource).target("3.4").load().migrate();

            assertThat(jdbc.queryForObject("SELECT nextval('file_info_id_seq')", Long.class))
                    .as("past the deleted file 5000").isEqualTo(5001L);
            assertThat(jdbc.queryForObject("SELECT nextval('file_details_id_seq')", Long.class))
                    .as("already past everything: untouched").isEqualTo(9001L);
            assertThat(jdbc.queryForObject("SELECT nextval('file_share_link_id_seq')", Long.class))
                    .as("an empty table with no history still starts at 1").isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT nextval('app_user_id_seq')", Long.class))
                    .as("past the one user there is").isEqualTo((long) userId + 1);
        } finally {
            admin.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    /**
     * {@code V3.9}: every folder already there gets its key path - none for the root and a top-level
     * folder (a bucket), the names below the bucket for the rest, at any depth, Persian and spaces
     * as they are.
     */
    @Test
    @DisplayName("V3.9 gives every existing folder its key path, at any depth")
    void keyPathsForTheFoldersThere() {
        String database = "migration_keypath_" + TestData.nextSequence();
        JdbcTemplate admin = superuser("postgres");
        admin.execute("CREATE DATABASE " + database);
        try {
            DriverManagerDataSource dataSource = dataSource(database, superuserName(), superuserPassword());
            flyway(dataSource).target("3.8").load().migrate();

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            int root = jdbc.queryForObject("SELECT id FROM folder WHERE parent_id IS NULL", Integer.class);
            int group = jdbc.queryForObject("""
                    INSERT INTO tag_group (name, title, enabled, created_at) VALUES ('erp', 'ERP', 1, now()) RETURNING id
                    """, Integer.class);
            int bucket = folder(jdbc, root, "ERP", group);
            int person = folder(jdbc, bucket, "P-1234", null);
            int contracts = folder(jdbc, person, "قراردادهای جاری 1404", null);
            int deepest = folder(jdbc, contracts, "C-9", null);

            flyway(dataSource).target("3.9").load().migrate();

            assertThat(keyPath(jdbc, root)).isEmpty();
            assertThat(keyPath(jdbc, bucket)).as("a bucket").isEmpty();
            assertThat(keyPath(jdbc, person)).isEqualTo("P-1234/");
            assertThat(keyPath(jdbc, contracts)).isEqualTo("P-1234/قراردادهای جاری 1404/");
            assertThat(keyPath(jdbc, deepest)).isEqualTo("P-1234/قراردادهای جاری 1404/C-9/");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM folder f JOIN folder p ON p.id = f.parent_id
                    WHERE f.key_path <> CASE WHEN p.parent_id IS NULL THEN '' ELSE p.key_path || f.name || '/' END
                    """, Integer.class)).as("no row disagrees with its parent").isZero();
        } finally {
            admin.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private static int folder(JdbcTemplate jdbc, int parentId, String name, Integer tagGroupId) {
        Integer id = jdbc.queryForObject("""
                INSERT INTO folder (parent_id, name, search_name, display_name, search_display_name, path, depth, kind,
                                    tag_group_id, enabled, state, created_at)
                SELECT p.id, ?, upper(?), ?, ?, 'pending', p.depth + 1, 'FOLDER', ?, 1, 0, now() FROM folder p WHERE p.id = ?
                RETURNING id
                """, Integer.class, name, name, name, name, tagGroupId, parentId);
        jdbc.update("UPDATE folder f SET path = p.path || f.id || '/' FROM folder p WHERE p.id = f.parent_id AND f.id = ?", id);
        return id;
    }

    private static String keyPath(JdbcTemplate jdbc, int folderId) {
        return jdbc.queryForObject("SELECT key_path FROM folder WHERE id = ?", String.class, folderId);
    }

    /**
     * {@code V3.5}: the file history starts from what the database already knows, and says no
     * more. The files there are get their uploads, versions and formats from their own rows;
     * what action_history recorded about them after they were made follows; a file that is gone
     * appears by its number only, never under a file that later took that number (issue 98).
     */
    @Test
    @DisplayName("V3.5 fills the history from the rows and the audit trail, exactly and no further")
    void theHistoryStartsFromWhatIsKnown() {
        String database = "migration_history_" + TestData.nextSequence();
        JdbcTemplate admin = superuser("postgres");
        admin.execute("CREATE DATABASE " + database);
        try {
            DriverManagerDataSource dataSource = dataSource(database, superuserName(), superuserPassword());
            flyway(dataSource).target("3.4").load().migrate();
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);

            int uploader = userNamed(jdbc, "uploader");
            int deleter = userNamed(jdbc, "deleter");
            int group = jdbc.queryForObject("INSERT INTO tag_group (name, title, enabled, created_at) VALUES ('q', 'Q', 1, now()) RETURNING id", Integer.class);
            int folder = jdbc.queryForObject("""
                    INSERT INTO folder (parent_id, name, search_name, display_name, search_display_name, path, depth, kind,
                                        tag_group_id, enabled, state, created_at)
                    VALUES (1, 'Quality', 'QUALITY', 'کیفیت', 'کیفیت', '/x/', 1, 'FOLDER', ?, 1, 0, now()) RETURNING id
                    """, Integer.class, group);
            jdbc.update("UPDATE folder SET path = '/1/' || id || '/' WHERE id = ?", folder);

            // The report: v1 as pdf and docx, then v2 by someone else.
            int report = file(jdbc, "report", folder, uploader, "2026-01-10T08:00:00Z");
            int pdf1 = revision(jdbc, report, 1, "pdf", uploader, "2026-01-10T08:00:00Z");
            revision(jdbc, report, 1, "docx", uploader, "2026-01-10T08:01:00Z");
            int pdf2 = revision(jdbc, report, 2, "pdf", deleter, "2026-01-10T08:02:00Z");
            // The memo: its version 1 was deleted since, only version 2 is left.
            int memo = file(jdbc, "memo", folder, uploader, "2026-01-10T08:00:00Z");
            revision(jdbc, memo, 2, "txt", uploader, "2026-01-10T08:05:00Z");

            audit(jdbc, "FileInfo", report, "CREATE", "CREATE NEW FILE_INFO", "CREATE NEW FILE_INFO", uploader, "2026-01-10T08:00:00Z");
            audit(jdbc, "FileInfo", report, "UPDATE_VALUES", "UPDATE FILE_INFO", "Update File info, new description=جدید", deleter, "2026-01-10T08:10:00Z");
            audit(jdbc, "FileInfo", report, "UPDATE_VALUES", "MOVE FILE_INFO", "MOVE file id=" + report + " from folder id=7 to folder id=" + folder, deleter, "2026-01-10T08:11:00Z");
            audit(jdbc, "FileInfo", report, "UPDATE_CHANGE_STATE", "CHANGE STATE FILE_INFO", "Change state from 0 to -1", deleter, "2026-01-10T08:12:00Z");
            audit(jdbc, "FileDetails", pdf2, "UPDATE_CHANGE_STATE", "CHANGE STATE FILE_DETAILS", "Change state from -1 to 0", deleter, "2026-01-10T08:13:00Z");
            audit(jdbc, "FileShareLink", 1, "CREATE", "CREATE SHARE LINK", "CREATE share link id=1 to fileDetails id=" + pdf2 + " valid 3 minute(s)", deleter, "2026-01-10T08:14:00Z");
            // A file long gone - and one that had the report's number before the report did.
            audit(jdbc, "FileInfo", 9000, "CREATE", "CREATE NEW FILE_INFO", "CREATE NEW FILE_INFO", uploader, "2025-12-01T08:00:00Z");
            audit(jdbc, "FileInfo", 9000, "DELETE", "DELETE FILE_INFO", "Delete Complete File_Info", deleter, "2025-12-02T08:00:00Z");
            audit(jdbc, "FileInfo", report, "CREATE", "CREATE NEW FILE_INFO", "CREATE NEW FILE_INFO", uploader, "2025-11-01T08:00:00Z");
            audit(jdbc, "FileInfo", report, "DELETE", "DELETE FILE_INFO", "Delete Complete File_Info", deleter, "2025-11-02T08:00:00Z");

            flyway(dataSource).target("3.5").load().migrate();

            String reportExternalId = jdbc.queryForObject("SELECT external_id FROM file_info WHERE id = ?", String.class, report);
            List<Map<String, Object>> reportHistory = jdbc.queryForList("""
                    SELECT event, version, file_extension, username, detail, folder_title, file_details_id
                    FROM file_history WHERE file_external_id = ? ORDER BY occurred_at, id
                    """, reportExternalId);
            assertThat(reportHistory).extracting(row -> row.get("event")).containsExactly(
                    "FILE_UPLOADED", "FORMAT_ADDED", "VERSION_ADDED", "DESCRIPTION_CHANGED", "FILE_MOVED",
                    "FILE_UNPUBLISHED", "REVISION_PUBLISHED", "SHARE_LINK_CREATED");
            assertThat(reportHistory.get(0)).containsEntry("file_details_id", pdf1).containsEntry("username", "uploader")
                    .containsEntry("folder_title", "کیفیت");
            assertThat(reportHistory.get(1)).containsEntry("file_extension", "docx").containsEntry("version", 1);
            assertThat(reportHistory.get(2)).containsEntry("username", "deleter").containsEntry("version", 2);
            assertThat(reportHistory.get(3)).containsEntry("detail", "جدید");
            assertThat((String) reportHistory.get(4).get("detail")).startsWith("MOVE file id=");
            assertThat(reportHistory.get(6)).containsEntry("file_details_id", pdf2);
            assertThat(reportHistory.get(7)).containsEntry("file_details_id", pdf2);

            String memoExternalId = jdbc.queryForObject("SELECT external_id FROM file_info WHERE id = ?", String.class, memo);
            assertThat(jdbc.queryForList("SELECT event FROM file_history WHERE file_external_id = ? ORDER BY occurred_at, id",
                    String.class, memoExternalId))
                    .as("its upload from the file row, its version 2 as a version added").containsExactly("FILE_UPLOADED", "VERSION_ADDED");

            assertThat(jdbc.queryForList("""
                    SELECT event || ':' || file_info_id || ':' || coalesce(file_name, '-') || ':' || username
                    FROM file_history WHERE file_external_id IS NULL ORDER BY occurred_at
                    """, String.class))
                    .as("gone files, nameless, under no file - the report's number's earlier owner included")
                    .containsExactly("FILE_UPLOADED:" + report + ":-:uploader", "FILE_DELETED:" + report + ":-:deleter",
                            "FILE_UPLOADED:9000:-:uploader", "FILE_DELETED:9000:-:deleter");
        } finally {
            admin.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private static int userNamed(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("""
                INSERT INTO app_user (username, personel_code, national_code, password, first_name, last_name,
                                      created_at, enabled, state)
                VALUES (?, ?, ?, 'x', 'f', 'l', now(), 1, 0) RETURNING id
                """, Integer.class, name, name.length(), String.format("%010d", name.hashCode() & 0x3fffffff));
    }

    private static int file(JdbcTemplate jdbc, String name, int folder, int user, String at) {
        return jdbc.queryForObject("""
                INSERT INTO file_info (external_id, file_name, search_name, code_name, file_name_description,
                                       last_version, folder_id, enabled, state, created_at, created_by)
                VALUES (gen_random_uuid()::text, ?, upper(?), ?, ?, 2, ?, 1, 0, ?::timestamptz, ?) RETURNING id
                """, Integer.class, name, name, name, name, folder, at, user);
    }

    private static int revision(JdbcTemplate jdbc, int file, int version, String extension, int user, String at) {
        return jdbc.queryForObject("""
                INSERT INTO file_details (file_info_id, external_id, file_name, search_name, file_extension, content_type,
                                          version, version_name, description, search_description, storage_key, file_size,
                                          enabled, state, created_at, created_by)
                SELECT ?, gen_random_uuid()::text, fi.file_name || '.' || ?, upper(fi.file_name), ?, 'application/octet-stream',
                       ?, 'V' || ?, 'd', 'D', 'files/x/' || gen_random_uuid(), 10, 1, 0, ?::timestamptz, ?
                FROM file_info fi WHERE fi.id = ? RETURNING id
                """, Integer.class, file, extension, extension, version, version, at, user, file);
    }

    private static void audit(JdbcTemplate jdbc, String entity, int id, String action, String actionDescription,
                              String description, int user, String at) {
        jdbc.update("""
                INSERT INTO action_history (entity_name, table_name, entity_id, action, action_description, description,
                                            enabled, state, created_at, user_id)
                VALUES (?, ?, ?, ?, ?, ?, 1, 0, ?::timestamptz, ?)
                """, entity, entity.toLowerCase(), id, action, actionDescription, description, at, user);
    }

    /**
     * Production's database is owned by the account the application connects as, which is no
     * superuser (docs/deployment.md). {@code V3.2} creates {@code pg_trgm}, which such an account
     * may do only because the extension is trusted; a migration that needed a superuser would stop
     * the first start of the release on production and nowhere in the suite.
     */
    @Test
    @DisplayName("every migration runs as a database owner that is not a superuser, as in production")
    void migratesAsTheDatabaseOwner() {
        long n = TestData.nextSequence();
        String database = "migration_owner_" + n;
        String owner = "migration_owner_" + n;
        JdbcTemplate admin = superuser("postgres");
        admin.execute("CREATE ROLE " + owner + " LOGIN PASSWORD 'owner' NOSUPERUSER NOCREATEDB NOCREATEROLE");
        admin.execute("CREATE DATABASE " + database + " OWNER " + owner
                + " TEMPLATE template0 ENCODING 'UTF8' LOCALE_PROVIDER icu ICU_LOCALE 'und'");
        try {
            DriverManagerDataSource dataSource = dataSource(database, owner, "owner");

            flyway(dataSource).load().migrate();

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class))
                    .isFalse();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isGreaterThanOrEqualTo(3);
        } finally {
            admin.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            admin.execute("DROP ROLE IF EXISTS " + owner);
        }
    }

    // ---------------------------------------------------------------- plumbing

    private static Instant instantOf(JdbcTemplate jdbc, String storageKey) {
        return jdbc.queryForObject("SELECT created_at FROM file_storage_write WHERE storage_key = ?",
                OffsetDateTime.class, storageKey).toInstant();
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(DriverManagerDataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }

    private static JdbcTemplate superuser(String database) {
        return new JdbcTemplate(dataSource(database, superuserName(), superuserPassword()));
    }

    private static DriverManagerDataSource dataSource(String database, String user, String password) {
        return new DriverManagerDataSource(TestDatabases.postgresqlUrlFor(database), user, password);
    }

    private static String superuserName() {
        return TestDatabases.postgresql().getUsername();
    }

    private static String superuserPassword() {
        return TestDatabases.postgresql().getPassword();
    }
}
