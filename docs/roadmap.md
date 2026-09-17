# Roadmap

This began as four goals — restructuring, PostgreSQL, S3 storage and a folder tree. Phase 0 came
first to make the rest safe, and Phases 6–8 grew out of the fourth once it became clear what a folder
tree actually implies. Phase 9 is the one addition from outside that line: machine access, which the
folder tree finally made it possible to scope. Every phase is sequenced to leave the application
working, and to depend only on what came before.

| Phase | Goal | Depends on | Status |
|---|---|---|---|
| 0 | Safety net — CI, smoke test, containerised dev environment | — | **done** |
| 1 | Spring Boot 4.1.1, staying on Java 21 | 0 | **done** |
| 2 | Architectural restructuring | 1 | |
| 3 | PostgreSQL migration | 1, partly 2, **and 7** | |
| 4 | S3 or MinIO as a storage backend, alongside the filesystem | 2, 3 | |
| 5 | Folder tree: read-only view, then drag-and-drop | 3, 4 | view **done** |
| 6 | Two-tier authorization: endpoint permissions + inherited folder access | 5.1 | **done**; enforcement switched on per installation, after the grants exist |
| 7 | Nested folders replace the taxonomy; the four levels become tags | 6 | 7.1, 7.2 steps 1–3 and 5a **done**; step 4 waits until step 3 has run in production (`deployment.md`, "Readiness for Phase 7 step 4") |
| 8 | IMS: controlled documents, a form builder and approval workflow | 7 | planned |
| 9 | API keys, an S3-style API v2, Actuator and OpenAPI | 6 | **done** |

**Phase 7 runs before Phase 3**, which is the one place the numbering does not match the order. It
is worth the inconsistency: Phase 3 writes a fresh PostgreSQL baseline, and writing it after the
four taxonomy tables are gone means not carrying them into a new schema only to drop them again.
Renumbering instead would break every reference in `docs/`, in issue entries and in code comments.

**Already done, outside a phase:** the Active Directory connection is documented with a worked
example in `application.properties` - domain, URL, the `login_type` gate, and why the two
properties default to empty rather than being absent. And the upload policy (`V2.6`): which kinds
of file may be uploaded and how large, system-wide and per role, edited by the administrator -
`docs/arch.md`, "The upload policy".

Phase 0 was not one of those goals, but every later phase is a large refactor of code that had **no**
automated verification at all (issues 36–38). Doing it first is what made the rest safe.

---

## Phase 0 — Safety net — **done**

