# UI guidelines

This document defines the visual and user-experience conventions for the application's Thymeleaf
interface. New pages must remain consistent with the current design, render Persian and mixed-direction
content correctly, and run without any production-time internet access.

## Design direction

The interface is a clear, professional administrative workspace built on an application shell: a
flat deep-green top bar for identity and the account menu, and a light sidebar holding the primary
navigation. Surfaces, borders, and the page background are **neutral grey**; green is reserved for
the top bar, primary actions, and active state. Blue or navy must not become the primary color.

Restraint is what makes the product read as enterprise software rather than a consumer app: shallow
elevation, small corner radii, no hover lift on controls, no decorative gradients or imagery.

The main task of a page should be visible in the first viewport. Avoid marketing-style hero sections,
decorative imagery, and excessive vertical spacing in working screens.

Design priorities, in order:

1. Readability and task completion speed.
2. Correct rendering of Persian text, numbers, and Latin file names.
3. Keyboard and screen-reader accessibility.
4. Mobile support and browser text zoom.
5. Visual consistency across all routes.

## Offline runtime assets

Nothing the interface needs may come from the internet, and nothing may depend on Maven resolving
an artifact at runtime. Every asset is a file in the source tree under `static/`.

| Asset | Version | Served from |
|---|---:|---|
| Tailwind CSS (compiled) | 4.3.3 | `static/css/app.css` |
| Alpine.js | 3.16.3 | `static/vendor/alpine/` |
| Bootstrap Icons | 1.13.1 | `static/vendor/bootstrap-icons/` |
| jQuery *(transitional)* | 4.0.0 | `static/vendor/jquery/` |
| Select2 *(transitional)* | 4.1.0 | `static/vendor/select2/` |
| Vazirmatn | 33.003 variable | `static/css/fonts/vazirmatn/` |

**Never load an asset from `/webjars/`.** WebJar URLs only resolve if Maven populated the local
repository with that exact artifact. An IDE or CI whose `~/.m2` is partly populated serves a 404
for every asset; because a 404 for a script is delivered as HTML, the browser reports
`Uncaught SyntaxError: Unexpected token '<'` on a completely unstyled page. This failure has been
hit twice. Vendored files cannot fail that way. `UiResourceTest` fails the build if a `/webjars/`
URL reappears or a vendored file goes missing.

Licences and provenance for the vendored files are recorded in `static/vendor/README.md`.
That inventory also records the date and scope of the latest upstream security review.

## Styling: Tailwind

The stylesheet is written in `src/main/frontend/app.css` and compiled with the Tailwind CLI:

```
npm run build:css     # once
npm run watch:css     # while working on the UI
```

### What Node is for, and what it is not for

`node_modules/` exists in this repository for exactly one job: running the Tailwind CLI to turn the
stylesheet source into the stylesheet the application serves.

```
src/main/frontend/app.css                 the file you edit (Tailwind directives + @layer blocks)
        |
        |  npm run build:css              the only thing Node ever does here
        v
src/main/resources/static/css/app.css     generated, minified, and COMMITTED
```

`package.json` has two dependencies and both are `devDependencies`: `tailwindcss` and
`@tailwindcss/cli`, pinned to the same version. There is no bundler, no framework, no build step for
JavaScript - the vendored scripts under `static/vendor/` are shipped as they were downloaded.
`node_modules/` is about 20 MB, is in `.gitignore`, and is restored with `npm install`.

**The application never needs Node.** The compiled `app.css` is committed, so:

* `./mvnw package` works with `node_modules` deleted - checked by deleting it and building;
* `./mvnw verify` needs a JDK and a Docker daemon, and nothing else;
* the running application has no relationship with Node at all;
* a deployment needs a JDK - see [deployment.md](deployment.md).

`UiResourceTest.nothingInTheBuildRequiresNode` keeps it that way: it fails if `frontend-maven-plugin`
or `exec-maven-plugin` ever appears in `pom.xml`, which are the two ways a Maven build starts
depending on Node.

### The rule, and what happens if you forget it

**If you edit `src/main/frontend/app.css`, run `npm run build:css` and commit both files in the same
commit.**

