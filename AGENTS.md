# AGENTS.md

Working agreement for anyone — human or AI — changing this repository.

Read this together with:

* [docs/arch.md](docs/arch.md) — how the code is built today
* [docs/issues.md](docs/issues.md) — the known defects; **check this before "fixing" something**
* [docs/target-architecture.md](docs/target-architecture.md) — where it is going
* [docs/roadmap.md](docs/roadmap.md) — in what order

## Project in one paragraph

Spring Boot MVC application. Thymeleaf UI plus a REST API. Files go on the local filesystem, metadata
in MySQL. A five-level taxonomy (`GeneralTag → FileCategory → FileSubCategory → MainTagFile →
FileInfo → FileDetails`) where the middle two levels are also real directories. Authorities are
fine-grained per-endpoint permissions, not roles. Package root `com.hnp.filemanagement`.

## Commands

```bash
./mvnw verify                 # build + all tests; needs only a Docker daemon (no Node)
npm run build:css             # only if you edited src/main/frontend/app.css
./mvnw clean package          # build → target/file-management.jar (executable)
./mvnw spring-boot:run        # run on :8122
./mvnw test -Dtest=FileServiceTest
docker compose up -d          # MySQL for running the application (NOT for tests)
```

The tests start their own MySQL through Testcontainers (`support/MySqlSupport`) and use
`./target/test-storage/` as the storage root (`support/StorageRootSupport`). There is nothing to
provision. **Run them.** If something prevents you from running them, say so rather than claiming a
change is verified.

## Before you change anything

1. **Check [docs/issues.md](docs/issues.md).** Much of what looks like a bug is a known one, often
   with a decided fix and a phase it belongs to. Fixing it out of order can conflict with the plan.
2. **Check which phase you are in.** The roadmap sequences work deliberately — e.g. the `@Data`
   entity fix must land *before* the Spring Boot upgrade, and checksum backfill must land *before*
   the S3 migration.
