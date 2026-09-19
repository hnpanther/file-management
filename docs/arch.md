# Architecture — Current State

> Snapshot of the codebase as it exists on branch `redesign-arch` (HEAD `08db773`).
> For where we are going, see [target-architecture.md](target-architecture.md).

## 1. What the application is

A server-rendered file-management web application. Users organise files into a folder tree
of any depth up to a configured limit (six by default), upload them into any folder, and create
additional **versions** and **formats** of the same logical file. Files live on the local filesystem; all metadata lives in MySQL.
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
| Content detection | Apache Tika (`tika-core` only: magic-byte detection for the content-kinds probe; no parsers) | 3.3.2 |
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

Since Phase 7 step 4 (migration `V2.8`) a file's place is one thing: a row in `folder`. The
taxonomy tables that used to sit beside it (`general_tag`, `file_category`, `file_sub_category`,
`main_tag_file`) are gone, and with them the mirror that kept the two in step. Since `V2.9` the
three fixed levels the taxonomy left behind are gone too: a folder is a folder, at any depth.

```
Folder(ROOT "Home")
  └─N─ Folder(FOLDER, depth 1) ──N:1──> TagGroup     the "general tag": a label group, not a place
        └─N─ Folder(FOLDER, depth 2)
              └─N─ … down to filemanagement.folders.max-depth (6)
                    └─N─ FileInfo ──1:N──> FileDetails      files sit in any folder below the root
                          └─N:M─> Tag ──N:1──> TagGroup
```

* **Folder** — one table, one tree, `parent_id` for structure and `path` as a derived index: a
  materialised path of ids with a leading and trailing slash (`/1/5/26/`), built from ids so a
  rename or a move costs nothing, and carrying the trailing slash so `/1/7/` cannot match
  `/1/70/`. `kind` is `ROOT` (one row, `Home`), `FOLDER` for everything below it, or `USER_HOME`
  (reserved for Phase 8); `depth` is the level, and the only thing that varies with it. Every
  folder below the root holds folders and files alike, down to
  `filemanagement.folders.max-depth` — a limit for people, not for the code. `name` is
  directory-safe (no `.`, no space, no `/`, at most 100 characters, unique among siblings,
  case-insensitively) and one name is reserved at the top level, `folders`, the directory the
  storage layout lives under; `display_name` is what a person reads.
* **TagGroup** — what the old *general tag* was: a grouping label carried by a top-level folder
  (`folder.tag_group_id`, required at depth 1, absent deeper). It is **not a folder**, has no
  directory and no level in the tree; creating a top-level folder names an existing group or a
  new one, a rename may change it, and nothing deeper may carry one. Managed on
  `/settings/tag-groups` (`TagGroupService`): name, title, and a delete refused while a folder
  carries the group or a tag sits in it.
* **FileInfo** — the *logical* file (e.g. "the Q3 report"). `folder_id` is `NOT NULL` and names
  any folder but the root (`FileService.targetFolderOf`). Holds `last_version` and the
  visibility `state`.
* **FileDetails** — one *concrete artefact*: a specific (version, format) pair of a `FileInfo`.
  Carries `file_name`, `file_extension`, `content_type`, `file_size`, `version`, `version_name`,
  and `storage_key` — the one record of where its bytes are.
* **Tag / file_tag** — labels, not places. A file's tags are a *function of its folder chain*:
  `TagMirrorService.tagsFor(folder)` is one tag per folder from the top level down to the file's
  own, in the top-level folder's group, named by the folder name and titled by its label;
  `retag(file)` makes the set exactly that, and `retagFilesUnder(folder)` re-derives the subtree
  on the inputs that change it - a rename, a move, a change of group. Unique by `(group, name)`,
  so two folders on one chain both named `HSED` are *one* tag, carried once.
  `findIdsWhoseTagsDisagreeWithTheFolders` must be empty (`FileServiceTest`, `FolderServiceTest`).

### The chain of a file

