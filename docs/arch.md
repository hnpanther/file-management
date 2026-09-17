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

### What a file is attached to, during Phase 7

Phase 7 separates *where a file is* from *what it is about*. Both halves already exist on every
file, written alongside the taxonomy keys (roadmap 7.2 steps 1–2). Step 3 moves the readers over
one at a time. The v2 object store reads files by `folder_id` — its listing is three queries
with no tag translation, and a key resolves to its file through the folder the key walked to.
The explorer does too: a folder's files, each child's file count and every search hit come from
`folder_id`, and folder access is applied as a set of folder ids in the query. So does folder
access in `FileService`: a download, the file page and a new version ask the file's own folder
(`requireReadAccess` / `requireWriteAccess` on a `FileInfo`), and the list page filters on
`readableFolderIds`; a file with no folder is refused to a restricted principal (fail closed). And
the tree: a tag node's files and count, opening a file, and the branch a search hit is placed on.
And uploading: a new file's place may be named as a `folderId` as well as by the taxonomy triple,
the folder is what write access is checked on, and the two addressings must agree when both are
sent. The taxonomy keys on `file_info` are still written, for the taxonomy pages and the
per-sub-category uniqueness rule, until step 4. The tags are still read by nothing.

```
FileInfo ──N:1──> Folder            file_info.folder_id   the folder mirroring its main tag   (V2.3)
FileInfo ──N:M──> Tag ──N:1──> TagGroup                   its category, sub-category and       (V2.4)
                  file_tag           tag / tag_group      main tag as labels, in the group of
                                                          its general tag
```

* **The folder** is `FolderMirrorService.folderOf(mainTag)` — get-or-create, so an upload into a
  tag written behind the services heals the mirror rather than storing a null. The foreign key is
  `RESTRICT`: a folder with files in it cannot be deleted by any route.
* **The tags** are written by `TagMirrorService`, the one writer of `tag_group`, `tag` and
  `file_tag` while the taxonomy is authoritative, with the same rules as the folder mirror
  (`MANDATORY` transaction, get-or-create). A file's tags are a *function of its taxonomy* —
  `retag(file)` makes the set exactly that — and are re-derived on the one input that can change,
  a main-tag rename.
* **A tag is a label, not a place.** Unique by `(group, name)`; a sub-category and a main tag both
  named `HSED` under one general tag are *one* tag, carried once. The files are still told apart
  by their folders. Titles are copied at creation and not followed (which of the merged rows'
  labels should win has no answer until tags are edited as tags, step 5).
* **Both are reconciled on every build**: `FileInfoRepository.findRowsWhoseFolderDisagreesWithTheMirror`
  and `findIdsWhoseTagsDisagreeWithTheTaxonomy` must be empty (`FileFolderLinkTest`, `FileTagTest`),
  and each migration's backfill is the statement the test runs, cut out of the file.

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

The interface now has two halves, and which one a caller uses is not a matter of taste.

**Key-shaped, for one stored object** (roadmap 7.1). The whole location is a single opaque
string — the value in `file_details.storage_key`:

```java
void     saveByKey(String storageKey, MultipartFile file);
Resource loadByKey(String storageKey);
void     deleteByKey(String storageKey);
```

Every read and write of a single file goes through these, so **where the bytes are is what was
recorded when they were written**, not something rebuilt from the taxonomy at read time. That is
what lets Phase 7 rename and move folders without moving a byte or orphaning a file.

**One boundary for both halves.** Every method, key-shaped or path-shaped, turns its relative
input into an absolute path through `within(relative)`: the root is resolved to an absolute,
normalised path, the relative part is resolved beneath it and normalised (which folds `..`), and
the result must still start with the root and must not *be* the root. Anything else is refused
before a filesystem call, whatever the caller spelled (issues 4 and 16). The spelling rules below
still apply on top, per segment.

**Path-shaped, for directories.** What is left on these is directory work — creating a
category's folder, removing an emptied one — which is genuinely path-shaped:

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

Name rules enforced at the storage boundary: directory names must contain **zero** of `.`, ` `, `/`,
applied to every segment of an address; file names must contain **exactly one** `.` and zero of
` `, `/`.

## 6. HTTP layers

There are four parallel HTTP surfaces over the same services:

| Package | Base path | Returns | Auth | Purpose |
|---|---|---|---|---|
| `controller/` | `/files`, `/file-categories`, `/file-sub-categories`, `/main-tags`, `/general-tags`, `/users`, `/roles`, `/api-keys`, `/file-explorer`, `/` | Thymeleaf view names | form login, session | the UI |
| `resource/` | `/resource/**` | JSON (`ApiResult` or a DTO) | form login, session, CSRF | AJAX called by the pages themselves |
| `api/` | `/api/v1/files` | JSON | HTTP Basic, stateless | external integrations (the shared machine account) |
| `api/` | `/api/v2/{bucket}` | JSON, S3-style | `Authorization: Bearer fmk_…` (an API key), stateless | external integrations, scoped to folders |

The v2 surface is described by an OpenAPI document at `/api-docs/v2-object-store` and a Swagger
page at `/swagger-ui/index.html`, both behind `VIEW_API_DOCS` on the browser chain and switchable
off with `springdoc.api-docs.enabled` / `springdoc.swagger-ui.enabled` (roadmap 9.6).

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
| GET | `/files/public-download/{id}` | permitAll (`?inline=1` is honoured only for `ContentTypes.inlineSafe` kinds; every download carries `nosniff` and a `default-src 'none'` CSP) |
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
| POST | `/` (multipart, `?public-file=0` for private; the place as `fileCategoryId` + `fileSubCategoryId` + `mainTagFileId`, or as `folderId`, or both agreeing; a request without `folderId` is answered with `Deprecation: true` and logged as `v1-upload-by-triple`, since the triple goes in Phase 7 step 4) | `API_SAVE_NEW_FILE` |
| DELETE | `/file-info/{fileInfoId}/file-details/{fileDetailsId}` | `API_DELETE_FILE_DETAILS` |
| GET | `/file-info/{fileInfoId}/file-details/{fileDetailsId}/download` | `API_DOWNLOAD_FILE` |

</details>

<details>
<summary>REST — /api/v2 (Bearer API key; roadmap 9.3)</summary>

Every handler requires `API_KEY`, which every key carries and no person does, or `ADMIN`. Which
objects a call actually reaches is decided by folder access inside `ObjectStoreService`: a key's
own scopes, never its creator's. A bucket is a top-level folder, matched case-insensitively and with
`_` ≡ `-`; a key is the path beneath it, decoded, with the version as a segment.

| Method | Path | Answers |
|---|---|---|
| GET | `/{bucket}?prefix=&delimiter=/&max-keys=&continuation-token=` | 200 listing; 404 no such bucket; 403 outside the key's folders |
| GET | `/{bucket}/{sub}/{tag}/{file}/v{n}/{file}.{ext}` | 200 bytes with `ETag`, `Last-Modified`, `x-fm-version`; 206 for a `Range`; 404; 403 |
| GET | `…?metadata`, or `HEAD` on the URL above | 200 metadata as JSON / headers only |
| PUT | `/{bucket}/{sub}/{tag}/{file}/{file}.{ext}` — **no** version segment; the body is the file | 201 with the canonical key and `x-fm-version`; 409 if the key names a version or the name is taken under a sibling tag; 403 without `WRITE` on the tag folder |
| DELETE | `/{bucket}/{sub}/{tag}/{file}/v{n}/{file}.{ext}` | 204; removing the last version removes the file |

Not S3-compatible: no Signature V4, no XML, so the AWS CLI and SDKs do not connect (roadmap 9.4).
A runnable client — upload, inspect, download, list, delete, standard library only — is in
[`template/v2_client.py`](../template/v2_client.py); it takes the base URL, the key, the bucket and
the folder from environment variables and never holds a credential in the file.

</details>

## 7. Security model

### Three filter chains

`SecurityConfig` publishes three `SecurityFilterChain` beans:

* **`@Order(0)` `actuatorSecurityFilterChain`** — `securityMatcher("/actuator/**")`, stateless,
  permits `/actuator/health`, `/actuator/health/**` and `/actuator/info` and refuses everything else
  under `/actuator` whether or not it is exposed (roadmap 9.5).
* **`@Order(1)` `apiSecurityFilterChain`** — `securityMatcher("/api/**")`, CSRF off, CORS off,
  `SessionCreationPolicy.STATELESS`. `ApiKeyAuthenticationFilter` runs before
  `BasicAuthenticationFilter`: a request carrying `Authorization: Bearer fmk_…` is resolved through
  `ApiKeyService.authenticate` (hash comparison, enabled, not revoked, not expired) into a principal
  whose `id` is the key's creator and whose `apiKeyId` is set; one carrying Basic credentials goes
  to `httpBasic()` as before; one carrying neither, or a refused credential, gets the chain's single
  401 entry point. The filter never writes a response of its own.
