package com.hnp.filemanagement;

import com.hnp.filemanagement.support.MySqlSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code docs/schema.md} describes the whole database as it stands after every migration, and this
 * is what keeps it true.
 *
 * <p>The migrations are the history; the document is the present. Nobody should have to replay
 * eleven files in their head to know what {@code file_info} looks like today, and a hand-maintained
 * description would drift the first time somebody added a column and forgot the doc. So the
 * description is <em>generated</em> - from {@code information_schema} of a database that Flyway has
 * just migrated - and compared with what is committed. The part between the two markers in
 * {@code docs/schema.md} is the generated part; everything above the first marker is written by hand
 * and left alone.
 *
 * <p>When a migration changes the schema, this test fails and says so. Regenerate with
 *
 * <pre>./mvnw test -Dtest=SchemaDocumentationTest -DargLine=-Dschema.doc.write=true</pre>
 *
 * and commit the result with the migration.
 */
@SpringBootTest
class SchemaDocumentationTest extends MySqlSupport {

    static final Path DOCUMENT = Path.of("docs/schema.md");
    static final String START = "<!-- generated from information_schema by SchemaDocumentationTest: do not edit below this line -->";
    static final String END = "<!-- end of generated section -->";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("docs/schema.md describes the migrated database exactly")
    void theDocumentMatchesTheMigratedSchema() throws IOException {
        String generated = render();
        String document = Files.exists(DOCUMENT) ? Files.readString(DOCUMENT, StandardCharsets.UTF_8) : "";

        if (Boolean.getBoolean("schema.doc.write")) {
            Files.writeString(DOCUMENT, replaceGeneratedSection(document, generated), StandardCharsets.UTF_8);
            return;
        }

        int start = document.indexOf(START);
        int end = document.indexOf(END);
        assertThat(start).as("docs/schema.md has the start marker").isNotNegative();
        assertThat(end).as("docs/schema.md has the end marker").isGreaterThan(start);

        String committed = document.substring(start + START.length(), end).strip();
        assertThat(committed)
                .as("docs/schema.md is out of date. Regenerate it with:\n"
                        + "  ./mvnw test -Dtest=SchemaDocumentationTest -DargLine=-Dschema.doc.write=true\n"
                        + "and commit it together with the migration that changed the schema.")
                .isEqualTo(generated.strip());
    }

    // ---------------------------------------------------------------- rendering

    private String render() {
        String schema = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        String flyway = jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1",
                String.class);

