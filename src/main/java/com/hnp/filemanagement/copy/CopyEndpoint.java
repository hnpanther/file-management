package com.hnp.filemanagement.copy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * One side of a {@link DatabaseCopy}: where the database is and who to be there.
 *
 * <p>{@link #toString} names the URL only, with any {@code password=} parameter in it masked - a
 * record prints every component, and this one carries a password to a production database.
 */
public record CopyEndpoint(String url, String username, String password) {

    public CopyEndpoint {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("a database URL is required");
        }
    }

    Connection connect() throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    /** The URL with any password parameter it carries masked - what a log line may show. */
    public String describe() {
        return url.replaceAll("(?i)(password=)[^&;]*", "$1***");
    }

    @Override
    public String toString() {
        return "CopyEndpoint[" + describe() + "]";
    }
}