Forgetting is quiet. The source has your new rules, the served file does not, and the application
starts and runs with no error anywhere - the new styles simply are not there, and a class that was
never compiled renders as an unstyled element. Tailwind only emits the utilities it finds in the
sources listed in the `@source` lines at the top of `app.css`, so a utility used in a *new* template
does not exist in the compiled file until the next build either.

### Why the output is committed rather than built by Maven

Nothing the interface needs may be fetched at runtime or resolved by Maven at request time. Both
failure modes reach the browser identically: a 404 for a script is served as HTML, so the page
arrives unstyled with `Uncaught SyntaxError: Unexpected token '<'` in the console and nothing that
points at the real cause. That has happened twice here with `/webjars/` URLs on a machine whose
`~/.m2` was only partly populated.

Committing the generated file removes the whole class of failure, and keeps the build reproducible
for anyone with a JDK - including a CI runner and an air-gapped server. It is also the pattern any
future front-end work has to follow: a framework with a bundler would have to commit its output the
same way, or `nothingInTheBuildRequiresNode` and the JDK-only deployment promise both stop being
true.

Right-to-left needs no separate stylesheet: Tailwind logical utilities (`ms-*`, `me-*`, `ps-*`,
`pe-*`, `start-*`, `end-*`, `text-start`, `text-end`, `border-e`) flip automatically under
`dir="rtl"`.

### The compatibility layer

The page templates are Bootstrap 3-era markup - `glyphicon`, `fa`, `well`, `control-label`,
`form-horizontal`, `col-md-6`. Rather than rewrite every template at once, the `@layer components`
block in `app.css` re-implements those class names on Tailwind, so the whole application picked up
the new design in one step. **This layer is temporary.** Convert page bodies to plain utilities one
at a time, and delete each block here as its last consumer disappears. Legacy icon markup
(`glyphicon`, `fa`) is hidden outright, because no stylesheet ever backed it.

## Behaviour: Alpine.js

Interaction state is declared in the markup - the sidebar drawer, dropdowns, disclosure. Bootstrap's
JavaScript is gone, so `data-bs-*` attributes do nothing; use Alpine instead:

```html
<div x-data="{ open: false }" @click.outside="open = false" @keydown.escape="open = false">
  <button @click="open = !open" :aria-expanded="open">...</button>
  <div x-show="open" x-cloak x-transition.opacity.duration.120ms>...</div>
</div>
```

Always pair `x-show` with `x-cloak`, or the element flashes before Alpine initialises.

`static/js/app.js` holds only what is genuinely global: active-navigation marking, the search-box
Enter key, `window.appCsrf()` for hand-written requests, and `window.appSessionExpired()`.

### An expired session

A resource endpoint answers a script's call with `401` once the session has ended - never with a
redirect to the login page (issue 77; the security chain tells a script from a person by
`X-Requested-With: XMLHttpRequest` or an `Accept` that asks for JSON). Every jQuery page handles it
for free: `app.js` registers one `ajaxError` hook that calls `window.appSessionExpired()`, which
goes to `/login`. A page that uses `fetch` checks `response.status === 401` itself and calls the
same function - or, like the explorer, shows its own message with a way back in, because it has
state on screen worth keeping. Send both headers on every hand-written request:

```js
fetch(url, { headers: { "Accept": "application/json", "X-Requested-With": "XMLHttpRequest" } })
```

Without them the request looks like a person navigating and gets the redirect.

jQuery and Select2 are still loaded for page AJAX and dependent dropdowns that have not been
converted to `fetch` + Alpine. They are transitional; remove them with their last consumer.

### Inline JavaScript in templates

A `<script>` that needs a message or a URL from Thymeleaf must declare `th:inline="javascript"`.
Inlining then emits a **quoted, escaped** JavaScript literal, so the expression must not be wrapped
in quotes of your own:

```html
<script th:inline="javascript">
    let contextPath = [[@{/}]];              /* correct   -> "/"   */
    if (confirm([[#{js.confirmDelete}]])) {} /* correct           */
</script>
```

