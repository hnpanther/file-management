package com.hnp.filemanagement.folder.domain;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * A reader's {@link FolderReadScope} as SQL, for the queries written by hand - the content search
 * (2.15.0) and the reports (roadmap 12.9): the reader's grants read once into a CTE, and a condition
 * that a folder is under one of them. The grants are {@link GrantedFolderPath}'s, so these queries and
 * the entity lists can never disagree about who may read what; an unrestricted scope asks nothing.
 * A scope of nothing is the caller's to answer before it asks.
 */
public final class ReadScopeSql {

    private ReadScopeSql() {
    }

    /**
     * {@code WITH granted AS MATERIALIZED (...)} and a newline, or the empty string for an
     * unrestricted scope - to be followed by the statement's own CTEs (with a comma) or its SELECT.
     */
    public static String granted(FolderReadScope scope) {
        return scope.unrestricted() ? "" : "WITH granted AS MATERIALIZED (SELECT g.path FROM (" + GrantedFolderPath.SQL
                + ") g WHERE g.user_id = :userId AND g.api_key_id = :apiKeyId)\n";
    }

    /**
     * {@code WITH granted ..., <more>} - the grants, then the statement's own CTEs ({@code more}, without
     * {@code WITH}); either may be absent.
     */
    public static String with(FolderReadScope scope, String more) {
        String granted = granted(scope).stripTrailing();
        if (granted.isEmpty()) {
            return more.isEmpty() ? "" : "WITH " + more + "\n";
        }
        return more.isEmpty() ? granted + "\n" : granted + ",\n" + more + "\n";
    }

    /** Whether the folder {@code alias} is readable in the scope: under one of the grants. */
    public static String readable(FolderReadScope scope, String alias) {
        return scope.unrestricted() ? "TRUE"
                : "EXISTS (SELECT 1 FROM granted WHERE " + alias + ".path LIKE granted.path || '%')";
    }

    /** The parameters {@link #granted} names. */
    public static MapSqlParameterSource parameters(FolderReadScope scope) {
        return new MapSqlParameterSource().addValue("userId", scope.userId()).addValue("apiKeyId", scope.apiKeyId());
    }
}