* **`@Order(2)` `securityFilterChain`** — everything else. CSRF **on** (all AJAX templates read
  `_csrf` / `_csrf_header` from `<meta>` tags and set the header), form login at `/login`,
  logout at `/logout`. PermitAll list: `/`, `/favicon.ico`, `/webjars/**`, `/css/**`, `/js/**`,
  `/public-pages/**`, `/files/public-files/**`, `/files/public-download/**`.
  **Two entry points for an unauthenticated request**, chosen by what made it
  (`SecurityConfig.isScriptCall`): a script — `X-Requested-With: XMLHttpRequest`, or an `Accept`
  that asks for JSON and not HTML — gets `401` and no `Location`; a person navigating is sent to
  `/login` as before. The same predicate decides what is *not* remembered for replay after login.
  Before the split, an expired session answered a `fetch` with a redirect the browser followed,
  and the script received the login page's HTML with a `200` (issue 77).

### Authentication

`AuthenticationManagerBuilder` is assembled conditionally on
`filemanagement.auth.ldap.activedirectory.enabled`:

* **off** → `DaoAuthenticationProvider` only (BCrypt against the `user` table).
* **on** → `ActiveDirectoryCustomAuthenticationProvider` first, then `DaoAuthenticationProvider`.
  The AD provider is shaped for a real directory: several controllers as a JNDI fail-over list,
  connect/read timeouts on every bind, an optional PKCS12 truststore handed to JNDI through
  `LdapTrustStoreSocketFactory` (self-signed controller certificates, pinned), and a hostname
  check that can be switched off only when that pin exists. `deployment.md`, "Active Directory
  behind a load balancer"; `ActiveDirectoryConnectionTest` exercises the trust with a real
  handshake.

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
* A grant is a row in `role_folder` or `user_folder` naming a folder and a verb, and it covers
  everything beneath that folder. `folder.path` is a materialised path of ids with a leading and
  trailing slash (`/1/5/26/`), so "is this inside that grant?" is a prefix test and an indexed range
  scan.
* **The verb is `READ` or `WRITE`, and `WRITE` implies `READ`** (roadmap 9.1). One ordered column
  rather than two flags, so "may write but may not read" cannot be represented and nothing has to
  decide what it would mean. `FolderAccess` keeps two reduced path lists, and every write path is in
  the readable one as well — but they are reduced independently, because a `READ` on a parent must
  not swallow a `WRITE` on its child.
* `FolderAccessService.accessFor(principalId)` resolves it once per call into a `FolderAccess`; the
  `ADMIN` role is unrestricted and reads no grant rows at all.
* **An API key is scoped to its own folders, not to its creator's** (roadmap 9.2). A request
  authenticated with `Authorization: Bearer fmk_…` carries a principal whose `id` is the person
  who created the key — so `principalId` and `action_history` are unchanged — and whose
  `apiKeyId` sends `accessFor` to `api_key_folder` instead. There is no administrator shortcut
  on that path: a key reaches what it was granted however powerful its creator is.
* **Readable and traversable are different.** A grant can sit in the middle of the tree, and the
  holder has no right to the folders above it — but hiding those would leave no route down to what
  they do have. So an ancestor of a grant is shown and can be opened, revealing only the branch that
  leads to the grant; its other branches and any file content stay hidden. `canRead` answers the
  first question, `isOnPathTo` the second, and `visible` is their union.
* Enforcement covers the tree, the folder listing and search behind the explorer, the file list
  (pushed into the query, so paging counts stay honest), the file page, both download endpoints and
  — since roadmap 9.1 — every upload path, which is what closed issue 76. The `permitAll` public
  download is deliberately outside it.
* **The tree addresses a node by its folder id**, not by the taxonomy row behind it — including the
  ids a search hit reports. The taxonomy id stays inside `FileTreeService`. A file is the one
  exception: it has no folder until roadmap 6.8, so it is still addressed by its own id and
  authorised through its tag.

**It is off by default.** `filemanagement.folder-access.enabled` is `false`, because turning it on
before any grant exists empties the tree for every non-administrator. Grants are set on the role
edit page, one three-state control per folder — no access, read, or read and write. With the flag
off, `accessFor` answers "unrestricted" for everyone.