Writing `"[[@{/}]]"` yields a doubly quoted value and breaks the script; omitting `th:inline="javascript"`
produces an unquoted bare value and breaks it the other way. Both mistakes have been made here.

## Shared template assets

Shared dependencies are declared in `templates/fragments.html`:

```html
<th:block th:replace="~{fragments.html :: head-assets}"></th:block>
<th:block th:replace="~{fragments.html :: scripts}"></th:block>
```

Use `head-assets-select` and `scripts-select` only on pages that actually use Select2. Do not create a
page-specific stylesheet unless the page contains a genuinely unique component. Extend the shared
tokens and component rules in `static/css/app.css` instead. Shared behavior belongs in
`static/js/app.js`; page-specific AJAX logic may remain next to its Thymeleaf template.

Every user-facing template starts with:

```html
<html lang="fa" dir="rtl"
      xmlns:th="http://www.thymeleaf.org">
```

Templates that use authorization attributes also declare:

```html
xmlns:sec="http://www.thymeleaf.org/extras/spring-security"
```

## Language, typography, and bidirectional text

The user interface is Persian and right-to-left. Prose and form content stay at 16px; interface
chrome - data tables, navigation, buttons, form controls - drops one step to 14px, which is how
document-management products stay dense without hurting the
readability of actual content. Nothing goes below 14px except small-caps section labels and badges. Write concise Persian labels with correct spacing and half-spaces.

Vazirmatn is the base typeface and includes suitable Latin glyphs. Apply the `technical` class to file
names, usernames, identifiers, extensions, and other technical values. It uses
`unicode-bidi: plaintext` to prevent mixed Persian and Latin content from being reordered incorrectly.
Add `dir="ltr"` when a complete value has an inherently left-to-right direction, such as a hash or full
filesystem path. Never change the direction of an entire page or table to fix one Latin value.

Source-code comments and project documentation are written in English. Visible interface copy remains
Persian unless a technical term must retain its established English form.

### Dates

Dates are shown in the Jalali (Solar Hijri) calendar with Persian digits, and the time alongside
where the value has one - `1405/06/24 07:27`, rendered in Persian digits. The database and every
DTO stay Gregorian; only the rendering changes, at the point of use.

* Server-rendered pages call the `jalali` bean: `th:text="${@jalali.format(fd.getCreatedAt())}"`.
  It is `JalaliDate` in `util/`, a port of the *jalaali-js* arithmetic with no dependency, and
  returns an empty string for a missing value so a page never fails on one.
* Pages that render from JSON in the browser (the explorer) use `Intl.DateTimeFormat("fa-IR")`,
  which the browser ships with - no locale data is downloaded.

Give a date cell `dir="ltr"`: the digits are Persian but the order of the parts is left-to-right.
Never print a raw `LocalDateTime` into a template; the ISO form is what an unformatted value
looks like, and `FilePreviewPageTest` fails the file page if one appears.

## Interface copy

Persian copy lives in `src/main/resources/messages.properties`, beside `application.properties`.
Templates reference a key with Thymeleaf's `#{...}`; Java resolves one through `MessageSource`.

```html
<h1 th:text="#{login.title}">[prototype text, replaced at render time]</h1>
<button th:aria-label="#{nav.aria.open}">...</button>
<title th:text="#{login.pageTitle(#{app.name})}">[title, takes the app name as {0}]</title>
```

Keys are grouped by area: `app.*`, `nav.*`, `login.*`, `error.*`. Add the key to the bundle first,
then reference it.

Persian left *between tags* is Thymeleaf prototype text - it is replaced at render time and makes a
raw template readable in a browser, so keep it. Persian inside an **attribute** is a real
untranslated string, because nothing replaces it; `MessageBundleTest` fails the build on one.

`MessageBundleTest` also verifies that every `#{key}` used by a template or by Java exists in the
bundle, and that the bundle decodes as UTF-8.

