package com.hnp.filemanagement;

import com.hnp.filemanagement.file.persistence.FileDetailsRepository;
import com.hnp.filemanagement.file.persistence.FileInfoRepository;
import com.hnp.filemanagement.folder.persistence.FolderRepository;
import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import com.hnp.filemanagement.support.TestDatabases;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every search is served by the trigram indexes of {@code V3.2} (issue 21) - asked of PostgreSQL,
 * for the SQL Hibernate actually writes.
 *
 * <p>An expression index is used only when the query's expression is the index's, character for
 * character: {@code replace(search_name, ' ', '')} in both. Nothing else would notice if a query
 * were rewritten some other way, or the index dropped: every result would stay right, and every
 * search would read the whole table again - 73 seconds for 200,000 files, as it did before 2.2.0.
 *
 * <p>Each repository search is run once for a page and once past the end, so that Spring Data runs
 * its count query too; Hibernate's statements are recorded on the way ({@link Recorder}), and each
 * is then planned with {@code EXPLAIN (GENERIC_PLAN)} - the plan for any value of its parameters -
 * with every access path but a bitmap scan priced out, on a connection of its own in the simple
 * query protocol (in the extended one the driver would have to bind the {@code $n} it has no
 * values for). That asks whether an index <em>can</em> serve the query, which is the property that
 * matters; whether the planner prefers it on a table of a dozen test rows is a question about the
 * test data, not about the query - on 200,000 files it does (docs/issues.md, issue 21).
 */