> Delivered. `./mvnw verify` is green with 87 tests on a machine with nothing but Docker.
> Three previously invisible bugs surfaced in the process — issues
> [47](issues.md#47-maintagfiledao-queried-file_info-which-does-not-exist-on-linux--s1),
> [48](issues.md#48-lombok-never-ran-on-jdk-23--s1-build) and
> [49](issues.md#49-the-application-cannot-start-without-ldap-properties-even-with-ad-disabled--s2),
> the first of which broke tag deletion on every Linux deployment.
>
> Two things were pulled forward out of necessity: Lombok is pinned to 1.18.48 with an explicit
> annotation-processor path (nothing compiled on JDK 25 otherwise), and Testcontainers is pinned to
> 1.21.4 (the version Boot 3.2.1 manages predates Docker context support). Phase 1 must preserve
> both when it moves the parent POM.

**Why first:** the Spring Boot upgrade already disappeared once in a merge without anyone noticing
(issue 1). Nothing in the repository would have caught it.

### What was delivered

1. **Smoke test.** `FileManagementApplicationTests.contextLoads` is a real `@SpringBootTest` again.
2. **Testcontainers for the whole suite.** `support/MySqlSupport` starts one MySQL 8.0.36 container
   per JVM and registers `spring.datasource.*` through `@DynamicPropertySource`;
   `support/StorageRootSupport` clears `./target/test-storage/` before and after every test so the
   existing create-in-setUp / delete-in-tearDown pattern keeps working and no longer breaks after an
   interrupted run. The hardcoded `jdbc:mysql://localhost:3306/file_management_test` and
   `D:/files/test/` are gone.
3. **`compose.yaml`** — MySQL for local runs, with `--lower-case-table-names=0` so identifier-casing
   bugs surface on Windows too. PostgreSQL and MinIO join it in Phases 3 and 4.
4. **CI.** `.github/workflows/build.yml` runs `./mvnw verify` on JDK 21 **and** 25 and adds
   `dependency-review-action` on pull requests; `.github/dependabot.yml` schedules weekly updates.
5. **Footguns deleted.** `schema-db/` (issue 32) and `FileDAO` (issue 5).
6. **Secrets out of the repository** (issue 11). `application.properties` reads every value from the
   environment, `FILEMANAGEMENT_DB_PASSWORD` deliberately has no default, the LDAP host is gone, and
   `application-local.properties` is gitignored with an `.example` beside it. `logback-spring.xml`
   takes `LOG_PATH` from the Spring environment instead of `D:/files/logs`.

**Still outstanding from this phase:** the credentials already in the git history have not been
rotated, and the `Admin`/`admin` bootstrap account is unchanged. Both are operational tasks.

**Done when:** `./mvnw verify` passes on a machine with only Docker installed, and CI is green.
✅ 87 tests, 0 failures.

---

## Phase 1 — Platform upgrade — **done**

> Delivered. Spring Boot 4.1.1 on Java 21: Spring Framework 7.0.9, Spring Security 7.1.1,
> Hibernate 7.4.5, Flyway 12.4.0, Jackson 3.1.5, JUnit 6.0.3, Tomcat 11.0.24. 123 tests green,
> every route verified in a browser, and an upload and delete driven end to end over HTTP.
>
> Done in two hops so each failure could be attributed. What actually broke, and why, is recorded
> in [issues 51-55](issues.md#51-cascade--all-on-the-inverse-side-of-a-many-to-many--s1).


**Target: Spring Boot 4.1.1, staying on Java 21.**

Verified against Maven Central at the time of writing — 4.1.1 is the latest stable release
(4.2.0-M1 is a milestone and is not a candidate). It brings Spring Framework 7.0.9,
Spring Security 7.1.1, Hibernate 7.4.5, Flyway 12.4.0, Jackson 3.1.5, Tomcat 11.0.24 and
JUnit 6.0.3.

Spring Boot 4.1.1 declares `java.version` **17** as its baseline, so Java 21 is fully supported.
There is no reason to move the language level in the same change as the framework: keep Java 21,
and raise it later, on its own, if something actually needs it.

This is a **three-major-step jump** from the actual 3.2.1 in `pom.xml` — not the one-step jump the
commit log implies. Treat it as such.

### 1.1 Record what happened to the previous attempt

`b831286` reached Spring Boot 3.5.5; merge `08db773` discarded it. Note this in the upgrade commit
so nobody re-applies the lost change or assumes 3.5.x was ever running.

### 1.2 Step through the intermediate versions

Do not jump straight to 4.1.1. Upgrade to **3.5.16** first (the last 3.5.x), get the suite green,
then to 4.1.1. Each hop has its own release notes and its own deprecation warnings, and a green
build between them tells you which hop broke what.

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.1.1</version>
</parent>

<properties>
  <java.version>21</java.version>   <!-- unchanged -->
</properties>
```

### 1.3 Known breakages to expect

| Area | What changes | Files affected |
|---|---|---|
| **Jackson 3** | new `tools.jackson` package root; Jackson 2 is still available as `jackson-2-bom` but the starters default to 3 | REST serialisation across `api/`, `resource/` |
| **Spring Security 7** | `DaoAuthenticationProvider.setUserDetailsService` was deprecated in 6.4 — use the constructor. `AntPathRequestMatcher` is removed in favour of `PathPatternRequestMatcher` | `SecurityConfig`, `ActiveDirectoryCustomAuthenticationProvider` |
| **Spring Security 7** | `AuthenticationManagerBuilder` assembly via `getSharedObject` is discouraged — publish a `ProviderManager` bean instead | `SecurityConfig.authenticationManager` |
| **Hibernate 7** | stricter HQL validation; the `(:search) IS NULL` idiom used in six repositories may need rewriting as `:search IS NULL` or split queries | all `@Query` repositories |
| **Hibernate 7** | `@Data`-generated `toString` recursion becomes more likely to be triggered by the new logging | all entities — fix issue 2 **before** this hop |
| **Servlet 6.1 / Tomcat 11** | `SpringBootServletInitializer` still exists, but external-Tomcat deployment now requires Tomcat 11 | `FileManagementApplication` |
| **JUnit 6** | `junit-jupiter` 6.0.x; `@ExtendWith(SpringExtension.class)` is unchanged but assertions and lifecycle APIs shifted | all tests |
| **Flyway 12** | `flyway-mysql` still required as an explicit dependency | `pom.xml` |
| **Lombok** | Already pinned to 1.18.48 with an explicit `annotationProcessorPaths` entry, because JDK 23+ dropped implicit annotation processing. Keep both when the parent moves. | `pom.xml` |

### 1.4 Order of operations within the phase

1. Fix issue 2 (`@Data` on entities) — do this **before** upgrading, on 3.2.1, where the behaviour
   is understood.
2. 3.2.1 → 3.5.16. Green build.
3. 3.5.16 → 4.1.1. Green build. The language level does not move.
5. Clean up every deprecation warning the hops surfaced.
6. Add `spring-boot-starter-actuator` (issue 41) and `springdoc-openapi-starter-webmvc-ui` 3.1.0
   (issue 42) while the dependency tree is already being touched.
7. ~~Switch `war` → `jar` (issue 28)~~ — **done ahead of this phase**; the container image is
   still outstanding.

**Done when:** `./mvnw verify` is green on Spring Boot 4.1.1 / Java 21 with zero deprecation
warnings, and `/actuator/health` reports the database.

---

## Phase 2 — Architectural restructuring

The design is specified in [target-architecture.md](target-architecture.md). This phase executes it.
Order matters — each step is independently shippable.

### 2.1 Configuration and cross-cutting concerns (low risk, high leverage)

* One `@ConfigurationProperties("filemanagement")` tree replacing all `@Value` injection (issue 27).
* JPA auditing (`@CreatedDate`, `@CreatedBy`, …) replacing hand-set `LocalDateTime.now()` and the
  `entityManager.getReference(User.class, …)` idiom (issues 23, 24).
* An `@Around` aspect or a servlet filter replacing the sixty copies of the logging preamble
  (issue 25), merged with `LoggingInterceptor`.
* `ActionHistory` written by an aspect on annotated service methods rather than by hand.
* One RFC 9457 `ProblemDetail` advice replacing `GlobalApiExceptionHandler`, with correct status
  codes for `AccessDeniedException` and no message leakage (issue 15).
* One generic `PageResponse<T>` replacing the six `*PageDTO` classes (issue 46).
* `messages.properties` + `MessageSource` for the Persian UI strings (issue 26).

### 2.2 The storage port

Introduce `BlobStore`, `StorageKey`, `StoredBlob` exactly as specified in
[target-architecture.md](target-architecture.md#the-storage-port), and reimplement
`FileStorageFileSystemService` as `FilesystemBlobStore` behind it — including the path-containment
check that is currently missing (issue 16) and the broken `load` guard (issue 4).

Write the **storage contract test** now, as an abstract JUnit class. `FilesystemBlobStore` is its
only subject until Phase 4 adds a second one.

> **Half of this is pulled forward into
> [Phase 7.1](#71-first-decouple-the-storage-key-from-the-structure--done)**: the `storage_key` column and
> the key → path mapping, because folders cannot become the structure while the structure *is* the
> path on disk. What is left here is the rest of the port — the interface itself, the containment
> check, the guards and the contract test.

### 2.3 Domain restructuring

* Re-slice packages by feature (`catalog/`, `file/`, `identity/`, `storage/`, `audit/`, `shared/`).
* Collapse `controller/` + `resource/` + `api/` into a Thymeleaf surface and a single versioned REST
  surface (issue 18). The `/resource/**` endpoints become part of `/api/v1`, called by the pages with
  the session cookie.
* Introduce the two-phase write with `TransactionSynchronization` (issue 3). This requires the
  `status` column, so it lands with the Phase 3 migration if Phase 3 goes first — either order works,
  but the column and the code must ship together.
* `@ManyToOne` → `LAZY` with explicit `@EntityGraph`s, and projection DTOs for the list pages
  (issue 20). Replace `ModelConverterUtil` with per-feature mappers (issue 29).

### 2.4 Authorization

* Collapse `PermissionEnum` into coarse verbs (issue 19), with a migration mapping existing
  permission rows onto the new set.
* Add `AccessPolicy` and call it from the domain services — this is what closes the private-file
  disclosure (issue 14).
* Sniff uploaded content with Tika and store the *sniffed* type, not the client's claim (issue 12).
* Force `attachment` except for a render-safe allow-list, add `nosniff` and a CSP (issue 13).
* Fix the Active Directory provider to throw rather than return `null` (issue 8).

**Done when:** one REST surface, one error shape, one storage port, resource-level authorization
enforced in the domain, and the storage contract test green.

---

## Phase 3 — PostgreSQL migration

> **Run [Phase 7](#phase-7--nested-folders-replace-the-taxonomy) first.** This phase's whole strategy
> is a fresh baseline rather than a portable rewrite (§3.2), and a baseline written while
> `general_tag`, `file_category`, `file_sub_category` and `main_tag_file` still exist would carry
> four tables into a new schema for the sole purpose of dropping them shortly after — along with
> their data migration, their indexes and their foreign keys. Phase 7 also removes the
> `file_path` / `relative_path` columns this phase would otherwise have to translate.

**Target: PostgreSQL 17** (the driver in the Spring Boot 4.1.1 BOM is `postgresql` 42.7.13).

### 3.1 The blocker

`@Table(name = "user")` (issue 30). `user` is reserved in PostgreSQL. Rename the table to `app_user`
in the migration and update `@Table`. Do not solve it with quoted identifiers.

### 3.2 Strategy: a new baseline, not a portable rewrite

V1.0–V1.2 are irreducibly MySQL-specific (issue 31): `ENGINE = InnoDB`, `utf8mb4` collations,
`AUTO_INCREMENT`, `#` comments, `ADD COLUMN ... AFTER`. Making them dialect-neutral is more work than
writing a clean PostgreSQL baseline and is worth nothing afterwards.

Use Flyway's location-per-vendor support:

```
src/main/resources/db/migration/
├── mysql/          V1.0, V1.1, V1.2       ← kept for reference, no longer executed
└── postgresql/     V2.0__Baseline.sql, V2.1__…
```

```properties
spring.flyway.locations=classpath:db/migration/postgresql
```

### 3.3 What the V2.0 baseline changes

| MySQL | PostgreSQL |
|---|---|
| `INT AUTO_INCREMENT` | `INTEGER GENERATED BY DEFAULT AS IDENTITY` |
| `DATETIME` | `TIMESTAMPTZ` (issue 24) |
| `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4` | dropped — database-level `UTF8` encoding |
| table `user` | table `app_user` (issue 30) |
| `file_size INT` | `file_size BIGINT` (issue 6) |
| `enabled` / `state` `INT` | `VARCHAR` + `CHECK` constraint, mapped to enums (issue 22) |
| `file_path` / `relative_path` | `storage_key`, `storage_backend`, `checksum_sha256`, `status` (issues 7, 35) |
| — | indexes on `file_info(file_name)`, `file_details(file_info_id, version)`, `file_details(status, state)`, `action_history(entity_name, entity_id)` (issue 34) |
| — | `NOT NULL` constraints aligned with the entity mappings (issue 33) |
| `LIKE '%term%'` search | `tsvector` column + GIN index + `websearch_to_tsquery` (issue 21) |

### 3.4 Dependency changes

```xml
<!-- remove -->  com.mysql:mysql-connector-j
<!-- remove -->  org.flywaydb:flyway-mysql
<!-- add    -->  org.postgresql:postgresql
<!-- add    -->  org.flywaydb:flyway-database-postgresql
```

Testcontainers switches from `MySQLContainer` to `PostgreSQLContainer`.

### 3.5 Data migration for the existing installation

1. Stand up PostgreSQL and run `V2.0` to create the empty schema.
2. Copy data with `pgloader` (which handles the MySQL type mapping) into a staging schema.
3. Run a one-off transform: `user` → `app_user`, `state`/`enabled` integers → enum strings,
   `file_path` → `storage_key` (derived from `file_info_id` / `version` / `id`), and compute
   `checksum_sha256` by reading each file once.
4. Verify: row counts per table, and a checksum-vs-disk audit over every `file_details`.
5. Cut over. Keep the MySQL instance read-only for a rollback window.

The checksum backfill in step 3 is also what makes the Phase 4 migration verifiable — do not skip it.

**Done when:** the suite runs against PostgreSQL via Testcontainers, full-text search replaces the
`LIKE` queries, and the production data has been copied and audited.

---

## Phase 4 — S3 as a storage backend

**Target: AWS SDK for Java v2** (`software.amazon.awssdk:bom` 2.54.x), which works against S3, MinIO,
Ceph RGW, Backblaze B2 and Cloudflare R2. `io.awspring.cloud:spring-cloud-aws-dependencies` 4.1.1 is
an alternative if the Spring-native configuration story is worth the extra abstraction; the plain SDK
is the lighter choice here.

Phase 2 already introduced `BlobStore` and its contract test, so this phase adds an implementation
rather than restructuring anything.

### 4.1 `S3BlobStore`

Implement the port against `S3Client` / `S3TransferManager`:

* `put` — `S3TransferManager.upload` with multipart for large objects, computing SHA-256 on the way
  through so it never buffers the whole file (issue 44). Set `ContentType` from the *sniffed* type.
* `open` — `getObject` returning the response stream.
* `presignedGet` — `S3Presigner.presignGetObject` with `ResponseContentDisposition` set. This is the
  big win: downloads stop flowing through the application entirely.
* `copy` — `copyObject`, server-side, used by the two-phase write's staging promotion.
* `delete`, `exists` — direct mappings.

Configuration must support `path-style-access: true`; MinIO and most self-hosted S3 stores require it.

### 4.2 Backend selection

`filemanagement.storage.backend` picks the implementation via `@ConditionalOnProperty`. Both beans
stay on the classpath, because reads must keep working against whichever backend a given row was
written to — that is what `file_details.storage_backend` records.

A `TieredBlobStore` composite reads from the backend named on the row and writes to the currently
configured one. That is what makes a gradual migration possible with zero downtime.

### 4.3 Migrating existing bytes

1. Deploy with `backend: s3`. New uploads go to S3; existing rows still read from disk.
2. Run a background job that, per `file_details` row with `storage_backend = FILESYSTEM`:
   reads the file, verifies `checksum_sha256` (backfilled in Phase 3), uploads to S3, re-verifies the
   stored object's checksum, then updates `storage_backend = S3` in a transaction.
3. Once no rows remain on `FILESYSTEM`, retire the volume.

Step 2's checksum verification on both sides is the whole reason Phase 3 backfills checksums.

### 4.4 Operational additions

* `BlobStore` health indicator — a `headBucket` call feeding `/actuator/health/readiness`.
* Micrometer timers tagged `backend=s3|filesystem` on every port operation.
* Server-side encryption (SSE-S3 or SSE-KMS) and a bucket lifecycle rule expiring `staging/`
  after 24 hours, which doubles as the orphan sweeper from
  [target-architecture.md](target-architecture.md#two-phase-write).
* MinIO in `compose.yaml`, and a MinIO container as the second subject of the storage contract test.

**Done when:** the contract test passes against both backends, uploads land in S3, downloads are
served by pre-signed URL, and every legacy row has been migrated and checksum-verified.

---

## Phase 5 — From taxonomy to a real folder tree

The read-only tree at `/files/tree` is the first step of this phase and is already in place.

### What the storage model actually is today

Verified against the code and the disk, not the documentation:

| Level | Directory on disk? | Where |
|---|---|---|
| General tag | **no** — it labels a category | — |
| Category | yes | `FileCategoryService.createCategory` → `createDirectory(name, false)` |
| Sub-category | yes | `FileSubCategoryService.createFileSubCategory` → `createDirectory(cat/sub, true)` |
| **Main tag** | **no** — metadata only | `MainTagFileService` has no storage dependency at all |
| File (`FileInfo`) | yes | created lazily by `FileStorageFileSystemService.save` |
| Version | yes | `v1`, `v2`, … |

So the path is `{base}/{Category}/{SubCategory}/{FileName}/v{n}/{file}.{ext}` — a main tag never
appears in it, even though every file must have one.

### 5.1 The tree view — done

`FileTreeService` + `FileTreeResource` + `/files/tree`. It presents category, sub-category **and
main tag** as folders, so the view already speaks the target model. Levels load on demand, which
matters because every `@ManyToOne` here is `EAGER`.

Read-only on purpose: no move, rename or delete. `FILE_TREE_PAGE` and `REST_GET_FILE_TREE` gate it.

### 5.2 Drag and drop

> **Unblocked differently than planned.** The premise below was that a move needs a storage port
> that can express one, because moving a node means moving bytes.
> [Phase 7.1](#71-first-decouple-the-storage-key-from-the-structure--done) removes the premise instead: once
> a stored object is addressed by a key rather than by its place in the tree, a move touches no bytes
> at all and becomes a `parent_id` change plus the one-statement path rewrite in
> [6.1](#61-choosing-how-to-store-the-tree). Do 7.1 first; then only steps 2 and 3 below remain, and
> step 1 is no longer on the critical path.

Needs a *move* operation, which the current storage port cannot express: `FileStorageService` is
path-shaped (`address`, `version`, `extension`) and has no `move`.

Order:
1. ~~`BlobStore.move(from, to)` on the port and both adapters.~~ Replaced by 7.1.
2. `PUT /resource/files/tree/move` — validates the target accepts the node type, moves the row in one
   transaction, writes an `ActionHistory` row.
3. Alpine drag handlers on the existing flat row list — it is already an ordered list with a
   `depth` on every row, which is what a drop target needs.

### 5.3 Collapsing the taxonomy into folders

Split across two phases in the end. [Phase 6](#phase-6--two-tier-authorization-endpoint-permissions-and-folder-access)
built the `folder` table, because the same table serves both the tree and folder-level access
control and two designs for one table would have been worse.
[Phase 7](#phase-7--nested-folders-replace-the-taxonomy) is the collapse itself: folders become
authoritative, and the four levels become tags.

### 5.4 Known gap in the current view

A node whose `childCount` is zero is rendered with a disabled twisty and no explanation: clicking it
does nothing, so a branch that is simply empty is indistinguishable from a tree that is broken. On
data with empty categories or tags this reads as "the tree does not work"
([issue 50](issues.md#50-an-empty-branch-in-the-file-tree-looks-like-a-broken-one--s3)).

Fix with the rest of the tree work: keep such a node expandable, and on open render an inline
"empty folder" row rather than silently doing nothing.

## Phase 6 — Two-tier authorization: endpoint permissions and folder access

> **6.1–6.6 delivered, enforcement off by default.** `folder` exists as a mirror of the taxonomy
> (`V1.4`), `role_folder` and `user_folder` carry the grants (`V1.5`), the role edit page grants
> folders to a role, and the tree, the file list, the file page and both download endpoints all ask
> both questions. Uploading does not yet (issue 76).
> `filemanagement.folder-access.enabled` is `false` in the shipped configuration: switching it on
> before any grant exists would empty the tree for every non-administrator, so the order is grant
> first, check, then enable.
>
> What the plan below said, and what was actually built where they differ:
>
> * **`folder.created_by` is nullable.** The sketch had it `NOT NULL`, but the backfill has no
>   principal — and on a fresh database the `user` table is still empty when Flyway runs. NULL means
>   "created by a migration".
> * **The mirror is self-healing.** 6.4 accepted that a taxonomy row written straight through a
>   repository has no folder, and left the reconciliation test to catch it. That is still true, but
>   `FolderMirrorService` now also creates any missing ancestor on the spot rather than failing —
>   most existing service tests build fixtures exactly that way, and a mirror that can fail the thing
>   it mirrors is worse than one that converges.
> * **No `Home/{username}` folders yet** (6.7). They could hold nothing — a file hangs off a main
>   tag, not a folder, until 6.8 — and six of the eight usernames in this installation contain a dot,
>   which `folder.name` is documented not to allow. `USER_HOME` and `owner_user_id` stay for 6.8.
>   See [issue 74](issues.md#74-usernames-are-not-directory-safe-but-are-destined-to-become-folder-names--s3).
> * **Granting is in the UI**, as a "دسترسی پوشه‌ها" section on the role edit page rather than a
>   screen of its own: a role already carries what its holder may do, and where belongs beside it.
>   The whole tree is rendered as one indented checkbox list — it is a couple of hundred rows, and
>   the rows come back ordered by `path`, so an ancestor is always above its children. A folder
>   reached through an ancestor is marked "از پوشهٔ بالاتر" rather than offered as another box to
>   tick, because a grant already covers everything beneath it.
> * **No `is_system` flag on roles** (6.7). Still open, as is granting to an individual user —
>   `user_folder` exists and is enforced, but only roles can be granted from the UI.
>
> Verified against the real database: the backfill produced 1 root, 4/4 categories, 34/34
> sub-categories and 148/148 main tags, with no path, depth or parentage disagreeing.

The target is two independent questions, asked in this order:

1. **May this user perform this operation at all?** — the existing `PermissionEnum` per endpoint.
2. **May this user touch *this* folder?** — new, and inherited down the tree.

Both must pass. They are separate because "may upload a file" and "may upload *here*" are
different facts, and today only the first exists: anyone holding `DOWNLOAD_FILE` can download
every file in the system ([issue 14](issues.md#14-no-resource-level-authorization--s1)).

### 6.0 Why this needs a folder table first

Folder access cannot be granted against category / sub-category / main tag: they are three separate
tables with a fixed depth, and a main tag is not even a directory. A grant has to name *one* kind of
thing and inherit down an arbitrary depth.

**Do not wait for the byte migration.** Build `folder` as a mirror of the existing taxonomy, keep
the taxonomy authoritative, and put the ACL on the mirror. Making `folder` authoritative and moving
bytes is a separate, later step that carries all the risk.

### 6.1 Choosing how to store the tree

The access patterns decide this, so they come first:

| Pattern | Frequency | Where |
|---|---|---|
| Children of one node | every folder opened | tree view |
| **Every descendant of a set of nodes** | **every list, every request** | folder ACL filtering |
| Ancestors of one node (breadcrumb) | per page | detail pages |
| Move a subtree | rare, interactive | drag-and-drop |
| Depth of a node | rendering | tree view |

The second row dominates: once folder access exists, *every* file list, search and tree call has to
be restricted to the descendants of the user's granted folders. That query has to be indexable.

| Model | Descendants | Move | Cost |
|---|---|---|---|
| Adjacency list (`parent_id`) alone | recursive CTE per query | one row update | descendant filtering is a CTE inside every list query |
| **Materialised path** | `path LIKE '/1/7/%'` — one index range scan | update the subtree's paths | a denormalised column to keep correct |
| Closure table | plain join | delete + insert `subtree × depth` rows | a second table, and the most write complexity |
| Nested sets | `BETWEEN lft AND rgt` | renumbers a large part of the table | wrong choice as soon as drag-and-drop exists |

**Decision: adjacency list as the source of truth, materialised path as a derived index.**

`parent_id` carries the foreign key and the structural truth - it cannot drift, and it is what
renders one level. `path` exists purely so descendant filtering is a prefix scan instead of a CTE
in every query. Nested sets are ruled out by drag-and-drop; a closure table is defensible but buys
little here, because the tree is shallow and the extra table has to be maintained anyway.

Details that matter and are easy to get wrong:

* **Build the path from ids, not names.** `/1/7/22/` and not `/Home/MainCat/SubCat/`. A rename then
  costs nothing, and only a move rewrites paths.
* **Leading *and* trailing slash.** `/1/7/%` must not match `/1/70/…`; with the trailing slash the
  next character after `/1/7` is `/`, so it cannot.
* **MySQL index limit.** `VARCHAR(1000)` in `utf8mb4` is 4000 bytes and exceeds the 3072-byte index
  limit. The path only ever holds digits and slashes, so declare it
  `VARCHAR(1000) CHARACTER SET ascii` and the whole column indexes cleanly.
* **PostgreSQL collation.** A prefix `LIKE` only uses a B-tree index under a non-C collation if the
  index is declared with `varchar_pattern_ops`. Miss this in Phase 3 and every ACL query silently
  becomes a sequential scan. (`ltree` is the nicer native option but adds an extension dependency;
  decide in Phase 3, not now.)
* **A move is one statement**, and it must run in the same transaction as the `parent_id` change:

```sql
UPDATE folder
   SET path = :newParentPath || id || '/'            -- for the moved node
 WHERE id = :id;
UPDATE folder                                        -- and its subtree
   SET path = :newPrefix || SUBSTRING(path, LENGTH(:oldPrefix) + 1)
 WHERE path LIKE :oldPrefix || '%';
```

* `path` is derived, so add a reconciliation query that recomputes it from `parent_id` and reports
  rows that disagree. Run it in a test and expose it to an admin endpoint.

### 6.2 The table

```sql
CREATE TABLE folder (
    id             INT NOT NULL PRIMARY KEY AUTO_INCREMENT,
    parent_id      INT NULL,
    name           VARCHAR(100) NOT NULL,   -- directory-safe: no '.', ' ' or '/'
    display_name   VARCHAR(200) NOT NULL,   -- the Persian label
    path           VARCHAR(1000) CHARACTER SET ascii NOT NULL,  -- '/1/7/22/'
    depth          INT NOT NULL,            -- derived, kept for cheap ordering
    kind           VARCHAR(30) NOT NULL,    -- ROOT | CATEGORY | SUB_CATEGORY | TAG | USER_HOME
    owner_user_id  INT NULL,                -- set on a personal home folder
    general_tag_id INT NULL,                -- a general tag stays a label, now on a folder

    -- only while the taxonomy is still authoritative; dropped in 6.6
    source_type    VARCHAR(20) NULL,        -- CATEGORY | SUB_CATEGORY | MAIN_TAG
    source_id      INT NULL,

    enabled INT NOT NULL, state INT NOT NULL,
    created_at DATETIME NOT NULL, created_by INT NOT NULL,
    updated_at DATETIME NULL,    updated_by INT NULL,

    CONSTRAINT fk_folder_parent FOREIGN KEY (parent_id) REFERENCES folder (id),
    CONSTRAINT uq_folder_sibling_name UNIQUE (parent_id, name),
    CONSTRAINT uq_folder_source UNIQUE (source_type, source_id)
);
CREATE INDEX idx_folder_path   ON folder (path);
CREATE INDEX idx_folder_parent ON folder (parent_id);
```

`file_info` gains a nullable `folder_id` alongside its existing `file_sub_category_id` and
`main_tag_file_id`, written in parallel and only made authoritative in 6.6.

`uq_folder_source` is what makes the backfill idempotent and the mirror verifiable: exactly one
folder row per legacy entity, so a reconciliation query is a full outer join, not a guess.

### 6.3 Two blockers to clear before the backfill — **already cleared**

> Both were closed by `V1.3` before this phase started: it added
> `uq_main_tag_file_name_per_sub_category` and `uq_file_sub_category_name_per_category`. The
> pre-flight below was run anyway, against the real data, and found nothing to resolve — no duplicate
> sibling names at any level, and no name containing `.`, ` ` or `/`, including main tag names, which
> nothing had ever validated.

Both were found in the current schema and would have made `uq_folder_sibling_name` fail:

1. **`main_tag_file.tag_name` had `@Column(unique = true)` on the entity but no unique constraint in
   the database.** `V1.0__Initial_Setup.sql` declared none, and `ddl-auto=validate` does not check
   unique constraints - so duplicate tag names could already exist. Same class as
   [issue 33](issues.md#33-schema-and-entity-mappings-disagree--s2).
2. **`file_sub_category.sub_category_name` uniqueness lived only in application code**
   (`FileSubCategoryService.checkDuplicate`), per category. Any row inserted another way bypassed it.

So the migration starts with a **pre-flight report**, not with `CREATE TABLE`:

```sql
SELECT tag_name, COUNT(*) FROM main_tag_file GROUP BY tag_name HAVING COUNT(*) > 1;
SELECT file_category_id, sub_category_name, COUNT(*) FROM file_sub_category
 GROUP BY file_category_id, sub_category_name HAVING COUNT(*) > 1;
```

If either returns rows, they are resolved by hand first. Then add the missing unique constraints to
the legacy tables in the same migration, so the problem cannot come back while both structures are
live.

### 6.4 Running it in parallel

The taxonomy stays authoritative. `folder` is a mirror, written in the same transaction as its
source, and read only by the new tree and ACL code.

| | Writes `folder` | Reads `folder` |
|---|---|---|
| `FileCategoryService` create / update / delete | yes | no |
| `FileSubCategoryService` create / update / delete | yes | no |
| `MainTagFileService` create / update / delete | yes | no |
| `UserService.createUser` | yes - creates `Home/{username}` | no |
| `FileTreeService` | no | **yes** |
| Folder ACL | no | **yes** |
| Everything else (upload, download, lists, search) | no | no |

Rules that keep this honest:

* **One writer.** A single `FolderMirrorService` owns every write to `folder`; the three taxonomy
  services call it. Nothing else touches the table, so there is one place to audit.
* **Same transaction.** A mirror write that can fail independently is a mirror that drifts.
* **Reconciliation is a test, not a hope.** A test asserts that the mirror and the taxonomy describe
  the same tree - same count, same parentage, same names, and every `path` recomputable from
  `parent_id`. It runs on every build, against the Testcontainers database.
* **Rollback is `DROP TABLE folder`.** Nothing operational depends on it until 6.6, which is the
  whole point of building it this way.

Known risk: anything that writes the taxonomy without going through those services bypasses the
mirror - a raw `JdbcClient` DAO, a Flyway data migration, or someone using a repository directly.
The reconciliation test is what catches it; keep it running.

### 6.5 Granting access

```sql
CREATE TABLE role_folder (role_id INT, folder_id INT, PRIMARY KEY (role_id, folder_id));
CREATE TABLE user_folder (user_id INT, folder_id INT, PRIMARY KEY (user_id, folder_id));
```

A role therefore carries **both** a set of permissions (`permission_role`, exists) and a set of
folders (`role_folder`, new). A user gets folders from their roles plus any direct grant.

A grant on a folder covers everything beneath it. There is no deny rule and no per-folder verb:
one grant, inherited. Adding "read vs write per folder" later means a column on these tables, not
a new model - but do not add it before something actually needs it.

### 6.6 Enforcing it

Resolve the user's granted path prefixes **once per request** and cache them on the authentication:

```java
record FolderAccess(boolean unrestricted, List<String> grantedPaths) { }
```

* `ADMIN` authority sets `unrestricted = true` and **the folder check is skipped entirely** - no
  `role_folder` rows are needed for the admin role, and no query is issued.
* Otherwise `grantedPaths` is the set of `folder.path` values from `user_folder` and `role_folder`,
  reduced to remove any path that is already covered by a shorter one.

Two enforcement shapes, and the second is the one that is usually forgotten:

| Case | How |
|---|---|
| Single item ("open folder 22", "download file 9") | `AccessPolicy.requireAccess(principal, folderId)` **inside the domain service**, never in the controller |
| Lists (tree children, file list, search) | push the prefixes into the query: `AND (f.path LIKE :p0 OR f.path LIKE :p1 ...)`. Never fetch then filter in Java - the paging counts come out wrong |

> **As built:** the list filter is pushed into the query, but as `mt.id IN (:mainTagIds)` rather than
> a run-time list of `LIKE` predicates. The granted prefixes are turned into the set of readable main
> tags first — one indexed prefix scan per grant, and grants are few and reduced so none is a prefix
> of another — which keeps the list query a fixed, readable piece of JPQL instead of one assembled as
> a string. An empty set short-circuits in Java, because `IN ()` is not valid SQL.
>
> The single-item check comes in two strengths, which this table did not anticipate:
> `requireAccess` for anything that returns content, and `requireVisible` for opening a folder that
> is merely on the way down to a grant.

`FileTreeService` is the first consumer.

> **Changed in the build:** `getRoots()` does *not* return the granted folders. It returns the
> categories the person may read **or walk through**, so a grant in the middle of the tree can be
> navigated down to from the top, exactly as an unrestricted user would reach it. Returning the
> grants themselves as roots was the first design and it was wrong in practice: it made a granted
> sub-folder appear at the top level, which is not where it lives, and it gave two different people
> two differently shaped trees over the same data.

### 6.7 `Home`, per-user folders, and the two system roles

* One `ROOT` folder named `Home`, created by a migration, `parent_id = NULL`.
* `UserService.createUser` also creates `Home/{username}` with `kind = USER_HOME`,
  `owner_user_id = <the new user>`, and inserts a `user_folder` grant - all in the **same
  transaction** as the user, so a half-provisioned user cannot exist.
* Two system roles, marked `is_system` so the UI refuses to delete them:
  * `ADMIN` - every permission, and `unrestricted` folder access by definition.
  * `USER` - the permissions needed to upload into and read one's own folder
    (`CREATE_FILE_PAGE`, `SAVE_NEW_FILE`, `FILE_TREE_PAGE`, `DOWNLOAD_FILE`, `ACCESS_HOME`,
    `REST_GET_FILE_TREE`), and **no** `role_folder` rows - a plain user reaches exactly the
    folders granted to them personally.

Existing users need a backfill migration that creates the missing home folders and grants.

### 6.8 Making `folder` authoritative (last, and the only risky step)

> **Confirmed as the destination.** The taxonomy — general tag, category, sub-category, main tag —
> goes away, and the folder tree becomes the only structure. Everything built before then should be
> shaped so that this is a data migration rather than a rewrite.
>
> **Done ahead of the rest: the tree addresses nodes by folder id.**
> `/resource/files/tree/children?type=CATEGORY&id=26` now names `folder` row 26, not a
> `file_category` row, and the same is true of every id the page holds and every id a search hit
> reports. The taxonomy id never leaves `FileTreeService`.
>
> Two things fall out of it. The access check stopped being a translation — the id in the request
> *is* the thing being authorised, so there is no longer a step where a taxonomy row and a folder
> could disagree. And 6.8 shrinks to dropping the three tables and the two `source_*` columns,
> rather than changing the endpoint, the DTO, the Alpine component and every access check together.
>
> **The one remaining exception is a file**, which has no folder of its own until 6.8 and is still
> addressed by its `file_info` id, authorised through the tag it is filed under. That is the last
> special case in `getChildren`, and it disappears when files become folders.

Drop `file_sub_category_id` / `main_tag_file_id` from `file_info`, retire the three taxonomy tables
and the `source_type` / `source_id` columns.

> **Superseded by [Phase 7](#phase-7--nested-folders-replace-the-taxonomy)**, which does this and
> more: the four levels do not merely stop being structure, they become tags. The sketch here also
> assumed the step must move bytes, "because a main tag has no directory". Phase 7 removes that
> assumption instead — see [7.1](#71-first-decouple-the-storage-key-from-the-structure--done).

---

## Phase 7 — Nested folders replace the taxonomy

The four levels — general tag, category, sub-category, main tag — stop being structure. A folder
tree of arbitrary depth becomes the only structure, and the four levels become **tags**: labels on a
file, not places to put it.

Two things that are one thing today come apart:

| | today | after |
|---|---|---|
| **Where a file is** | category → sub-category → main tag, exactly three levels | one folder, any depth |
| **What a file is about** | the same three levels | tags, many per file |

Half of this is already built. `folder` exists and mirrors the taxonomy, the tree already addresses
every node by folder id, and folder access control is already enforced against it (Phase 6). What
remains is to make `folder` authoritative, attach files to it, and delete the taxonomy.

### 7.0 The decision behind the plan

Should the initial folder tree have the same shape as today's taxonomy?

**Yes.** It already does — that is what the mirror is — and keeping it means the migration changes no
one's mental model on the day it ships. The four levels are turned into tags *as well*, which is
strictly redundant at first (a file's folder path already says its category and sub-category), and
that is accepted on purpose: it preserves every existing way of finding a file while the folder tree
takes over, and the redundant tags can be pruned later at no risk. Reshaping the tree and cutting the
taxonomy in the same change would leave nothing recognisable to compare against if something looks
wrong.

### 7.1 First: decouple the storage key from the structure — **done**

> Shipped as migration `V2.2`. `file_details.storage_key` holds the whole relative location of
> one stored object, backfilled from `relative_path`, and `FileStorageService` gained a
> key-shaped half — `saveByKey`, `loadByKey`, `deleteByKey` — that every read and write of a
> single file now goes through. **No byte moved.**
>
> The backfill is sound because nothing renames a category or a sub-category: both services
> update the *description* only, so the stored location and the location a read used to derive
> were the same string for every existing row. The migration carries the verification query to
> confirm that against real data before deploying, and `StorageKeyTest` asserts it on generated
> data.
>
> The proof of what this buys is `StorageKeyTest.aRenameDoesNotOrphanTheBytes`: a category is
> renamed in the database with no directory touched, and the file still downloads. Before this
> step the read would have looked under the new name and found nothing.
>
> Two things came with it. The new methods resolve against an absolute, normalised root and
> refuse a key that would leave it, so the containment gap the path-shaped methods have is not
> inherited. And `FileStorageFileSystemService`'s own class comment was wrong: it said a
> revision was stored as `<name>-v<version>.<extension>` and that "a version is a file name
> rather than a directory". It never was — `save` has always built `level2Dir + "/v" + version`.

**This is the prerequisite, and the single most important step.** Today the structure *is* the path
on disk:

```
{base-dir}/{Category}/{SubCategory}/{FileName}/v{n}/{file}.{ext}
```

With folders of arbitrary depth and drag-and-drop, every folder move would then mean moving bytes on
disk *and* rewriting the denormalised `file_path` and `relative_path` on two tables. That is exactly
how files get lost.

So: add `file_details.storage_key`, backfill it to each row's current relative path, and let the
filesystem adapter map key → path. **No bytes move.** From that point a folder move is a metadata
change, and three other things fall out of it:

* it is the small, useful half of the `BlobStore` port that [Phase 2](#22-the-storage-port) wants
  anyway, so it is brought forward rather than duplicated;
* [Phase 5.2](#52-drag-and-drop) (drag-and-drop) stops being blocked on a storage port that can
  express a move;
* **a folder name no longer has to be directory-safe.** It stops being a directory name, so folders
  can be named in Persian — which `folder.name` cannot allow today, and which is also what stands in
  the way of [issue 74](issues.md#74-usernames-are-not-directory-safe-but-are-destined-to-become-folder-names--s3).

### 7.2 The steps

Each is independently shippable, and only the fourth cannot be undone.

| # | Step | Migration | Reverting it |
|---|---|---|---|
| 0 | `file_details.storage_key`, backfilled from `relative_path`; adapter maps key → path — **done** | `V2.2` | an unused column |
| 1 | `file_info.folder_id`, nullable, backfilled to the folder mirroring the file's main tag; written alongside the old foreign keys — **done** | `V2.3` | an unused column |
| 2 | `tag_group`, `tag`, `file_tag`; every file gets a tag per level it sits under — **done** | `V2.4` | `DROP TABLE` |
| 3 | **Reads move to the folder**: tree, upload, file list, search — **done**, one reader per commit: 1 API v2, 2 explorer, 3 folder access on download / file page / list / new version, 4 tree, 5 upload by `folderId` alongside the triple | — | revert the code |
| 4 | `folder_id` `NOT NULL`; drop the old foreign keys, the four taxonomy tables, and `folder.source_type` / `source_id` | `V2.7` (`V2.5` went to the content-type fix, `V2.6` to the upload policy) | ⚠️ **none** |
| 5 | Folder operations: create, rename, move, delete — and drag-and-drop. **Started**: uploading into a folder from the explorer (5a) | `V2.x` | — |

> **Step 1 done.** `FileInfo.folder` is set from `FolderMirrorService.folderOf(mainTag)` on every
> upload — get-or-create, so an upload into a tag that was never mirrored heals the mirror rather
> than storing a null. The backfill's own `UPDATE` is what the test runs (cut out of the migration
> file and executed against rows deliberately un-linked), and
> `FileInfoRepository.findRowsWhoseFolderDisagreesWithTheMirror()` is the reconciliation, asked
> on every build. The foreign key is `RESTRICT`, so a folder with files in it cannot be deleted by
> any route. Nothing reads the column: the whole suite, `RestContractTest` included, passed
> without another line changing, which is the test of "no behaviour change". `FileFolderLinkTest`.
>
> **Step 2 done.** `TagMirrorService` is the one writer, shaped like `FolderMirrorService`. A
> file's tags are a function of its taxonomy — category, sub-category and main tag, in the group
> of its general tag — re-derived on upload and on a main-tag rename, the one name that changes.
> The migration's three backfill statements skip what exists, so they double as a repair script,
> and `FileTagTest` runs them (cut out of the file) against files it has stripped of their tags,
> group and all, then runs them again to prove the second pass is a no-op. The v1 API round trip
> is asserted unchanged in the same test. Two things were decided while doing it, both recorded
> in §7.3 below.
>
> **Step 3, reader 1 done — the v2 object store.** `ObjectStoreService` lists a bucket from
> `file_info.folder_id`: the subtree's folders, the files in the readable ones, their versions —
> three queries, and the key is built from the subtree already in hand instead of translating
> each file's tag back to a folder and fetching its ancestry by id. A key resolves to its file by
> `findByFolderIdAndFileName` rather than by sub-category-and-tag. The write path (`put`) still
> creates files through the taxonomy triple; that is reader 5. Two rules set here for every
> reader that follows: a file without a `folder_id` is invisible to the folder read and logged as
> such (`countByFolderIsNull`), never a 500; and equivalence is asserted against the taxonomy
> rows themselves, not against the previous implementation — `ObjectStoreFolderReadTest` builds
> the expected key of every stored version from the category, sub-category, tag and file rows and
> compares. The order for the rest: 2 explorer, 3 folder access in `FileService` (download, list,
> search), 4 tree, 5 upload with `folderId` alongside the triple.
>
> **Step 3, reader 2 done — the explorer.** `FolderContentService` lists a folder's files by
> `findByFolderId`, counts the files of every child by folder id in one grouped query (no more
> splitting children by kind), and searches within `readableFolderIds(access)` ∩ the scope's
> subtree — folder ids, where it used to intersect main-tag ids and then translate each hit's tag
> back to a folder. A hit's folder now arrives with the row (`JOIN FETCH f.folder`), one query
> fewer per page. The `kind == TAG` gate on "can this folder hold files" stays until step 5, since
> uploading still files under a main tag. The class comment's promise — "when `file_info` gets a
> `folder_id`, the two references to `sourceId` go and nothing else changes" — held: the six
> existing tests changed only their fixture, which had inserted a file straight through the
> repository and therefore, like a pre-`V2.3` row, without a folder. `FolderContentFolderReadTest`
> is the oracle test (listing, counts, search, a single-folder grant, the orphan case).
>
> **Step 3, reader 3 done — folder access in `FileService`.** The security-bearing one. A
> download, the file page and a new version or format now ask `requireReadAccess(access, file)`
> / `requireWriteAccess(access, file)` on the file's own folder; the list page filters on
> `readableFolderIds` inside the query (`searchWithinFolders`). The one thing a folder read can
> meet that a tag read could not — a file with no `folder_id` — **fails closed**: refused to any
> restricted principal and logged, still reachable by an administrator, never a 500. This is the
> same rule `holds()` already applied to a taxonomy row with no mirror, for the same reason: it is
> the one place where guessing wrong shows somebody a document they were not granted.
> `FileServiceFolderAccessTest` writes every combination out rather than sampling — no grant,
> administrator, READ and WRITE on a tag, READ and WRITE inherited from the sub-category, READ on
> the parent with WRITE on one child, a grant through a role, and the folderless file — against a
> taxonomy oracle. The existing `FolderAccessEnforcementTest` needed only its fixture linked to a
> folder, as the explorer's had. Uploading a *new* file (`createNewFile`) still checks the tag the
> form named: that is reader 5, where the form learns `folderId`. The tree is reader 4.
>
> **Step 3, reader 4 done — the tree.** `FileTreeService` renders a tag node's children from
> `findByFolderIdOrderByFileNameAsc(folderId)` and counts them with `countByFolderId`; opening a
> file asks `requireReadAccess(access, file)` on the file's own folder; a search hit is placed by
> the file's folder and its two ancestors, all fetched with the search (`searchForTree` now
> fetches `folder → parent → parent` instead of the taxonomy chain, and the labels are the folders'
> display names, which the mirror keeps equal to the taxonomy's). The folder *levels* of the tree —
> categories, sub-categories, tags as nodes — still come from the taxonomy tables with their
> folder ids looked up; that is not a file read and goes with the taxonomy in step 4. The
> `FileTreeSearchTest` fixture needed the same one-line link as the others. `FileTreeFolderReadTest`
> is the oracle test. **Every file read in the application now goes through `folder_id`**; the
> only tag-based *write* left is filing a new document, reader 5.
>
> **Step 3, reader 5 done — uploading by `folderId`.** `FileInfoDTO` gained `folderId`, and the
> taxonomy triple lost its `@NotNull`: `FileService.createNewFile` resolves the target folder
> from whichever the request sent — the folder directly, or the main tag's mirror — then checks
> write access on that folder, then holds whatever else the request said about the place to
> agreeing with it (a `folderId` and a `mainTagFileId` that name different places are a 400, as is
> a folder that is not a tag folder, as is neither). The uniqueness scope is the sub-category the
> *resolved* folder sits in, not a request field — the test caught the first version reading the
> request. Both `POST /api/v1/files` and the web form accept either addressing through the same
> binding; `FileUploadAddressingTest` writes out every combination through the real endpoint,
> including the 403-before-400 ordering. This is the compatibility window §7.4 item 2 asked for:
> the triple stays until step 4, so no integration changes today, and any that wants to can send
> `folderId` now. The upload form itself still shows the three selects; a folder picker belongs
> with the folder operations of step 5.
>
> **Step 3 is done.** Nothing in the application reads a file's place from the taxonomy any more;
> `file_info.main_tag_file_id` and `file_sub_category_id` are still *written*, for the taxonomy
> pages and the uniqueness rule, until step 4 removes them.
>
> **Step 5a done — uploading into a folder from the explorer.** The first thing a person does
> with a folder that is not just looking at it. `FolderContentDTO.writable` says whether the
> folder on screen can be filed into (a tag folder inside a `WRITE` grant); the explorer shows
> "upload here" on it, which opens `/files/create?folderId=`. The form then fixes the target -
> `FileService.uploadTargetOf` resolves the folder to its labels and refuses the same way the
> upload would - and posts `folderId` alone, which reader 5 already accepts. Ordered before rename
> and move on purpose: it needs nothing of the taxonomy to change, while a rename of a *folder*
> would have to flow back into the taxonomy it mirrors until step 4, and a move is something the
> taxonomy forbids outright (a tag cannot change sub-category). Both wait for step 4, after which
> the folder is the only structure and there is nothing to keep in step. `FileUploadAddressingTest`
> covers the page in both modes and `writable` for every grant shape.

Steps 0–2 only add data and change no behaviour, so they can ship early and sit in production while
step 3 is written. Step 3 is where the application actually changes. Step 4 should follow only after
step 3 has run for long enough to trust it, because it is the point of no return: after it,
`FolderMirrorService`, `FolderMirrorReconciliationTest`, the three taxonomy services and their pages
are all deleted.

### 7.3 The tag model

```sql
tag_group (id, name, title)                      -- organisational unit, document type, ...
tag       (id, group_id NULL, name, title, enabled)
file_tag  (file_info_id, tag_id, PRIMARY KEY (file_info_id, tag_id))
```

`tag_group` goes in from the start even if nothing uses it at first: adding it later means migrating
every tag row, and the four levels being collapsed are visibly of different kinds (a general tag is
not the same sort of label as a main tag). `general_tag` becomes a `tag_group`.

A tag name is unique **within its group**, which incidentally closes
[issue 73](issues.md#73-main-tags-under-ims_document_system-reuse-the-exact-names-of-unrelated-sibling-sub-categories--s2):
there can no longer be two different "HSED" in two different branches of one general tag, because a
tag is not a place.

> **As built (step 2), two refinements to the above.** First, uniqueness is per group rather than
> system-wide: with one group per general tag, `IMS / HSED` and some other general tag's `HSED`
> are labels in two different organisational scopes, and merging across them would fold two
> unrelated things into one on the strength of a spelling. `UNIQUE (group_id, name)` is what the
> table says. Second, when several taxonomy rows merge into one tag the title comes from the
> highest level that carries the name (category, then sub-category, then main tag, then the lowest
> id) — deterministic, so the migration's pre-flight query shows exactly what it will write — and
> is not followed afterwards. `V2.4` documents the query that lists every merge before deploying.

### 7.4 What has to be dealt with, and will hurt if it is not

1. **`file_path` and `relative_path` on `file_info` and `file_details`** become lies the first time a
   folder moves. They are dropped in step 4 or derived from the folder — never left to drift
   ([issue 35](issues.md#35-paths-are-denormalised-into-three-places--s2)).
2. **`/api/v1/files` takes a category, a sub-category and a tag.** Step 3 breaks every machine
   integration. It needs a window where the endpoint accepts both the old triple and a `folderId`,
   and the old form is removed only once callers have moved. — **The window is open** (step 3,
   reader 5): both are accepted, the triple is unchanged, and it is removed in step 4 only.
3. **File-name uniqueness moves** from "per sub-category" (`uq_file_info_name_per_sub_category`) to
   "per folder". The existing data may not satisfy the new rule — it needs the same pre-flight query
   the `folder` backfill got, run before the constraint is added, not after. — **The pre-flight
   exists**: `FileInfoRepository.findFileNamesSharedWithinAFolder`, asked at every start by
   `FolderReadinessReport` along with the `V2.3` and `V2.4` checks, and given in SQL in
   `deployment.md` ("Readiness for Phase 7 step 4"). Whether any caller still sends the triple is
   answered by the same section: since 1.2.0 each such upload logs `v1-upload-by-triple` and
   carries a `Deprecation: true` header.
4. **Uploading still does not check folder access**
   ([issue 76](issues.md#76-a-folder-access-grant-does-not-gate-uploading-into-that-folder--s2)). It
   has to be closed before folders become the structure, or a user will file documents into a folder
   they cannot even open.
5. **Old `action_history` rows** reference `FileCategory`, `FileSubCategory` and `MainTagFile` ids
   that will no longer exist. Acceptable for a historical log, but it is a decision to take
   deliberately rather than discover. — **Decided: they stay as they are.** A log of what happened
   is not rewritten to what would have happened; the ids stop resolving and the rows keep their
   text. Recorded in `deployment.md` so nobody rediscovers it.
6. **Folder access grants are unaffected** — they name `folder.id` and always have.

### 7.5 What this unblocks

[Phase 8](#phase-8--ims-controlled-documents-forms-and-approval) — document lifecycle, forms and an
approval workflow — all attach to a folder. Building them against the taxonomy first would mean
writing them twice.

**Done when:** a file belongs to a folder and to tags; the four taxonomy tables are gone; a folder can
be created, renamed, moved and deleted without touching a byte on disk; and the file-name rule,
the API contract and upload authorisation have all been moved across rather than left behind.

---

## Phase 8 — IMS: controlled documents, forms and approval

A form is filled in, it goes through an approval workflow, and what comes out the other side is a
controlled document — an آیین‌نامه, a procedure, a work instruction. A form builder defines those
forms rather than each one being a hand-written page.

### 8.0 The thing to understand before designing anything

**This application already manages controlled documents.** The file names are not names, they are
document codes:

```
PR-CLS-LR-001    procedure
WI-HSE-SA-013    work instruction
BL-HRM-JC-001    by-law / آیین‌نامه
```

So an آیین‌نامه is not a new kind of thing. It is a document with a **lifecycle** — draft, under
review, approved, effective, superseded — living in the same tree, with the same versioning, the
same folder access control, the same search and the same audit trail as everything else here.

Building it as a parallel world would mean re-implementing all five. Everything below therefore
attaches to `folder` and to the existing file/version model rather than beside them.

### 8.1 A correction to the obvious framing

"A form for creating an آیین‌نامه" and "a form for editing an آیین‌نامه" should **not** be two form
definitions. One definition per *document type* is enough; what differs between creating and editing
is which workflow starts and whether the result is revision 1 or revision n+1. Two definitions means
two places to maintain the same fields, and by the sixth month they will have drifted and nobody
will know which is authoritative.

### 8.2 Four things that must stay separate

The common failure in this kind of system is collapsing these into one table:

| | What it is | The rule that matters |
|---|---|---|
| **Form definition** | the schema: fields, labels, validation | versioned, and published as a version |
| **Submission** | one filled-in form | pins the definition *version* it was filled with |
| **Workflow** | who decides what, and when | definition versioned; instance pins a version |
| **Controlled document** | the approved output | lives in a folder, has revisions |

### 8.3 The schema

```sql
form_definition             (id, key, document_type, title, status)
form_definition_version     (id, definition_id, version, schema_json, published_at, published_by)

form_submission             (id, definition_version_id, folder_id, data_json,
                             status, created_by, created_at)

workflow_definition_version (id, key, version, definition_json)
workflow_instance           (id, definition_version_id, subject_type, subject_id,
                             current_step, status, started_by, started_at, finished_at)
workflow_task               (id, instance_id, step_key, assignee_role_id, assignee_user_id,
                             status, decision, comment, acted_by, acted_at, due_at)
workflow_event              (id, instance_id, from_step, to_step, action, actor_id, at, comment)

controlled_document         (id, folder_id, code, document_type, title, status,
                             current_revision, effective_from, next_review_at)
document_revision           (id, document_id, revision, submission_id, workflow_instance_id,
                             approved_at, approved_by, file_details_id)
```

`document_revision.file_details_id` is nullable and points at the existing `file_details`: the
rendered PDF of an approved revision is stored and versioned exactly like every other file, rather
than through a second storage path.

### 8.4 JSON or relational?

* **Form schema → JSON.** One column, versioned, and no migration for every new field.
* **Submission data → JSON, plus a few extracted columns** for the handful of values that are
  actually filtered or reported on.
* **Never EAV.** An `(entity, attribute, value)` table is the classic trap here: no types, no usable
  indexes, and every report becomes a self-join per field.

With Hibernate 7 this is `@JdbcTypeCode(SqlTypes.JSON)`. It works on the MySQL 8 in use today and
gets better, not worse, on the `jsonb` that [Phase 3](#phase-3--postgresql-migration) brings.

### 8.5 Three decisions that are expensive to get wrong

1. **A submission pins the form definition version.** Pointing at the definition instead means that
   the day a form changes, every document ever filled with the old one renders wrongly. This is the
   same lesson `file_details.version` already encodes.
2. **A workflow instance pins the workflow definition version.** Otherwise editing a workflow breaks
   every case currently in flight, halfway through.
3. **Validation is server-side, from the same definition the form was rendered from**, and the set of
   field types is **closed**: text, number, Jalali date, select, multi-select, checkbox, attachment,
   user picker, folder picker, repeating group. Every new type costs a renderer, a validator and a
   reporting path — so each one is a decision, not a convenience.

### 8.6 Workflow: an engine, or a state machine of our own?

| Hand-rolled is right when | Flowable / Camunda is right when |
|---|---|
| linear, with a few branches | genuine parallel branches, sub-processes |
| approver chosen by role or by folder | delegation, time-based escalation, timers |
| — | business users draw the BPMN themselves |

**Hand-rolled, for this application.** A BPMN engine brings dozens of tables and its own identity
model, and "draft → review → approve → publish" uses almost none of it. Keeping the instance
separate from the versioned definition is what leaves the door open: that shape translates to BPMN
later if the need ever arrives.

### 8.7 Authorization: do not invent a third model

The two questions from [Phase 6](#phase-6--two-tier-authorization-endpoint-permissions-and-folder-access)
already answer most of this. A document lives in a folder, so "who may see this آیین‌نامه" is a
question that already has an answer.

What is genuinely new is **the right to decide** — being an approver at a step. That is not the right
to see, and it belongs on `workflow_task`, not on the folder grant.

### 8.8 What not to build

* A free-form drag-and-drop layout designer. Constrain it to a simple multi-column grid; arbitrary
  layout is unmaintainable to render, and worse to print.
* Gregorian dates in the interface. Jalali in the UI, `DATE`/UTC in the database.
* Digital signatures in the first version, unless there is a legal requirement — an approval record
  with an audit trail satisfies ISO 9001 §7.5 on its own.

### 8.9 Two gaps in the current model that this phase has to close

1. **A document code is only unique per sub-category** (`uq_file_info_name_per_sub_category`), and in
   [Phase 7](#phase-7--nested-folders-replace-the-taxonomy) that becomes per-folder. A controlled
   document code must be unique **system-wide** and issued centrally, not typed in as a file name.
2. **There is no "superseded".** `state` is `0` / `-1` and nothing more. Controlled documents need an
   effective date, a next-review date, and an obsolete marker so that a withdrawn revision can be
   told from a current one at a glance.

### 8.10 Order of work

1. `controlled_document` + `document_revision` over the folder tree, with no forms and no workflow —
   this alone gives the existing documents a code, a revision and a lifecycle, and is the largest
   gain for the least risk;
2. the form builder, for **one** document type;
3. a linear one-step approval workflow;
4. the remaining document types and multi-step workflows.

**Done when:** a form defined in the builder can be filled in, routed for approval, and — on approval
— produce a numbered revision of a controlled document that lands in the right folder, is visible to
exactly the people the folder grants allow, and leaves an audit trail from submission to approval.

---

## Phase 9 — API keys and an S3-style API v2

Machine access today is one shared account with HTTP Basic and four endpoint permissions. This phase
gives it credentials of its own, scopes those credentials to folders, and puts a second API in front
of them whose shape an integrator already knows.

### 9.0 First: two different things were both called "S3-compatible"

[Phase 4](#phase-4--s3-as-a-storage-backend) makes this application a **client** of S3 — bytes move
to MinIO or S3 and `BlobStore` reads them back. This phase makes it look like a **server** of S3 —
callers address objects the way they would address them in a bucket.

They point in opposite directions and both were called "S3-compatible". Phase 4's heading was
therefore changed to "S3 as a storage backend", and this one is deliberately named **S3-*style***,
not S3-compatible. §9.4 says exactly how far the resemblance goes.

### 9.1 The prerequisite: folder grants gain a verb — **done**

> Shipped as migration `V2.0`. `role_folder` and `user_folder` carry a `permission` column, both are
> mapped as entities (`RoleFolderGrant`, `UserFolderGrant`) because a join table with a third column
> can no longer be a `@ManyToMany`, `FolderAccess` keeps a readable and a writable path list, every
> upload path calls `requireWriteAccess`, and the role page sets each folder to none / read / write.
> Existing rows became `READ`, which is what they already meant, so nothing changed for anyone.
>
> Two things worth knowing for the steps that follow. **Replacing a role's grants merges rather than
> clears and re-adds** — the key is (role, folder), so removing and re-adding the same folder in one
> transaction puts two objects with one identifier in the persistence context and Hibernate refuses
> the flush. And **the write check runs before the taxonomy chain is validated**, so a refusal cannot
> be used to discover which category/sub-category/tag triples exist.

A grant is one boolean today: `FolderAccess.allows(path)` means "may read". There is no way to say
"may write here", and worse, **uploading is not checked against folder access at all**
([issue 76](issues.md#76-a-folder-access-grant-does-not-gate-uploading-into-that-folder--s2)). An
API key "with write access to a folder" is a sentence the system cannot currently enforce, so this
comes first.

`V1.5` anticipated it in writing:

> Adding "read here, write there" later is a column on these two tables rather than a new model —
> but it is not added before something actually needs it.

Something needs it.

| | |
|---|---|
| Schema | `permission` on `role_folder` and `user_folder` — `READ` or `WRITE`, where `WRITE` implies `READ` |
| Mapping | the two stop being pure join tables: `User.folders` and `Role.folders` become `Set<FolderGrant>`, not `Set<Folder>` |
| `FolderAccess` | two path sets instead of one; `allows` splits into `canRead` and `canWrite` |
| Enforcement | `requireWrite` at the top of the three upload paths, which closes issue 76 |
| UI | the role page's folder checkbox becomes three-state: none / read / write |

Nothing above is specific to API keys. It is the missing half of Phase 6, and the interface needs it
too.

### 9.2 API keys — **done**

> Shipped as migration `V2.1`. A key is `fmk_{keyId}_{secret}`, stored as the id plus a SHA-256
> hash, shown once at creation and never again; it carries a title, a description, an optional
> expiry and its own folder scopes, and is managed on its own screen under
> `/api-keys`. `ApiKeyAuthenticationFilter` sits in front of Basic on the `/api/**` chain and
> authenticates or steps aside — it never writes an error, so a bad key and a bad password
> produce the same 401 from the same entry point.
>
> **A key holds `API_KEY` and `API_HEALTH_TEST`, and nothing else.** The v1 file permissions
> belong to the shared machine account; a key inheriting them would reach every file in the
> system. The health probe is granted so that a newly issued key can be proved to work on the
> day it is issued. The endpoints a key is actually for arrive in §9.3.
>
> **The key id is hex, not base64.** Both halves were base64url at first, and base64url's
> alphabet contains the underscore the credential is split on — so roughly one key in four came
> out unusable, intermittently and only once generated. Anything that becomes part of the
> credential before the separator has to come from an alphabet that cannot contain it.

A section of its own, separate from users, because a key is not a person.

```sql
api_key        (id, key_id, secret_hash, title, description, expires_at NULL,
                enabled, revoked_at, last_used_at, created_at, created_by)
api_key_folder (api_key_id, folder_id, permission)
```

* **The key is `fmk_{key_id}_{secret}`.** The embedded `key_id` is what makes verification a single
  indexed row read rather than a scan of every hash in the table.
* **Only `secret_hash` is stored**, SHA-256, and the plaintext is shown once at creation and never
  again. A random secret has full entropy, so bcrypt buys nothing here and would put a deliberate
  delay on every API request. §9.4 explains why hashing is an option at all.
* **`expires_at` is nullable** — null means it does not expire. `revoked_at` is the separate,
  deliberate kill switch.
* **`last_used_at` is written at most once every few minutes**, not on every call: a read-only API
  that writes a row per request is not a read-only API.
* **`created_by` is not decoration.** `action_history.created_by` is a foreign key to `user`, so
  anything a key does has to be attributable to a person or the audit trail breaks. It is invisible
  in the interface; it exists so the log stays complete.
* **A key cannot be scoped to a folder its creator cannot see** (administrators excepted). Without
  that rule, the permission to create keys quietly becomes the permission to reach everything.

### 9.3 The v2 API — **done**

> `ObjectStoreApi` and `ObjectStoreService`. A bucket is a top-level folder, a key is everything
> below it flattened with `/`, and the version is a segment of the key. Listing supports `prefix`,
> `delimiter=/` with `commonPrefixes`, `max-keys` and `continuation-token`; writing appends a
> version and answers with the canonical key it landed on.
>
> **Folder access reaches it without a single new check.** `FolderAccessService.accessFor` reads
> the API key out of the security context and resolves the key's own scopes instead of the
> creator's — so every existing enforcement point became key-aware at once. Threading an
> `apiKeyId` parameter through instead would have meant an extra argument on every service that
> takes a `principalId`, and every one of those is a place to forget it, on a security check,
> silently.
>
> A listing gathers the subtree and sorts it in memory: the key space is derived from the folder
> tree rather than stored, so there is no index to page over. Three queries, and right at this
> size — 1358 files in the whole installation. `file_info.folder_id` and a real key column
> (Phase 7) are what would change that.
>
> **Two things a review found after it was first called done.** The key was sliced out of
> `getRequestURI()`, which is the *raw* request line — so a Persian file name arrived
> percent-encoded and was stored as `%DA%AF%D8%B2…`, on disk and in `file_info`. The key is now a
> `{*key}` capture, which Spring decodes; the lesson is that `getRequestURI()` is never the thing
> to parse a name out of. And because file names are unique per *sub-category* rather than per tag
> folder, a `PUT` under one tag could append a version to the file that owns that name under a
> sibling tag, then answer 404 for the key it had just written — the write access checked was the
> named folder's, the write landed elsewhere. That is now a 409 before anything is stored, as is a
> `PUT` to an explicit `v{n}` (it answered 417 through `BusinessException`; the table below had
> promised 409 all along). Both have tests in `ObjectStoreApiTest`. The OpenAPI document, too,
> had lost its most important entry: the download and the `?metadata` variant share a path and a
> method, a document holds one operation per pair, and springdoc had kept the wrong one. `HEAD` is
> now an explicit handler (answering from the row, not by reading the file and discarding it) and
> the `?metadata` twin is hidden from the document; `OpenApiDocumentTest` asserts the download is
> the `GET` that is described.

Bucket, key, and the five operations an integrator expects:

| Operation | Request |
|---|---|
| List | `GET /api/v2/{bucket}?prefix=&delimiter=/&max-keys=&continuation-token=` |
| Download | `GET /api/v2/{bucket}/{key}` — honours `Range` |
| Metadata | `HEAD /api/v2/{bucket}/{key}`, or `GET …?metadata` for the same as a JSON body |
| Upload | `PUT /api/v2/{bucket}/{key}` |
| Delete | `DELETE /api/v2/{bucket}/{key}` |

**A bucket is a top-level folder.** Not every folder: a bucket cannot contain a bucket, and in S3
there are no folders at all — `a/b/c.pdf` is one flat key and the "folders" are prefixes. Modelling
nested folders as nested buckets would confuse precisely the person this API is shaped for. One
level of buckets, everything below it a prefix.

`folder.name` is already the right thing to name a bucket with: it is documented directory-safe (no
`.`, no space, no `/`) and `uq_folder_sibling_name` makes it unique among siblings — and top-level
folders are all siblings, so it is globally unique. §9.7 covers the one way it deviates from AWS's
naming rules.

**The version is part of the key**, which is the decision taken for this phase:

```
IMS/IMS_Document_System/HSED/BL-HRM-JC-001/v3/BL-HRM-JC-001.pdf
```

The first segment is the bucket and everything after it is the key. That is exactly the layout on
disk today, and it answers "S3 has no versions" without implementing versioning: a version is a path
segment like any other.

**Writing appends a version; it does not overwrite.** In S3 a `PUT` to an existing key replaces it.
Here a file's versions are immutable, which for a document system is the point rather than a
limitation:

* `PUT .../{fileName}/{fileName}.pdf` — no version segment — creates the next version, and the
  response says which one in `x-fm-version`;
* `PUT` to an explicit `v{n}` answers `409`, and so does a file name already taken by a file under
  a sibling tag folder — names are unique per sub-category, and the alternative is a version
  landing somewhere the caller did not name.

`DELETE` on `.../v2/file.pdf` maps onto the existing `deleteFileDetails`: one format of one version,
and removing the last version removes the file.

Responses are JSON rather than S3's XML. There is no real S3 client to satisfy (§9.4), and JSON
keeps v2 consistent with the rest of the API. Familiar headers stay familiar: `ETag`,
`Content-Length`, `Last-Modified`, `Range` / `Content-Range`.

### 9.4 What this is not, stated plainly

**`aws s3 cp` will not work against it, and neither will the AWS SDKs.** Every real S3 client signs
with Signature V4, and implementing SigV4 would mean:

* verifying a four-step HMAC chain over a canonical request, including the chunked
  `STREAMING-AWS4-HMAC-SHA256-PAYLOAD` body format the CLI uses for uploads;
* multipart upload, because the CLI switches to it automatically above roughly 8 MB — without it,
  large uploads simply fail;
* ETag semantics clients verify (MD5 for a single `PUT`; MD5-of-part-MD5s plus `-N` for multipart);
* and **storing every secret reversibly**, because SigV4 is a symmetric HMAC: the server must know
  the plaintext secret to recompute the signature. That single consequence is what lets §9.2 hash
  secrets instead.

The goal is a shape people recognise, not a protocol they can point tooling at. Authentication is
therefore a bearer credential — `Authorization: Bearer fmk_…` — and the documentation must say
outright that this is S3-*style*, so nobody plans an integration around a CLI that will never
connect. If real S3 compatibility is ever wanted it is its own phase, and it starts by making
secrets recoverable.

### 9.5 Actuator — **done**

> `health` and `info` only, with `readiness` and `liveness` as separate groups, behind an
> `@Order(0)` chain that permits the three health URLs and refuses everything else under
> `/actuator` whether or not it is exposed. Details and component names are off, so the answer is
> `UP` or `DOWN` and nothing more. `deployment.md` was rewritten around it.
>
> **The settings live in `management.properties`, imported by both `application.properties`** —
> because `src/test/resources/application.properties` *shadows* the main file rather than adding
> to it, so anything written only in the main one is absent from every test. The first version of
> this work asserted Spring Boot's defaults and would not have noticed the real configuration
> being wrong.

This closes [issue 41](issues.md#41-no-actuator-no-metrics-no-real-health-check--s2), which was
planned in Phase 1 and never done — `pom.xml` carries no actuator dependency at all.

* Expose `health` and `info`. Never `env`, `beans`, `configprops` or `heapdump`.
* `management.endpoint.health.probes.enabled=true` for the `readiness` and `liveness` groups.
* **`/actuator/**` needs a security chain of its own.** Without one it falls into the browser chain,
  whose form-login entry point answers an unauthenticated probe with `302 /login` — and `GET /login`
  returns `200`, so a health check would report a healthy application with its database down. That
  is the same failure `SecurityConfig` already documents at length for the API chain.
* **[deployment.md](deployment.md) has to be revised with this.** It currently states that no
  actuator exists and builds its whole health-check section on that, recommending `GET /login` for
  liveness and `GET /files/public-files` as the closest thing to a readiness probe. Both become
  wrong the day this ships.

### 9.6 OpenAPI — **done**

> springdoc-openapi 3.1.0, the line that targets Boot 4. Two groups rather than one —
> `v2-object-store` and `v1-files` — because they are two contracts that happen to share a
> prefix, not two versions of one. `/resource/**` is not described: those endpoints belong to
> this application's own screens and change with them, and a test asserts they stay out.
>
> Behind `VIEW_API_DOCS` on the browser chain, not the machine one, so an anonymous visitor is
> sent to the login page rather than being answered with a Basic challenge the browser would
> turn into a native password box. The path is `/api-docs`, not springdoc's default
> `/v3/api-docs`, which is one character away from `/api/**` and would land in the machine
> chain.
>
> **Both halves switch off independently and are tested doing so**, because a property that
> quietly does nothing looks exactly like one that works.
>
> The settings live in `openapi.properties`, imported by both `application.properties` files.
> The test copy *shadows* the main one — same classpath name, and the test copy wins — so
> anything the suite must exercise from the shipped configuration has to be imported rather
> than written in the main file, where it would be silently absent from every test.

`springdoc-openapi` 3.x, whose major version tracks Spring Boot's; pin the current stable release at
implementation time rather than from this document.

* Swagger UI is served from the classpath of the dependency itself, so it works offline. This is
  **not** the `/webjars/` failure mode `ui.md` records — that one depends on Maven having resolved
  an artifact at runtime.
* A `GroupedOpenApi` limited to `/api/**`, so the internal `/resource/**` endpoints the pages use do
  not appear in a document meant for outside callers.
* **Enabled in production, and switchable** — the decision taken for this phase. Two properties
  (`springdoc.api-docs.enabled`, `springdoc.swagger-ui.enabled`) default to on and can be turned off
  without a rebuild, and the UI sits behind its own permission rather than being public.

### 9.7 The question of underscores — and why not to migrate

AWS bucket names may not contain `_` or an uppercase letter. `IMS_Document_System` breaks both
rules. The temptation is an update that rewrites `_` to `-` across the taxonomy.

**Do not.** It buys nothing and costs a great deal:

* nothing here checks those rules, because no real S3 client is the target (§9.4);
* a category or sub-category name **is** a directory name on disk, and it is denormalised into
  `file_info.file_path`, `file_info.relative_path`, `file_details.file_path` and
  `file_details.relative_path`
  ([issue 35](issues.md#35-paths-are-denormalised-into-three-places--s2)). Renaming means moving
  directories *and* rewriting four columns across every row — the exact shape of migration that
  loses files;
* [Phase 7.1](#71-first-decouple-the-storage-key-from-the-structure--done) makes a rename free by
  decoupling the storage key from the structure. Doing it before then is paying full price for
  something that is about to cost nothing.

**Normalise at the boundary instead.** The v2 layer resolves a bucket name case-insensitively and
treats `_` and `-` as equivalent, so `ims-document-system` and `IMS_Document_System` reach the same
folder and nothing stored changes. Two folder names that collide once normalised would be ambiguous
— check for that before shipping, and enforce uniqueness of the normalised form from then on.

### 9.8 The steps

| # | Step | Migration | Independent? |
|---|---|---|---|
| 0 | `permission` on the two grant tables; `FolderAccess` splits; upload gated; role page three-state — **done**, `V2.0` | `V2.0` | prerequisite for 1 |
| 1 | `api_key` + `api_key_folder`; bearer filter on the API chain; the "API keys" page — **done**, `V2.1` | `V2.1` | needs 0 |
| 2 | Actuator, its security chain, and the deployment.md revision — **done** | — | yes |
| 3 | `/api/v2/**` — the five operations, bucket and key resolution, version in the key — **done** | — | needs 1 |
| 4 | springdoc, grouped to `/api/**`, switchable — **done** | — | yes |

Steps 2 and 4 depend on nothing else here and can ship whenever.

### 9.9 What Phase 7 does to this

**Bucket and key names are an external contract, and Phase 7 makes folders renamable and movable.**
A rename changes the keys of everything beneath it, and any caller holding those keys breaks. The
position taken here is a filesystem gateway's — keys follow names, and a rename is a visible,
documented event — rather than inventing a second, immutable key space. If that turns out to be
unacceptable to an integrator, the alternative is an id-based key space alongside the name-based
one, and it is cheaper to add then than to retrofit stability onto names now.

**Done when:** a key can be created with a title, a description and an optional expiry; its secret
is shown once and never again; it reaches exactly the folders it was scoped to and no more; the five
v2 operations work against a bucket with the version in the key; uploading is refused where the
grant is read-only; `/actuator/health` reflects the database; and the API documents itself at a URL
that can be switched off without a rebuild.

---

## Cross-cutting acceptance criteria

Nothing in this roadmap is finished until, for every phase:

* `./mvnw verify` is green in CI with no hand-provisioned infrastructure;
* the smoke test proves the context starts;
* no secret is committed;
* `/actuator/health` reflects the real state of the database and the blob store;
* the phase's entries in [issues.md](issues.md) are struck off, and any newly discovered ones added.

---