All current user-facing templates are registered in `MessageBundleTest.EXTERNALISED`. Keep that list
complete when adding a template. Persian literals still inside controllers are tracked as
[issue 26](issues.md#26-persian-ui-strings-hardcoded-in-java--s3).

## Design tokens

Colors, corner radii, shadows, and shell dimensions are defined in the Tailwind `@theme` block in
`src/main/frontend/app.css`. Deep green represents primary actions, a brighter green represents
positive emphasis, and red is reserved for destructive actions. Reuse the `brand-*`, `ink-*`, and
semantic state scales instead of adding arbitrary colors to templates.

Spacing follows an approximate 4px rhythm. The default card radius is `0.5rem` (`--radius-card`),
controls use `0.375rem` (`--radius-control`), and primary controls are approximately 44px tall.
Define text sizes in `rem` so browser font scaling remains effective.

## Page patterns

### Lists

Use `container-fluid app-content`, followed by a `page-header` and a `card`. Place search controls above
the table at the same visual level. Use a side filter only when the page has more than two independent
filters. Tables belong inside `table-responsive`, column headers require `scope="col"`, and empty
results must show a concise, actionable empty state instead of an empty table. Keep row actions short
and group related actions together.

### Forms

Place the page title before the form and each label above its control. A placeholder never replaces a
label. Forms use `form-grid` for a responsive one/two-column layout and `form-actions` for a consistent
footer. Put naming rules and upload restrictions next to the relevant control with `form-text`. Use one
clear primary action such as Save or Upload and a secondary Back action. Success messages use
`role="status"`; correctable errors use `role="alert"`. Long identifier collections, such as role
permissions, use `permission-grid`; identifiers must be allowed to wrap rather than widening the page.
File uploads use the `file-picker` pattern so the action and empty state remain Persian instead of
depending on browser-native English copy.

The upload form's hint and its file picker's `accept` come from the person's own upload limits
(`uploadLimits` / `uploadAccept` in the model), not from a fixed list; a refusal by the policy is
rendered as the form's warning message with the kind or the size and the limit.

The upload policy is one table, the `upload-rules` fragment in `fragments.html`, rendered by the settings page for the
system-wide policy and by the role page for a role's own: one row per catalogued kind, a checkbox
that allows it and a number input for its limit in megabytes. On the role page the table follows a
two-way radio (system-wide / own) through an Alpine `mode` and is disabled while the role is
governed by the system-wide policy; on the settings page there is no Alpine scope and the same
binding is inert.

The content-kinds page (`settings/content-kinds.html`) is three cards: a probe form (one file
input, posts multipart to `/probe`, re-renders the page with a `probe-result` definition list), the
add-kind form (prefilled from the probe when there was one; only for `SAVE_CONTENT_KIND`), and the
catalogue table with a built-in / custom badge and a delete button on custom rows. The probe's
hex and media types are `technical` and `dir="ltr"`; everything else follows the page direction.

The upload form asks for the place with the **folder chooser** (below): opened plainly it
starts at `Home` with nothing chosen; opened from the explorer's "upload here" button -
`/files/create?folderId=` - it starts on that folder with it already chosen and its path
inlined for the first render (`window.UPLOAD_TARGET_PATH`). Either way a hidden `folderId` is
what posts, and the submit handler refuses an empty one, since the browser does not validate a
hidden input. A folder that cannot be uploaded into (the root, or outside the person's write
grants) drops the form back to an unchosen target with a message rather than an error page -
the person came to upload, and the form is where they can still do that.

### The folder chooser

One component for every "which folder?" question: `fragments.html :: folder-chooser` is the
markup, `window.folderChooser(config)` in `app.js` the Alpine behaviour, and the enclosing
element declares `x-data="folderChooser({url, initialId, initialPath, rootTitle, selectRoot,
copy})"`. It drills down through `/resource/folders/children` - the same endpoint the explorer
reads, so it shows exactly the folders the person may walk into - as a crumb row (each crumb
goes back up), a list of child folders (each goes down, with the count badge), and a "choose this folder" button (`folderChooser.chooseThis`) for the folder on screen. The choice is `chosen` (`{id, title, path}`) and a
`folder-chosen` event on the root element, which the upload form binds to its hidden input and
the explorer's move dialog to its target. `selectRoot` says whether `Home` may be chosen (a
move's target may be the root; an upload's may not).

