package com.hnp.filemanagement;

import com.hnp.filemanagement.support.DatabaseSupport;
import com.hnp.filemanagement.support.ServiceIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every foreign key has an index that starts with its columns (2.7.4). PostgreSQL indexes the
 * referenced side of a key, never the referencing one: without it, deleting a folder, a user or
 * a file reads the whole referencing table once per row deleted, and a join on the key reads it
 * whole too. A new key without its index fails here, naming it.
 */
@ServiceIntegrationTest
class ForeignKeyIndexTest extends DatabaseSupport {

    /** Keys on tables that stay small, or are never deleted from - each with why. */
    private static final List<String> EXEMPT = List.of();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("every foreign key's columns lead some index of its table")
    void everyForeignKeyIsIndexed() {
        List<String> unindexed = jdbcTemplate.queryForList("""
                SELECT c.conrelid::regclass || '.' || c.conname || ' (' ||
                       (SELECT string_agg(a.attname, ', ' ORDER BY k.n)
                        FROM unnest(c.conkey) WITH ORDINALITY AS k(attnum, n)
                        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum) || ')'
                FROM pg_constraint c
                WHERE c.contype = 'f'
                  AND c.connamespace = 'public'::regnamespace
                  AND NOT EXISTS (
                      SELECT 1 FROM pg_index i
                      WHERE i.indrelid = c.conrelid
                        AND (i.indkey::int2[])[0:array_length(c.conkey, 1) - 1] = c.conkey)
                ORDER BY 1
                """, String.class);

        assertThat(unindexed).as("foreign keys with no index on their columns").isSubsetOf(EXEMPT);
    }
}