`FolderService.ancestryOf(folders)` reads, for a whole page of files at once, every folder from
the top level down to each file's own — one `findAllById` over the ids their paths name, which is
what the materialised path is for, since a chain of any depth cannot be fetch-joined. Everything
that used to read three fixed levels reads the chain: the `folderPath` / `folderTitle` on
`FileInfoDTO` and `PublicFileDetailsDTO` (the labels joined with ` / `), the breadcrumb of a
search hit in the tree, and the tags. Queries that a converter will walk fetch the file's folder
(`JOIN FETCH f.folder`) and search across the ancestors with a path-prefix `EXISTS`.

### Where a file's name is unique

Per **folder** — `uq_file_info_name_per_folder`, and since `V2.9` the storage layout agrees:
every file's bytes live under `folders/{folder id}/`, so the same name under a sibling folder is
another file in another directory (section 5). `FileService.isDuplicate` is the friendly error;
the constraint is the guarantee.

### Managing the tree

`FolderService` is the one writer of `folder`: `create(parentId, …)`, `rename`, `move`,
`delete`, each needing `WRITE` on the folder concerned (the parent for a create and a delete,
both parents for a move) and each writing an `action_history` row. A rename changes names,
labels and — at the top level — the group; a move rewrites `parent`, `depth` and `path` for the
whole subtree in one transaction, refuses the folder's own subtree, the depth limit and a taken
sibling name, and carries the tag group across (a folder moved to the top level keeps the group
it came from; a top-level folder moved beneath another loses its own). Neither moves a byte or
rewrites a key — that is what `storage_key` is for — and both re-tag the subtree. A delete takes
an empty folder only (409 while it holds folders or files) and its grants go with it
(`ON DELETE CASCADE`). The root and a `USER_HOME` are neither renamed, moved nor deleted. The
explorer is the screen for all four (section 6).

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
  `FileInfoRepository.search` returns a `Page` with the whole folder chain attached;
* fetching a collection cannot be paginated in SQL, so those queries return a single row
  (`findByIdAndFetchFileDetails`) and use `DISTINCT`.

`spring.jpa.open-in-view` is **off**. With it on, a lazy association touched during template
rendering silently issues a query from the view layer, which is an N+1 invisible in the service
code. With it off, anything a page needs must be fetched inside a `@Transactional` service method —
which is why no service returns an entity.

**Children are read by query, not through the parent's collection.** `FolderRepository.findChildrenWithTagGroup`,
`countChildFoldersByParent` and `FolderService.delete`'s emptiness checks query the child
tables directly. Reading a parent's collection answers from the persistence context, which can
hand back a collection initialised earlier in the same transaction when it was empty — a folder
that had just gained a child looked empty, and a delete check passed.

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
├── folders/                            every file uploaded since V2.9 (saveByKey creates parents)
│   └── {folder id}/
│       └── {fileNameWithoutExtension}/
│           └── v{version}/
│               └── {fileName}.{ext}
└── {category name}/                    files stored before V2.9, under the names of the two
    └── {sub-category name}/            folders above them as they stood when written
        └── {fileNameWithoutExtension}/
            └── v{version}/
                └── {fileName}.{ext}
