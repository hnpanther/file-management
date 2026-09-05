/**
 * The programmatic API, for callers that are not this application's own pages.
 *
 * <p>It is intentionally small - upload, delete a version, download - and versioned in its path
 * ({@code /api/v1/files}), which the {@code /resource/**} endpoints are not: those may change
 * whenever the page that calls them changes, these may not.
 *
 * <p>Messages from this package are English, because its callers are programs. The Persian wording
 * belongs to the pages.
 */
package com.hnp.filemanagement.api;