### Detail pages

Present immutable information as label-and-value pairs and keep related actions in the same card.
Separate destructive actions from ordinary actions and provide a clear confirmation that explains the
effect. Never rely on color alone to communicate state.

### Login

The desktop login page uses a two-panel composition: the form is the direct task surface and the green
panel establishes application identity and context. On mobile, the contextual panel collapses to a
compact brand header so the form remains immediately accessible. Do not reuse a generic centered-card
login treatment.

## Responsive behavior and accessibility

- The sidebar becomes an offcanvas drawer below Tailwind's `lg` breakpoint and remains keyboard
  accessible; the top bar stays fixed at every width.
- Tables scroll horizontally on narrow displays; meaningful column content is not hidden to force a fit.
- Empty results render outside the wide table so the message remains fully visible on mobile.
- Side filters move before result content on narrow displays.
- Touch targets are approximately 44px high or larger.
- Icon-only buttons require an `aria-label`; visible text is preferred where space allows.
- Never remove keyboard focus indicators.
- Motion must respect the user's `prefers-reduced-motion` setting.
- Links that open a new tab require `rel="noopener noreferrer"`; the shared script also enforces this as
  a defensive layer.
- Session-authenticated AJAX requests must read the CSRF token and header name from the `_csrf` and
  `_csrf_header` meta elements.

## Application shell

`templates/navbar.html :: navbar` emits the whole shell — the fixed top bar and the sidebar — in a
single fragment. Pages insert it and add no wrapper of their own:

```html
<div th:replace="~{navbar.html :: navbar}"></div>
```

`app.css` offsets the page with `body:has(#app-sidebar)`, so a page that does not include the
fragment (login, error) stays full-bleed automatically. Nothing in a page template needs to know the
sidebar exists.

- **Top bar** (the fixed `header` in the fragment): brand, the sidebar toggle below `lg`, and the account menu. Keep it
  thin; it is not a navigation surface.
- **Sidebar** (`#app-sidebar`): all primary navigation, grouped into semantic `section` blocks with
  an `.app-nav-heading`. Below the `lg` breakpoint Alpine toggles it as a drawer.
- Active state is applied by `static/js/app.js`, which matches the longest link path against the
  current URL and adds `.active`.

Add a new destination by adding one `.app-nav-link` inside the right section, wrapped in the
`sec:authorize` expression that matches the endpoint's `@PreAuthorize`.

### Dropdowns inside the top bar

The account menu is an Alpine disclosure positioned relative to its trigger. Keep `x-cloak`,
`@click.outside`, and the Escape handler together so it neither flashes during startup nor traps the
user. The menu is always a white surface even though its trigger sits in the dark top bar.

## Tree component

`.app-tree` styles the file tree at `/files/tree`. The view only navigates; the tree is managed
from the explorer, and the page carries no badge or note saying so - a page states what it does,
not what it will do (drag-and-drop, roadmap 5.2, would land here or in the explorer).

The tree shows folders to any depth (one `folder` table; one `FOLDER` node type since `V2.9`,
whose children are its folders and then its files). A top-level folder's note is the title of
the tag group it carries. A search hit carries `folderIds` / `folderTitles`, the chain down to
the file, which `revealHit` opens level by level.

Rows are held as a **flat list**, each carrying its `depth`, rather than as nested markup:

```
rows = [ {depth:0, type:FOLDER, open:true},
         {depth:1, type:FOLDER, open:true},
         {depth:2, type:FILE,   open:false} ]
```

Opening a folder splices its children in after it; closing removes every following row that is
deeper. That keeps the template to a single `x-for`, supports any depth without recursive
templates, and hands drag-and-drop one ordered list to work against. Indentation comes from
`padding-inline-start` computed from `depth`, so it flips correctly in RTL.

Children load on demand from `/resource/files/tree/children?type=&id=`. Root categories open one level
on initial display so the hierarchy and its disclosure controls are immediately discoverable. The
user can then open a branch or use **Expand all**. Do not build the whole subtree in the controller:
every `@ManyToOne` in this codebase is `EAGER`, so one node drags in its whole ancestry.