```

By folder *id* so that renaming or moving any folder above a file changes nothing on disk; the
old layout stays where it is, because a key records where the bytes went and nothing rebuilds it.
A later version of a file goes beside its first version whichever layout wrote that
(`FileService.directoryOf`, read off the existing key). The two layouts share one root, which is
why no top-level folder may be named `folders`: `FolderService` refuses the name and `V2.9`
refuses to run where one exists.

The interface now has two halves, and which one a caller uses is not a matter of taste.

**Key-shaped, for one stored object** (roadmap 7.1). The whole location is a single opaque
string — the value in `file_details.storage_key`:

```java
void     saveByKey(String storageKey, MultipartFile file);
Resource loadByKey(String storageKey);
void     deleteByKey(String storageKey);
```

Every read and write of a single file goes through these, so **where the bytes are is what was
recorded when they were written**, not something rebuilt from the folder names at read time. That
is what lets a folder be renamed without moving a byte or orphaning a file (`StorageKeyTest`).

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

`address` is the directory of a file or of one of its versions, and it is **derived from a stored
key** (`FileService.directoryOf`: the grandparent directory of the first revision's key), never
from the folder names. Because the signature bakes in "directory + version + extension", it
cannot express an object-store key without change — this is the first thing the S3 work has to
fix.

Name rules enforced at the storage boundary: directory names must contain **zero** of `.`, ` `, `/`,
applied to every segment of an address; file names must contain **exactly one** `.` and zero of
` `, `/`.

### What each operation touches

Three things describe where a file is, and they are deliberately independent: the **tree**
(`folder.parent_id`, `folder.depth`, and `folder.path`, a materialised path of ids such as
`/1/5/412/`), the **key** (`file_details.storage_key`, one per stored version, e.g.
`folders/412/report/v2/report.pdf`), and the **bytes** (the file under `base-dir` at exactly the
key's relative path). The key is written once, when the version is stored, and is the only thing
a read ever consults; the tree is what people navigate; the bytes follow the key. The table is
exhaustive — an operation not listed here (changing a description, a state, a permission) touches
none of the three.

| Operation | Tree (`folder` rows) | Keys (`storage_key`) | Bytes on disk | Tags (`file_tag`) |
|---|---|---|---|---|
| **Upload a new file** (`FileService.createNewFile`; web form, v1, v2 `PUT`) | — | one new key, `folders/{folder id}/{name}/v1/{name}.{ext}` | one file written at that path; `saveByKey` creates the directories and refuses an existing path | derived: one tag per folder from the top level down, in the top-level folder's group |
| **New version / new format of a file** (`createNewFileDetails`) | — | one new key **beside the first version's**: the directory is read off that key (`directoryOf`), so a file stored under the old `{category}/{sub}` layout keeps growing there, one stored under `folders/{id}` there | one file written; nothing else moves | — |
| **Delete one version or format** (`deleteFileDetails`) | — | that row's key gone | that file removed; when it was the last format of its version, the `v{n}` directory too | — |
| **Delete a file** (`deleteCompleteFileById`, or deleting its last version) | — | every key of the file gone | the file's whole directory (`…/{name}/`) removed, read off a stored key; the folder's directory (`folders/{id}/` or `{category}/{sub}/`) stays, possibly empty | rows cascade |
| **Create a folder** (`FolderService.create`) | one row: `parent_id`, `depth = parent + 1`, `path = parent.path + id + "/"` | — | **nothing** — a folder has no directory until its first upload | — |
| **Rename a folder** (`rename`: name, label, or at the top level the group) | that row's `name` / `display_name` / `tag_group_id`; `path` and `depth` unchanged (they are ids) | **nothing** | **nothing** — a file stored under the old layout keeps its old directory name; one stored under `folders/{id}` never had the name in it | re-derived for every file beneath, when the name or the group changed |
| **Move a folder** (`move`) | the folder's `parent_id`; `depth` and `path` **rewritten for the whole subtree** (`/1/5/412/…` → `/1/9/412/…`) in one transaction; `tag_group_id` set to the former top-level folder's group when the target is the root, cleared when a top-level folder goes below another | **nothing** | **nothing** | re-derived for every file beneath (the chain of names changed, and possibly the group) |
| **Delete a folder** (`delete`; empty only) | that row gone; its grants cascade | — | **nothing** — its `folders/{id}/` directory, if an upload ever created it, is left empty | — |
| **Change a tag group's name or title** (`/settings/tag-groups`) | — | — | — | — (tags hang off the group's id) |

What follows from the table:

* **`folder.path` and `storage_key` are two different things.** The first is an index over the
  tree and changes with every move; the second is an address on disk and never changes. A move of
  a folder holding ten thousand files is a few `UPDATE`s on `folder` and `file_tag` and zero disk
  I/O.
* **The directory tree under `base-dir` is not a mirror of the folder tree**, and it stops being
  one the first time a folder is renamed or moved. For files stored since `V2.9` it never was:
  `folders/412/` says nothing about where folder 412 sits. The database is the only source of a
  file's place; a backup is the database **and** `base-dir` together
  ([deployment.md](deployment.md)).
* **Old layout, new layout, one root.** A file stored before `V2.9` lives under the names its
  two upper folders had when it was written and stays there through every rename and move;
  every later version of it goes beside it. A file stored since lives under its folder's id. The
  only place the two could meet is a top-level folder literally named `folders`, which
  `FolderService` refuses and `V2.9` checks for. Nothing relocates the old files
  ([issue 81](issues.md#81-base-dir-now-holds-two-layouts-side-by-side--s3-by-design-recorded)).
* **Deleting removes the file's own directory and nothing above it.** An emptied
  `folders/{id}/` or `{category}/{sub}/` is left on disk. That is deliberate: the directory is
  cheap, and removing a parent would mean deciding whether it is "ours", which the old layout
  cannot answer safely.
* **Tags are derived, never stored independently.** Every operation that changes a file's chain
  of folder names re-derives `file_tag` for the files beneath, and
  `FileInfoRepository.findIdsWhoseTagsDisagreeWithTheFolders` is empty after each
  (`FolderServiceTest`, `FileServiceTest`).

## 6. HTTP layers

There are four parallel HTTP surfaces over the same services:

| Package | Base path | Returns | Auth | Purpose |
|---|---|---|---|---|
| `controller/` | `/files`, `/users`, `/roles`, `/api-keys`, `/settings/upload`, `/settings/content-kinds`, `/settings/tag-groups`, `/settings/general`, `/files/explorer`, `/` | Thymeleaf view names | form login, session | the UI |
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
| GET | `/files/public-files` | open to everyone, or to signed-in people only - the `public-files.anonymous` setting, asked on every request (`PublicFilesAuthorizationManager`); no permission beyond being signed in |
| GET | `/files/public-download/{id}` | the same switch (`?inline=1` is honoured only for `ContentTypes.inlineSafe` kinds; every download carries `nosniff` and a `default-src 'none'` CSP) |
| GET / POST | `/settings/general` | `GENERAL_SETTINGS_PAGE`, `SAVE_GENERAL_SETTINGS` |
| GET | `/files/file-info`, `/files/file-info/{id}` | `GET_ALL_FILE_INFO_PAGE`, `FILE_INFO_PAGE` |
| GET | `/files/file-info/{fileInfoId}/file-details/{fileDetailsId}/download` | `DOWNLOAD_FILE` |
| GET / POST | `/files/file-info/{fileInfoId}/file-details/create`, `.../file-details` | `SAVE_NEW_FILE_DETAILS_PAGE`, `SAVE_NEW_FILE_DETAILS` |
| GET / POST | `/users/**`, `/roles/**` | one permission per handler |

</details>

<details>
<summary>REST — /resource/** (session)</summary>

| Method | Path |
|---|---|
| GET | `/resource/folders/children?folderId=&page=&size=`, `/resource/folders/{id}` (one folder's details: trail, group, direct and total counts, audit), `/resource/folders/search?query=&folderId=` (folders by id / name / label as `folders`, at most 20; files paged as `hits`) (`REST_GET_FOLDER_CONTENT` / `REST_SEARCH_FOLDER_CONTENT`, or `FILE_EXPLORER_PAGE`) |
| GET | `/resource/folders/tag-groups` (`REST_GET_TAG_GROUPS` or `REST_CREATE_FOLDER`) |
| POST | `/resource/folders` `{parentId, name, displayName, tagGroupId | newTagGroupName}` → 201 (`REST_CREATE_FOLDER`; under the root a group is needed, deeper none is taken; 400 past the depth limit) |
| PUT | `/resource/folders/{id}` `{name, displayName, tagGroupId?}` (`REST_RENAME_FOLDER`; the group only at the top level) |
| PUT | `/resource/folders/{id}/move` `{parentId}` (`REST_MOVE_FOLDER`; 400 into itself, past the depth limit; 409 on a taken name) |
| DELETE | `/resource/folders/{id}` → `{"outcome":"DELETED","resource":"folder"}`, 409 while not empty (`REST_DELETE_FOLDER`) |
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
| POST | `/` (multipart, `?public-file=0` for private; the place is `folderId`, the id of any folder below the root — a request without it is a 400 naming the parameter; the pre-step-4 triple is ignored) | `API_SAVE_NEW_FILE` |
| DELETE | `/file-info/{fileInfoId}/file-details/{fileDetailsId}` | `API_DELETE_FILE_DETAILS` |
| DELETE | `/file-details/{fileDetailsId}` (the same delete by the version's id alone) | `API_DELETE_FILE_DETAILS` |
| GET | `/file-info/{fileInfoId}/file-details/{fileDetailsId}/download` | `API_DOWNLOAD_FILE` |
| GET | `/file-details/{fileDetailsId}/download` (the same download by the version's id alone) | `API_DOWNLOAD_FILE` |

The id-only forms with `folderId` on the upload are the contract an integration keeps since Phase 7
step 4: nothing in them names anything but a folder and a version. Both deletes are judged on the file's own folder
(`requireWriteAccess` on the `FileInfo`), the same way a download and a new version are. The
whole group accepts either credential: the shared account's Basic password, or a Bearer API key,
which reaches its own folders only.

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
| GET | `/{bucket}/{folders…}/{file}/v{n}/{file}.{ext}` (zero or more folder segments below the bucket) | 200 bytes with `ETag`, `Last-Modified`, `x-fm-version`; 206 for a `Range`; 404; 403 |
| GET | `…?metadata`, or `HEAD` on the URL above | 200 metadata as JSON / headers only |
| PUT | `/{bucket}/{folders…}/{file}/{file}.{ext}` — **no** version segment; the body is the file | 201 with the canonical key and `x-fm-version`; 409 if the key names a version; 403 without `WRITE` on the folder |
| DELETE | `/{bucket}/{folders…}/{file}/v{n}/{file}.{ext}` | 204; removing the last version removes the file |

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

* `folder` is the tree — `Home` and folders beneath it to any depth — written only by
  `FolderService` (section 4).
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
  on that path: a key reaches what it was granted however powerful its creator is. **And a key
  is outside the enforcement flag**: the flag exists so that switching enforcement on cannot
  lock people out before their roles have grants; a key is created with its grants, so its scope
  applies whether the flag is on or off. Since 1.2.0 a key also holds the three v1 file
  authorities (`API_SAVE_NEW_FILE`, `API_DELETE_FILE_DETAILS`, `API_DOWNLOAD_FILE`), so an
  integration may use the v1 id routes with a key instead of the shared account's password and
  reach exactly what it would reach on v2.
* **Readable and traversable are different.** A grant can sit in the middle of the tree, and the
  holder has no right to the folders above it — but hiding those would leave no route down to what
  they do have. So an ancestor of a grant is shown and can be opened, revealing only the branch that
  leads to the grant; its other branches and any file content stay hidden. `canRead` answers the
  first question, `isOnPathTo` the second, and `visible` is their union.
* Enforcement covers the tree, the folder listing and search behind the explorer, the file list
  (pushed into the query, so paging counts stay honest), the file page, both download endpoints and
  — since roadmap 9.1 — every upload path, which is what closed issue 76. The public page and
  download are deliberately outside it: they show files marked public, to everyone or to
  signed-in people, as the `public-files.anonymous` setting says.
* **The tree addresses a node by its folder id** — including the ids a search hit reports; there is
  no other id since step 4. A file is addressed by its own id and authorised through its folder.

**It is off by default.** `filemanagement.folder-access.enabled` is `false`, because turning it on
before any grant exists empties the tree for every non-administrator. Grants are set on the role
edit page, one three-state control per folder — no access, read, or read and write. With the flag
off, `accessFor` answers "unrestricted" for everyone.

With the flag off, holding `DOWNLOAD_FILE` still grants download of every file, private ones
included (issue 14) — the endpoint permission is then the only check there is.

### Run-time settings

`app_setting` (`V2.10`) holds the switches an administrator flips without a restart, one row per
name, read by `AppSettingService` on every use - a lookup by unique key, chosen over a cache
because the one reader that matters is the security chain. The first switch,
`public-files.anonymous`, decides whether `/files/public-files` and `/files/public-download/**`
answer a visitor who is not signed in: `PublicFilesAuthorizationManager` sits on those two
paths in the browser chain in place of the `permitAll` they carried, grants everyone while the
switch is on, and otherwise grants only an authenticated, non-anonymous principal - so a
visitor is sent to the login form like any other protected page, and the login form drops its
"public files" link while the switch is off. Edited on `/settings/general`
(`GENERAL_SETTINGS_PAGE` / `SAVE_GENERAL_SETTINGS`); every change is an `action_history` row.

### The upload policy

Which kinds of file may be uploaded, and how large each may be, is a setting rather than a
constant: `upload_policy` and `upload_policy_rule` (`V2.6`), edited on `/settings/upload`
(`UPLOAD_POLICY_PAGE`, `SAVE_UPLOAD_POLICY`) for the whole system, and on a role's edit page for
that role alone. The rules:

* **What is on offer is the catalogue, never more.** `ContentTypes` lists the kinds it can
  recognise from their first bytes; the policy chooses among them and sets a size. A kind the
  application cannot verify cannot be allowed by any setting.
* **A role without a policy of its own is governed by the system-wide one; with one, by that
  alone.** An own policy that lists nothing means the role may upload nothing.
* **Across several roles, the union**: a person may upload what any of their roles allows, up to
  the largest limit any of them gives for that kind — the same way permissions combine. A person
  with no role, and an API key (which holds none), have the system-wide limits. Nobody is exempt,
  the administrator included: being governed by a policy that can only narrow the catalogue costs
  nothing that could otherwise be had.
* **One enforcement point.** `UploadPolicyService.requireAllowed` is called in
  `FileService.newFileDetails`, which every route that stores a file passes through — the form,
  v1, v2, a new version. A refusal is `UploadRefusedException` (a 400 whose `detail` names the
  kind or the size and the limit); the pages say the same in Persian, and the upload form shows
  the person's own limits and restricts the file picker to them.
* **The server's cap is the ceiling.** `spring.servlet.multipart.max-file-size` is enforced by the
  container before any of this; a limit above it is refused on save, and the pages show it.

### The content catalogue

What `ContentTypes` can recognise has two halves. The **built-in** kinds are in code, each with
its signature (`%PDF-`, the PNG header, the ZIP and OLE2 containers, `ftyp`, ...); they cannot be
edited or removed, and they are the only kinds a browser may ever render inline. The **custom**
kinds are rows of `content_kind` (`V2.7`), added on `/settings/content-kinds` (`CONTENT_KIND_PAGE`,
`SAVE_CONTENT_KIND`, `DELETE_CONTENT_KIND`) and registered into the same static registry by
`ContentKindService` at start-up and after every change, so an upload route cannot tell the two
apart: same extension check, same byte check, same served type. A custom kind is an extension, a
media type and a rule - a signature of at least two bytes at an offset, or "text only" (no NUL in
the first block). It is never inline-safe.

The page has a **probe**: hand it a sample and it answers, without storing a byte, with the
extension, what Tika makes of the first bytes (`tika-core`, detection only), the bytes as hex,
whether the catalogue already knows the extension and whether the bytes would pass its rule. The
add-form is prefilled from that answer; what gets stored is what the person submits.

Two things can never become a kind: a built-in extension, and anything a browser would execute
as a document of this origin (`html htm xhtml shtml svg xml xsl xslt js mjs`,
`ContentTypes.isBrowserActive`) - refused by the service and, as a second line, skipped by the
registry. A new kind is on the upload-policy pages from then on, allowed for nobody until ticked;
deleting one also deletes every policy rule that named it, and files already stored under it
keep their rows and are served as `application/octet-stream` attachments.

### Bootstrap

`BootstrapConfig`'s runner runs only when `spring.profiles.active=prod`. `DataInitializer`
inserts any missing `PermissionEnum` value, creates the `ADMIN` and `USER` roles, and creates the
`Admin` account if absent (password from `filemanagement.bootstrap.admin-password`, or generated
and logged once). The pre-flight report that preceded step 4 is gone with the step: `V2.8`
itself refuses to run on a database that would have failed it (section 10).

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
       ├─ @Validated(InsertValidation) → @ValidFile asks ContentTypes (catalogued extension + first bytes)
       └─ FileService.createNewFile(dto, principalId, publicFile)          @Transactional
            ├─ targetFolderOf(dto): folderId → any folder but the root, else 400
            ├─ folderAccessService.requireWriteAccess(access, folder)
            ├─ isDuplicate(baseName, folder) → 409 if taken in this folder
            ├─ ValidationUtil.checkCorrectFileName
            ├─ build FileInfo (folder, state, lastVersion = 1); tagMirrorService.retag(fileInfo)
            ├─ build FileDetails v1: UploadPolicyService.requireAllowed(principal, file) → kind and size for this person
            │                        then hashId = random UUID, storageKey, content_type = ContentTypes.detect
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
| `V1.0__Initial_Setup.sql` | `user`, `role`, `user_role`, `permission`, `permission_role`, `general_tag`, `file_category`, `file_sub_category`, `main_tag_file` (the four dropped by `V2.8`), `file_info`, `file_details` |
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
| `V2.6__Add_Upload_Policy.sql` | `upload_policy` (one system-wide row, `role_id` null; one per role that has its own), `upload_policy_rule` (extension → `max_size_bytes`); the system-wide row seeded with the nine default kinds at 20 MB |
| `V2.7__Add_Content_Kind.sql` | `content_kind`: the custom half of the content catalogue - extension, media type, and a byte signature at an offset or "text only"; empty until an administrator adds one |
| `V2.10__Add_App_Setting.sql` | `app_setting` (name → value, audited), seeded with `public-files.anonymous = true`, the behaviour there always was; `GENERAL_SETTINGS_PAGE` and `SAVE_GENERAL_SETTINGS` |
| `V2.9__Folders_Any_Depth.sql` | Folders of any depth: refuses to run where a top-level folder or a stored key is named `folders`; `CATEGORY` / `SUB_CATEGORY` / `TAG` become `FOLDER`; `REST_MOVE_FOLDER` (mapped onto the roles that may rename) and the three `TAG_GROUP` page permissions |
| `V2.8__Remove_Taxonomy.sql` | Phase 7 step 4. Fails fast first: `file_info.folder_id NOT NULL`, `uq_file_info_name_per_folder`, `folder.tag_group_id` backfilled from each category's general tag and required on every `CATEGORY` row. Then the four `REST_*_FOLDER` / `REST_GET_TAG_GROUPS` permissions, mapped onto the roles that held the taxonomy ones; the 27 taxonomy permissions deleted; `file_info` / `file_details` lose `file_path`, `relative_path`, `file_sub_category_id`, `main_tag_file_id`; `folder` loses `general_tag_id`, `source_type`, `source_id`; `main_tag_file`, `file_sub_category`, `file_category`, `general_tag` dropped. Not reversible without the backup |

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
| `file.management.base-dir` | `./TempFiles/files/main/` | `FileStorageFileSystemService` |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | `20MB` | |
| `filemanagement.default.page-size` / `element-size` | `30` | injected per-controller with `@Value` |
| `filemanagement.folders.max-depth` | `6` | `FolderService`: how deep the tree may go below `Home`; a create or a move past it is a 400 |
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
`new SomeService(entityManager, repository, actionHistoryService)` — which produces an object
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
