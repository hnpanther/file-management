package com.hnp.filemanagement.support;

import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A test that is about MySQL itself and so has nothing to say on a PostgreSQL run: the MySQL
 * migrations run against rows ({@code PortableSchemaMigrationTest}), the history those migrations
 * left ({@code VendorMigrationLayoutTest}), and {@code docs/schema.md}, generated from MySQL until
 * release C. Skipped under {@code -Ddb=postgresql}; everything else runs on both.
 *
 * <p>Not a way to park a test that fails on PostgreSQL. A query that behaves differently on the two
 * is the bug release B exists to find.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@DisabledIfSystemProperty(named = "db", matches = "(?i)\\s*(postgresql|postgres|pg)\\s*",
        disabledReason = "about MySQL itself; this run is on PostgreSQL (-Ddb)")
public @interface MySqlOnly {
}
