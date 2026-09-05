# Architecture — Current State

> Snapshot of the codebase as it exists on branch `redesign-arch` (HEAD `08db773`).
> For where we are going, see [target-architecture.md](target-architecture.md).

## 1. What the application is

A server-rendered file-management web application. Users organise files into a fixed
five-level taxonomy, upload them, and create additional **versions** and **formats** of the
same logical file. Files live on the local filesystem; all metadata lives in MySQL.
A small machine-facing REST API (`/api/v1/files`) was added later for programmatic upload,
download and delete.

## 2. Technology stack

| Concern | Choice | Version |
|---|---|---|
| Language | Java | 21 |
| Framework | Spring Boot | 4.1.1 (Framework 7.0.9, Security 7.1.1, Hibernate 7.4.5, Jackson 3) |
| Packaging | Executable JAR (embedded Tomcat) | `java -jar target/file-management.jar` |
| View layer | Thymeleaf + `thymeleaf-extras-springsecurity6` | |
| Client assets | WebJars: Bootstrap 5.3.8, jQuery 4.0.0, Select2 4.1.0; local Vazirmatn 33.003 | |
| Persistence | Spring Data JPA / Hibernate | |
| Database | MySQL (`mysql-connector-j`) | |
| Schema | Flyway (`flyway-core` + `flyway-mysql`) | |
| Security | Spring Security 6, optional Active Directory via `spring-security-ldap` | |
| Boilerplate | Lombok | |
| Build | Maven wrapper | 3.9.5 |

## 3. Package layout

```
com.hnp.filemanagement
├── FileManagementApplication      entry point + CommandLineRunner that seeds permissions/roles/admin
├── api/                           FileApi — machine-facing REST, HTTP Basic, stateless
├── config/
│   ├── logging/                   LoggingInterceptor + MyWebMvcConfigurer
│   └── security/                  SecurityConfig, UserDetailsImpl/ServiceImpl,
│                                  ActiveDirectoryCustomAuthenticationProvider, SecurityController
├── controller/                    Thymeleaf page controllers (return view names)
├── resource/                      REST endpoints consumed by the pages' own jQuery (session auth)
├── service/                       business logic + the FileStorageService abstraction
├── repository/                    Spring Data JPA interfaces + two hand-written JdbcClient DAOs
├── entity/                        JPA entities and the ActionEnum/EntityEnum/PermissionEnum enums
├── dto/                           form-binding, paging and response DTOs
├── exception/                     custom exceptions + two @ControllerAdvice handlers
├── util/                          ModelConverterUtil (entity→DTO), GlobalGeneralLogging
└── validation/                    @ValidFile constraint, validation groups, ValidationUtil
```

## 4. The domain model

Five entities form a strict parent→child chain, and `FileDetails` hangs off the end:

```
GeneralTag ──1:N──> FileCategory ──1:N──> FileSubCategory ──1:N──> MainTagFile
                                                 │                      │
                                                 └──────────┬───────────┘
                                                            ▼
                                                        FileInfo ──1:N──> FileDetails
```

* **GeneralTag** — top-level grouping label. Purely organisational; it has no directory of its own.
* **FileCategory** — first physical directory level. `category_name` is unique and must contain
  no `.`, no space and no `/`, because it becomes a directory name.
* **FileSubCategory** — second physical directory level, scoped to a category.
* **MainTagFile** — a tag scoped to a sub-category. Every `FileInfo` must point at one, and
  `FileService.createNewFile` re-validates that the tag's sub-category and category match the
  ones submitted on the form.
* **FileInfo** — the *logical* file (e.g. "the Q3 report"). Holds `last_version` and the
  visibility `state`.
* **FileDetails** — one *concrete artefact*: a specific (version, format) pair of a `FileInfo`.
  Carries `file_name`, `file_extension`, `content_type`, `file_size`, `version`, `version_name`.

`FileInfo` and `FileDetails` both denormalise `file_path` (absolute) and `relative_path` onto the
row, so the physical location is recorded in three places: the two path columns and the directory
structure itself.

### The folder mirror

Alongside the taxonomy, and derived from it, is a single tree in `folder` (migration `V1.4`):

```
Home ──> {category} ──> {sub-category} ──> {main tag}
 ROOT      CATEGORY       SUB_CATEGORY        TAG
```

It exists because folder-level access cannot be granted against three separate tables at a fixed
depth — one of which is not even a directory. One table means one kind of grant, inherited down an
arbitrary depth.