@ServiceIntegrationTest
@TestPropertySource(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.hnp.filemanagement.SearchIndexTest$Recorder")
class SearchIndexTest extends DatabaseSupport {

    private static final String TERM = "REPORT1403";
    private static final Sort NEWEST_FIRST = Sort.by("createdAt").descending().and(Sort.by("id").descending());
    private static final Pageable BY_NAME = PageRequest.of(0, 25, Sort.by("fileName").and(Sort.by("id")));
    private static final Pageable PAST_THE_END_BY_NAME = PageRequest.of(5, 1, Sort.by("fileName").and(Sort.by("id")));

    private static final String FILE_NAME = "ix_file_info_search_name_trgm";
    private static final String FILE_DESCRIPTION = "ix_file_info_search_description_trgm";
    private static final String REVISION_NAME = "ix_file_details_search_name_trgm";
    private static final String REVISION_DESCRIPTION = "ix_file_details_search_description_trgm";
    private static final String FOLDER_NAME = "ix_folder_search_name_trgm";
    private static final String FOLDER_LABEL = "ix_folder_search_display_name_trgm";

    /** Every SQL statement Hibernate prepares in this context, as it prepares it. */
    public static class Recorder implements StatementInspector {
        static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

        @Override
        public String inspect(String sql) {
            STATEMENTS.add(sql);
            return sql;
        }
    }

    @Autowired
    private FileInfoRepository fileInfoRepository;
    @Autowired
    private FileDetailsRepository fileDetailsRepository;
    @Autowired
    private FolderRepository folderRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        Recorder.STATEMENTS.clear();
    }

    @Test
    @DisplayName("the file list's search - the file, then every folder above it - uses the file and the folder indexes")
    void theFileListSearch() {
        fileInfoRepository.search(TERM, PageRequest.of(0, 20, NEWEST_FIRST));
        fileInfoRepository.search(TERM, PageRequest.of(5, 1, NEWEST_FIRST));

        assertEveryStatementUses(2, FILE_NAME, FILE_DESCRIPTION, FOLDER_NAME, FOLDER_LABEL);
    }

    @Test
    @DisplayName("the same search inside a reader's folders uses them too")
    void theFileListSearchWithinFolders() {
        fileInfoRepository.searchWithinFolders(TERM, Set.of(1, 2), PageRequest.of(0, 20, NEWEST_FIRST));
        fileInfoRepository.searchWithinFolders(TERM, Set.of(1, 2), PageRequest.of(5, 1, NEWEST_FIRST));

        assertEveryStatementUses(2, FILE_NAME, FILE_DESCRIPTION, FOLDER_NAME, FOLDER_LABEL);
    }

    @Test
    @DisplayName("the public list's search uses the revision indexes and the folder label's")
    void thePublicListSearch() {
        fileDetailsRepository.searchPublicFiles(TERM, PageRequest.of(0, 20, NEWEST_FIRST));
        fileDetailsRepository.searchPublicFiles(TERM, PageRequest.of(5, 1, NEWEST_FIRST));

        assertEveryStatementUses(2, REVISION_NAME, REVISION_DESCRIPTION, FOLDER_LABEL);
    }

    @Test
    @DisplayName("the explorer's and the tree's file searches use the file indexes")
    void theExplorerAndTreeFileSearches() {
        fileInfoRepository.searchFiles(null, TERM, BY_NAME);
        fileInfoRepository.searchFiles(null, TERM, PAST_THE_END_BY_NAME);
        fileInfoRepository.searchForTree(null, TERM, PageRequest.of(0, 20));

        assertEveryStatementUses(3, FILE_NAME, FILE_DESCRIPTION);
    }

    /**
     * Restricted to a reader's folders, the explorer's search is one table with two ways in: the
     * trigram indexes, or the folders' own ({@code ix_file_info_folder}) when there are few of
     * them - both right, and which is cheaper depends on the data. What must never happen is the
     * table read whole.
     */
    @Test
    @DisplayName("the explorer's search within a reader's folders never reads the files whole")
    void theExplorerSearchWithinFolders() {
        fileInfoRepository.searchFilesWithinFolders(null, TERM, Set.of(1, 2), BY_NAME);
        fileInfoRepository.searchFilesWithinFolders(null, TERM, Set.of(1, 2), PAST_THE_END_BY_NAME);

        for (String plan : plansOfRecordedSearches(2)) {
            assertThat(plan).doesNotContain("Seq Scan on file_info")
                    .containsAnyOf(FILE_NAME, "ix_file_info_folder");
        }
    }

    @Test
    @DisplayName("the explorer's folder search uses the folder indexes")
    void theFolderSearch() {
        folderRepository.searchFolders(null, TERM, "/", PageRequest.of(0, 50));

        assertEveryStatementUses(1, FOLDER_NAME, FOLDER_LABEL);
    }

    @Test
    @DisplayName("the indexes are on the expression the queries compare, and the extension is installed")
    void theIndexesExist() {
        List<String> definitions = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname LIKE '%\\_trgm'",
                String.class);

        assertThat(definitions).hasSize(6).allSatisfy(definition -> assertThat(definition)
                .contains("USING gin (replace(", "' '::text, ''::text) gin_trgm_ops)"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- the plan

    /**
     * At least {@code expected} searching statements were recorded, and each one's plan reads
     * every index named - a statement that searches without one of them has fallen back to
     * reading that table whole.
     */
    private void assertEveryStatementUses(int expected, String... indexes) {
        for (String plan : plansOfRecordedSearches(expected)) {
            assertThat(plan).contains(indexes);
        }
    }

    /**
     * The generic plan of each searching statement recorded - at least {@code expected} of them -
     * with only bitmap scans allowed, each preceded by its SQL so that a failure says which.
     */
    private List<String> plansOfRecordedSearches(int expected) {
        List<String> searches = Recorder.STATEMENTS.stream()
                .filter(sql -> sql.toLowerCase(Locale.ROOT).contains(" like "))
                .distinct()
                .toList();
        assertThat(searches).as("searching statements recorded").hasSizeGreaterThanOrEqualTo(expected);

        var container = TestDatabases.postgresql();
        String url = container.getJdbcUrl() + (container.getJdbcUrl().contains("?") ? "&" : "?")
                + "preferQueryMode=simple";
        try (Connection connection = DriverManager.getConnection(url, container.getUsername(), container.getPassword());
             Statement statement = connection.createStatement()) {
            // Only bitmap scans left: they need an index condition, so a plan that reads a
            // table without one of the searched indexes cannot hide behind a full index scan -
            // the planner's cheapest way round a disabled sequential scan on a near-empty table.
            statement.execute("SET enable_seqscan = off");
            statement.execute("SET enable_indexscan = off");
            statement.execute("SET enable_indexonlyscan = off");
            List<String> plans = new ArrayList<>();
            for (String sql : searches) {
                StringBuilder plan = new StringBuilder(sql).append('\n');
                try (ResultSet rows = statement.executeQuery("EXPLAIN (GENERIC_PLAN) " + numbered(sql))) {
                    while (rows.next()) {
                        plan.append(rows.getString(1)).append('\n');
                    }
                }
                plans.add(plan.toString());
            }
            return plans;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * JDBC's {@code ?} placeholders as PostgreSQL's {@code $1, $2 ...}, outside string literals.
     * A parameter that stands alone in {@code ? is not null} - the optional id - is given its type,
     * which a generic plan cannot infer from anything beside it.
     */
    static String numbered(String sql) {
        StringBuilder out = new StringBuilder(sql.length() + 16);
        boolean quoted = false;
        int parameter = 0;
        for (char c : sql.toCharArray()) {
            if (c == '\'') {
                quoted = !quoted;
            }
            if (c == '?' && !quoted) {
                out.append('$').append(++parameter);
            } else {
                out.append(c);
            }
        }
        return out.toString().replaceAll("(\\$\\d+) is not null", "$1::integer is not null");
    }
}
