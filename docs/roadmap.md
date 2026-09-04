# Roadmap

Four goals, sequenced so that each phase leaves the application working and each depends only on
what came before.

| Phase | Goal | Depends on | Status |
|---|---|---|---|
| 0 | Safety net — CI, smoke test, containerised dev environment | — | **done** |
| 1 | Spring Boot 4.1.1, staying on Java 21 | 0 | **done** |
| 2 | Architectural restructuring | 1 | |
| 3 | PostgreSQL migration | 1, partly 2 | |
| 4 | S3-compatible storage alongside the filesystem | 2, 3 | |
| 5 | Folder tree: read-only view, then drag-and-drop | 3, 4 | view **done** |
| 6 | Two-tier authorization: endpoint permissions + inherited folder access | 5.1 | planned |

**Already done, outside a phase:** the Active Directory connection is documented with a worked
example in `application.properties` - domain, URL, the `login_type` gate, and why the two
properties default to empty rather than being absent.

Phase 0 is not one of the four stated goals, but every later phase is a large refactor of code that
currently has **no** automated verification (issues 36–38). Doing it first is what makes the rest
safe.

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

## Phase 4 — S3-compatible storage

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

### 5.2 Drag and drop (next)

Needs a *move* operation, which the current storage port cannot express: `FileStorageService` is
path-shaped (`address`, `version`, `extension`) and has no `move`. Do this **after** the
`BlobStore` port from [target-architecture.md](target-architecture.md#the-storage-port), where a
move is a key change plus a database update, and on S3 a server-side copy then delete.

Order:
1. `BlobStore.move(from, to)` on the port and both adapters.
2. `PUT /resource/files/tree/move` — validates the target accepts the node type, moves bytes and
   row in one transaction, writes an `ActionHistory` row.
3. Alpine drag handlers on the existing flat row list — it is already an ordered list with a
   `depth` on every row, which is what a drop target needs.

### 5.3 Collapsing the taxonomy into folders

Moved to [Phase 6](#phase-6--two-tier-authorization-endpoint-permissions-and-folder-access), which
owns the `folder` table: the same change serves both the tree and folder-level access control, and
splitting it across two phases would have produced two designs for one table.

### 5.4 Known gap in the current view

A node whose `childCount` is zero is rendered with a disabled twisty and no explanation: clicking it
does nothing, so a branch that is simply empty is indistinguishable from a tree that is broken. On
data with empty categories or tags this reads as "the tree does not work"
([issue 50](issues.md#50-an-empty-branch-in-the-file-tree-looks-like-a-broken-one--s3)).

Fix with the rest of the tree work: keep such a node expandable, and on open render an inline
"empty folder" row rather than silently doing nothing.

## Phase 6 — Two-tier authorization: endpoint permissions and folder access

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

### 6.3 Two blockers to clear before the backfill

Both were found in the current schema and will make `uq_folder_sibling_name` fail:

1. **`main_tag_file.tag_name` has `@Column(unique = true)` on the entity but no unique constraint in
   the database.** `V1.0__Initial_Setup.sql` declares none, and `ddl-auto=validate` does not check
   unique constraints - so duplicate tag names can already exist. Same class as
   [issue 33](issues.md#33-schema-and-entity-mappings-disagree--s2).
2. **`file_sub_category.sub_category_name` uniqueness lives only in application code**
   (`FileSubCategoryService.checkDuplicate`), per category. Any row inserted another way bypasses it.

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

`FileTreeService` is the first consumer: `getRoots()` returns the granted folders rather than all
categories, and `getChildren` checks the parent before it queries.

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

Drop `file_sub_category_id` / `main_tag_file_id` from `file_info`, retire the three taxonomy tables
and the `source_type` / `source_id` columns, and give every folder a real directory - a main tag has
none today, so each file's directory has to move into a newly created one.

**This is the only step that moves bytes.** It runs after Phase 3 has backfilled `checksum_sha256`
and Phase 4 has introduced `BlobStore`, and every file is verified by checksum on both sides.


---

## Cross-cutting acceptance criteria

Nothing in this roadmap is finished until, for every phase:

* `./mvnw verify` is green in CI with no hand-provisioned infrastructure;
* the smoke test proves the context starts;
* no secret is committed;
* `/actuator/health` reflects the real state of the database and the blob store;
* the phase's entries in [issues.md](issues.md) are struck off, and any newly discovered ones added.

---
