package com.hnp.filemanagement;

import com.hnp.filemanagement.support.DatabaseSupport;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code docs/schema.md} describes the whole database as it stands after every migration, and this
 * is what keeps it true.
 *
 * <p>The migrations are the history; the document is the present. Nobody should have to replay
 * eleven files in their head to know what {@code file_info} looks like today, and a hand-maintained
 * description would drift the first time somebody added a column and forgot the doc. So the
 * description is <em>generated</em> - from the catalogue of a PostgreSQL that Flyway has just
 * migrated ({@code information_schema}, and {@code pg_index} / {@code pg_constraint} for what the
 * standard views do not say: expression indexes, and which index is a key) - and compared with what is committed. The part between the two markers in
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
class SchemaDocumentationTest extends DatabaseSupport {

    static final Path DOCUMENT = Path.of("docs/schema.md");
    static final String START = "<!-- generated from information_schema by SchemaDocumentationTest: do not edit below this line -->";
    static final String END = "<!-- end of generated section -->";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("docs/schema.md describes the migrated database exactly")
    void theDocumentMatchesTheMigratedSchema() throws IOException {
        String generated = render();
        // Line endings are the editor's business, not the schema's: git may check the file out
        // with CRLF on Windows, and that must not read as a schema change.
        String document = Files.exists(DOCUMENT)
                ? Files.readString(DOCUMENT, StandardCharsets.UTF_8).replace("\r\n", "\n")
                : "";

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
        String flyway = jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class);

        StringBuilder out = new StringBuilder();
        out.append("\n\n_As of migration `V").append(flyway).append("`. Types and defaults are PostgreSQL's own; ")
                .append("every table is in the `public` schema of a `UTF8` database with ICU's root collation ")
                .append("([deployment.md](deployment.md#creating-the-database-and-its-account))._\n");

        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """, String.class);

        for (String table : tables) {
            out.append("\n### `").append(table).append("`\n\n");
            renderColumns(out, table);
            renderKeys(out, table);
        }
        return out.toString();
    }

    private void renderColumns(StringBuilder out, String table) {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT column_name, data_type, character_maximum_length, datetime_precision,
                       is_nullable, column_default, is_identity
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                ORDER BY ordinal_position
                """, table);

        out.append("| Column | Type | Null | Default | Notes |\n|---|---|---|---|---|\n");
        for (Map<String, Object> column : columns) {
            String type = switch (text(column.get("data_type"))) {
                case "character varying" -> "varchar(" + column.get("character_maximum_length") + ")";
                case "timestamp without time zone" -> "timestamp(" + column.get("datetime_precision") + ")";
                case "timestamp with time zone" -> "timestamptz(" + column.get("datetime_precision") + ")";
                default -> text(column.get("data_type"));
            };
            String defaultValue = plainDefault(column.get("column_default"));
            out.append("| `").append(column.get("column_name")).append("` | `").append(type).append("` | ")
                    .append("YES".equals(column.get("is_nullable")) ? "yes" : "no").append(" | ")
                    .append(defaultValue == null ? "" : "`" + defaultValue + "`").append(" | ")
                    .append("YES".equals(column.get("is_identity")) ? "identity" : "").append(" |\n");
        }
    }

    private void renderKeys(StringBuilder out, String table) {
        List<String> lines = new ArrayList<>();

        // Every index with its columns - or the expression it is on, such as upper(name) - in order:
        // the primary key first, then the unique ones, then the plain ones after the foreign keys.
        List<Map<String, Object>> indexes = jdbcTemplate.queryForList("""
                SELECT i.relname AS name, ix.indisprimary AS primary_key, ix.indisunique AS is_unique,
                       (SELECT string_agg(pg_get_indexdef(ix.indexrelid, k, true), '|' ORDER BY k)
                        FROM generate_series(1, ix.indnkeyatts) AS k) AS columns,
                       pg_get_indexdef(ix.indexrelid) AS definition
                FROM pg_index ix
                    JOIN pg_class i ON i.oid = ix.indexrelid
                    JOIN pg_class t ON t.oid = ix.indrelid
                    JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = 'public' AND t.relname = ?
                ORDER BY ix.indisprimary DESC, ix.indisunique DESC, i.relname
                """, table);
        List<String> plain = new ArrayList<>();
        for (Map<String, Object> index : indexes) {
            String columns = columnsOf(text(index.get("columns")));
            if (text(index.get("definition")).contains("varchar_pattern_ops")) {
                // The operator class is what lets a B-tree serve a prefix LIKE under a linguistic
                // collation; without it every subtree query is a sequential scan.
                columns += ", for prefix `LIKE` (`varchar_pattern_ops`)";
            }
            if (text(index.get("definition")).contains("gin_trgm_ops")) {
                // A trigram GIN index: what serves a LIKE '%term%', which no B-tree can (issue 21).
                columns += ", trigram GIN for `LIKE '%term%'` (`gin_trgm_ops`)";
            }
            if (Boolean.TRUE.equals(index.get("primary_key"))) {
                lines.add("* **primary key** " + columns);
            } else if (Boolean.TRUE.equals(index.get("is_unique"))) {
                lines.add("* **unique** `" + index.get("name") + "` (" + columns + ")");
            } else {
                plain.add("* **index** `" + index.get("name") + "` (" + columns + ")");
            }
        }

        List<Map<String, Object>> foreignKeys = jdbcTemplate.queryForList("""
                SELECT con.conname AS name,
                       (SELECT string_agg(a.attname, '|' ORDER BY k.ord)
                        FROM unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
                            JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum) AS columns,
                       rf.relname AS target,
                       (SELECT string_agg(a.attname, '|' ORDER BY k.ord)
                        FROM unnest(con.confkey) WITH ORDINALITY AS k(attnum, ord)
                            JOIN pg_attribute a ON a.attrelid = con.confrelid AND a.attnum = k.attnum) AS target_columns,
                       con.confdeltype::text AS on_delete
                FROM pg_constraint con
                    JOIN pg_class cl ON cl.oid = con.conrelid
                    JOIN pg_class rf ON rf.oid = con.confrelid
                    JOIN pg_namespace n ON n.oid = cl.relnamespace
                WHERE con.contype = 'f' AND n.nspname = 'public' AND cl.relname = ?
                ORDER BY con.conname
                """, table);
        for (Map<String, Object> key : foreignKeys) {
            String onDelete = switch (text(key.get("on_delete"))) {
                case "c" -> ", on delete cascade";
                case "n" -> ", on delete set null";
                case "d" -> ", on delete set default";
                default -> "";
            };
            lines.add("* **foreign key** `" + key.get("name") + "` " + columnsOf(text(key.get("columns")))
                    + " → `" + key.get("target") + "` (" + columnsOf(text(key.get("target_columns"))) + ")" + onDelete);
        }
        lines.addAll(plain);

        if (!lines.isEmpty()) {
            out.append("\n").append(String.join("\n", lines)).append("\n");
        }
    }

    /** {@code name|upper(file_name::text)} as {@code `name`, `upper(file_name)`}. */
    private static String columnsOf(String list) {
        return Arrays.stream(list.split("\\|"))
                .map(column -> "`" + column.replace("::text", "") + "`")
                .collect(Collectors.joining(", "));
    }

    /** {@code 'READ'::character varying} as {@code READ}; an identity has no default of its own. */
    private static String plainDefault(Object value) {
        if (value == null) {
            return null;
        }
        Matcher quoted = Pattern.compile("^'(.*)'::[a-z ]+$").matcher(value.toString());
        return quoted.matches() ? quoted.group(1) : value.toString();
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
