# Architecture — Current State

> Snapshot of the codebase as it exists on branch `redesign-arch` (version 1.5.0, HEAD `ac5ac68`).
> For where we are going, see [target-architecture.md](target-architecture.md).

## 1. What the application is

A server-rendered file-management web application. Users organise files into a folder tree
of any depth up to a configured limit (six by default), upload them into any folder, and create
additional **versions** and **formats** of the same logical file; each user may have a folder of
their own, with a quota, and anyone who may read a file can hand out a temporary link to one of
its revisions that works without a sign-in. Files live on the local filesystem; all metadata
lives in MySQL. A small machine-facing REST API (`/api/v1/files`) was added later for
programmatic upload, download and delete, and an S3-style one (`/api/v2`) after it.

## 2. Technology stack

| Concern | Choice | Version |
|---|---|---|
| Language | Java | 25 (since 1.4.0; 21 before) |
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
├── service/                       business logic
├── storage/                       the BlobStore port and its filesystem adapter
├── repository/                    Spring Data JPA interfaces + two hand-written JdbcClient DAOs
├── entity/                        JPA entities and the ActionEnum/EntityEnum/PermissionEnum enums
├── dto/                           form-binding, paging and response DTOs
├── exception/                     custom exceptions + two @ControllerAdvice handlers
├── util/                          ModelConverterUtil (entity→DTO), GlobalGeneralLogging
└── validation/                    ContentTypes (the catalogue), validation groups, ValidationUtil
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
  `/1/70/`. `kind` is `ROOT` (one row, `Home`), `FOLDER` for everything below it, `PROFILES`
  (one row, `Home/Profiles`, `V2.11`) or `USER_HOME` (a user's own folder under it, carrying
  `owner_user_id`); `depth` is the level, and the only thing that varies with it. Every
  folder below the root holds folders and files alike, down to
  `filemanagement.folders.max-depth` — a limit for people, not for the code — except
  `Profiles`, which holds homes and nothing else. `quota_bytes` (`V2.11`) caps the bytes of
  every revision beneath the folder, null for none — see "Personal folders and quotas". `name` is a safe
  path segment (`ValidationUtil`: no separator, none of `<>:"|?*`, no control character, not a
  dot-name, no trailing dot or space, not a Windows-reserved name — spaces, dots and Persian
  are fine), at most 100 characters, unique among siblings case-insensitively, and one name is
  reserved at the top level, `files`, the directory the storage layout lives under;
  `display_name` is what a person reads.
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

Per **folder** — `uq_file_info_name_per_folder`, and since `V2.9` the storage layout cannot
disagree: every file's bytes live under `files/{its own id}/`, so a namesake anywhere is another
file in another directory (section 5). `FileService.isDuplicate` is the friendly error; the
constraint is the guarantee. A file name itself is a safe segment with an extension
(`ValidationUtil.checkCorrectFileName`: at least one dot, the extension letters and digits;
spaces, inner dots and Persian are fine).

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