* **The taxonomy stays authoritative.** Every folder row is written by `FolderMirrorService`, in the
  same transaction as the category, sub-category or main tag it reflects, and read only by the
  folder-access code. Rolling the whole thing back is `DROP TABLE folder`.
* **`parent_id` is the structure; `path` is a derived index.** `path` is a materialised path of ids
  with a leading and trailing slash (`/1/5/26/`), built from ids so a rename costs nothing, and
  carrying the trailing slash so `/1/7/` cannot match `/1/70/`. It exists so "every descendant of
  these folders" is a prefix scan rather than a recursive query.
* **`source_type` + `source_id`** point back at the mirrored row, unique together, which is what makes
  the backfill re-runnable and reconciliation a join. Both columns disappear when `folder` becomes
  authoritative (roadmap 6.8).
* **It self-heals.** A taxonomy row written straight through a repository has no folder; rather than
  fail the next legitimate write, the missing ancestry is created on the spot.
  `FolderMirrorReconciliationTest` is what proves the mirror describes the *whole* taxonomy, and it
  runs on every build.

### How the entities are mapped

Four rules hold across every entity, and each replaced something that was actively wrong.

**Identity lives in `AbstractEntity`.** Every entity extends it, and it owns the `id` plus `equals`,
`hashCode` and `toString` — all three `final`. Previously every entity was annotated `@Data`, which
generated them over *all* fields including the associations:

* `toString()` recursed across `FileInfo` ↔ `FileDetails` until the stack overflowed, so any log
  line or exception message that touched an entity crashed the request;
* `equals`/`hashCode` initialised lazy collections just to answer a comparison;
* `hashCode` included the generated id, which is null before the insert, so an entity put into a
  `HashSet` before flush could not be found afterwards.

The replacements compare ids (via `Hibernate.getClass`, so a lazy proxy equals its entity), hash on
the type, and print `Type#id`. `EntityIdentityTest` pins all three down.

**Audit columns live in `AuditableEntity`.** The six domain tables share `created_at`, `updated_at`,
`created_by`, `updated_by`. The timestamps are written by Hibernate's `@CreationTimestamp` /
`@UpdateTimestamp`; they used to be set by hand in every service, and an update that forgot the
second line kept a stale timestamp.

**Every association is `LAZY`.** Loading one `FileInfo` used to load its two users, its
sub-category, that category, that category's tag, its main tag, *that* tag's sub-category — a join
across the whole schema for a row on a list page. What a query needs it now fetches explicitly;
see §"Fetch plans" below.

**Many-to-many collections are `Set`, not `List`.** A `List`-mapped many-to-many is a Hibernate
*bag*: changing one member deletes every join row for the owner and re-inserts the survivors. It is
also what makes the login query legal — two `List` collections in one `JOIN FETCH` is
`MultipleBagFetchException`.

### Fetch plans

With lazy associations, a query states what it needs. Two shapes appear in the repositories:

* fetching `@ManyToOne` chains is free to paginate — one row per entity either way — so
  `FileInfoRepository.search` returns a `Page` with the whole taxonomy attached;
* fetching a collection cannot be paginated in SQL, so those queries return a single row
  (`findByIdAndFetchFileDetails`) and use `DISTINCT`.

`spring.jpa.open-in-view` is **off**. With it on, a lazy association touched during template
rendering silently issues a query from the view layer, which is an N+1 invisible in the service
code. With it off, anything a page needs must be fetched inside a `@Transactional` service method —
which is why no service returns an entity.

**Children are read by query, not through the parent's collection.** `getFileSubCategoryOfCategory`,
`getMainTagsOfSubCategory`, `getFileCategoryOfGeneralTag` and all three delete checks query the
child table directly. Reading `parent.getChildren()` answers from the persistence context, which
can hand back a collection initialised earlier in the same transaction when it was empty — a
category that had just gained a sub-category looked empty, and the delete check passed.

### Versions vs. formats

`FileService.createNewFileDetails` branches on `FileUploadDTO.type`:

* `"version"` — must be exactly `fileInfo.lastVersion + 1`. Creates a new `FileDetails` and bumps
  `FileInfo.lastVersion`.
* `"format"` — must be `<= lastVersion`. Copies `version`/`versionName` from a *reference*
  `FileDetails` (`fileDetailsId`) and rejects a duplicate (version, extension) pair.

