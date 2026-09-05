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

**Always stop the application when you are finished.** `spring-boot:run` holds port 8122 for as
long as it lives, and the next run — yours or someone else's — fails with a port conflict that
looks nothing like its cause. Leaving it running against the real database also means a background
process still holding connections after you have moved on.

```bash
# Windows / PowerShell
Get-NetTCPConnection -LocalPort 8122 -State Listen -ErrorAction SilentlyContinue |
  Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object { Stop-Process -Id $_ -Force }

# Linux / macOS
lsof -ti tcp:8122 | xargs -r kill
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
3. **Never claim a version that is not in `pom.xml`.** It now says Spring Boot 4.1.1 on Java 21.
   A merge once discarded an upgrade and left the log claiming a version the build never had
   ([issue 1](docs/issues.md#1-the-spring-boot-upgrade-was-silently-reverted-by-a-merge--s1)),
   so read the file rather than the history.

## Conventions in this codebase

Match what is already there unless the roadmap says to change it.

**Layers.** `controller/` returns Thymeleaf view names. `resource/` is REST for the UI's own AJAX
(session auth, CSRF). `api/` is REST for machines (HTTP Basic, stateless). Business logic goes in
`service/`, never in a controller.

**Constructor injection only.** No field `@Autowired`. Every dependency is `private final` and set
in the constructor. Services take repositories, not `EntityManager`; where you need a foreign-key
reference without loading the row, use `repository.getReferenceById(id)`.

**Transactions belong on the service.** Every service is `@Transactional(readOnly = true)` at class
level, and each mutating method opts in with a bare `@Transactional`. A change and the
`action_history` row that records it must commit together — `ActionHistoryService.saveActionHistory`
is `Propagation.MANDATORY`, so calling it outside a transaction fails loudly instead of writing a
history row that outlives a rolled-back change. Inside a transaction a loaded entity needs no
`save()`; the dirty check writes it.

**Entity → DTO** conversion goes through `util/ModelConverterUtil`, and it happens **inside the
service**, in the transaction that loaded the data. `spring.jpa.open-in-view` is off, so an entity
that reaches a controller is a lazy graph with no persistence context behind it. No service method
returns an entity; the few that must share one with a sibling service are package-private
(`getFileCategoryEntity`, `getMainTagFileEntity`, …).

**Entities extend `AbstractEntity`** (id, `equals`, `hashCode`, `toString`) or `AuditableEntity`
(those four plus the audit columns). Do not add `@Data` to an entity, do not override the three
`Object` methods — they are `final` for a reason — and do not set `createdAt` / `updatedAt` by hand.

**Every association is `LAZY`, and every query says what it needs.** Add a `JOIN FETCH` to the
repository method rather than making a mapping eager. Fetching `@ManyToOne` chains paginates
fine; fetching a collection does not, so those queries return one row.

**Read children by query, not through the parent's collection.** `parent.getChildren()` answers
from the persistence context and can be stale within a transaction — that is how a delete check on
a category with sub-categories passed. Use the child repository.

**Validation groups.** `InsertValidation` / `UpdateValidation` / `UpdatePasswordValidation` on the
DTO fields, activated by `@Validated(InsertValidation.class)` on the handler parameter.

**Exceptions.** Throw the domain exceptions from `exception/`: `ResourceNotFoundException` (404),
`DuplicateResourceException` (409), `DependencyResourceException` (409), `InvalidDataException`
(400), `BusinessException` (417). The status lives on the exception class as `@ResponseStatus`, and
the single `GlobalExceptionHandler` reads it — do not repeat a status in a handler.

**REST handlers do not catch.** A method in `resource/` or `api/` throws and lets the advice answer.
Catching locally is what used to flatten 404, 409 and 417 into one 400 with the body
`"invalid data"`. Return `ApiResult.created/updated/deleted/stateChanged(...)` for a mutation and a
DTO for a lookup; success is 200, because the pages branch only on `xhr.status === 200`. Full
contract in [arch.md §6](docs/arch.md#the-rest-contract).

**Bind request bodies, never parse them.** Add a small record to `dto/` (see `StateChangeRequest`)
and take it as `@RequestBody`. `JsonParserFactory` plus `map.get("x").toString()` is how these
endpoints used to turn a missing field into a 500.

**Log with the request-aware overload.** `globalGeneralLogging.controllerLogging(userDetails,
request, YourClass.class, "what you are about to do")` replaces the six-line preamble. The old
five-argument signature is still there for the Thymeleaf controllers; do not use it in new code.

**Every handler needs a permission.** Add a constant to `PermissionEnum`, annotate the handler with
`@PreAuthorize("hasAuthority('YOUR_CONSTANT') || hasAuthority('ADMIN')")`, and keep the comment above
the constant naming the endpoint. `FileManagementApplication.initialize` seeds new constants
automatically on the next `prod` start.

**Every mutation writes audit history.** Call
`actionHistoryService.saveActionHistory(EntityEnum.X, id, ActionEnum.Y, principalId, actionDesc, desc)`
after the change. It is not automatic.

**Handler preamble.** The `resource/` and `api/` packages are converted to the one-line overload
above. The Thymeleaf controllers still open with the six-line block; match the file you are in —
a half-converted file is worse than a consistent one — and see
[issue 25](docs/issues.md#25-sixty-copies-of-the-same-logging-preamble--s3).

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

## Tests

Four kinds, and the choice is not stylistic — each answers something the others cannot. The table in
[arch.md §12](docs/arch.md#12-tests) says which is which. In short:

* **unit** (`*UnitTest`, plain JUnit or Mockito) — guard clauses, pure functions, and *negative*
  assertions like "a rejected upload never reaches storage". No Spring, no Docker;
* **repository** (`@DataJpaTest`) — fetch plans, cascades, bulk updates, schema constraints;
* **service** (`@ServiceIntegrationTest`) — the whole path through the real Spring beans;
* **web** (`@SpringBootTest` + MockMvc) — statuses, shapes, redirects, authorization.

Rules for new tests:

* **Never construct a service with `new`.** It has no proxy, so its `@Transactional` does nothing
  and the test cannot fail for a missing transaction boundary. Autowire it.
* **Do not clean up by hand.** `@ServiceIntegrationTest` and `@DataJpaTest` roll back. A
  `deleteAll()` teardown has to be maintained in foreign-key order and leaks rows when a test fails
  part-way.
* **Build fixtures with `support/TestData`.** It fills every `NOT NULL` column and generates the
  unique ones; override only what the test is about.
* **Name what the test proves**, not the method it calls: `refusesToDeleteATagInUse`, not
  `deleteMainTagFileTest`. Add `@DisplayName` in a sentence.

## Definition of done

A change is done when:

* `./mvnw verify` is green;
* new behaviour has a test, or you have stated explicitly that you could not run the suite and why;
* any new endpoint has a `PermissionEnum` constant and a `@PreAuthorize`;
* any new mutation writes an `ActionHistory` row;
* any schema change has a migration *and* the matching entity update;
* no credential, absolute developer path, or `TODO` without an owner was added;
* **port 8122 is free again** — if you started the application to check something, stop it before
  you finish, every time.

Report honestly. If the suite did not run, say the suite did not run.
