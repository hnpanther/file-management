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
        // Without a class grant, the class rows are left out as well as the class test (readable): never one without
        // the other, or a class grant's row - its scope's path, '/' for everywhere - would cover what it scopes whole.
        return scope.unrestricted() ? "" : "WITH granted AS MATERIALIZED (SELECT g.path, g.class_key, g.class_id FROM ("
                + GrantedFolderPath.SQL + ") g WHERE g.user_id = :userId AND g.api_key_id = :apiKeyId"
                + (scope.classes() ? "" : " AND g.class_id = 0") + ")\n";
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

    /**
     * Whether the folder {@code alias} is readable in the scope: under one of the grants - and, for a class
     * grant (roadmap Phase 14), of its class or beneath a folder of it ({@code class_path}; a folder grant's
     * key is empty, which every class path matches). For an unrestricted reader, every folder but those in the
     * recycle bin ({@link Bin}) - a restricted one's grants never reach it.
     */
    public static String readable(FolderReadScope scope, String alias) {
        if (scope.unrestricted()) {
            // Everything but the recycle bin (roadmap 13.1), which no grant reaches and an administrator does not either.
            return "(" + Bin.notIn(alias) + ")";
        }
        // The class test only for a reader who holds a class grant (FolderReadScope.classes): every other reader's
        // statement is what it was. As strpos(...) > 0, not a second LIKE: a LIKE of an unknown pattern is estimated
        // to keep one row in two hundred, and two of them made a reader's folders look so few that the content
        // search was planned from them (34 s for 0.3).
        return "EXISTS (SELECT 1 FROM granted WHERE " + alias + ".path LIKE granted.path || '%'"
                + (scope.classes() ? " AND strpos(" + alias + ".class_path, granted.class_key) > 0" : "") + ")";
    }

    /**
     * {@link #granted}, and after it {@code signposts}: the folders on the way to what a class grant covers - every
     * folder whose id is on the path of a folder of the class within the grant's scope, or on the path of a scope
     * folder that lies beneath a folder of the class (roadmap Phase 14). A path names its folder and every folder
     * above it, so this is one read of the folders of the granted classes, made a set the statement hashes once;
     * each folder asked is then a lookup - where asking, folder by folder, whether a folder of the class lies
     * below took a probe each, and its estimate alone made PostgreSQL compile the statement (half a second at
     * five thousand contracts, for a query of ten milliseconds). For {@link #visible}; nothing for an
     * unrestricted scope.
     */
    public static String grantedWithSignposts(FolderReadScope scope) {
        if (scope.unrestricted() || !scope.classes()) {
            return granted(scope);
        }
        return granted(scope).stripTrailing() + """
                ,
                signposts AS MATERIALIZED (
                    SELECT DISTINCT CAST(a.id AS integer) AS id
                    FROM granted g
                    JOIN folder d ON d.class_id = g.class_id AND d.path LIKE g.path || '%'
                    CROSS JOIN LATERAL unnest(string_to_array(btrim(d.path, '/'), '/')) AS a(id)
                    WHERE g.class_id <> 0
                    UNION
                    SELECT CAST(a.id AS integer)
                    FROM granted g
                    JOIN folder s ON s.path = g.path AND s.class_path LIKE '%' || g.class_key || '%'
                    CROSS JOIN LATERAL unnest(string_to_array(btrim(s.path, '/'), '/')) AS a(id)
                    WHERE g.class_id <> 0)
                """;
    }

    /**
     * Whether the folder {@code alias} may be walked into: readable, or on the way to something readable - above a
     * folder grant, or one of the {@code signposts} of the class grants. What {@code FolderAccess#visible} answers.
     * Asked after {@link #grantedWithSignposts}, never after {@link #granted} alone.
     */
    public static String visible(FolderReadScope scope, String alias) {
        if (scope.unrestricted()) {
            return "(" + Bin.notIn(alias) + ")";
        }
        return "(" + readable(scope, alias) + " OR EXISTS (SELECT 1 FROM granted g WHERE g.class_id = 0 AND g.path LIKE "
                + alias + ".path || '%' AND g.path <> " + alias + ".path)"
                + (scope.classes() ? " OR " + alias + ".id IN (SELECT id FROM signposts)" : "") + ")";
    }

    /** The parameters {@link #granted} names. */
    public static MapSqlParameterSource parameters(FolderReadScope scope) {
        return new MapSqlParameterSource().addValue("userId", scope.userId()).addValue("apiKeyId", scope.apiKeyId());
    }
}