Both paths require the uploaded file's base name to equal `fileInfo.fileName`.

### Magic-number columns

Every entity carries `enabled` and `state` as bare `Integer`s. Per the comment on `FileInfoDTO`:

| Column | Value | Meaning |
|---|---|---|
| `FileInfo.state` | `0` | public |
| | `-1` | private |
| | `1` | "rule base" — declared in a comment, never reachable (`changeFileInfoState` rejects it) |
| `FileDetails.state` | `0` | visible |
| `*.enabled` | `1` | active |

A file is publicly downloadable only when **both** `FileDetails.state = 0` **and**
`FileInfo.state = 0` (`FileDetailsRepository.findPublicFile`).

## 5. Physical storage layout

`FileStorageFileSystemService` is the only implementation of `FileStorageService`. It is
registered as `@Service("fileSystem") @Primary` and takes `${file.management.base-dir}`.

```
{base-dir}/
└── {CategoryName}/                     created by FileCategoryService.createCategory
    └── {SubCategoryName}/              created by FileSubCategoryService.createFileSubCategory
        └── {fileNameWithoutExtension}/ created lazily by FileStorageFileSystemService.save
            └── v{version}/
                └── {fileName}.{ext}
```

The interface is deliberately narrow and path-shaped:

```java
void     save(String address, MultipartFile file, int version, String extension);
Resource load(String address, String fileName, int version, String extension);
void     delete(String address, String fileName, int version, String extension, boolean isFile);
void     createDirectory(String title, boolean isSubDirectory);
```

`address` is always `"{CategoryName}/{SubCategoryName}"` (with `/{fileName}` or `/v{n}` appended for
directory deletes) and is rebuilt by walking the entity graph at every call site. Because the
signature bakes in "directory + version + extension", it cannot express an object-store key without
change — this is the first thing the S3 work has to fix.

Name rules enforced at the storage boundary: directory names must contain **zero** of `.`, ` `, `/`;
file names must contain **exactly one** `.` and zero of ` `, `/`.

## 6. HTTP layers

There are three parallel HTTP surfaces over the same services:

| Package | Base path | Returns | Auth | Purpose |
|---|---|---|---|---|
| `controller/` | `/files`, `/file-categories`, `/file-sub-categories`, `/main-tags`, `/general-tags`, `/users`, `/roles`, `/` | Thymeleaf view names | form login, session | the UI |
| `resource/` | `/resource/**` | JSON (`ApiResult` or a DTO) | form login, session, CSRF | AJAX called by the pages themselves |
| `api/` | `/api/v1/files` | JSON | HTTP Basic, stateless | external integrations |

### The REST contract

Both JSON surfaces answer the same way. This was not true until the cleanup pass: each endpoint
caught its own exceptions and invented its own wording, so the same failure came back as 400 from
one path and 404 from another.

**Success** is `ApiResult` for a mutation and a DTO for a lookup:

```json
{"outcome": "DELETED", "resource": "fileDetails", "id": 41}
```

`outcome` is one of `CREATED`, `UPDATED`, `DELETED`, `STATE_CHANGED`. The status code carries the
meaning; the body carries the identity of what changed. Success is always 200 — the pages branch
only on `xhr.status === 200` and never read the body.

**Failure** is an RFC 9457 problem document, `application/problem+json`:

```json
{
  "type": "https://github.com/hnpanther/file-management/blob/main/docs/issues.md#resourcenotfoundexception",
  "title": "ResourceNotFoundException",
  "status": 404,
  "detail": "file info with id=999999 not exists",
  "path": "/resource/files/file-info/999999"
}
```

The status comes from the `@ResponseStatus` on the exception class, so the exception is the single
source of truth:

| Exception | Status | Means |
|---|---|---|
| `ResourceNotFoundException` | 404 | the id does not exist |
| `DuplicateResourceException` | 409 | something with that name already exists |
| `DependencyResourceException` | 409 | still referenced — a category with sub-categories, a tag with files |
| `InvalidDataException` | 400 | a value outside the allowed set, or a missing required field |
| `BusinessException` | 417 | a rule the caller could not have known from the request alone |
| — (`AccessDenied`) | 403 | `@PreAuthorize` refused |
| — (`InvalidRequestBody`) | 400 | the body is not readable JSON |
| — (`InvalidParameter`) | 400 | a path variable or query parameter will not convert |
| — (`Unexpected`) | 500 | anything else; the message is generic |