3. **Never claim a version that is not in `pom.xml`.** The commit log says Spring Boot 3.5.5; the
   pom says 3.2.1, because a merge discarded the upgrade
   ([issue 1](docs/issues.md#1-the-spring-boot-upgrade-was-silently-reverted-by-a-merge--s1)).
   Read the file.

## Conventions in this codebase

Match what is already there unless the roadmap says to change it.

**Layers.** `controller/` returns Thymeleaf view names. `resource/` is REST for the UI's own AJAX
(session auth, CSRF). `api/` is REST for machines (HTTP Basic, stateless). Business logic goes in
`service/`, never in a controller.

**Constructor injection only.** No field `@Autowired`. Every dependency is `private final` and set
in the constructor.

**Entity → DTO** conversion goes through `util/ModelConverterUtil`. Do not return entities from
controllers.

**Validation groups.** `InsertValidation` / `UpdateValidation` / `UpdatePasswordValidation` on the
DTO fields, activated by `@Validated(InsertValidation.class)` on the handler parameter.

**Exceptions.** Throw the domain exceptions from `exception/`: `ResourceNotFoundException`,
`DuplicateResourceException`, `InvalidDataException`, `DependencyResourceException`,
`BusinessException`. The `@ControllerAdvice` beans map them.

**Every handler needs a permission.** Add a constant to `PermissionEnum`, annotate the handler with
`@PreAuthorize("hasAuthority('YOUR_CONSTANT') || hasAuthority('ADMIN')")`, and keep the comment above
the constant naming the endpoint. `FileManagementApplication.initialize` seeds new constants
automatically on the next `prod` start.

**Every mutation writes audit history.** Call
`actionHistoryService.saveActionHistory(EntityEnum.X, id, ActionEnum.Y, principalId, actionDesc, desc)`
after the change. It is not automatic.

**Handler preamble.** Existing handlers open with the `globalGeneralLogging.controllerLogging(...)`
block. Match it in new handlers in existing files — it is scheduled for replacement by an aspect
([issue 25](docs/issues.md#25-sixty-copies-of-the-same-logging-preamble--s3)), but a half-converted
file is worse than a consistent one.

**The app shell.** `templates/navbar.html :: navbar` emits the fixed top bar *and* the sidebar.
Pages just insert it and need no wrapper; `app.css` offsets `<body>` via `body:has(.app-sidebar)`.
Primary navigation belongs in the sidebar, not the top bar.

**Naming.** Directory names (category, sub-category) must contain no `.`, no space, no `/`.
File names must contain exactly one `.`, no space, no `/`. Enforced in both `ValidationUtil` and
`FileStorageFileSystemService` — keep the two in agreement.

## Things that will bite you

* **`@Data` on entities recurses.** `FileInfo` ↔ `FileDetails` is bidirectional and both are
  `@Data`. Calling `toString()` on either — including implicitly in a log line or an exception
  message — is a `StackOverflowError`. Do not add an entity to a log statement.
* **The disk write is not in the transaction.** `FileService.createNewFile` persists rows, then
  writes the file. A rollback after the write leaves an orphan. Do not add work between them.
* **`state` and `enabled` are magic integers.** `state`: `0` public, `-1` private. `enabled`: `1`
  active. There is no enum and no constraint.
* **Everything is `FetchType.EAGER`.** Loading one `FileDetails` pulls the entire ancestry plus two
  `User` rows per level. Adding a field to a mapper can quietly add joins to every list page.
* **`hash_id` is a random UUID, not a hash.** Nothing verifies file integrity.
* **`base-dir` must end with a separator.** The storage service concatenates; it does not resolve.
* **Hand-written SQL must match table names exactly.** MySQL folds identifiers on Windows but not on
  Linux, so a typo like `file_Info` passes locally and fails in production. `compose.yaml` sets
  `--lower-case-table-names=0` and the test container is Linux, so both now catch it — do not work
  around either. See [issue 47](docs/issues.md#47-maintagfiledao-queried-file_info-which-does-not-exist-on-linux--s1).
* **Lombok needs an explicit `annotationProcessorPaths` entry.** JDK 23 dropped implicit annotation
  processing; without the entry in `pom.xml` every generated getter vanishes and the build fails
  with hundreds of `cannot find symbol`. Do not remove it.
* **A `@Component` is constructed whether or not its feature is enabled.** Give every `@Value`
  placeholder for an optional feature a default, or the app will not start without it.
* **`data-bs-theme` cascades to descendants.** A Bootstrap 5.3 dropdown inside a dark region
  inherits `--bs-dropdown-bg: #212529`; if the stylesheet also paints items with the dark body
  text colour the menu is black on black. Restate the `--bs-dropdown-*` variables on the menu
  rather than colouring individual items.
* **The UI is Tailwind + Alpine, not Bootstrap.** `data-bs-*` attributes do nothing. Page
  bodies are still Bootstrap 3-era markup kept alive by a compatibility layer in
  `src/main/frontend/app.css`; convert them to utilities and delete the matching block.
* **Never load an asset from `/webjars/`.** Everything lives under `static/vendor/` or
  `static/css/`, because a partly populated `~/.m2` serves 404s that reach the browser as
  `Unexpected token '<'`. See [docs/ui.md](docs/ui.md).
* **The build must never need Node.** `static/css/app.css` is generated and committed; rebuild it
  with `npm run build:css` and commit both files together.
* **Inline `<script>` needing Thymeleaf values must set `th:inline="javascript"`**, and the
  expression must not be wrapped in your own quotes - inlining supplies them.

## Database changes

Schema is owned by **Flyway** (`src/main/resources/db/migration`), and `ddl-auto=validate` means
Hibernate will refuse to start on a mismatch.

* Add a new `V1.x__Description.sql`. Never edit an applied migration.
* Flyway is the only source of schema. There is no schema dump to keep in sync any more.
* Update the entity in the same commit.
* `ddl-auto=validate` checks types and existence but **not** nullability — if you add a `NOT NULL`
  column, set `nullable = false` on the mapping too, or you will get a runtime insert failure
  ([issue 33](docs/issues.md#33-schema-and-entity-mappings-disagree--s2)).
* Migrations are MySQL-specific today. If you are writing one during the PostgreSQL migration, see
  [roadmap Phase 3](docs/roadmap.md#phase-3--postgresql-migration) for the vendor-directory layout.

## Security rules

* **Never commit a credential.** `application.properties` reads everything from the environment;
  keep it that way. Local values go in `application-local.properties`, which is gitignored. The
  credentials in the git history still need rotating
  ([issue 11](docs/issues.md#11-credentials-and-infrastructure-details-are-committed--s1)).
* **Do not trust `MultipartFile.getContentType()`** for anything security-relevant. It is a
  client-supplied header.
* **Do not widen the `permitAll` list** in `SecurityConfig` without saying why in the commit message.
* **Do not add `inline` content disposition** to any new download path.
* **Every AJAX call needs the CSRF header.** Read `_csrf` / `_csrf_header` from the `<meta>` tags,
  as every existing template does. The session chain has CSRF enabled and it must stay that way.
* **Authorization is per-endpoint only.** There is no per-file check
  ([issue 14](docs/issues.md#14-no-resource-level-authorization--s1)). If you add an endpoint that
  reads file bytes, assume the permission grants access to *every* file, and say so.

## Commits and pull requests

* Conventional, imperative subject lines. Reference an issue number from
  [docs/issues.md](docs/issues.md) when a change closes one, and strike it off there in the same
  commit.
* One concern per commit. Do not mix a dependency bump with a refactor — that is exactly how the
  Spring Boot upgrade was lost.
* **Merge carefully.** Verify `pom.xml` after any merge that touches it. See issue 1.
* Do not commit `.idea/`, `target/`, or anything under `TempFiles/`.

## Definition of done

A change is done when:

* `./mvnw verify` is green;
* new behaviour has a test, or you have stated explicitly that you could not run the suite and why;
* any new endpoint has a `PermissionEnum` constant and a `@PreAuthorize`;
* any new mutation writes an `ActionHistory` row;
* any schema change has a migration *and* the matching entity update;
* no credential, absolute developer path, or `TODO` without an owner was added.

Report honestly. If the suite did not run, say the suite did not run.
