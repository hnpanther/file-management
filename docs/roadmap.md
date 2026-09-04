# Roadmap

Four goals, sequenced so that each phase leaves the application working and each depends only on
what came before.

| Phase | Goal | Depends on | Status |
|---|---|---|---|
| 0 | Safety net — CI, smoke test, containerised dev environment | — | **done** |
| 1 | Spring Boot 4.1.1 + Java 25 | 0 | next |
| 2 | Architectural restructuring | 1 | |
| 3 | PostgreSQL migration | 1, partly 2 | |
| 4 | S3-compatible storage alongside the filesystem | 2, 3 | |
| 5 | Folder tree: read-only view, then drag-and-drop, then one `folder` table | 3, 4 | view **done** |

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

## Phase 1 — Platform upgrade

**Target: Spring Boot 4.1.1 on Java 25.**

Verified against Maven Central at the time of writing — 4.1.1 is the latest stable release
(4.2.0-M1 is a milestone and is not a candidate). It brings Spring Framework 7.0.9,
Spring Security 7.1.1, Hibernate 7.4.5, Flyway 12.4.0, Jackson 3.1.5, Tomcat 11.0.24 and
JUnit 6.0.3. Java 25 is already installed on the development machine (Temurin 25.0.3).

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
  <java.version>25</java.version>
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
| **Java 25** | Lombok must be on a version that supports the Java 25 class-file format | `pom.xml` |

### 1.4 Order of operations within the phase

1. Fix issue 2 (`@Data` on entities) — do this **before** upgrading, on 3.2.1, where the behaviour
   is understood.
2. 3.2.1 → 3.5.16. Green build.
3. Java 21 → 25. Green build.
4. 3.5.16 → 4.1.1. Green build.
5. Clean up every deprecation warning the hops surfaced.
6. Add `spring-boot-starter-actuator` (issue 41) and `springdoc-openapi-starter-webmvc-ui` 3.1.0
   (issue 42) while the dependency tree is already being touched.
7. ~~Switch `war` → `jar` (issue 28)~~ — **done ahead of this phase**; the container image is
   still outstanding.

**Done when:** `./mvnw verify` is green on Spring Boot 4.1.1 / Java 25 with zero deprecation
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

## Cross-cutting acceptance criteria

Nothing in this roadmap is finished until, for every phase:

* `./mvnw verify` is green in CI with no hand-provisioned infrastructure;
* the smoke test proves the context starts;
* no secret is committed;
* `/actuator/health` reflects the real state of the database and the blob store;
* the phase's entries in [issues.md](issues.md) are struck off, and any newly discovered ones added.

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

### 5.3 Collapsing the taxonomy into folders (the real change)

Target: one `folder` table, self-referencing, replacing category, sub-category and main tag.

```
folder(id, parent_id, name, name_description, general_tag_id, path, created_*, ...)
file_info(folder_id, ...)   -- instead of file_sub_category_id + main_tag_file_id
```

* A general tag stays a label, on a folder rather than on a category.
* Depth becomes unbounded, which is the point.
* Migration: create `folder`; insert one row per category, one per sub-category (parent = its
  category), one per main tag (parent = its sub-category); repoint `file_info.folder_id`; keep the
  old tables for one release behind a read-only view.
* On disk, a main tag currently has **no** directory, so the migration has to create one and move
  each file's directory into it — this is the only step that touches bytes, and it must be
  checksum-verified the same way the S3 migration is.
* `FileTreeService` collapses to a single recursive query on `folder`; the view does not change,
  which is why it was built this way.

**Do not start 5.3 before Phase 3 (PostgreSQL, with `checksum_sha256` backfilled) and Phase 4's
`BlobStore`.** Moving bytes without checksums and without a storage port is how a file gets lost.