With the flag off, holding `DOWNLOAD_FILE` still grants download of every file, private ones
included (issue 14) — the endpoint permission is then the only check there is.

### Bootstrap

`BootstrapConfig`'s runner runs only when `spring.profiles.active=prod`. `DataInitializer`
inserts any missing `PermissionEnum` value, creates the `ADMIN` and `USER` roles, and creates the
`Admin` account if absent (password from `filemanagement.bootstrap.admin-password`, or generated
and logged once). `FolderReadinessReport` then asks the Phase 7 step 4 pre-flight queries -
files without a folder, files whose folder is not their tag's mirror, files whose tags disagree
with the taxonomy, names shared within a folder - and logs one line, INFO when every figure is
zero and WARN with the figures otherwise. It changes nothing.

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
       ├─ @Validated(InsertValidation) → @ValidFile asks ContentTypes (extension allow-list + first bytes)
       └─ FileService.createNewFile(dto, principalId, publicFile)          @Transactional
            ├─ MainTagFileService.getMainTagFileByIdOrTagName
            ├─ verify tag.subCategory / tag.subCategory.category match the form
            ├─ isDuplicate(baseName, subCategoryId)
            ├─ ValidationUtil.checkCorrectFileName
            ├─ build FileInfo (paths, state, lastVersion = 1)
            ├─ build FileDetails v1 (hashId = random UUID, storageKey, content_type = ContentTypes.detect)
            ├─ fileInfoRepository.save(fileInfo)          ← cascades to FileDetails
            ├─ actionHistoryService.saveActionHistory × 2
            └─ fileStorageService.saveByKey(fileDetails.storageKey, multipartFile)  ← disk write, LAST
```

The disk write happens inside the transaction but is not part of it — see
[issues.md](issues.md#3-storage-writes-are-not-atomic-with-the-database--s1).

## 10. Database schema

The tables as they stand after every migration — columns, keys, indexes — are in
[schema.md](schema.md), generated from the migrated database and checked on every build. The
migrations themselves, in `src/main/resources/db/migration`:

| Version | Contents |
|---|---|
| `V1.0__Initial_Setup.sql` | `user`, `role`, `user_role`, `permission`, `permission_role`, `general_tag`, `file_category`, `file_sub_category`, `main_tag_file`, `file_info`, `file_details` |
| `V1.1__Add_LoginType_To_User.sql` | `user.login_type INT NOT NULL DEFAULT 0 AFTER updated_at` |
| `V1.2__Add_Action_History_Table.sql` | `action_history` |
| `V1.3__Add_Uniqueness_And_Indexes.sql` | the composite unique constraints the services check in Java, and indexes on the filtered columns |
| `V1.4__Add_Folder_Mirror.sql` | `folder`, plus the backfill that mirrors every category, sub-category and main tag into it |
| `V1.5__Add_Folder_Grants.sql` | `role_folder`, `user_folder` |
| `V2.0__Add_Permission_To_Folder_Grants.sql` | `permission` (READ / WRITE) on both grant tables |
| `V2.1__Add_Api_Keys.sql` | `api_key`, `api_key_folder` |
| `V2.2__Add_Storage_Key_To_File_Details.sql` | `file_details.storage_key`, backfilled from `relative_path` (roadmap 7.1) |
| `V2.3__Add_Folder_To_File_Info.sql` | `file_info.folder_id`, nullable, indexed, backfilled to the folder mirroring the file's main tag; written on every upload, read by the v2 API since step 3 (roadmap 7.2 step 1) |
| `V2.4__Add_Tags.sql` | `tag_group` (one per general tag), `tag` (unique per group), `file_tag`; backfilled from the three levels beneath each general tag, names merging within a group; re-runnable (roadmap 7.2 step 2) |
| `V2.5__Normalise_Content_Type.sql` | data only: `file_details.content_type` rewritten from the extension for the nine accepted kinds, so the column holds the server's word rather than the client's (issues 12, 13) |

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

`./mvnw test` runs 285 tests and needs only a working Docker daemon: `MySqlSupport` starts one
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
2. `FileStorageService`'s directory half is filesystem-shaped; its key half (roadmap 7.1) is
   the part an object store can implement.
3. Storage and database mutations are not atomic in either direction.
4. `@Table(name = "user")` — a reserved word in PostgreSQL.
5. Every `@ManyToOne` is `EAGER`; `ModelConverterUtil` walks the full graph on every list page.
6. Authorization is per-endpoint, never per-resource.
