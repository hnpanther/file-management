# Vendored front-end assets

These files are committed on purpose. The UI must not depend on Maven resolving a WebJar at
runtime: an IDE or CI whose local repository is only partly populated serves a 404 for every
asset, and the browser reports it as `Uncaught SyntaxError: Unexpected token '<'` on a page with
no styling. Vendored files are part of the source tree, so the interface works regardless of the
state of `~/.m2` and with no network access at any point.

| Asset | Version | Licence | Upstream |
|---|---:|---|---|
| Alpine.js (`alpine/alpine.min.js`) | 3.16.3 | MIT | https://alpinejs.dev |
| jQuery (`jquery/jquery.min.js`) | 4.0.0 | MIT | https://jquery.com |
| Select2 (`select2/`) | 4.1.0 | MIT | https://select2.org |
| Bootstrap Icons (`bootstrap-icons/`) | 1.13.1 | MIT | https://icons.getbootstrap.com |

The Vazirmatn typeface is vendored separately under `static/css/fonts/vazirmatn/` and is licensed
under SIL OFL 1.1; its licence file sits beside it.

`bootstrap-icons.min.css` references `fonts/bootstrap-icons.woff2` relative to itself, which is why
the font files keep their `fonts/` subdirectory.

## Updating

Assets were extracted from the corresponding WebJars. To refresh one, take the file out of the
published package for the new version, replace it here, and update the version in this table and
in `docs/ui.md`. `UiResourceTest` checks that every file listed here is present and non-empty.

jQuery and Select2 are transitional: they exist only for page AJAX and dependent dropdowns that
have not yet been converted to `fetch` + Alpine. Delete them once the last consumer is gone.