### The general settings page

`settings/general.html` is one card per switch, each a form of its own posting to the same
URL. A checkbox that is not ticked is not posted, so the controller reads absence as "off"; the
box is rendered disabled for someone who may see the page but not save it, and the save button
is not rendered at all for them.

### Managing folders from the explorer

The explorer (`file-management/files/file-explorer.html`) is where the tree is edited, since
Phase 7 step 4 removed the taxonomy pages. Four controls sit in the content pane's toolbar,
each rendered only for a permission (`sec:authorize` on `REST_CREATE_FOLDER`,
`REST_RENAME_FOLDER`, `REST_MOVE_FOLDER`, `REST_DELETE_FOLDER`) and shown only when the folder
on screen reports `manageable` (the caller holds `WRITE` on it) and the operation applies:
*new folder* while `canHoldFolders` (the depth limit is not reached), *rename* and *move* on
anything but the root, *delete* on an empty folder that is not the root. No disabled buttons
stand in for what a person cannot do. Move opens a panel with the folder chooser
(`selectRoot: true`) and one button that becomes active once a target is chosen.

**Selecting a folder** is distinct from opening it: a row's click opens the folder, and the
`.explorer-row-action` info button at the end of the row (or the "folder details" button in
the toolbar, for the folder on screen) selects it - `showFolder(id)` reads
`/resource/folders/{id}` and the details pane shows the folder instead of a file: label, id and
name, the trail, depth, the tag group, direct contents, total files beneath, created and last
changed by whom, and an "open" button. `selectedFile` and `selectedFolder` are exclusive; Escape
and the overlay clear both.

**Search** finds folders as well as files, by id or by a fragment of the name or label. The
response carries `folders` (a short list, never paged) and `hits` (files, paged); the results
pane renders the folders first, each with its trail and counts and its own info button, and the
"nothing found" state only when both lists are empty.

Create and rename share one inline form (`.explorer-manage`, under the toolbar, `x-show` on
`manage.mode`): a directory-safe name (`technical`, `dir="ltr"`, `pattern="[^./ ]+"`), a label,
and - when creating under the root, or renaming a top-level folder - a tag-group select fed by
`/resource/folders/tag-groups`; on a create its empty option is "new group" and reveals a name
field, on a rename it is "keep". The form posts JSON with the CSRF header
through `writeJson`, and shows a problem response's `detail` beside the buttons; a `409` on a
taken name gets its own copy (`explorer.manage.nameTaken`). Delete asks with `confirm()` and
reports a non-empty folder with `explorer.manage.deleteRefused`. After any of the three the
folder is reloaded, and the tree pane refreshed, so the new state comes from the server rather
than from the form.

## Legacy compatibility classes

Some templates still contain class names inherited from the old Bootstrap UI, including `btn-block`,
`form-row`, `sr-only`, `input-group-addon`, `col-md-*`, and `badge-secondary`. Bootstrap is not loaded;
these names work only because the Tailwind component layer implements them. Do not add more legacy
classes. Convert a page to the explicit Tailwind patterns when editing it, then remove a compatibility
rule only after its final consumer is gone. Be especially careful with names such as `w-100`: that is
a real Tailwind spacing utility (25rem), not Bootstrap's `width: 100%` helper.

## UI change checklist

- The page declares `lang="fa"` and `dir="rtl"`.
- Navigation changes were made in the sidebar, not the top bar, and carry the matching
  `sec:authorize` expression.
- Shared fragments load the compiled Tailwind stylesheet and only the vendored scripts required by
  the page; pages with Select2 use the dedicated select fragments.
- No external `src`, `href`, or `url()` runtime reference was introduced.
- The page remains usable on mobile, desktop, and at 200% browser text zoom.
- Relevant empty, error, success, loading, and disabled states were checked.
- Technical values use `technical` or an explicit `dir="ltr"` where appropriate.
- Destructive actions use destructive styling and a clear confirmation.
- Existing validation and CSRF behavior remains intact.
- Source comments and documentation are in English.
- `./mvnw verify` passes before the change is considered complete.
