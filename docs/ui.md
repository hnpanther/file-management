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

**The compiled `static/css/app.css` is committed, and Node is a development-time tool only.** The
Maven build contains no Node plugin, and `./mvnw package` works with `node_modules` deleted - that
is verified by `UiResourceTest.nothingInTheBuildRequiresNode` and was checked by building with the
directory removed. Anyone can build and run the application with nothing but a JDK.

If you change `app.css`, rebuild and commit both files together.

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
Enter key, and `window.appCsrf()` for hand-written requests.

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

`.app-tree` styles the file tree at `/files/tree`. The view is **read-only for now**; drag-and-drop
is planned once the storage port can express a move (roadmap Phase 5).

The tree deliberately shows category, sub-category **and main tag** as folders, even though a main
tag creates no directory today. The target model turns all three into one `folder` table, so
presenting them as folders now means that migration is a data change and not a redesign.

Rows are held as a **flat list**, each carrying its `depth`, rather than as nested markup:

```
rows = [ {depth:0, type:CATEGORY,     open:true},
         {depth:1, type:SUB_CATEGORY, open:true},
         {depth:2, type:MAIN_TAG,     open:false} ]
```

Opening a folder splices its children in after it; closing removes every following row that is
deeper. That keeps the template to a single `x-for`, supports any depth without recursive
templates, and hands drag-and-drop one ordered list to work against. Indentation comes from
`padding-inline-start` computed from `depth`, so it flips correctly in RTL.

Children load on demand from `/resource/files/tree/children?type=&id=`. Root categories open one level
on initial display so the hierarchy and its disclosure controls are immediately discoverable. The
user can then open a branch or use **Expand all**. Do not build the whole subtree in the controller:
every `@ManyToOne` in this codebase is `EAGER`, so one node drags in its whole ancestry.

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