Authentication is the one answer that does not come from this advice. `/api/**` is HTTP Basic and a
missing or wrong credential is a **401** carrying `WWW-Authenticate`, produced by an explicit entry
point on that chain. It used to be `302 Location: /login`: `BasicAuthenticationEntryPoint` reports
with `sendError`, whose ERROR dispatch re-entered the filter chains as `/error` — which no longer
matches `/api/**`, so the session chain answered with a redirect. Since `GET /login` returns 200,
any client that follows redirects could read a final 200 and conclude a failed call had succeeded.

The wording of the generic messages depends on the surface: `/api/**` gets English, because its
callers are programs, and everything else gets the Persian bundle. A domain exception's own message
is English on both and is passed through untouched.

A request that accepts `text/html` gets `error.html` at the same status instead of the problem
document, so a browser navigation still lands on a page.

### Endpoint inventory

<details>
<summary>Thymeleaf controllers</summary>

| Method | Path | Permission |
|---|---|---|
| GET | `/` | `ACCESS_HOME` |
| GET | `/login` | permitAll |
| GET / POST | `/files/create`, `/files` | `CREATE_FILE_PAGE`, `SAVE_NEW_FILE` |
| GET | `/files/public-files` | `PUBLIC_FILE_PAGE` (path is also permitAll in the chain) |
| GET | `/files/public-download/{id}` | permitAll (`?inline=1` switches Content-Disposition) |
| GET | `/files/file-info`, `/files/file-info/{id}` | `GET_ALL_FILE_INFO_PAGE`, `FILE_INFO_PAGE` |
| GET | `/files/file-info/{fileInfoId}/file-details/{fileDetailsId}/download` | `DOWNLOAD_FILE` |
| GET / POST | `/files/file-info/{fileInfoId}/file-details/create`, `.../file-details` | `SAVE_NEW_FILE_DETAILS_PAGE`, `SAVE_NEW_FILE_DETAILS` |
| GET / POST | `/file-categories/**`, `/file-sub-categories/**`, `/main-tags/**`, `/general-tags/**` | one permission per handler |
| GET / POST | `/users/**`, `/roles/**` | one permission per handler |

</details>