**Deleting a folder with everything in it** (roadmap 10.3) is `FolderTreeDeleteService`, a
class of its own behind a permission of its own (`REST_DELETE_FOLDER_TREE`, on
`DELETE /resource/folders/{id}?recursive=true`), so that pruning empty folders never implies
erasing a subtree. One transaction: every file's rows through `FileService.deleteFileRows`
(each with its own audit row, its bytes' address kept back), then the folders deepest first
(grants cascade), then the bytes, then one audit row for the tree with its totals — so a
database failure anywhere rolls back with the disk untouched, and the bytes are removed
best-effort once the rows are gone: a missing directory is nothing to remove, a failure is
logged, counted in the audit row and left as an orphan directory, never thrown, because an
exception there would restore rows whose bytes were already erased. The single-file delete
follows the same rule for a directory that is already gone. `WRITE` on the parent, as
for the empty delete (grants are path prefixes, so that covers the tree). The cap
`filemanagement.folders.max-delete-files` (default 1000) refuses a larger tree with a 409 that
names the count: a bound on one transaction and one pass over the disk, not a quota; a larger
tree is deleted in parts. The explorer's confirmation names the totals from the folder's
details (`totalFolders`, `totalFiles`).

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

`FilesystemBlobStore` is the only implementation of `BlobStore` (roadmap 2.2). It takes the
storage root from `FileManagementProperties` and resolves every key against it.

```
{base-dir}/
├── files/                              every file uploaded since V2.9 (saveByKey creates parents)
│   ├── {shard}/                        since 1.5.0: "s" + file id / 1000, three digits at least
│   │   └── {file id}/                     files/s000/123, files/s001/1234, files/s999/999999,
│   │       └── {fileNameWithoutExtension}/  files/s1000/1000000 - the width grows past a million
│   │           └── v{version}/
│   │               └── {fileName}.{ext}
│   └── {file id}/                      1.4.0: the same, without the shard
│       └── {fileNameWithoutExtension}/v{version}/{fileName}.{ext}
└── {category name}/                    files stored before V2.9, under the names of the two
    └── {sub-category name}/            folders above them as they stood when written
        └── {fileNameWithoutExtension}/
            └── v{version}/
                └── {fileName}.{ext}
```

By the file's *own id* so that nothing above it — a folder renamed or moved, the file itself
moved — changes anything on disk, and a file that leaves a folder leaves nothing behind for a
namesake to collide with; the older layouts stay where they are, because a key records where
the bytes went and nothing rebuilds it. A later version of a file goes beside its first version
whichever layout wrote that (`FileService.directoryOf`, read off the existing key). The
layouts share one root, which is why no top-level folder may be named `files`: `FolderService`
refuses the name and `V2.9` refuses to run where one exists.

The shard (`StorageLayout`, roadmap 10.1) is for what surrounds the application, not for it:
no code lists `files/`, and NTFS finds one entry among a million without trouble — but
Explorer, `dir`, a backup job and a virus scanner all crawl on a directory with a million
children. A thousand directories of at most a thousand is what they can walk. `StorageLayout`
is the one place the shape is written; it is never read back, so files stored flat by 1.4.0
keep their keys and their place. The `s` on the shard is what keeps the two id-based layouts
apart on disk: a flat directory is a bare id, so a shard named `123` would *be* file 123's
directory, and deleting that file would take the shard — a thousand other files — with it.

### The storage port

One interface, one shape, and it is the shape an object store can also take (roadmap 2.2):

```java
StoredBlob put(StorageKey key, InputStream data);   // never overwrites; returns size + SHA-256
Resource   open(StorageKey key);
boolean    exists(StorageKey key);
void       delete(StorageKey key);
void       deleteDirectory(String prefix);          // everything under a prefix
```

A `StorageKey` is the whole location as one opaque string — the value in
`file_details.storage_key` — and it is written once, when the bytes are stored, never rebuilt
from the folder names at read time. That is what lets a folder be renamed or moved, and a file
be moved between folders, without touching a byte (`StorageKeyTest`). The key's own rule is the
one every backend shares: a relative path, no leading slash, no backslash, no empty segment, no
`.` or `..`. Two spellings of one object would otherwise exist, and on a filesystem some of
them would name somewhere else entirely.

**The boundary.** `FilesystemBlobStore.within(relative)` turns a key into an absolute path: the
root is resolved and normalised, the key is resolved beneath it and normalised (which folds
`..`), and the result must still start with the root and must not *be* the root. Anything else
is refused before a filesystem call, whatever the caller spelled (issues 4 and 16). The key rule
and this one both stay: a key can be well formed and still name somewhere it may not.

**`StoredBlob`** is what the store actually wrote — the byte count and the SHA-256 of the bytes
that streamed past, computed during the write rather than taken from the caller. `FileService`
records both on the revision: `file_details.checksum_sha256` (since 1.8.0) and `file_size` as
stored. Phase 4 is what needs the checksum, since copying bytes between two stores is only
verifiable with it (issues 6 and 7). Revisions stored before 1.8.0 get theirs from
**`ChecksumBackfill`**: after the application is ready, on a virtual thread of its own, it reads
each revision that has none, a batch at a time in id order, and writes the digest only where the
column is still null and the key is still the one it read (`recordChecksum`) - no transaction is
held while the bytes stream. A revision whose bytes are missing is logged and left null; the run
ends with a summary line, and a restart resumes with what is still null
(`filemanagement.storage.checksum-backfill-enabled`).

**The contract** every implementation keeps is written once, in `BlobStoreContractTest`: what
`put` refuses, what a missing key answers, what a directory delete takes with it, which keys are
refused. A second store — the S3 adapter of Phase 4 — is finished when it passes that class.

### Writing bytes inside a transaction

An upload is one transaction for the database and no transaction at all for the disk. Nothing
makes those two agree by itself, so the agreement is arranged, in `StorageWriter` — the only way
into `put` (roadmap 2.3, [issue 3](issues.md#3-storage-writes-are-not-atomic-with-the-database--s1)):

```
StorageWriteJournal.begin(key)   ← its own transaction: commits before the bytes exist
BlobStore.put(key, bytes)        ← the final key, not a staging one
registerSynchronization(...)     ← afterCompletion:
                                     rolled back → delete the bytes, clear the note
                                     committed   → clear the note
                                     unknown     → leave both; the sweeper decides
```

`file_storage_write` (`V2.13`) is that note, and it is the one record that outlives the
transaction whose outcome is in doubt. What stays in it is what nobody was alive to clear — the
process was killed between the write and the commit — and `StorageSweeper` settles each note
older than `unfinished-after-minutes` against `file_details.storage_key`: a key a revision claims
keeps its bytes, a key nothing claims loses them. It is the application's only scheduled job
(`SchedulingConfig`), and it reads notes rather than walking the storage root, so its cost is the
number of uploads in flight and not the number of files stored.

**The bytes go to their final key**, not to a staging key promoted on commit. A promotion has a
window of its own, between the rename and the commit, so it moves the problem rather than
removing it; it would make a stored file unreadable until its transaction committed; and the
failure it is meant to cover is the one the journal covers anyway. The window that remains is a
commit that fails after `afterCompletion` could not run at all — a killed process — which is
exactly what the sweeper is for.

**Deletes are not deferred.** A whole-file delete, a revision delete and a tree delete all send
every row change to the database first (`flush`), and only then remove bytes, best effort: a byte
that cannot be removed is logged and counted into the audit row, never thrown, because undoing
the rows after earlier files had already lost their bytes would make every retry erase more. What
that leaves is a commit failing after the bytes are gone; the alternative — removing bytes after
the commit — cannot report what it failed to remove to the operation that asked for it, and cannot
be observed by any test that rolls back. The trade is recorded here on purpose.

### What each operation touches

Three things describe where a file is, and they are deliberately independent: the **tree**
(`folder.parent_id`, `folder.depth`, and `folder.path`, a materialised path of ids such as
`/1/5/412/`), the **key** (`file_details.storage_key`, one per stored version, e.g.
`files/9081/report/v2/report.pdf`), and the **bytes** (the file under `base-dir` at exactly the
key's relative path). The key is written once, when the version is stored, and is the only thing
a read ever consults; the tree is what people navigate; the bytes follow the key. The table is
exhaustive — an operation not listed here (changing a description, a state, a permission) touches
none of the three.

| Operation | Tree (`folder` rows) | Keys (`storage_key`) | Bytes on disk | Tags (`file_tag`) |
|---|---|---|---|---|
| **Upload a new file** (`FileService.createNewFile`; web form, v1, v2 `PUT`) | — | one new key, `files/{shard}/{file id}/{name}/v1/{name}.{ext}` (`StorageLayout`) | one file written at that path; `put` creates the directories and refuses an existing key | derived: one tag per folder from the top level down, in the top-level folder's group |
| **New version / new format of a file** (`createNewFileDetails`) | — | one new key **beside the first version's**: the directory is read off that key (`directoryOf`), so a file stored under the old `{category}/{sub}` layout keeps growing there, one stored flat under `files/{id}` there, one under a shard there | one file written; nothing else moves | — |
| **Delete one version or format** (`deleteFileDetails`) | — | that row's key gone | that file removed; when it was the last format of its version, the `v{n}` directory too | — |
| **Delete a file** (`deleteCompleteFileById`, or deleting its last version) | — | every key of the file gone | read off a stored key: under an id-based layout the file's own id directory (`files/{shard}/{id}/` or `files/{id}/`) removed whole, the shard directory left; under the old layout the file's `…/{name}/` directory removed and the shared `{category}/{sub}/` left, possibly empty; a directory already gone is nothing to remove, not a refusal | rows cascade |
| **Create a folder** (`FolderService.create`) | one row: `parent_id`, `depth = parent + 1`, `path = parent.path + id + "/"` | — | **nothing** — a folder has no directory until its first upload | — |
| **Rename a folder** (`rename`: name, label, or at the top level the group) | that row's `name` / `display_name` / `tag_group_id`; `path` and `depth` unchanged (they are ids) | **nothing** | **nothing** — a file stored under the old layout keeps its old directory name; one stored under `files/` never had a folder name in it | re-derived for every file beneath, when the name or the group changed |
| **Move a folder** (`move`) | the folder's `parent_id`; `depth` and `path` **rewritten for the whole subtree** (`/1/5/412/…` → `/1/9/412/…`) in one transaction; `tag_group_id` set to the former top-level folder's group when the target is the root, cleared when a top-level folder goes below another | **nothing** | **nothing** | re-derived for every file beneath (the chain of names changed, and possibly the group) |
| **Move a file** (`FileService.moveFile`) | — (the file's `folder_id` changes) | **nothing** | **nothing** — the file's directory is its own id, wherever it is filed | re-derived for the file |
| **Delete a folder** (`delete`; empty only) | that row gone; its grants cascade | — | **nothing** — a folder never had a directory of its own since `V2.9` | — |
| **Delete a folder with everything in it** (`FolderTreeDeleteService.deleteTree`; `?recursive=true`) | every row of the subtree gone, deepest first; grants cascade | every key of every file beneath gone | each file's own directory removed, exactly as a single whole-file delete would, and **last** — after every row, best-effort (a directory that cannot be removed is logged and left, never a rollback); the shard directories and the old layout's shared `{category}/{sub}/` stay | rows cascade with the files |
| **Change a tag group's name or title** (`/settings/tag-groups`) | — | — | — | — (tags hang off the group's id) |

What follows from the table:

* **`folder.path` and `storage_key` are two different things.** The first is an index over the
  tree and changes with every move; the second is an address on disk and never changes. A move of
  a folder holding ten thousand files is a few `UPDATE`s on `folder` and `file_tag` and zero disk
  I/O.
* **The directory tree under `base-dir` is not a mirror of the folder tree**, and it stops being
  one the first time a folder is renamed or moved. For files stored since `V2.9` it never was:
  `files/s009/9081/` says nothing about where file 9081 is filed. The database is the only source of a
  file's place; a backup is the database **and** `base-dir` together
  ([deployment.md](deployment.md)).
* **Three layouts, one root.** A file stored before `V2.9` lives under the names its two
  upper folders had when it was written and stays there through every rename and move; every
  later version of it goes beside it. A file stored by 1.4.0 lives flat under its own id, one
  stored since 1.5.0 under a shard and its id. The only place they could meet is a top-level
  folder literally named `files`, which `FolderService` refuses and `V2.9` checks for. Nothing
  relocates the old files
  ([issue 81](issues.md#81-base-dir-holds-three-layouts-side-by-side--s3-by-design-recorded)).
* **Deleting removes what is the file's own and nothing above it.** Under an id-based layout
  that is the id directory, so a deleted file leaves no empty directory behind — a million
  deletions must not leave a million of them — and the shard directory, which is shared, stays.
  Under the old layout it is the file's `{name}/` directory, and the shared
  `{category}/{sub}/` above it is left, possibly empty: removing it would mean deciding whether
  it is "ours", which that layout cannot answer safely. Which case applies is read off the
  stored key (`StorageLayout.isIdBased`).
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
| GET | `/` | permitAll - it only redirects: to the file list for whoever may see it, to the public files otherwise |
| GET | `/login` | permitAll |
| GET / POST | `/files/create`, `/files` | `CREATE_FILE_PAGE`, `SAVE_NEW_FILE` |
| GET | `/files/public-files` | open to everyone, or to signed-in people only - the `public-files.anonymous` setting, asked on every request (`PublicFilesAuthorizationManager`); no permission beyond being signed in |
| GET | `/files/public-download/{id}` | the same switch (`?inline=1` is honoured only for `ContentTypes.inlineSafe` kinds; every download carries `nosniff` and a `default-src 'none'` CSP) |
| GET / POST | `/settings/general` | `GENERAL_SETTINGS_PAGE`, `SAVE_GENERAL_SETTINGS` |
| GET | `/files/file-info`, `/files/file-info/{id}` | `GET_ALL_FILE_INFO_PAGE`, `FILE_INFO_PAGE` |
| GET | `/files/file-info/{fileInfoId}/file-details/{fileDetailsId}/download` | `DOWNLOAD_FILE` |
| GET / POST | `/files/file-info/{fileInfoId}/file-details/create`, `.../file-details` | `SAVE_NEW_FILE_DETAILS_PAGE`, `SAVE_NEW_FILE_DETAILS` |
| GET / POST | `/users/**` | one permission per handler; `POST /users/{id}/home` creates the user's personal folder (`CREATE_USER_HOME`), `POST /users/{id}/home/quota` sets or clears its quota in megabytes (`SET_FOLDER_QUOTA`). `POST /users/{id}` refuses a changed username unless the principal holds the ADMIN role |
| GET | `/roles`, `/roles/create`, `/roles/{id}` (`?tab=permissions`, `folders` or `upload`) | `GET_ALL_ROLE_PAGE`, `CREATE_ROLE_PAGE`, `UPDATE_ROLE_PAGE` |
| POST | `/roles` (a name only; answers with a redirect to the new role's page) | `SAVE_NEW_ROLE` |
| POST | `/roles/{id}/permissions`, `/roles/{id}/folders` - the edit page's first two tabs, each saved alone, each the complete selection of its tab | `SAVE_UPDATED_ROLE` |
| POST | `/roles/{id}/upload-policy` - the third tab (`GLOBAL`, or `OWN` with the ticked kinds) | `SAVE_UPDATED_ROLE` and `SAVE_UPLOAD_POLICY` |
| POST | `/roles/{id}/copy` (`newRoleName`) - a new role with the source's permissions, folder grants and own upload policy | `COPY_ROLE` |
| GET / POST | `/share/{token}` | permitAll — the landing page, and the download (a `POST`, with the password when the link has one); unknown, expired, revoked and used-up tokens are one 404 |
| GET | `/files/share-links` | `SHARE_LINKS_PAGE` (one's own links; every link with `REVOKE_SHARE_LINK`) |

</details>

<details>
<summary>REST — /resource/** (session)</summary>

| Method | Path |
|---|---|
| GET | `/resource/folders/children?folderId=&page=&size=`, or `?fileId=` for the folder a file is in at the page that lists it - "show in the explorer"; a 400 if both are given (each file entry names its latest revision - of the latest version, the format uploaded last - as `latestFileDetailsId`, for the explorer's download), `/resource/folders/{id}` (one folder's details: trail, group, direct and total counts, audit), `/resource/folders/search?query=&folderId=` (folders by id / name / label as `folders`, at most 20; files paged as `hits`) (`REST_GET_FOLDER_CONTENT` / `REST_SEARCH_FOLDER_CONTENT`, or `FILE_EXPLORER_PAGE`) |
| GET | `/resource/folders/tag-groups` (`REST_GET_TAG_GROUPS` or `REST_CREATE_FOLDER`) |
| POST | `/resource/folders` `{parentId, name, displayName, tagGroupId | newTagGroupName}` → 201 (`REST_CREATE_FOLDER`; under the root a group is needed, deeper none is taken; 400 past the depth limit) |
| PUT | `/resource/folders/{id}` `{name, displayName, tagGroupId?}` (`REST_RENAME_FOLDER`; the group only at the top level) |
| PUT | `/resource/folders/{id}/move` `{parentId}` (`REST_MOVE_FOLDER`; 400 into itself, past the depth limit; 409 on a taken name) |
| DELETE | `/resource/folders/{id}` → `{"outcome":"DELETED","resource":"folder"}`, 409 while not empty (`REST_DELETE_FOLDER`) |
| DELETE | `/resource/folders/{id}?recursive=true` → the same, with every folder, file and byte beneath; 409 above `filemanagement.folders.max-delete-files`, 400 for the root or a home (`REST_DELETE_FOLDER_TREE`) |
| DELETE, PUT | `/resource/files/file-info/{id}`, `.../change-state`, `.../move` `{folderId}` (`REST_MOVE_FILE_INFO`: 400 into the root, 403 without write on both folders, 409 on a taken name) |
| DELETE, PUT | `/resource/files/file-info/{id}/file-details/{fdId}`, `.../change-state/{newState}` |
| PUT | `/resource/users/{userId}/change-enabled`, `.../change-login-type/{type}` |
| POST | `/resource/files/file-details/{id}/share-links` `{minutes?, password?, maxDownloads?}` → 201 `{link, url}`, the token shown this once (`CREATE_SHARE_LINK` and `READ` on the folder; 400 below one minute or download, or without a password under `REQUIRED`) |
| DELETE | `/resource/share-links/{id}` (`CREATE_SHARE_LINK` for one's own, `REVOKE_SHARE_LINK` for anyone's; 403 otherwise) |
| GET | `/resource/files/tree/children?type=&id=` |

</details>

<details>
<summary>REST — /api/v1/files (HTTP Basic)</summary>

| Method | Path | Permission |
|---|---|---|
| GET | `/health-test` | `API_HEALTH_TEST` |
| POST | `/` (multipart; private unless `public-file=1` or `true` - since 1.7.0, before which it was public unless `0`; the place is `folderId`, the id of any folder below the root — a request without it is a 400 naming the parameter; the pre-step-4 triple is ignored) | `API_SAVE_NEW_FILE` |
| DELETE | `/file-info/{fileInfoId}/file-details/{fileDetailsId}` (each id a number or an external id - below) | `API_DELETE_FILE_DETAILS` |
| DELETE | `/file-details/{fileDetailsId}` (the same delete by the version's id alone) | `API_DELETE_FILE_DETAILS` |
| GET | `/file-info/{fileInfoId}/file-details/{fileDetailsId}/download` | `API_DOWNLOAD_FILE` |
| GET | `/file-details/{fileDetailsId}/download` (the same download by the version's id alone) | `API_DOWNLOAD_FILE` |
| GET | `/file-info/{fileInfoId}/download` (`?version=`, `?format=`) - a file by its own id: the latest version, or the one named; a version with several formats and no `format` is a 400 listing them (1.9.0) | `API_DOWNLOAD_FILE` |

The id-only forms with `folderId` on the upload are the contract an integration keeps since Phase 7
step 4: nothing in them names anything but a folder and a version. **Since 1.8.0 every id in these
paths is a number or an external id** - the file's or the revision's `external_id`, a UUID in any
case (`IdReference`, converted like any path variable, so a segment that is neither is the same
400 `InvalidParameter` a non-number always was). The upload answers `fileId`, `fileDetailsId`,
`fileExternalId`, `fileDetailsExternalId` and `checksumSha256`; the first two are unchanged, and the
numbers keep working. An external id is a name, not a permission: the folder check is the same.
Every v1 download says which revision it served - `X-File-External-Id`, `X-File-Details-Id`,
`X-File-Details-External-Id`, `X-File-Version`, `X-Checksum-SHA256` (`FileApi.serve`) - and a
`HEAD` answers those alone, with the stored size as `Content-Length`, without opening the file
(Spring would otherwise run the `GET` and read the whole file to discard it). **The client-facing guide, and how to move a client to the external
ids, is [api-v1.md](api-v1.md).** Both deletes are judged on the file's own folder
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
  logout at `/logout`. PermitAll list: `/`, `/share/**`, `/favicon.ico`, `/css/**`, `/js/**`,
  `/vendor/**`; `/files/public-files/**` and `/files/public-download/**` follow the
  `public-files.anonymous` setting (`PublicFilesAuthorizationManager`).
  **Two entry points for an unauthenticated request**, chosen by what made it
  (`SecurityConfig.isScriptCall`): a script — `X-Requested-With: XMLHttpRequest`, or an `Accept`
  that asks for JSON and not HTML — gets `401` and no `Location`; a person navigating is sent to
  `/login` as before. The same predicate decides what is *not* remembered for replay after login.
  Before the split, an expired session answered a `fetch` with a redirect the browser followed,
  and the script received the login page's HTML with a `200` (issue 77).

### Authentication

`AuthenticationManagerBuilder` is assembled conditionally on
`filemanagement.auth.ldap.activedirectory.enabled`:

* **off** → `DaoAuthenticationProvider` only (BCrypt against the `app_user` table; the username is
  matched without case, so `admin` signs in to `Admin` on any database - issue 86).
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

### Roles: two fixed, the rest edited on a three-tab page (1.9.0)

* **`FixedRole`** defines the two roles every installation has, in code. **ADMIN** is
  everything, and says so: the name gives its holders the `ADMIN` wildcard and passes every folder
  check (`FolderAccessService`); the role *holds* every assignable permission row
  (`FixedRole.everything()`, computed from `PermissionEnum`, so a constant added in a release is
  ADMIN's on the next start); and its holders may upload every kind the content catalogue knows,
  built-in and custom, up to the server's cap (`UploadPolicyService.administratorLimits`, computed
  on each upload, so a kind added on the content-kinds page is theirs at once). It has no folder
  grants and no upload policy row - it needs neither. **USER** is what every new account is given
  (`UserService.createUser`): `FixedRole.USER_PERMISSIONS`, which is the groups `FILE_READ`,
  `FILE_WRITE`, `FILE_DELETE`, `FOLDER_MANAGE` and `SHARE_LINKS` - and no folder grants, so with
  folder access on its holders reach their own personal folder (a direct `WRITE` grant from
  `UserHomeService`) and whatever else they are granted, nothing more. **With folder access off,
  USER's permissions apply to every folder.** USER follows the system-wide upload policy. The
  services refuse every change to either (`RoleService.requireEditable`, called for permissions,
  folder grants and `UploadPolicyService.saveForRole`), and `DataInitializer.reconcile` brings
  both back to the definition on every start - copying anything extra of USER's into
  `USER_PREVIOUS` first and giving that copy to every holder of USER, so no one loses access;
  nothing of ADMIN's needs keeping (section "Bootstrap").
* **`PermissionGroup`** sorts every assignable permission into exactly one group (`FILE_READ`,
  `USERS_ADMIN`, ...). It is **for the role page only**: ticking a group ticks its members in the
  browser, and what is saved is the members - no table stores a group and no `@PreAuthorize`
  names one, so a group can be redrawn without a migration. `ADMIN` and `API_KEY` are
  `NOT_ASSIGNABLE`: never offered, and kept as they are when a role's permissions are saved.
  `PermissionGroupTest` fails when a new `PermissionEnum` constant is in no group.
* **The edit page** (`role/role-edit.html`, `RoleController`) is three tabs - permissions, folder
  access, upload policy - each its own form posting to its own address and answered with a
  redirect back to the same tab with a flash message; saving one never touches the other two.
* **Copying** (`RoleService.copyRole`, `COPY_ROLE`) makes an independent new role with the
  source's permissions, folder grants and own upload policy, held by nobody. A copy of ADMIN gets
  every assignable permission but not the name's two privileges (the wildcard, the folder bypass).
* **Only an administrator changes a username** (`UserService.updateUser`): holding
  `SAVE_UPDATED_USER` edits the rest of a person's details, not the name they sign in with. Asked
  of the database (`RoleService.isAdministrator`, the ADMIN role), like the folder bypass.
* **An administrator's account is an administrator's business** (issue 91). Whoever may reset an
  administrator's password, disable them or hand out ADMIN holds ADMIN in effect, so `UserService`
  refuses each of those - and every change to an account holding ADMIN, and every change that
  gives or takes the role - unless the person making it holds ADMIN too
  (`requireAdministratorFor`). The last **enabled** administrator can be neither disabled nor
  demoted (`requireAnotherAdministrator`). Ordinary accounts are managed with the ordinary
  permissions.

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

### Personal folders and quotas

A user may have one folder of their own, `Home/Profiles/{username}` (kind `USER_HOME`,
`V2.11`, roadmap 10.4), made by `UserHomeService.ensureHome`: the folder under `Profiles`
(kind `PROFILES`, one row, created or adopted by the migration), named after the user and
labelled with their name; a `WRITE` grant in `user_folder` for that user directly, so that
with folder access on they reach it without any role; and the quota
`filemanagement.profiles.default-quota-mb` gives a new home (`0` for none). Idempotent, one
transaction, one `action_history` row. It is asked for by the new-user form (a box ticked by
default) and by the user's page (`POST /users/{id}/home`, `CREATE_USER_HOME`) — an
administrator's decision each time, never on a sign-in. A personal folder does **not** change
where anyone lands after signing in - the landing page is what it always was; it is one more
place to go, a link in the navigation offered to whoever has one and may open the explorer
(`UserHomeNavigation.folderIdOf`, asked by the navbar fragment, so the lookup happens on the
pages that render a menu and nowhere else). A home is renamed only with its user (`UserService.updateUser` →
`renameHomeOf`), moved by nobody, deleted by nobody — `FolderService` and the tree delete
refuse all three for `ROOT`, `PROFILES` and `USER_HOME` alike (`isSystemFolder`) — and
nothing is created under `Profiles` by hand (`canHoldFolders` and `canHoldFiles` say no).

**The quota** is a column on `folder`, not on the user, so the check is one and general:
`FolderQuotaService.requireRoom(target, bytes)` walks the target's path, and every folder on
it carrying `quota_bytes` must satisfy `used + incoming <= quota`, `used` being
`SUM(file_details.file_size)` over that folder's subtree, computed each time (a maintained
counter would drift; the sum runs over the index on `path`). It is asked before anything is
inserted on every path that adds bytes: `createNewFile`, `createNewFileDetails` (version and
format alike), `moveFile` (with the file's revisions as the incoming size) and
`FolderService.move` (the subtree's), the last two skipping any quota folder the source
already sits under — a move within a quota adds nothing to it, a move out asks nothing. A
refusal is `QuotaExceededException`, a 409 that names the folder, its quota, its usage and
the size; the upload forms say it in Persian, the JSON layers return it as problem detail. A
home's quota is set, changed or cleared on the user's page (`POST /users/{id}/home/quota`,
megabytes or blank, `SET_FOLDER_QUOTA`); lowering it below what is stored is allowed and
simply stops the next upload. The explorer's folder details show `usedBytes / quotaBytes` on a
folder that carries one.

### Temporary share links

A share link (`file_share_link`, `V2.12`, roadmap 10.5) is a link to one stored **revision**,
valid for a number of minutes, optionally behind a password, optionally for a number of
downloads, that anyone holding it may download at `/share/{token}` **without signing in** and
outside folder access — the link is the access. Which is why making one needs the access:
`CREATE_SHARE_LINK` and `READ` on the file's folder (`ShareLinkService.create`), the same test
as downloading the file oneself. The validity is clamped to
`filemanagement.share-links.max-minutes` silently (the answer names the real expiry), defaults
to `default-minutes`, and `password=REQUIRED` makes a password mandatory for the installation.

The token is 32 random bytes, base64url; only its SHA-256 is stored, as for an API key, so the
table never holds a working link, and the token is shown once — in the answer to the creation —
and never again. The revision, not the logical file, so that a link hands out what its maker
saw and not a version uploaded later; deleting the revision or the file removes its links
(`FileService`, entity by entity, not left to the schema's cascade). A visitor sees a landing
page on `GET` — the file, its size, when the link ends, a password field if there is one — and
the `POST` is the download, so that a link previewer or a prefetch never spends one of a capped
link's downloads. Unknown, expired, revoked and exhausted tokens are one identical 404
(`ShareLinkService.usable`), so the token space cannot be probed; a wrong password
`max-failed-attempts` times locks the link for `lock-minutes`, and BCrypt checks it in constant
time. A download reads the row **locked** (`findByTokenHashForUpdate`), because it writes the
count: without it two downloads arriving together would both pass a cap of one. The token
travels in the path, so every place that writes a path down masks it
(`GlobalGeneralLogging.maskSecrets`: `/share/***`) - the access log, the exception log and the
problem JSON's `path` alike. Revoking is the maker's (`CREATE_SHARE_LINK`) or anyone's under `REVOKE_SHARE_LINK`, which
also shows every link on `/files/share-links` (`SHARE_LINKS_PAGE` shows one's own). Creation,
revocation and every download are `action_history` rows — the download on the maker, since the
visitor has no principal. The clock is a bean (`ClockConfig`) so that expiry and locks are tested
without waiting.

### Enabling and disabling an account

`user.enabled` is asked by Spring Security when it authenticates (`UserDetailsImpl.isEnabled`),
which stops the next sign-in but not a session that already exists - so
`UserService.changeEnabled(userId, 0, …)` also ends them, through `ActiveUserSessions` and the
`SessionRegistry` the browser chain registers into (`SessionRegistryConfig`, a configuration of
its own because `SecurityConfig` would close a bean cycle). The count of ended sessions is part
of the `action_history` row. The switch is on the user's page and on the users list, both behind
`REST_CHANGE_USER_ENABLED`.

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
  with no role, and an API key (which holds none), have the system-wide limits.
* **The administrator is above the policy** (1.9.0): a holder of the ADMIN role may upload every
  catalogued kind - built-in and custom - up to the server's cap (`administratorLimits`),
  whatever the policies say. Computed on each upload, not stored: a custom kind added on the
  content-kinds page is the administrator's immediately, with no policy to edit. A copy of the
  ADMIN role does not inherit this; it follows the policies like any role.
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
inserts any missing `PermissionEnum` value, creates the `ADMIN` and `USER` roles, brings both to
their `FixedRole` definition - which is how a permission added in a release reaches ADMIN: the
seeding inserts its row and the reconcile gives it to ADMIN, in the same start (anything extra of
USER's is first copied into `USER_PREVIOUS` and given to the same people, with a WARN line saying
so; nothing is written when both already match) - and creates the `Admin` account if absent (password from `filemanagement.bootstrap.admin-password`, or generated
and logged once). The pre-flight report that preceded step 4 is gone with the step: `V2.8`
itself refuses to run on a database that would have failed it (section 10).

## 8. Cross-cutting concerns

**Audit trail** — `ActionHistoryService.saveActionHistory(entity, id, action, userId, ...)` writes an
`action_history` row. It is called explicitly from the services after each mutation; it is not an
aspect, so coverage depends on the author remembering.

**Logging** — one writer per concern:

* `LoggingInterceptor` (registered by `MyWebMvcConfigurer`) writes two lines per request: what
  arrived - method, path (a share-link token masked), caller, the signed-in user, the handler
  Spring chose - and what was answered.
* A handler adds only what the interceptor cannot know, with
  `GlobalGeneralLogging.detail("...")` - an id, a name, a decision. The six-line preamble that
  used to rebuild the request line by hand in over a hundred handlers is gone (roadmap 2.1,
  issue 25).

`logback-spring.xml` writes to `filemanagement.log.path` (`FILEMANAGEMENT_LOG_PATH`, default
`./logs`), rolling daily and at 10 MB, keeping 10 days within 1 GB.
`com.hnp.filemanagement` is at `debug`, root at `info`.

**Two messages for one refusal.** An `InvalidDataException` may carry a message code beside its
English message (issue 89). The API answers the English one as the detail, as before; a page shows
the code's text from `messages.properties`, so a person reads which rule and which file - «نوع
فایل .vsdx برای شما مجاز نیست؛ ...» - rather than "enter the information correctly". The upload
path gives one to every refusal a person can fix; the generic sentence is left for what a person
could not have caused.

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
       ├─ @Validated(InsertValidation): the required fields only; the file's kind is the service's to judge
       └─ FileService.createNewFile(dto, principalId, publicFile)          @Transactional
            ├─ targetFolderOf(dto): folderId → any folder but the root, else 400
            ├─ folderAccessService.requireWriteAccess(access, folder)
            ├─ isDuplicate(baseName, folder) → 409 if taken in this folder
            ├─ ValidationUtil.checkCorrectFileName
            ├─ build FileInfo (folder, state, lastVersion = 1); tagMirrorService.retag(fileInfo)
            ├─ build FileDetails v1: UploadPolicyService.requireAllowed(principal, file) → kind and size for this person
            │                        then externalId = random UUID, storageKey, content_type = ContentTypes.detect
            ├─ fileInfoRepository.save(fileInfo)          ← cascades to FileDetails
            ├─ actionHistoryService.saveActionHistory × 2
            └─ storageWriter.write(fileDetails.storageKey, bytes)          ← disk write, LAST
                 ├─ StorageWriteJournal.begin(key)   ← committed in its own transaction
                 ├─ blobStore.put(key, bytes)        → StoredBlob: size, SHA-256
                 ├─ registerSynchronization → rolled back? delete the bytes; either way clear the note
                 └─ fileDetails.checksumSha256 (and fileSize) ← from the StoredBlob, committed with the row
```

The disk write is still not part of the transaction — nothing can put it there — but it no longer
outlives one that does not commit (§5, "Writing bytes inside a transaction",
[issue 3](issues.md#3-storage-writes-are-not-atomic-with-the-database--s1)).

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
| `V2.11__Profiles_And_Quota.sql` | `folder.quota_bytes`; `uq_folder_owner_user` (one home per user); the `Profiles` top-level folder (kind `PROFILES`, in a `profiles` tag group), created — or adopted, if a top-level folder of that name exists and holds no files directly (refused otherwise, fail-fast); `CREATE_USER_HOME` and `SET_FOLDER_QUOTA` |
| `V2.13__Add_File_Storage_Write.sql` | `file_storage_write`: the note a byte write leaves until its transaction ends, which `StorageSweeper` settles (roadmap 2.3) |
| `V2.14__Rename_User_To_App_User.sql` | `user` renamed `app_user` - reserved in PostgreSQL; ids, foreign keys and indexes unchanged (release A) |
| `V2.15__Widen_File_Size.sql` | `file_details.file_size` a `BIGINT` (issue 6) |
| `V2.16__Checksum_External_Id_And_Search_Keys.sql` | 1.8.0: `file_details.hash_id` renamed `external_id` (and its index); `file_details.checksum_sha256`; `file_info.external_id`; the search keys - `search_name` and `search_description` on `file_info` and `file_details`, `search_name` and `search_display_name` on `folder`, `utf8mb4_bin` - all nullable here |
| `V2_17__Fill_Search_Keys_And_External_Ids` (Java) | fills them for existing rows: every key from `SearchKey`, a random UUID for every file, a revision's id kept if it is a canonical UUID (lower-cased) and replaced otherwise; paged by id, one transaction, re-runnable |
| `V2.18__Require_External_Id_And_Search_Keys.sql` | makes them `NOT NULL` (all but a file's description key and the checksum), narrows `external_id` to `VARCHAR(36)` ascii, adds `uq_file_info_external_id` |
| `V2.12__Add_File_Share_Link.sql` | `file_share_link` (token hash, revision, expiry, optional password hash and download cap, counts, lock, revocation, maker); `CREATE_SHARE_LINK`, `SHARE_LINKS_PAGE`, `REVOKE_SHARE_LINK` |
| `V2.9__Folders_Any_Depth.sql` | Folders of any depth: refuses to run where a top-level folder or a stored key is named `files`; `CATEGORY` / `SUB_CATEGORY` / `TAG` become `FOLDER`; `REST_MOVE_FOLDER` (mapped onto the roles that may rename) and the three `TAG_GROUP` page permissions |
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
| `file.management.base-dir` | `./TempFiles/files/main/` | `FilesystemBlobStore` |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | `20MB` | |
| `filemanagement.default.page-size` | `30` | rows per list page, read from `FileManagementProperties`; a `page-size` in the URL is clamped to 200 and a bad one falls back to this (`PageRequests`) |
| `filemanagement.folders.max-depth` | `6` | `FolderService`: how deep the tree may go below `Home`; a create or a move past it is a 400 |
| `filemanagement.folders.max-delete-files` | `1000` | `FolderTreeDeleteService`: the most files one recursive delete may remove; a larger tree is a 409 naming the count |
| `filemanagement.profiles.default-quota-mb` | `0` | `UserHomeService`: the quota a new personal folder is created with, in megabytes; `0` for none. Changed per user on the user's page afterwards |
| `filemanagement.share-links.max-minutes` | `1440` | `ShareLinkService`: the longest a share link may live; a longer request is clamped to it, silently |
| `filemanagement.share-links.default-minutes` | `60` | the validity when the maker does not say |
| `filemanagement.share-links.password` | `OPTIONAL` | `REQUIRED` refuses a link without a password |
| `filemanagement.share-links.max-failed-attempts` | `5` | wrong passwords before a link locks |
| `filemanagement.share-links.lock-minutes` | `15` | how long it stays locked |
| `filemanagement.storage.sweep-enabled` | `true` | `StorageSweeper`: whether the scheduled sweep of unfinished byte writes runs. Off leaves the notes for an operator to settle by hand |
| `filemanagement.storage.sweep-every-minutes` | `15` | how often it runs. Read by the `@Scheduled` annotation from the raw property, because an annotation is resolved before any binding happens |
| `filemanagement.storage.unfinished-after-minutes` | `60` | how old a byte write must be before it is treated as abandoned; longer than any upload could possibly take |
| `filemanagement.storage.sweep-batch-size` | `200` | notes settled per read |
| `filemanagement.storage.checksum-backfill-enabled` | `true` | `ChecksumBackfill`: whether it runs, once, after each start, for the revisions with no checksum. Off also logs how many there are |
| `filemanagement.storage.checksum-backfill-batch-size` | `50` | revisions read per batch |
| `filemanagement.auth.ldap.activedirectory.enabled` / `.domain` / `.url` | `false`, `hnp.local`, `ldap://172.29.76.9` | |

Note the two different prefixes: `filemanagement.*` is the application's own settings, bound and
validated once in `FileManagementProperties` (roadmap 2.1), while `file.management.base-dir` keeps
the spelling it has always had — renaming a published setting silently changes behaviour on every
installation that sets it.

## 12. Tests

`./mvnw test` runs 642 tests and needs only a working Docker daemon: `MySqlSupport` starts one
MySQL 8.0.36 container per JVM, and `StorageRootSupport` gives each test a clean storage root.

Four kinds, and the kind is the point — each answers something the others cannot.

| Kind | How | What only it can answer |
|---|---|---|
| **Unit** | plain JUnit, or Mockito with every collaborator mocked | that a guard clause rejects before anything is written: `FileServiceUnitTest` asserts the storage service is never touched on a rejected upload. `EntityIdentityTest` and `ValidationUtilTest` need neither Spring nor Docker |
| **Repository** | `@DataJpaTest` + real MySQL | that a fetch plan actually resolved (`Hibernate.isInitialized`), that a bulk update reached the database, that a cascade removed what it should, and that the schema enforces its constraints |
| **Service** | `@ServiceIntegrationTest` — `@SpringBootTest` + `@Transactional` | that the whole path works through the real Spring beans, so the transaction annotations are live |
| **Web** | `@SpringBootTest` + MockMvc | status codes, response shapes, redirects and authorization, through the real security chain |

**Source-reading tests** are a fourth kind, needing neither Spring nor Docker: they read the
repository's own files and assert a rule the compiler cannot. `PermissionNamesTest` checks every
`hasAuthority('X')` in the Java sources and the templates against `PermissionEnum` - a drifted
name compiles and then silently locks an endpoint or a control to administrators
([issue 84](issues.md#84-the-new-user-page-asks-for-a-permission-that-does-not-exist--s3)).
`MessageBundleTest` checks that the externalised templates hold no Persian, `DependencyPinTest`
that the build targets the Java version it says.

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
2. ~~`FileStorageService`'s directory half is filesystem-shaped~~ — gone (roadmap 2.2): the port
   is `BlobStore`, one key names one object, and `BlobStoreContractTest` is what a second
   implementation has to satisfy.
3. Storage and database mutations are still not one transaction — nothing can make them one — but
   a write no longer outlives a transaction that does not commit: `StorageWriter` undoes it, and
   `StorageSweeper` settles what a killed process left (§5, roadmap 2.3). A delete keeps the order
   it had: rows first, bytes last, a byte that cannot be removed logged and left rather than
   undoing the rows (§4, "What each operation touches").
4. `@Table(name = "user")` — a reserved word in PostgreSQL.
5. Every `@ManyToOne` is `EAGER`; `ModelConverterUtil` walks the full graph on every list page.
6. Authorization is per-endpoint **and** per-folder since Phase 6 (§7), but never per-file: a grant
   names a folder and covers everything beneath it. A temporary share link is the one way a single
   revision is reachable on its own, and it is deliberately short-lived.
