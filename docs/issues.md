# Known Issues

Catalogue of defects, risks and debts found while reading the codebase at `redesign-arch` / `08db773`.
Each entry names the file so it can be verified independently.

Severity legend: **S1** breaks correctness or security · **S2** will break under production load or
during the planned migrations · **S3** maintainability / hygiene.

Sequencing of the fixes is in [roadmap.md](roadmap.md).

---

## Correctness

### 1. The Spring Boot upgrade was silently reverted by a merge — **S1**

`pom.xml` declares `spring-boot-starter-parent` **3.2.1**, but the history says otherwise:

```
08db773 (merge)  change version in pom
├── 74fd372      Update some property and docs      → pom has 3.2.1
└── b831286      update to spring boot 3.5.5        → pom has 3.5.5
```

`git show b831286:pom.xml` → `3.5.5`. `git show HEAD:pom.xml` → `3.2.1`. The merge resolved
`pom.xml` in favour of the `74fd372` side and threw away both `f813070` (3.5.4) and `b831286` (3.5.5).
The project version was also reverted from `1.0` back to `1.0.0`.

Consequence: the deployed artefact is on Spring Boot 3.2.x, which is out of OSS support, while the
commit log claims 3.5.5. Anyone reading the log will trust the wrong number.

*Fixed by the Spring Boot 4.1.1 upgrade in [roadmap.md](roadmap.md#phase-1--platform-upgrade); the
lesson is that this merge needs to be called out in the commit message so nobody re-applies 3.5.5.*

### 2. `@Data` on bidirectional JPA entities — **S1**

`FileInfo.fileDetailsList` ↔ `FileDetails.fileInfo` is a bidirectional association, and both classes
are annotated `@Data` (`entity/FileInfo.java`, `entity/FileDetails.java`). Lombok generates
`equals`/`hashCode`/`toString` over **all** fields, including the association on both sides.

* `toString()` on either entity recurses infinitely → `StackOverflowError`. Any log line, any
  exception message, any debugger inspection that touches one of these triggers it.
* `equals`/`hashCode` force the lazy `fileDetailsList` to initialise, defeating `FetchType.LAZY`.
* `hashCode` includes the generated `id`, which is `null` before persist and non-null after, so an
  entity put in a `HashSet` before flush is lost afterwards.

The same pattern exists on `FileCategory` ↔ `FileSubCategory` ↔ `MainTagFile`, `GeneralTag` ↔
`FileCategory`, `Role` ↔ `User` ↔ `Permission`.

Fix: replace `@Data` with `@Getter @Setter`, and hand-write `equals`/`hashCode` on the business key
(or on `id` with the `instanceof` + `Hibernate.getClass` guard), never on the associations.

**Fixed in the architecture pass.** All three methods moved to a `@MappedSuperclass`
`AbstractEntity` and are `final`: `equals` compares ids through `Hibernate.getClass` so a proxy
equals its entity, `hashCode` is constant per type so it survives the id being assigned at insert,
and `toString` prints `Type#id` and can never walk an association. `EntityIdentityTest` covers each
of the three original failure modes.

### 3. Storage writes are not atomic with the database — **S1**

`FileService.createNewFile` is `@Transactional`. It persists `FileInfo` + `FileDetails` + two
`action_history` rows, and only then calls `fileStorageService.save(...)`. The disk write is not
enlisted in the transaction:

* If the commit fails after the write, the bytes stay on disk with no row pointing at them.
* If the process dies between the write and the commit, likewise.

The delete path has the mirror problem. `deleteCompleteFileById` deletes the row, then calls
`fileStorageService.delete(address, "", 1, "", false)`, which walks the directory deleting entries
one at a time. If that walk throws halfway, the transaction rolls back and the row returns — but the
files it references are already partially gone.

There is no reconciliation job and no orphan sweeper, so both failure modes are permanent.

Fix: write to storage **before** the commit under a staged/temporary key, and register a
`TransactionSynchronization` that promotes on commit and deletes on rollback; for deletes, mark the
row deleted first and let an asynchronous sweeper remove the bytes.

### 4. `FileStorageFileSystemService.load` has a broken guard — **S1** (latent)

```java
if(!checkCorrectDirectoryName(address) && !checkCorrectFileName(fileName)) {
    throw new BusinessException(...);
}
```

`address` is always `"{Category}/{SubCategory}"`, so it always contains `/`, so
`checkCorrectDirectoryName(address)` is always `false`, so `!checkCorrectDirectoryName(address)` is
always `true`. The condition collapses to `!checkCorrectFileName(fileName)` and the directory check
never runs. The operator should be `||`, and the directory check should be applied per path segment.

Not currently exploitable — `address` is derived from DB rows written through validated services —
but it is a dead guard on the file-read path, and the new storage layer must not inherit it.

### 5. `FileDAO.isDuplicateNewFile` contains SQL that cannot execute — **S2**

> **Resolved in Phase 0.** `FileDAO` deleted.

```sql
SELECT fi.* FROM file_info fi JOIN file_sub_category WHERE fi.file_name = (:fileName) AND fsc.id = (:fileSubCategoryId)
```

A `JOIN` with no `ON`, a second table with no alias, and a predicate on `fsc.id` where `fsc` is never
bound. This would fail at runtime. `FileDAO` is dead code — nothing calls it (only `MainTagFileDAO`
is wired in, from `MainTagFileService.isDeletable`) — but it is committed and reads as if it works.

Fix: delete `FileDAO`. `FileService.isDuplicate` already does this correctly via
`FileInfoRepository.checkExistsFile`.

### 6. `file_size` is a 32-bit `INT` — **S2**

`file_details.file_size INT NOT NULL` in `V1.0__Initial_Setup.sql`, `Integer fileSize` on the entity,
and `FileService` writes `(int) fileInfoDTO.getMultipartFile().getSize()`. Files above 2 GiB overflow
into a negative number silently. The 20 MB multipart cap hides this today, but the cap is a config
value and the S3 work will raise it.

Fix: `BIGINT` / `long`, and migrate the column.

### 7. `hash_id` is not a hash — **S2**

`FileDetails.hashId` is `UUID.randomUUID().toString()` (three call sites in `FileService`). The
column is named `hash_id`, carries a `UNIQUE` constraint, and the commented-out line above each
assignment shows it used to hold the file name. Nothing in the system ever computes a checksum of the
stored bytes.

Consequences: no integrity verification, no de-duplication, no `ETag` for conditional GETs, and no
way to detect silent corruption after the S3 migration.

Fix: keep the UUID as a surrogate `external_id`, and add a real `checksum_sha256` column populated
during the upload stream.

### 8. The Active Directory provider returns `null` instead of throwing — **S1**

`ActiveDirectoryCustomAuthenticationProvider.authenticate` returns `null` when the user authenticated
against AD but has the wrong `loginType`, or is disabled. In Spring Security, `null` means
*"this provider has no opinion"*, so `ProviderManager` falls through to the next provider —
`DaoAuthenticationProvider` — which then re-attempts the same credentials against the local
password hash. A disabled AD user is not rejected; they are quietly re-tested against a different
backend, and the login failure reason is lost.

It also constructs a fresh `ActiveDirectoryLdapAuthenticationProvider` (and therefore a fresh LDAP
context factory) on **every** login attempt.

Fix: throw `DisabledException` / `BadCredentialsException`, and make the delegate a singleton bean.

### 9. `UserService`'s logger is bound to the wrong class — **S3**

```java
private final Logger logger = LoggerFactory.getLogger(RoleService.class);   // in UserService
```

Every `UserService` log line is attributed to `RoleService`, which makes log-level configuration and
grep-based debugging misleading.

**Fixed** in the cleanup pass. `GeneralTagController` had the same defect — its logger named
`FileSubCategoryController` — and was fixed with it.

### 10. Duplicated statements that hint at copy-paste bugs — **S3**

* `FileService.createNewFile`: `fileInfo.setDescription(fileInfoDTO.getDescription())` twice in a row.
* `FileManagementApplication.initialize`: `roleRepository.save(role)` twice for both `ADMIN` and `USER`.
* `SecurityConfig`: `auth.requestMatchers("/files/public-download/**").permitAll()` twice.
* `FileService.changeFileDetailsState`: the not-found message interpolates the **repository bean**
  (`fileDetailsRepository`) instead of `fileDetailsId`.
* `PermissionRepository` imports `javax.swing.text.html.Option` — an IDE auto-import accident.

**Fixed** in the cleanup pass, all five.

---

## Security

### 11. Credentials and infrastructure details are committed — **S1**

> **Partly addressed in Phase 0.** `application.properties` now reads every credential from the environment (`FILEMANAGEMENT_DB_PASSWORD` has no default, so startup fails loudly), the LDAP host is no longer in the file, and `application-local.properties` is gitignored with an `.example` alongside it. **The credentials already in the git history still have to be rotated**, and the `Admin`/`admin` bootstrap account is untouched.

| File | What is exposed |
|---|---|
| `src/main/resources/application.properties` | DB URL, username and password (`file_management` / `file_management`) |
| `src/test/resources/application.properties` | test DB credentials |
| `src/main/resources/application.properties` | AD domain `hnp.local` and LDAP host `ldap://172.29.76.9` |
| `schema-db/schema.sql` | `CREATE USER 'file_management'@'localhost' IDENTIFIED BY 'file_management'` |

Additionally, `FileManagementApplication.initialize` creates `Admin` / `admin` on every `prod`
startup and never forces a rotation, so the default administrator password is both public and
permanent.

Fix: environment variables / a secrets manager, `application-local.properties` gitignored, a
first-login password change, and rotation of anything already committed.

### 12. File-type validation trusts the client — **S1**

`FileValidator.isValid` allow-lists `MultipartFile.getContentType()`. That value is the
`Content-Type` header the *client* put in the multipart part — entirely attacker-controlled. A
`.exe`, a `.html` or an `.svg` renamed and labelled `image/png` passes.

The same unverified string is then persisted to `file_details.content_type` and replayed on download
via `MediaType.parseMediaType(contentType)`, so the server tells the browser to trust it too.

Fix: sniff the magic bytes (Apache Tika), verify the sniffed type against the extension **and** the
declared type, and store the sniffed value. Add an AV scan hook for untrusted uploads.

### 13. `inline` disposition on the public download endpoint — **S1**

`GET /files/public-download/{id}?inline=1` is `permitAll` and sets
`Content-Disposition: inline` with the stored `content_type`. Combined with issue 12, any stored
HTML or SVG executes as script **on the application's own origin**, against the session of whoever
opens the link — stored XSS with full access to the authenticated UI.

There is no `Content-Security-Policy`, no `X-Content-Type-Options: nosniff`, and no separate download
origin.

Fix: serve downloads from a distinct origin or via pre-signed URLs; force `attachment` for anything
not on a small render-safe allow-list (PDF, PNG, JPEG, MP4, MP3); add `nosniff` and a restrictive CSP.

### 14. No resource-level authorization — **S1**

`FileService.downloadFile(int fileDetailsId)` does `fileDetailsRepository.findById(...)` with **no**
visibility or ownership check. Any principal holding `DOWNLOAD_FILE` (or `API_DOWNLOAD_FILE`) can
enumerate `fileDetailsId` and pull every file in the system, including those with `state = -1`
(private). The permission model is purely per-endpoint; there is no notion of "which files may
*this* user see".

`downloadPublicFile` does check (`findPublicFile` requires `state = 0` on both rows), so the gap is
specific to the authenticated download paths.

Fix: introduce per-category / per-file ACLs, or at minimum scope private files to their creator plus
an explicit grant, and enforce it in the service, not the controller.

### 15. Exception handlers leak internals and mangle status codes — **S2**

`GlobalApiExceptionHandler`:

* maps `AccessDeniedException` to **400 Bad Request** — API clients can no longer distinguish
  "you sent nonsense" from "you are not allowed";
* returns `e.getMessage()` as the response body for every handled exception, and the catch-all
  `@ExceptionHandler(Exception.class)` returns the raw message with a 500. Messages in this codebase
  routinely embed entity IDs, file paths and SQL context.

The custom exceptions' own `@ResponseStatus` annotations (404 / 409 / 417) are overridden by the
advices, so the declared contract and the actual contract disagree.

Fix: a single RFC 9457 `ProblemDetail` handler, an opaque message for unhandled exceptions, and a
correlation id in the response for log lookup.

**Fixed** in the cleanup pass, except the correlation id. `GlobalApiExceptionHandler` is deleted;
`GlobalExceptionHandler` is now the only advice, reads the status off the exception's own
`@ResponseStatus`, returns `ProblemDetail` to non-browsers and `error.html` to browsers, and
replaces the message with a generic translated one for anything unhandled. A body Jackson cannot
read is 400 rather than 500. Pinned by `web/RestContractTest`; contract documented in
[arch.md §6](arch.md#the-rest-contract).

The correlation id is still missing — a 500 tells the user nothing they can quote to an
administrator. That belongs with the observability work in Phase 2.

### 16. No path-containment check at the storage boundary — **S2**

`FileStorageFileSystemService` concatenates strings (`baseDir + address + "/" + ...`) and never
calls `Path.normalize()` nor verifies that the resolved path still starts with `baseDir`. The inputs
come from DB rows today, so there is no live traversal hole, but the only thing standing between a
future caller and `../../` is a character-counting helper.

Fix: resolve against a canonical base and assert containment, in one place, unconditionally.

### 17. No transport security configuration — **S2**

HTTP Basic on `/api/**` with no `requiresChannel().requiresSecure()`, no HSTS configuration, and no
documented TLS termination. Credentials are one misconfigured proxy away from the wire.

### CSRF — verified, no issue

Every Thymeleaf template that issues `$.ajax` reads `_csrf` / `_csrf_header` from `<meta>` tags and
sets the header. The session chain leaves CSRF enabled; the stateless API chain correctly disables it.

---

## Architecture

### 18. Three HTTP layers over one service layer — **S2**

`controller/` (Thymeleaf), `resource/` (REST for the UI), `api/` (REST for machines) expose
overlapping operations with three different error shapes, three sets of permission constants
(`REST_DELETE_FILE_DETAILS` vs `API_DELETE_FILE_DETAILS` vs `SAVE_NEW_FILE_DETAILS`), and no shared
contract. Deleting a `FileDetails` is implemented once in the service and exposed twice, with
different status codes on failure.

**Partly fixed** in the cleanup pass: the two JSON layers now share one contract — same success
envelope (`ApiResult`), same failure shape (`ProblemDetail`), same status per failure kind — so
deleting a `FileDetails` answers identically through both. What remains is the duplication itself:
two permission constants and two handler methods for one operation. Collapsing them is Phase 2
work, and it needs a decision about whether the pages should call `/api/**` directly.

### 19. `PermissionEnum` is a hardcoded list of endpoint names — **S2**

~70 constants, each mirroring one handler method, each referenced from a magic string in
`@PreAuthorize("hasAuthority('X') || hasAuthority('ADMIN')")`, each seeded into the `permission`
table by `FileManagementApplication.initialize`. Adding an endpoint means touching the enum, the
annotation string and the seed. Renaming one silently orphans the DB row. Nothing verifies that the
annotation strings correspond to real enum constants.

### 20. Every `@ManyToOne` is `EAGER` — **S2**

> **Fixed in the architecture pass.** Every association is `LAZY`, `spring.jpa.open-in-view` is off,
> and the queries that feed a converter declare their fetch joins. `FileInfoRepositoryTest` asserts
> with `Hibernate.isInitialized` that the taxonomy chain is resolved and that the audit user is not.

`FileDetails` → `FileInfo` → `MainTagFile` → `FileSubCategory` → `FileCategory` → `GeneralTag`, plus
`createdBy` and `updatedBy` on every one of them. Loading a single `FileDetails` drags in the whole
ancestry and two `User` rows per level. `ModelConverterUtil.convertFileInfoToFileInfoDTO` then walks
that entire graph on purpose, and `getPageFileInfo` calls it once per row.

Fix: `LAZY` everywhere, explicit `@EntityGraph` / fetch-join per use case, and projection DTOs for
list pages.

### 21. Search is `LIKE '%term%'` across the whole graph — **S2**

`FileInfoRepository.findByParameterAndPagination` and
`FileDetailsRepository.getAllPublicFileDetailsPage` OR together six to eight leading-wildcard `LIKE`
predicates spanning four joined tables. No index can serve this; every search is a full scan plus
joins. The abandoned `origin/elasticsearch` branch suggests this was already felt in practice.

Fix: PostgreSQL full-text search (`tsvector` + GIN) as part of the Postgres migration — cheaper than
running Elasticsearch, and enough for this data volume.

### 22. `state` / `enabled` are untyped magic numbers — **S2**

`Integer` columns with meanings documented only in a comment on `FileInfoDTO`. `1` ("rule base") is
documented but unreachable — `changeFileInfoState` accepts only `0` and `-1`. No enum, no check
constraint, no DB-level protection against a nonsense value.

### 23. Services depend on `EntityManager` to fake FK writes — **S3**

> **Fixed in the architecture pass.** `EntityManager` is gone from the service layer;
> `repository.getReferenceById(id)` does the same thing through the repository, and the one raw
> `createQuery` call is now `PermissionRepository.findDistinctByRoleIds`.

`entityManager.getReference(User.class, principalId)` appears in six services purely to set
`createdBy` / `updatedBy` without a `SELECT`. It couples the service layer directly to JPA and
substitutes for the missing auditing infrastructure.

Fix: Spring Data JPA auditing (`@CreatedBy`, `@CreatedDate`, `@LastModifiedBy`, `@LastModifiedDate`
with an `AuditorAware`).

### 24. Timestamps are hand-set `LocalDateTime` — **S2**

> **Fixed in the architecture pass.** `AuditableEntity` writes them with Hibernate's
> `@CreationTimestamp` and `@UpdateTimestamp`, so an update cannot forget to touch `updated_at` —
> which several did.

`LocalDateTime.now()` written by hand in every create/update method across every service. No time
zone: `LocalDateTime` + MySQL `DATETIME` means the value is ambiguous the moment the server moves or
DST shifts. `updated_at` is set inconsistently — `changeFileInfoState` and `changeFileDetailsState`
never touch it.

Fix: `Instant` / `TIMESTAMPTZ` and JPA auditing.

### 25. Sixty copies of the same logging preamble — **S3**

Every handler opens with four to six lines building `principalId`, `principalUsername`, `logMessage`
and `path`, then calls `globalGeneralLogging.controllerLogging(...)`. This is ~300 lines of pure
duplication that a filter or `@Around` aspect replaces, and it duplicates what `LoggingInterceptor`
already does.

**Partly fixed** in the cleanup pass. `GlobalGeneralLogging` gained
`controllerLogging(principal, request, sourceClass, message)`, which does the whole preamble in one
line, and every handler in `resource/` and `api/` now uses it. The Thymeleaf controllers still carry
the long form; converting them is worth doing with the aspect, not before it.

### 26. Persian UI strings hardcoded in Java — **S3**

`"لطفا اطلاعات را بطور صحیح وارد نمایید"`, `"اطلاعات با موفقیت ذخیره شد"` and similar are string
literals inside controllers and `FileApi`. No `messages.properties`, no `MessageSource`, no locale
negotiation. The API returns Persian prose to machine clients.

**Narrowed.** `messages.properties` now exists and the templates are fully converted; the API no
longer returns Persian at all, since `FileApi` throws instead of composing sentences. What remains
is the Thymeleaf controllers, which still assign Persian literals to the `message` model attribute
in every `catch`. Converting them means injecting `MessageSource` into eight controllers, which is
the same edit as the logging-aspect work in [issue 25](#25-sixty-copies-of-the-same-logging-preamble--s3) —
worth doing in one pass, in Phase 2.

### 27. No `@ConfigurationProperties` — **S3**

`${file.management.base-dir}` is `@Value`-injected into five separate beans; page sizes into every
controller. Two prefixes coexist (`file.management.*` and `filemanagement.*`). Nothing validates the
values at startup — a missing `base-dir` fails at first upload, not at boot.

### 28. WAR packaging for an external Tomcat — **S2**

> **Resolved.** `<packaging>jar</packaging>`, `SpringBootServletInitializer` removed. The build
> now produces an executable `target/file-management.jar`. A container image is still open.

`<packaging>war</packaging>` + `SpringBootServletInitializer` + a fixed `finalName`. Workable for the
current deployment, awkward for containers, and it rules out the executable-jar layered-image build
that the rest of the roadmap assumes.

### 29. `ModelConverterUtil` is a 300-line static mapper — **S3**

Untestable in isolation, silently coupled to the eager-fetch decisions in the entities, and it
duplicates field lists that already exist on the DTOs.

---

## Data & persistence

### 30. `@Table(name = "user")` — a reserved word in PostgreSQL — **S1 for the migration**

`user` is reserved in PostgreSQL (it resolves to `CURRENT_USER`). Every unquoted reference fails.
This is the single largest blocker for the Postgres migration and it touches the entity, all three
migration files, both `JOIN` DAOs and `schema-db/schema.sql`.

Fix: rename the table to `app_user` in a migration and set `@Table(name = "app_user")`. Do **not**
solve it by quoting — quoted mixed-case identifiers are worse to live with.

### 31. Migrations are MySQL-only — **S1 for the migration**

`ENGINE = InnoDB`, `DEFAULT CHARSET = utf8mb4 COLLATE utf8mb4_unicode_ci`, `AUTO_INCREMENT`,
`DATETIME`, `#` line comments (V1.1 — not valid SQL in PostgreSQL), `ADD COLUMN ... AFTER updated_at`.
`flyway-mysql` is a declared dependency.

Fix: a fresh `V2.0` PostgreSQL baseline plus a documented data-copy path, rather than trying to make
V1.0–V1.2 portable.

### 32. `schema-db/schema.sql` is a live footgun — **S1**

> **Resolved in Phase 0.** `schema-db/` deleted. Flyway is now the only way the schema is created.

277 lines that begin with `DROP DATABASE IF EXISTS file_management;` and go on to drop
`flyway_schema_history`. It duplicates the Flyway migrations, is not referenced by the build, and
carries no warning. One accidental execution against the wrong connection destroys production and
Flyway's record of it.

Fix: delete it, or move it to `docs/` clearly marked as a local-development reset script.

### 33. Schema and entity mappings disagree — **S2**

> **Fixed in the architecture pass.** The mappings now match the schema in both directions:
> `GeneralTag.tagName` gained the `unique` the schema has, `MainTagFile.tagName` lost the one it
> does not, and the `NOT NULL` columns are marked as such.

* `file_category.general_tag_id` is `NOT NULL` in SQL; the entity's `@JoinColumn` omits
  `nullable = false`.
* `file_details.description` is `NOT NULL` in SQL, but `FileDetails.description` is nullable and
  `FileUploadDTO.fileDetailsDescription` can arrive null on the format/version path.
* `file_info.file_sub_category_id` and `main_tag_file_id` are `NOT NULL` in SQL, unannotated on the
  entity.

`ddl-auto=validate` checks types and existence, not nullability, so these drift silently until an
insert fails at runtime.

### 34. No indexes beyond primary, foreign and unique keys — **S2**

> **Fixed in the architecture pass** by migration `V1.3`, which also adds the composite unique
> constraints the services were checking only in Java.

Nothing on `file_info.file_name`, `file_details(file_info_id, version)`, `file_details.state`,
`file_info.state`, or `action_history(entity_name, entity_id)` — the last of which is the only access
path `ActionHistoryRepository.findByEntityIdAndEntityName` has.

### 35. Paths are denormalised into three places — **S2**

`file_path` (absolute, includes `base-dir`) and `relative_path` on both `file_info` and
`file_details`, plus the directory tree itself. Renaming a category, moving `base-dir`, or migrating
to S3 invalidates the absolute column on every row. Nothing keeps the three in sync.

Fix: store only a storage-neutral key; derive everything else.

---

## Testing, build, operations

### 36. Nothing verifies that the application starts — **S1**

> **Resolved in Phase 0.** `FileManagementApplicationTests.contextLoads` is enabled and runs the full `@SpringBootTest` context against a Testcontainers MySQL.

`FileManagementApplicationTests` has `@SpringBootTest` commented out, `@Test` commented out, and an
empty method body. There is no smoke test. A broken bean graph reaches production undetected.

### 37. Tests require a hand-provisioned MySQL and a Windows path — **S1**

> **Resolved in Phase 0.** `MySqlSupport` starts a MySQL container per JVM; `StorageRootSupport` clears `./target/test-storage/` before and after every test. `./mvnw verify` needs nothing but a Docker daemon. 87/87 green.

`@AutoConfigureTestDatabase(replace = NONE)` plus `src/test/resources/application.properties`
hardcoding `jdbc:mysql://localhost:3306/file_management_test` and
`file.management.base-dir=D:/files/test/`. The suite cannot run on CI, on Linux, or on a second
developer's machine. `FileStorageFileSystemServiceTest.setUp()` calls `Files.createDirectory(baseDir)`
unconditionally and fails if a previous run left the directory behind.

Fix: Testcontainers for PostgreSQL, `@TempDir` for the filesystem, MinIO in a container for S3.

### 38. No CI — **S1**

> **Resolved in Phase 0.** `.github/workflows/build.yml` builds and tests on JDK 21 and 25, plus `dependency-review-action` on pull requests and `.github/dependabot.yml` for weekly updates.

No `.github/workflows`, no pipeline of any kind. Nothing builds, tests, or scans dependencies on
push. Given issue 1, that is exactly how a version upgrade disappears without anyone noticing.

### 39. No containerisation — **S2**

> **Resolved in Phase 0.** `compose.yaml` brings up MySQL for local runs. An application image still belongs to Phase 1, when packaging switches from WAR to JAR.

No `Dockerfile`, no `compose.yaml`. Bringing up the application requires manually installing MySQL,
running `schema-db/schema.sql`, and creating Windows directories by hand — as the current README
instructs.

### 40. `logback-spring.xml` hardcodes a Windows path — **S2**

> **Resolved in Phase 0.** `LOG_PATH` now comes from `filemanagement.log.path` / `FILEMANAGEMENT_LOG_PATH` via `<springProperty>`, defaulting to `./logs`.

`<property name="LOG_PATH" value="D:/files/logs" />` with no property placeholder and no profile
override. On Linux the appender silently fails or writes to an unexpected location.

Fix: `${LOG_PATH:-./logs}` sourced from a Spring property, and console-only JSON logging in
containers.

### 41. No Actuator, no metrics, no real health check — **S2**

`spring-boot-starter-actuator` is not a dependency. `/api/v1/files/health-test` returns a hardcoded
string, requires authentication, and checks nothing — not the database, not the filesystem. There is
no liveness/readiness split, no Prometheus endpoint, no build-info.

### 42. No API documentation — **S3**

No springdoc / OpenAPI. The `/api/v1/files` contract exists only as Java source. External integrators
have to read `FileApi.java`.

### 43. No static analysis, formatting or dependency policy — **S3**

No Checkstyle, Spotless, SpotBugs, ErrorProne, `maven-enforcer`, OWASP dependency-check, or Renovate
config. Formatting is inconsistent across files, and dead imports (issue 10) survive indefinitely.

### 44. Uploads are fully buffered — **S2**

`Files.copy(file.getInputStream(), targetPath)` after Spring has already materialised the multipart
part. The 20 MB cap keeps this survivable; raising it for the S3 work without switching to streaming
multipart upload will put whole files in heap.

### 45. The `prod` profile writes into the working tree — **S3**

> **Partly addressed in Phase 0.** The path is now `FILEMANAGEMENT_BASE_DIR`-overridable. The in-repo default remains, and is only appropriate for a local run.

`file.management.base-dir=./TempFiles/files/main/` is the committed default while
`spring.profiles.active=prod`. `TempFiles/` is gitignored, so a production run quietly stores user
data in a directory the repository is configured to ignore. There is already an
`offline-first.png` sitting there from a manual test.

### 46. Two paging DTOs per entity, hand-rolled — **S3**

`FileInfoPageDTO`, `FileCategoryPageDTO`, `FileSubCategoryPageDTO`, `GeneralTagPageDTO`,
`MainTagFilePageDTO`, `PublicFileDetailsPageDTO` each re-declare `totalPages`, `pageSize`,
`numberOfElement` and a list. One generic `PageResponse<T>` replaces all six.

---

## Found while building the Phase 0 safety net

These three were invisible until the suite ran on Linux, on a current JDK, and against a full
application context. They are the argument for Phase 0 existing at all.

### 47. `MainTagFileDAO` queried `file_Info`, which does not exist on Linux — **S1**

> **Resolved in Phase 0.** Corrected to `file_info`.

```sql
SELECT COUNT(fi.id) FROM file_Info fi JOIN main_tag_file mtf ON fi.main_tag_file_id = mtf.id WHERE mtf.id = (:id)
```

MySQL's `lower_case_table_names` defaults to `1` on Windows and macOS, where identifiers are folded
and `file_Info` silently resolves to `file_info`. On Linux it defaults to `0`, identifiers are
case-sensitive, and the query fails with `Table 'file_Info' doesn't exist`.

`MainTagFileDAO.isDeletable` is the only caller, and it is reached from
`MainTagFileService.deleteMainTagFile`. So **deleting a main tag threw `BadSqlGrammarException` on
any Linux deployment** and always had; the developer machine could never reproduce it.
`MainTagFileServiceTest` covered the path and passed, because it ran against the same Windows MySQL.

The container in `compose.yaml` sets `--lower-case-table-names=0` so this class of bug now surfaces
locally too.

### 48. Lombok never ran on JDK 23+ — **S1** (build)

> **Resolved in Phase 0.** Lombok pinned to 1.18.48 and declared as an explicit
> `annotationProcessorPaths` entry on `maven-compiler-plugin`.

Two independent problems, both fatal on the JDK 25 installed on the development machine:

1. Spring Boot 3.2.1 pins Lombok 1.18.30, whose processor cannot run on a JDK 25 `javac`.
2. **JDK 23 removed implicit annotation processing.** A processor merely present on the classpath is
   no longer discovered; it has to be requested with `-proc:full` or declared as a processor path.

The symptom is `cannot find symbol: method getId()` on hundreds of lines — every Lombok-generated
accessor in the project. `./mvnw package` could not build this project at all on the machine it is
developed on. Phase 1 must keep the processor path when it moves the parent to Spring Boot 4.1.1.

### 49. The application cannot start without LDAP properties, even with AD disabled — **S2**

> **Resolved in Phase 0.** Both placeholders now default to empty, matching the `enabled:false`
> default already used in the same class.

`ActiveDirectoryCustomAuthenticationProvider` injected
`${filemanagement.auth.ldap.activedirectory.domain}` and `.url` with no defaults, while `.enabled`
had `:false`. Since the bean is a plain `@Component` it is constructed whether or not AD is enabled,
so any deployment that omitted the two LDAP properties failed at startup with
`Could not resolve placeholder`, not with anything that named Active Directory.

Nothing caught it because no test had ever loaded the full application context — see issue 36.

### 50. An empty branch in the file tree looks like a broken one — **S3**

`FileTreeService` sets `expandable = childCount > 0`, and the view renders a non-expandable node
with a disabled twisty. Clicking it does nothing and says nothing, so a category with no
sub-categories, or a tag with no files, is indistinguishable from a tree that has stopped working.
On a data set where most branches are empty the whole view reads as broken.

Fix: keep the node expandable, and on open render an inline empty-folder row. The child count stays
useful as a badge, but it must not be what decides whether the control responds.

---

## Found during the Spring Boot 4 upgrade

Each was exposed by a version hop, which is why the upgrade was done in two steps rather than one.

### 51. `cascade = ALL` on the inverse side of a many-to-many — **S1**

`Permission.roles` and `Role.users` were both `@ManyToMany(mappedBy = ..., cascade = CascadeType.ALL)`.
On the inverse side that means **deleting a permission cascades a remove to every role that holds
it, and deleting a role cascades a remove to every user who has it**. Nothing in production deletes
roles or permissions, so the data loss never happened - but it was one call away.

Hibernate 6.6 made this visible: `RoleServiceTest.tearDown` began failing with
`TransientObjectException`, because the stricter flush checks would no longer tolerate a managed
entity referencing a removed one through a cascading collection.

Fixed by removing the cascade from both inverse sides, and by deleting in foreign-key order in the
tests (`user` owns `user_role`, `role` owns `permission_role`).

### 52. A generated id was read before the cascade had assigned it — **S1**

`FileService.createNewVersionFileDetails` added a new `FileDetails` to the parent's collection,
called `fileInfoRepository.save(fileInfo)`, and then passed `fileDetails.getId()` to the audit log.
The cascade only inserts the child at flush, so on Hibernate 6.6 the id was still `null` and the
call threw `NullPointerException` - meaning **creating a new version of a file failed outright**.

The first attempt at a fix made it worse and is worth recording: adding an explicit
`fileDetailsRepository.save(fileDetails)` *after* `save(fileInfo)` produced a duplicate row. The
parent is already managed there, so `save()` is a `merge()`, and merging a managed parent whose
collection holds a transient child inserts a **copy** of the child - two rows, and a unique-key
violation on `hash_id`.

Fixed by persisting the child directly and not re-saving the managed parent at all: its
`lastVersion` change is picked up by the dirty check.

### 53. Spring Boot 4 splits auto-configuration into per-technology modules — **S1** (build)

`flyway-core` on the classpath no longer brings Flyway's auto-configuration: that lives in
`org.springframework.boot:spring-boot-flyway`. Without it the migrations never ran, and Hibernate's
`ddl-auto=validate` failed the whole context with `missing table [action_history]` - 87 tests
erroring from one root cause.

The test slices moved the same way, both artifact and package:

| Annotation | Was | Now | Module |
|---|---|---|---|
| `@DataJpaTest` | `…boot.test.autoconfigure.orm.jpa` | `…boot.data.jpa.test.autoconfigure` | `spring-boot-data-jpa-test` |
| `@AutoConfigureTestDatabase` | `…boot.test.autoconfigure.jdbc` | `…boot.jdbc.test.autoconfigure` | `spring-boot-jdbc-test` |
| `@AutoConfigureMockMvc` | `…boot.test.autoconfigure.web.servlet` | `…boot.webmvc.test.autoconfigure` | `spring-boot-webmvc-test` |

### 54. Spring Security 7 removals — **S1** (build)

* `DaoAuthenticationProvider` lost its no-arg constructor and `setUserDetailsService`; the
  `UserDetailsService` is now a constructor argument.
* `AntPathRequestMatcher` is gone. Replaced by
  `PathPatternRequestMatcher.pathPattern(...)`, which also takes an `HttpMethod` overload.
* The entry point now issues a **context-relative** redirect to the login page (`/login`) where
  Spring Security 6 issued an absolute one (`http://localhost/login`). Better behaviour behind a
  reverse proxy, but anything asserting the absolute form breaks.

### 55. The logback configuration silently never deleted old logs — **S2**

`maxHistory` sat inside a `SizeAndTimeBasedFNATP` nested in a `TimeBasedRollingPolicy`, where
logback ignores it - so archives accumulated without limit. The console appender also used a
`<layout>`, which a `ConsoleAppender` no longer accepts, and its `<charset>` was discarded.

Rewritten to a single `SizeAndTimeBasedRollingPolicy` with `maxFileSize`, `maxHistory` and
`totalSizeCap`, and an `<encoder>` on the console. Startup is now free of logback warnings.

---

## Found during the cleanup pass

### 56. `findByIdOrUsername(id, null)` matches on a null username — **S3** (latent)

```java
Optional<User> findByIdOrUsername(int id, String username);   // UserRepository
```

is derived to `... WHERE u.id = ? OR u.username = ?`, and JPA renders a null argument as
`u.username IS NULL`. Every caller that has only an id passes `null` for the name — the test log
shows it plainly:

```sql
from user u1_0 where u1_0.id = ? or u1_0.username is null
```

Today `user.username` is `NOT NULL`, so the second branch never matches and the query is merely
misleading. It stops being harmless the moment a nullable name column is introduced, or the same
pattern is copied to one — `findByIdOrCategoryName`, `findByIdOrTagName` and `findByIdOrRoleName`
are all shaped this way.

Fix: two derived methods, or a `@Query` with an explicit `(:username IS NOT NULL AND ...)` guard.
Left alone here because changing lookup semantics is not a cleanup, and every one of these methods
is on a hot path.

### 57. Two "change state" endpoints, two different shapes — **S3**

`PUT /resource/files/file-info/{id}/change-state` takes `{"newState": 0}` in the body.
`PUT /resource/files/file-info/{id}/file-details/{fdId}/change-state/{newState}` takes it in the
path. Same operation, same allowed values, two spellings — a client has to learn both.

The path form is what `file-info-page.html` already calls, so unifying them is a breaking change to
a URL, not a refactor. Do it when the page is rewritten for the tree file manager (Phase 5).

### 58. The API upload answers 200, not 201 — **S3**

`POST /api/v1/files` creates a resource and returns 200 with the created version's DTO. 201 with a
`Location` header is the correct answer, but `/api/v1` is published and the status is part of its
contract, so this is a versioning decision rather than a cleanup. Grouped with the API
documentation work ([issue 42](#42-no-api-documentation--s3)).

### 59. A dead null check on a stream result — **S3**

```java
List<FileDetails> list = fileDetailsList.stream().filter(...).toList();
if (list == null || list.size() == 0) {
    throw new BusinessException("list of same version file is empty!");
}
```

in `FileService.deleteFileDetails`. `Stream.toList()` never returns null, so the first half is dead
and the second would read better as `isEmpty()`. Cosmetic, and inside the most delicate method in
the codebase — worth doing with the tests that already cover version deletion, not on its own.

---

## Found and fixed in the architecture pass

Each of these was found while reviewing the entity, repository, service and controller layers, and
fixed in the same pass. They are recorded because a fix is only obvious once the defect is named.

### 60. Deleting the newest version left `lastVersion` pointing at it — **S1**

`FileInfo.lastVersion` is a denormalised `MAX(version)`. Creating a version raised it; deleting one
never lowered it. Removing the newest version of a file therefore left the column naming a version
that no longer existed: the next upload of that number was rejected as "wrong version for create new
version", so the number could never be reused, and the file page displayed a version that was gone.

Fixed with `FileInfoRepository.recalculateLastVersion` — one `UPDATE ... SET lastVersion = (SELECT
MAX(...))` rather than read-modify-write in Java, so two sessions deleting different versions cannot
each compute a maximum from a stale snapshot and write it back.
`FileServiceTest.aFreedVersionNumberCanBeReused` covers the user-visible consequence.

### 61. A validation whose body was commented out — **S2**

```java
if(!Objects.equals(mainTagFile.getFileSubCategory().getId(), mainTagFileDTO.getFileSubCategoryId())) {
//    throw new InvalidDataException("invalid subCategoryId=" + mainTagFileDTO);
}
```

in `MainTagFileService.updateMainTagFile` — an `if` whose only statement was disabled. A request
naming a different sub-category was accepted and silently ignored, and the same method never updated
`tagNameDescription` even though create set it. Both fixed; moving a tag between sub-categories is
now a 400, because files derive their directory from the sub-category and moving the tag would leave
the stored bytes where the metadata no longer points.

### 62. Two copies of the login principal builder, already drifted — **S1**

`UserService.createUserDetailsFromUser` and `UserDetailsServiceImpl.loadUserByUsername` built
`UserDetailsImpl` from the same two calls — but only one granted the synthetic `ADMIN` authority. An
administrator signing in through Active Directory got it; the same administrator signing in with a
local password did not. There is now one builder, and each provider only decides whether its
mechanism is allowed for that user.

The same path also refused a user with no roles: the permission lookup threw
`ResourceNotFoundException("user don t have any role!")`, which the login translated into
`UsernameNotFoundException`, so a roleless account was rejected with "username not found". A user
with no roles now signs in with an empty authority list.

### 63. The bootstrap shipped a known administrator password — **S1** (security)

`FileManagementApplication.initialize` created the `Admin` account with the literal password
`"admin"`, so every deployment shipped with the same known credentials for an account holding every
permission. It is now `filemanagement.bootstrap.admin-password`; with nothing configured a random
password is generated and logged once, at WARN, on the run that creates the account.

### 64. `@Transactional` on a self-invoked method — **S2**

The same bootstrap was annotated `@Transactional` and called on `this` from the
`CommandLineRunner` lambda. Spring's transaction support is a proxy and a call from inside the bean
does not pass through it, so the annotation did nothing and the seeding ran as a dozen independent
transactions — failing halfway left the database half-seeded with no error the next start could
detect. It is now `DataInitializer`, a bean of its own.

### 65. The profile default carried its own quotes — **S2**

`@Value("${spring.profiles.active:'prod'}")` compared `"'prod'"` to `"prod"`. A deployment that did
not set the property silently skipped the entire bootstrap; it worked only because
`application.properties` always set it explicitly. Now read from the `Environment`.

### 66. Duplicate checks read a collection that can be stale — **S2**

`deleteFileCategory` asked `fileCategory.getFileSubCategories().isEmpty()`, and the child listings
reached the children through the parent the same way. A collection answers from the persistence
context, which can hand back one initialised earlier in the same transaction when it was empty — so
a category that had just gained a sub-category looked empty and passed the delete check. All of them
are now counts and queries against the child table, which is also cheaper.

### 67. Sentinel arguments standing in for "this field did not change" — **S3**

`UserService.updateUser` packed unchanged fields into `""` and `0` and passed all four to
`existsByUsernameOrPersonelCodeOrNationalCodeOrPhoneNumber`, asking the database "is there a user
whose username is the empty string, or whose personnel code is zero". It worked because no such row
exists. It also dereferenced `getPhoneNumber()` on a nullable column, so a user without a phone
number could not be edited at all.

The same shape appeared in `MainTagFileService` (an empty-string description) and in every
`findByIdOr<Name>(id, null)`, where Spring Data renders the null as `IS NULL` — asking for role 5 by
id also asked for "any role whose name is null". All replaced by methods that take one key each.

### 68. The user list page could disagree with its own pager — **S3**

`getAllUserWithSearchPage` and `countAllUserWithSearchPage` each parsed the search term, with
slightly different rules: the row query blanked an all-whitespace term and the count query did not.
Two queries deriving their filter independently is a pager that can contradict the list it pages.
Now one `Page<UserDTO>`.

### 69. A dropdown filled by a paged query — **S3**

Four forms called `getAllFileCategories(defaultElementSize, 0)` to fill the category `<select>`, so
the list silently truncated once a deployment had more categories than the configured page size —
and differently depending on the property. Replaced by `getAllFileCategoriesForSelection()`.

### 70. Duplicate rules were checks, not constraints — **S2**

Every "is this a duplicate?" in the services was a `SELECT` followed by an `INSERT`, which two
concurrent requests can both pass. For a category or a sub-category that also means two rows
claiming one directory on disk. Migration `V1.3` adds the four composite unique constraints; the
in-code checks stay, because they are what turns a violation into a readable 409 rather than a 500.

---

## Found while investigating the file tree (Phase 5)

Reported as: on real data, opening the tree gets heavy as the taxonomy grows, and a file that
provably exists in the database does not appear under its tag. Verified against a live instance
(`docker` MySQL, app on `:8122`) and the current data: category `IMS` (id 5, "سیستم مدیریت
یکپارچه") → sub-category `IMS_Document_System` (id 35, "سیستم مستندات IMS") → tag `HR` (id 146,
"منابع انسانی و پشتیبانی") — the exact path reported — has 49 `file_info` rows correctly linked
(`main_tag_file_id = 146`, all `state = 0`, `enabled = 1`). `FileTreeService.filesOf` and
`countFileWithTagId` both query unconditionally on `main_tag_file.id`, so the data is intact and
the row count the server would report matches the row count it would list. The defect is client-side.

### 71. The tree's "expand" paths fetch one node at a time with no cap, and no level paginates — **S2**

> **Partly fixed.** `expandRoots()` now fires all root categories concurrently
> (`Promise.all`) instead of one `await` per category, and `expandAll()` stops at the main-tag
> level instead of also opening every file and fetching every file's version list — on the
> taxonomy checked above that was the difference between a few dozen requests and one per file.
> **Still open:** none of `subCategoriesOf` / `mainTagsOf` / `filesOf` paginate, so a single very
> large node (a tag with hundreds of files) still returns and renders everything at once. Worth
> revisiting if a tag's file count grows much past what was measured here.

`file-tree.html`'s `init()` calls `expandRoots()`, which `await`s `open()` on every root category
**serially** on every page load — one HTTP round trip per category before the page is idle.
`expandAll()` did the same for the *entire* tree: as each node opens, its children are spliced into
`rows` and the same `while (index < this.rows.length)` loop walks over them too, so it issued one
sequential `fetch()` per category, sub-category, main tag, file **and** version in the whole
taxonomy. On the current data that is not a small number: category 5 alone has 29 sub-categories,
sub-category 35 alone has 29 main tags, and tag 146 alone has 49 files — and every one of those 49
files would also have had its own version list fetched. This is the "gets heavy once there are a lot
of files" report.

### 72. A tree node's `id` is unique only within its own type, and the client used to index rows by `{type, id}` alone — **S3** (latent)

> **Fixed.** `file-tree.html`'s `indexOf(row)` now matches on `row.key` — the string `decorate()`
> already generates once per rendered row with a random suffix — instead of `{type, id}`, so it can
> no longer resolve to the wrong row regardless of what a future node type reuses as `id`.
>
> **Correction after re-reading the code:** this was not actually reachable today. `VERSION` rows
> are the only ones with a non-unique `id` (`FileTreeService.toVersionNode` uses the raw version
> number, and every file's first version is `1`), but a `VERSION` row is never `expandable`, and
> `open()`/`toggle()` refuse to act on a non-expandable row — so `indexOf` was never actually called
> with a `VERSION` row as the argument, and this was not the cause of the reported missing file. It
> is fixed anyway as a latent fragility (the same pattern would misbehave the moment any non-leaf
> node reused an `id` across parents), but the missing-file report itself is better explained by
> issue 71: a failed `load()` during a bulk expand silently re-closed the node
> (`row.open = false` in the `catch`) with only a page-level banner to say so, which a per-row
> `row.error` (added with this fix) now makes visible on the exact node that failed.

`TreeNodeDTO.id`'s own doc comment says "identifier **within its own type**" (`dto/TreeNodeDTO.java`).
That holds for `CATEGORY`/`SUB_CATEGORY`/`MAIN_TAG`/`FILE`, whose `id` is the entity's database
primary key and therefore globally unique — but `FileTreeService.toVersionNode` gives a `VERSION`
node the raw version number (`1`, `2`, …) as its `id`, and every file's first version is `1`.
`file-tree.html`'s `indexOf(row)` — used by `open`, `close` and `load` to find where a row sits in
the flat `rows` array and where to splice its children in or out — matched on
`candidate.type === row.type && candidate.id === row.id` alone, with no reference to which parent
the row belongs to.

### 73. Main tags under `IMS_Document_System` reuse the exact names of unrelated sibling sub-categories — **S2**

> **Partly fixed: candidate (a).** `/files/tree` now has a search box
> (`FileTreeService.search`, `GET /resource/files/tree/search`, permission
> `REST_SEARCH_FILE_TREE`) that matches a file by exact id or a fragment of its name/description
> and returns the full chain of ids and titles down to it (`TreeSearchHitDTO`). Picking a result
> opens category → sub-category → main tag in turn and scrolls to and highlights the file row, so
> finding a file no longer depends on recognising which of several identically-labelled branches
> holds it. Covered by `web/FileTreeSearchTest` against real fixtures (a main tag named `HSED`
> mirroring the reported shape), including that a Persian-numeral query is treated as text rather
> than crashing the id parse. **Not done:** (b) and (c) — the tree still gives no visual cue that
> two nodes share a label, and the `IMS_Document_System` tag names themselves are untouched. Worth
> revisiting once it is clear whether search alone resolves the confusion in practice.
>
> A new permission constant means existing roles that already had `REST_GET_FILE_TREE` do **not**
> automatically get `REST_SEARCH_FILE_TREE` — `FileManagementApplication`'s seeding only inserts
> the row, it does not grant it to any role. Grant it through the roles admin page to whichever
> roles should see the search box (`ADMIN` is unaffected: its `@PreAuthorize` bypass is unconditional).

The user reported a second "missing" file after 71/72 (`file_info` id 1578) and it is the same
shape as the first, not a regression: `file_info.id = 1578` has `main_tag_file_id = 136`
("HSED"/"HSED"), which sits under sub-category 35, `IMS_Document_System`. `file_category` `IMS`
(id 5) *also* has a direct sub-category 8, also named "HSED" — a different node entirely, with its
own unrelated tags (`ConsultingCenter`, `Safety`, …). Whoever opens sub-category 8's "HSED" looking
for this file will not find it there; it is three levels deeper, under `IMS_Document_System > HSED`.

This is not a one-off. Query:

```sql
SELECT COUNT(*) FROM main_tag_file mt
JOIN file_sub_category sc ON TRIM(sc.sub_category_name_description) = TRIM(mt.tag_name_description);
```

returns **20** — and sub-category 35 has only 30 main tags in total. `IMS_Document_System` was
evidently built as one tag per department, reusing each department's existing sub-category name
verbatim, so most of its tags are same-labelled twins of an unrelated, shallower branch in the same
tree (the first report — tag 146 "منابع انسانی و پشتیبانی" vs. sub-category 14 "HumanResource" — is
one of these 20 pairs too).

Nothing in `FileTreeService` or `file-tree.html` drops or misplaces these rows — both reported files
are returned correctly by the same query used to build their tag's badge count (verified directly
against the database, see the note at the top of this section). The tree renders exactly what is
asked of it; the ambiguity is that two different nodes in the same tree carry the identical label, and
a label is the only thing the current UI gives a user to navigate by.

Fix direction: this needs a decision, not just a patch. Candidates: (a) a "find in tree" search that
jumps straight to a file by id/name/description regardless of which of several identically-labelled
branches holds it; (b) visually distinguishing a main tag whose label collides with another node
elsewhere in the tree (e.g. a note showing its full path); (c) a data fix that renames the
`IMS_Document_System` tags so they read as "IMS document — HSED" rather than bare "HSED". (a) helps
regardless of which of (b)/(c) is also done, and does not require touching the existing taxonomy data.

---

## Found while building the folder mirror (Phase 6)

### 74. Usernames are not directory-safe, but are destined to become folder names — **S3**

Roadmap 6.7 gives every user a `Home/{username}` folder, and roadmap 6.8 turns every folder into a
real directory. `folder.name` is therefore declared directory-safe — no `.`, no space, no `/`, the
rule `ValidationUtil.checkCorrectDirectoryName` applies to categories and sub-categories.

`UserService.createUser` applies no such rule to a username, and six of the eight usernames in this
installation contain a dot:

```
f.zakeri  h.mirzaeizadeh  a.mahmoodi  s.naseri  h.nikouei  moj.tavakkoli
```

So the user-home folders were **not** created in `V1.5`: the rows would have violated the column's
documented contract on the day they were written, and every one of them would have needed renaming
at 6.8. They would also have been useless in the meantime — a file hangs off a main tag, not a
folder, so nothing could be filed in a home folder until 6.8 anyway.

This has to be decided before 6.7 can land, and it is a product decision as much as a technical one:

* constrain usernames going forward and migrate the existing six (they are login names, so renaming
  them is not free); or
* give `folder` a separate directory-safe `slug`, derived from the name and unique among siblings,
  and let `name` hold whatever the source says. This is the smaller change and it also covers the
  Persian category labels if folders ever take those.

Nothing is broken today. `USER_HOME` and `folder.owner_user_id` exist and are unused.

### 75. Folder access is enforced in the tree only — **S2** (in progress)

`FolderAccessService` answers "may this person see this folder", and `FileTreeService` asks it on
every roots call, every child listing and every search hit. Nothing else does yet: `FileService`,
`FileApi` and the file-list pages still answer from the taxonomy with no folder check, so
[issue 14](#14-no-resource-level-authorization--s1) is narrowed rather than closed — a principal
holding `DOWNLOAD_FILE` can still fetch any file by id, and the tree is simply where they can no
longer find it.

Closing it means calling `FolderAccessService.requireAccess` from `FileService.downloadFile` and
pushing the granted prefixes into the file-list and public-file queries, which is the "push the
prefixes into the query" half of roadmap 6.6 and is the next piece of work. It is deliberately not
done in the same change as the model: enforcement in the download path changes what a live
integration can fetch, and it should land on its own, with `filemanagement.folder-access.enabled`
already proven in the tree.