<details>
<summary>REST — /resource/** (session)</summary>

| Method | Path |
|---|---|
| GET | `/resource/file-categories/{id}/sub-categories` |
| DELETE | `/resource/file-categories/{id}` |
| GET | `/resource/file-sub-categories/{id}/main-tags` |
| DELETE | `/resource/file-sub-categories/{id}` |
| GET, DELETE | `/resource/general-tags`, `/resource/general-tags/{id}` |
| DELETE | `/resource/main-tags/{id}` |
| DELETE, PUT | `/resource/files/file-info/{id}`, `.../change-state` |
| DELETE, PUT | `/resource/files/file-info/{id}/file-details/{fdId}`, `.../change-state/{newState}` |
| PUT | `/resource/users/{userId}/change-enabled`, `.../change-login-type/{type}` |
| GET | `/resource/files/tree/children?type=&id=` |

</details>

<details>
<summary>REST — /api/v1/files (HTTP Basic)</summary>

| Method | Path | Permission |
|---|---|---|
| GET | `/health-test` | `API_HEALTH_TEST` |
| POST | `/` (multipart, `?public-file=0` for private) | `API_SAVE_NEW_FILE` |
| DELETE | `/file-info/{fileInfoId}/file-details/{fileDetailsId}` | `API_DELETE_FILE_DETAILS` |
| GET | `/file-info/{fileInfoId}/file-details/{fileDetailsId}/download` | `API_DOWNLOAD_FILE` |

</details>

## 7. Security model

### Two filter chains

`SecurityConfig` publishes two `SecurityFilterChain` beans:

* **`@Order(1)` `apiSecurityFilterChain`** — `securityMatcher("/api/**")`, CSRF off, CORS off,
  `SessionCreationPolicy.STATELESS`, `httpBasic()`.
* **`@Order(2)` `securityFilterChain`** — everything else. CSRF **on** (all AJAX templates read
  `_csrf` / `_csrf_header` from `<meta>` tags and set the header), form login at `/login`,
  logout at `/logout`. PermitAll list: `/`, `/favicon.ico`, `/webjars/**`, `/css/**`, `/js/**`,
  `/public-pages/**`, `/files/public-files/**`, `/files/public-download/**`.

### Authentication

`AuthenticationManagerBuilder` is assembled conditionally on
`filemanagement.auth.ldap.activedirectory.enabled`:

* **off** → `DaoAuthenticationProvider` only (BCrypt against the `user` table).
* **on** → `ActiveDirectoryCustomAuthenticationProvider` first, then `DaoAuthenticationProvider`.

`User.loginType` gates which provider may accept a user: `0` = either, `1` = local DB only,
`2` = Active Directory only. `UserDetailsServiceImpl` refuses `loginType != 0 && != 1`; the AD
provider refuses `loginType != 0 && != 2`.

### Authorization

Authorities are **not** roles — they are `PermissionEnum` constants, one per handler method
(~70 of them). `UserDetailsServiceImpl` loads a user's permissions through their roles and, if
any role is named `ADMIN`, additionally grants the synthetic `ADMIN` authority. Every handler
carries `@PreAuthorize("hasAuthority('X') || hasAuthority('ADMIN')")`.

### Folder access — the second question

Since Phase 6 there is a second, independent question: not "may this user list folders" but "may this
user see *this* folder". Both must pass.

* `folder` mirrors the taxonomy as one tree — `Home` → category → sub-category → main tag — written
  only by `FolderMirrorService`, always in the same transaction as the row it mirrors.
* A grant is a row in `role_folder` or `user_folder` naming a folder, and it covers everything
  beneath that folder. `folder.path` is a materialised path of ids with a leading and trailing slash
  (`/1/5/26/`), so "is this inside that grant?" is a prefix test and an indexed range scan.
* `FolderAccessService.accessFor(principalId)` resolves it once per call into a `FolderAccess`; the
  `ADMIN` role is unrestricted and reads no grant rows at all.
* **Readable and traversable are different.** A grant can sit in the middle of the tree, and the
  holder has no right to the folders above it — but hiding those would leave no route down to what
  they do have. So an ancestor of a grant is shown and can be opened, revealing only the branch that
  leads to the grant; its other branches and any file content stay hidden. `allows` answers the
  first question, `isOnPathTo` the second, and `visible` is their union.
* Enforcement covers the tree, the file list (pushed into the query, so paging counts stay honest),
  the file page and both download endpoints. Uploading is not covered yet (issue 76), and the
  `permitAll` public download is deliberately outside it.
* **The tree addresses a node by its folder id**, not by the taxonomy row behind it — including the
  ids a search hit reports. The taxonomy id stays inside `FileTreeService`. A file is the one
  exception: it has no folder until roadmap 6.8, so it is still addressed by its own id and
  authorised through its tag.

**It is off by default.** `filemanagement.folder-access.enabled` is `false`, because turning it on
before any grant exists empties the tree for every non-administrator and there is no screen for
granting folders yet. With it off, `accessFor` answers "unrestricted" for everyone.

With the flag off, holding `DOWNLOAD_FILE` still grants download of every file, private ones
included (issue 14) — the endpoint permission is then the only check there is.

### Bootstrap

`FileManagementApplication.runner` runs only when `spring.profiles.active=prod`. It inserts any
missing `PermissionEnum` value, creates the `ADMIN` and `USER` roles, and creates user
`Admin` / `admin` with the `ADMIN` role if absent.

## 8. Cross-cutting concerns

**Audit trail** — `ActionHistoryService.saveActionHistory(entity, id, action, userId, ...)` writes an
`action_history` row. It is called explicitly from the services after each mutation; it is not an
aspect, so coverage depends on the author remembering.

**Logging** — two mechanisms overlap:

* `LoggingInterceptor` (registered by `MyWebMvcConfigurer`) logs method / URI / remote-addr per request.
* `GlobalGeneralLogging.controllerLogging(...)` is called by hand at the top of roughly sixty
  handler methods, each rebuilding `request.getRequestURI() + "?" + request.getQueryString()`.

`logback-spring.xml` writes to `D:/files/logs`, rolling daily / 10 MB, keeping 10 files.
`com.hnp.filemanagement` is at `debug`, root at `info`.

**Exception handling** — one `@ControllerAdvice`, `GlobalExceptionHandler`. It picks its shape from
the request: `Accept: text/html` gets `error.html` at the right status, anything else gets an
RFC 9457 `ProblemDetail`. The status is read off the `@ResponseStatus` annotation on the exception
class rather than hard-coded in the advice, so adding an exception type does not mean editing the
advice.

There used to be a second advice scoped to `FileApi` which disagreed with this one — an
authorization failure was 403 through one path and 400 through the other — and the page controllers
each caught their own exceptions and flattened them. Both are gone; see §6, "The REST contract".

The page controllers still catch, and should: they re-render the submitted form with a message
beside it, which a status code cannot do.

**Mapping** — `ModelConverterUtil` holds ~300 lines of static entity→DTO methods.

## 9. Request flow — uploading a new file

```
POST /files (multipart)
  └─ FileController.saveNewFile
       ├─ @PreAuthorize SAVE_NEW_FILE || ADMIN
       ├─ @Validated(InsertValidation) → @ValidFile checks MultipartFile.getContentType()
       └─ FileService.createNewFile(dto, principalId, publicFile)          @Transactional
            ├─ MainTagFileService.getMainTagFileByIdOrTagName
            ├─ verify tag.subCategory / tag.subCategory.category match the form
            ├─ isDuplicate(baseName, subCategoryId)
            ├─ ValidationUtil.checkCorrectFileName
            ├─ build FileInfo (paths, state, lastVersion = 1)
            ├─ build FileDetails v1 (hashId = random UUID)
            ├─ fileInfoRepository.save(fileInfo)          ← cascades to FileDetails
            ├─ actionHistoryService.saveActionHistory × 2
            └─ fileStorageService.save(address, multipartFile, 1, ext)   ← disk write, LAST
```

The disk write happens inside the transaction but is not part of it — see
[issues.md](issues.md#3-storage-writes-are-not-atomic-with-the-database--s1).

## 10. Database schema

Flyway migrations in `src/main/resources/db/migration`:

| Version | Contents |
|---|---|
| `V1.0__Initial_Setup.sql` | `user`, `role`, `user_role`, `permission`, `permission_role`, `general_tag`, `file_category`, `file_sub_category`, `main_tag_file`, `file_info`, `file_details` |
| `V1.1__Add_LoginType_To_User.sql` | `user.login_type INT NOT NULL DEFAULT 0 AFTER updated_at` |
| `V1.2__Add_Action_History_Table.sql` | `action_history` |
| `V1.3__Add_Uniqueness_And_Indexes.sql` | the composite unique constraints the services check in Java, and indexes on the filtered columns |
| `V1.4__Add_Folder_Mirror.sql` | `folder`, plus the backfill that mirrors every category, sub-category and main tag into it |
| `V1.5__Add_Folder_Grants.sql` | `role_folder`, `user_folder` |

`V1.3` turns four rules that lived only in application code into constraints: a sub-category name is
unique per category, a main-tag name per sub-category, a file name per sub-category, and a
(version, format) pair per file. Each of those checks was a `SELECT` followed by an `INSERT`, which
two concurrent requests can both pass — and for a category or a sub-category that also means two
rows claiming one directory on disk. The in-code checks stay, because they are what turns a
violation into a readable 409 instead of a 500.

It also declares the indexes the application depends on. MySQL creates one per foreign key,
PostgreSQL does not, and Phase 3 migrates to PostgreSQL.

If an existing database already holds rows that violate one of these rules the migration fails and
Flyway stops with nothing half-applied; find them with the matching
`SELECT ... GROUP BY ... HAVING COUNT(*) > 1` and resolve them first.

All of it is MySQL-specific: `ENGINE = InnoDB`, `DEFAULT CHARSET = utf8mb4 COLLATE utf8mb4_unicode_ci`,
`AUTO_INCREMENT`, `DATETIME`, `#` line comments, `ADD COLUMN ... AFTER`.

`schema-db/schema.sql` is a **separate, hand-maintained duplicate** of the same schema that begins
with `DROP DATABASE IF EXISTS file_management;` and also drops `flyway_schema_history`. It is not
wired into the build.

`spring.jpa.hibernate.ddl-auto=validate` — Hibernate verifies the mapping against the Flyway-built
schema at startup but never modifies it.

## 11. Configuration

| Property | Default in repo | Used by |
|---|---|---|
| `spring.profiles.active` | `prod` | gates the admin / permission seeding |
| `server.port` | `8122` | |
| `spring.datasource.*` | `jdbc:mysql://localhost:3306/file_management`, user/pass `file_management` | |
| `spring.jpa.hibernate.ddl-auto` | `validate` | |
| `spring.flyway.baseline-on-migrate` | `true` | |
| `file.management.base-dir` | `./TempFiles/files/main/` | `FileStorageFileSystemService`, `FileService`, `FileCategoryService`, `FileSubCategoryService`, `MainTagFileService` |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | `20MB` | |
| `filemanagement.default.page-size` / `element-size` | `30` | injected per-controller with `@Value` |
| `filemanagement.auth.ldap.activedirectory.enabled` / `.domain` / `.url` | `false`, `hnp.local`, `ldap://172.29.76.9` | |

Note the two different prefixes (`file.management.*` and `filemanagement.*`) and that no
`@ConfigurationProperties` type exists — everything is `@Value`-injected at five call sites.

## 12. Tests

`./mvnw test` runs 263 tests and needs only a working Docker daemon: `MySqlSupport` starts one
MySQL 8.0.36 container per JVM, and `StorageRootSupport` gives each test a clean storage root.

Four kinds, and the kind is the point — each answers something the others cannot.

| Kind | How | What only it can answer |
|---|---|---|
| **Unit** | plain JUnit, or Mockito with every collaborator mocked | that a guard clause rejects before anything is written: `FileServiceUnitTest` asserts the storage service is never touched on a rejected upload. `EntityIdentityTest` and `ValidationUtilTest` need neither Spring nor Docker |
| **Repository** | `@DataJpaTest` + real MySQL | that a fetch plan actually resolved (`Hibernate.isInitialized`), that a bulk update reached the database, that a cascade removed what it should, and that the schema enforces its constraints |
| **Service** | `@ServiceIntegrationTest` — `@SpringBootTest` + `@Transactional` | that the whole path works through the real Spring beans, so the transaction annotations are live |
| **Web** | `@SpringBootTest` + MockMvc | status codes, response shapes, redirects and authorization, through the real security chain |

Two things about the service tests are deliberate corrections of how they used to work.

**The beans are Spring's, not `new`.** They used to be constructed by hand —
`new GeneralTagService(entityManager, repository, actionHistoryService)` — which produces an object
with no proxy, so every `@Transactional` on the class under test was inert. Those tests could not
have caught a missing transaction boundary, which is precisely the class of bug that turned out to
be there.

**Each test rolls back.** `@Transactional` on the test class replaces `@Commit` on every method plus
a hand-written sequence of `deleteAll()` calls in `@AfterEach` — in foreign-key order, so adding a
table meant editing six teardowns, and a test that failed part-way left rows that broke the next
class to run. Where a test is about a constraint that only fires at flush time, it flushes
explicitly.

Fixtures come from `support/TestData`, which sets every `NOT NULL` column to something valid and
generates the unique ones, so a test overrides only what it is actually about.

| Class | Covers |
|---|---|
| `entity/EntityIdentityTest` | `equals` / `hashCode` / `toString`, and both sides of the `FileInfo` ↔ `FileDetails` link |
| `validation/ValidationUtilTest` | the naming rules, including path traversal |
| `repository/FileInfoRepositoryTest` | fetch plans, the `lastVersion` recompute, orphan removal, the `V1.3` constraints |
| `repository/UserRepositoryTest` | the login fetch, permission de-duplication, the search page |
| `service/FileServiceUnitTest` | the upload guard clauses, and that a rejected request writes nothing |
| `service/*ServiceTest` (7 classes) | each service end to end against a real database |
| `web/RestContractTest` | the REST contract of §6 |
| `web/AuthenticationRedirectTest` | where an anonymous, a signed-in and an unauthorized visitor land |
| `web/FileTreeTest` | the tree page and its children endpoint |
| `UiResourceTest` | every asset the templates reference exists locally — no CDN, no network at runtime |
| `MessageBundleTest` | every `#{...}` key is backed, and no Persian is hardcoded in a template |
| `DependencyPinTest` | the pinned versions that clear known advisories stay pinned |
| `FileManagementApplicationTests` | the context starts |

## 13. Known structural weaknesses

Catalogued in full in [issues.md](issues.md). The ones that shape the architecture:

1. Three HTTP layers over one service layer. The two JSON layers now share one contract (§6); the
   Thymeleaf layer deliberately does not, because it re-renders forms rather than returning statuses.
2. `FileStorageService`'s signature is filesystem-shaped, blocking S3.
3. Storage and database mutations are not atomic in either direction.
4. `@Table(name = "user")` — a reserved word in PostgreSQL.
5. Every `@ManyToOne` is `EAGER`; `ModelConverterUtil` walks the full graph on every list page.
6. Authorization is per-endpoint, never per-resource.