        StringBuilder out = new StringBuilder();
        out.append("\n\n_As of migration `V").append(flyway).append("`. Types and defaults are MySQL's own; ")
                .append("every table is InnoDB, `utf8mb4` / `utf8mb4_unicode_ci` unless a column says otherwise._\n");

        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = ? AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """, String.class, schema);

        for (String table : tables) {
            out.append("\n### `").append(table).append("`\n\n");
            renderColumns(out, schema, table);
            renderKeys(out, schema, table);
        }
        return out.toString();
    }

    private void renderColumns(StringBuilder out, String schema, String table) {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, column_type, is_nullable, column_default, extra, character_set_name, collation_name
                FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ?
                ORDER BY ordinal_position
                """, schema, table);

        out.append("| Column | Type | Null | Default | Notes |\n|---|---|---|---|---|\n");
        for (Map<String, Object> column : columns) {
            List<String> notes = new ArrayList<>();
            String extra = text(column.get("extra")).toLowerCase();
            if (extra.contains("auto_increment")) {
                notes.add("auto-increment");
            }
            String charset = text(column.get("character_set_name"));
            if (!charset.isEmpty() && !charset.equals("utf8mb4")) {
                notes.add(charset);
            }
            Object defaultValue = column.get("column_default");
            out.append("| `").append(column.get("column_name")).append("` | `")
                    .append(column.get("column_type")).append("` | ")
                    .append("YES".equals(column.get("is_nullable")) ? "yes" : "no").append(" | ")
                    .append(defaultValue == null ? "" : "`" + defaultValue + "`").append(" | ")
                    .append(String.join(", ", notes)).append(" |\n");
        }
    }

    private void renderKeys(StringBuilder out, String schema, String table) {
        // Constraints, with their columns in order.
        List<Map<String, Object>> keyColumns = jdbcTemplate.queryForList("""
                SELECT tc.constraint_name, tc.constraint_type, kcu.column_name,
                       kcu.referenced_table_name, kcu.referenced_column_name, rc.delete_rule, rc.update_rule
                FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu
                        ON kcu.constraint_schema = tc.constraint_schema
                       AND kcu.constraint_name = tc.constraint_name
                       AND kcu.table_name = tc.table_name
                    LEFT JOIN information_schema.referential_constraints rc
                        ON rc.constraint_schema = tc.constraint_schema
                       AND rc.constraint_name = tc.constraint_name
                       AND rc.table_name = tc.table_name
                WHERE tc.table_schema = ? AND tc.table_name = ?
                ORDER BY FIELD(tc.constraint_type, 'PRIMARY KEY', 'UNIQUE', 'FOREIGN KEY'), tc.constraint_name, kcu.ordinal_position
                """, schema, table);

        Map<String, List<Map<String, Object>>> byConstraint = new LinkedHashMap<>();
        for (Map<String, Object> row : keyColumns) {
            byConstraint.computeIfAbsent(text(row.get("constraint_name")), k -> new ArrayList<>()).add(row);
        }

        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : byConstraint.entrySet()) {
            List<Map<String, Object>> rows = entry.getValue();
            String type = text(rows.getFirst().get("constraint_type"));
            String columns = rows.stream().map(r -> "`" + r.get("column_name") + "`").collect(Collectors.joining(", "));
            switch (type) {
                case "PRIMARY KEY" -> lines.add("* **primary key** " + columns);
                case "UNIQUE" -> lines.add("* **unique** `" + entry.getKey() + "` (" + columns + ")");
                case "FOREIGN KEY" -> {
                    Map<String, Object> first = rows.getFirst();
                    String delete = text(first.get("delete_rule"));
                    lines.add("* **foreign key** `" + entry.getKey() + "` " + columns + " → `"
                            + first.get("referenced_table_name") + "` (`" + first.get("referenced_column_name") + "`)"
                            + ("NO ACTION".equals(delete) || "RESTRICT".equals(delete) ? "" : ", on delete " + delete.toLowerCase()));
                }
                default -> lines.add("* " + type.toLowerCase() + " `" + entry.getKey() + "` (" + columns + ")");
            }
        }

        // Plain indexes: what is left once the ones backing a constraint are removed.
        List<Map<String, Object>> indexColumns = jdbcTemplate.queryForList("""
                SELECT index_name, column_name
                FROM information_schema.statistics
                WHERE table_schema = ? AND table_name = ? AND non_unique = 1
                ORDER BY index_name, seq_in_index
                """, schema, table);
        Map<String, List<String>> byIndex = new LinkedHashMap<>();
        for (Map<String, Object> row : indexColumns) {
            byIndex.computeIfAbsent(text(row.get("index_name")), k -> new ArrayList<>())
                    .add("`" + row.get("column_name") + "`");
        }
        for (Map.Entry<String, List<String>> index : byIndex.entrySet()) {
            if (!byConstraint.containsKey(index.getKey())) {
                lines.add("* **index** `" + index.getKey() + "` (" + String.join(", ", index.getValue()) + ")");
            }
        }

        if (!lines.isEmpty()) {
            out.append("\n").append(String.join("\n", lines)).append("\n");
        }
    }

    private static String replaceGeneratedSection(String document, String generated) {
        int start = document.indexOf(START);
        int end = document.indexOf(END);
        String head = start < 0 ? "# Database schema\n\n" : document.substring(0, start);
        String tail = end < 0 ? "\n" : document.substring(end + END.length());
        return head + START + "\n" + generated.strip() + "\n\n" + END + tail;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
