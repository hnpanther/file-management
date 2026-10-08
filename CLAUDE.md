# CLAUDE.md

The working agreement for this repository lives in **[AGENTS.md](AGENTS.md)** — read it first and
follow it. This file adds only the points worth repeating for an AI assistant working here.

## Orientation

| Question | Read |
|---|---|
| How does this application work? | [docs/arch.md](docs/arch.md) |
| What does the database look like right now? | [docs/schema.md](docs/schema.md) — generated; regenerate it with every migration |
| Is this thing I found already known? | [docs/issues.md](docs/issues.md) — **check before "fixing"** |
| What is it becoming? | [docs/target-architecture.md](docs/target-architecture.md) |
| In what order, and what is next? | [docs/roadmap.md](docs/roadmap.md) — start at "Where things stand, and what comes next" |
| How is it deployed and backed up? | [docs/deployment.md](docs/deployment.md) |
| How are the files moved to the object store, and back? | [docs/storage-copy.md](docs/storage-copy.md) |
| What does an integration (APEX) call, and how does it move to the external ids? | [docs/api-v1.md](docs/api-v1.md) |
| What does an S3 client call, and how is it signed? | [docs/api-s3.md](docs/api-s3.md) |
| How do I work in it? | [AGENTS.md](AGENTS.md) |

## Verify, don't infer

* **Read the version in `pom.xml`; never take it from the log.** It is Spring Boot 4.1.1 on Java
  25 now, but a merge once discarded an upgrade and left the commit log claiming a version the
  build never had ([issue 1](docs/issues.md#1-the-spring-boot-upgrade-was-silently-reverted-by-a-merge--s1)).
* **The tests need only a Docker daemon.** `support/DatabaseSupport` starts a PostgreSQL 18
  container, `support/TestObjectStores` a SeaweedFS one for the object-store tests (2.6.0), and
  `support/StorageRootSupport` uses `./target/test-storage/`. Run `./mvnw verify`;
  if you did not run it, say you did not run it - do not describe a change as verified.
* **PostgreSQL only since release C (2.1.0).** A schema change is the next
  `db/migration/V3.x__Description.sql`, never an edit of `V3.0` or any applied migration (Flyway
  checksums comments too, and production refuses to start on a mismatch).
* **A query that leans on a collation was wrong on MySQL and passed every test there.** Its
  `utf8mb4_unicode_ci` collation made `=` and `LIKE` on text case-insensitive by itself;
  PostgreSQL does not, so a name is compared through `UPPER(...)` and is unique on `upper(column)`
  ([issue 86](docs/issues.md#86-case-insensitive-equality-and-uniqueness-come-from-the-mysql-collation-and-release-a-plans-only-for-like--s2)).
* **`docs/issues.md` is a catalogue, not a backlog of things to fix now.** Each entry has a phase in
  the roadmap. Fixing one out of order can conflict with a later step (e.g. checksum backfill must
  precede the S3 migration; a schema change wanted soon belongs before PostgreSQL release B).

## Traps specific to writing code here

* **Compare names through `UPPER(...)` on both sides** (`IgnoreCase` in a derived query); **search
  files and folders through their folded keys** (`search_name` and the like, `SearchKey.forSearch`
  for the term - 1.8.0); and pass an empty search as `''`, never `null` - a `null` in `LIKE CONCAT(...)` is a type
  PostgreSQL refuses ([issue 87](docs/issues.md#87-an-empty-search-box-is-a-null-postgresql-cannot-type--s1-for-the-migration)).
  The accounts table is `app_user`; the entity is still `User`. Details in
  [AGENTS.md](AGENTS.md#database-changes).
* **ADMIN and USER are fixed roles, defined in `FixedRole`** and reset to that definition on every
  start (`DataInitializer.reconcile`, which first copies anything extra of USER's into
  `USER_PREVIOUS`). ADMIN holds every assignable permission and may upload every catalogued file
  type up to the server's cap; a new permission or content kind reaches it by itself, at the next
  start or at once - **do not write a migration for that**. Do not change what they hold anywhere else; a different role is a copy
  (`RoleService.copyRole`). Every account holds USER, so **a file operation that forgets
  `folderAccessService.requireWriteAccess` on the file's folder lets everybody do it** (issue 90).
  `PermissionGroup` is a role-page shortcut only - nothing stores a group.
* **Log an id, not an entity - and never a DTO.** `AbstractEntity.toString` prints `Type#id` and no
  longer recurses ([issue 2](docs/issues.md#2-data-on-bidirectional-jpa-entities--s1) is fixed), but
  an entity in a message says less than its id. A form DTO's `toString` prints what the person
  typed, which is how a password reached the log
  ([issue 92](docs/issues.md#92-a-user-form-wrote-the-password-it-carried-to-the-log--s1)); a failed
  binding is logged with `globalGeneralLogging.invalid(bindingResult)`, never by concatenation, and
  a new record holding a secret overrides `toString`.
* **An administrator's account is changed by an administrator only**
  ([issue 91](docs/issues.md#91-anybody-who-could-manage-users-could-make-themselves-an-administrator--s1)):
  a new user operation that changes an account, or who holds ADMIN, calls
  `UserService.requireAdministratorFor` first.
* **A page size from a URL goes through `PageRequests`** (at most 200 rows, never a 500), and a
  list converts nothing per row that its query did not fetch - `ListQueryCountTest` counts the
  statements.
* **A level of folders is paged, through `FolderLevelService`** (2.11.0): a folder may hold tens of
  thousands of folders (one per person, the ERP workflow). Never read a level whole
  (`findChildrenWithTagGroup`, `findAllByOrderByPathAsc`) to list it, and never filter one by folder
  access in Java - `FolderAccess.visibleChildIdsUnder` puts it in the query. `LargeTreeTest` pins the
  statements of every listing at 20,000 children.
* **Folder access in a list query is `FolderAccessService.readScope` and `GrantedFolderPath`**,
  never the readable folders' ids in an `IN`: a grant over 65,535 folders made that a statement
  PostgreSQL refuses (issue 114).
* **Bytes are written through `StorageWriter`, never through `BlobStore.put` directly.** It is
  what makes a write disappear with a transaction that does not commit, and what records it in
  `file_storage_write` so `StorageSweeper` can settle what a killed process left (roadmap 2.3,
  [issue 3](docs/issues.md#3-storage-writes-are-not-atomic-with-the-database--s1)). Reads and
  deletes go straight to the port.
* **Bytes go through `BlobStore`** (`put`, `open`, `exists`, `delete`, `deleteDirectory`): the
  filesystem (`FilesystemBlobStore`, the default) or an S3-compatible store (`S3BlobStore`, 2.6.0),
  one per installation, chosen by `filemanagement.storage.backend` in `BlobStoreConfig`. Nothing
  else may know which. One opaque `StorageKey` names one object;
  do not add a method that takes a directory, a version and an extension, and do not rebuild a
  location from folder names — `file_details.storage_key` is the only record of where the bytes
  are, and folders are renamed without moving them. Anything the port promises is written in
  `BlobStoreContractTest`, which every implementation must pass - the S3 one against a real
  SeaweedFS (`support/TestObjectStores`).
* **A general tag is not a folder.** `Folder` kinds are `ROOT`, `FOLDER`, `PROFILES` (the one
  folder the personal folders sit under; takes nothing by hand) and `USER_HOME` (a user's own,
  renamed only with the user, moved and deleted by nobody, usually carrying `quota_bytes`); the
  tree goes to `filemanagement.folders.max-depth`, every folder below the root holds folders and
  files, and only a top-level folder carries a `TagGroup` (the old general tag) in
  `folder.tag_group_id`. Do not treat a tag group as a place. A chain of any depth cannot be
  fetch-joined: load a page's ancestors with `FolderService.ancestryOf`, off the materialised
  path, never by walking `getParent()` per row.
* **`folder.key_path` is derived, like `path`** (V3.9, 2.12.0): the names below the bucket, each with
  its `/` - the S3 key's prefix, and what the listing orders by (`COLLATE "C"`, byte order, as S3).
  Anything that creates, renames or moves a folder keeps it (`Folder.onCreate`,
  `FolderService.rewriteKeyPath`, `UserHomeService`), and a test asserts
  `findRowsWhoseKeyPathDisagrees` empty after it; a folder inserted in SQL writes it too. Compare S3
  keys in Java with `S3Keys.compare`, never `String.compareTo` (UTF-16 is not byte order).
* **An S3 handler takes only the query parameters it understands** (`S3Controller.asksWhatIsNotServed`,
  issue 116): a sub-resource it ignored ran as the plain operation - `DELETE …?tagging` deleted the
  file. A new S3 operation names its parameters there; anything else stays a 501.
* **A multipart upload is completed by `S3ObjectService.put`** (2.14.0) - its parts read as one body
  (`S3PartsBody`), never stored by a path of their own, so the checks, the versions and the records
  are a `PUT`'s. A part is a file named in its row, written before the row under the upload's lock;
  an upload is one key's (`NoSuchUpload` to any other).
* **Metadata is checked by `MetadataRules` and written through `FileMetadataService` /
  `FolderMetadataService`** (or an upload's own path) - never set on `FileDetails.metadata` or
  `Folder.metadata` elsewhere: those are what check access, record the change with both documents and
  read the stored text back for its tag. A document may be personal: **never log one, and never let a
  DTO that carries one print it** (`@ToString.Exclude`). `{}` is no document - the column is `NULL`.
* **New files are stored by their own id, under a key that names nothing a person wrote**
  (`files/{shard}/{file id}/rev/v{n}/{revision external id}.{ext}`, 2.10.0, `StorageLayout` — the
  one place a key is written and the one place a file's directory is read back off a key, by its
  shape: **never count a key's parents** - a shorter key once put the third parent at the shard).
  1.5.0 - 2.9.0 put the title where `rev` is, 1.4.0 stored files flat under `files/{file id}/`,
  and older ones sit under the names of the folders above them as they stood. None is ever rebuilt
  from the tree, and `files` is a reserved top-level name. Do not move bytes on a rename or a
  move — what each operation may touch (tree, keys, bytes, tags) is tabulated in
  [docs/arch.md](docs/arch.md#what-each-operation-touches); keep it true.
* **Times are `Instant`s; a zone appears only where a person reads or types one**, and it is
  `filemanagement.time-zone` - the `Clock` bean's zone - never the server's (2.2.0,
  [issue 24](docs/issues.md#24-timestamps-are-hand-set-localdatetime--s2)). No
  `LocalDateTime.now()`, `LocalDate.now()` or `ZoneId.systemDefault()`; `Instant.now(clock)`, and
  `JalaliDate` / `appDateTime` to show one.
* **A request with an API key runs as the key's creator; the key is recorded beside them** (2.3.0,
  [issue 97](docs/issues.md#97-what-an-api-key-did-was-recorded-as-its-creators-doing-with-nothing-to-say-a-key-did-it--s2)):
  `ActingApiKey.currentId()`. The audit trail takes it by itself; a new way to create a file or a
  revision sets `createdByApiKey` as `FileService` does.
* **Every change to a file records a `file_history` event** (2.5.0): `fileHistoryService.record`
  in the transaction of the change, as `FileService` does - and a search term goes through
  `SearchTerms.escapeLike` with `ESCAPE '\'` on its `LIKE` (issue 96).
* **Every way bytes leave records the download** (2.7.0): `downloadAudit.served(download, channel)`
  in the handler once it has decided to send them; `DownloadRecorder` writes it off the request, so
  never write `file_download` in a request's transaction.
* **A search must hit its trigram index** (2.2.0,
  [issue 21](docs/issues.md#21-search-is-like-term-across-the-whole-graph--s2)): keep
  `REPLACE(x.searchName, ' ', '')` exactly as written, match through another table with a `UNION`
  of ids rather than `OR EXISTS`, and give a new search a case in `SearchIndexTest`.
* **Adding a field to a mapper (`FileMapper`, `UserMapper`, ...) that follows an association costs a query per row** -
  every association is `LAZY` and `open-in-view` is off. Fetch it in the repository query.
* **Flyway owns the schema; `docs/schema.md` describes it.** The old `schema-db/schema.sql`
  (which began with `DROP DATABASE IF EXISTS file_management;`) was deleted in Phase 0. Never
  recreate it, and never suggest a `DROP DATABASE` against anything but a throw-away local
  database — the one place the reset is written down, with that warning, is
  [docs/schema.md](docs/schema.md#a-throw-away-developer-database).
* **`CopyableStore` is the storage copy's, never the application's** (2.8.0, roadmap 4.4): the
  service reads and writes through `BlobStore` only. The copy (`storage.copy`) runs outside the
  application on `--spring.profiles.active=storage-copy`, reads the database and never writes it,
  and makes an object visible only once its bytes hash to the row's checksum; its guarantees are
  `CopyableStoreContractTest`, which both stores pass. A change to it changes
  [docs/storage-copy.md](docs/storage-copy.md) with it - the operators' only manual.
* **Every storage path is resolved by `FilesystemBlobStore.within`** against the absolute,
  normalised `base-dir`, and refused if it lands outside it or on the root itself. Do not build a
  `Path` from `baseDir` anywhere else. The root is resolved, never concatenated, so it means the
  same directory with or without a trailing separator.

## When adding an endpoint

Four things, all required (details in [AGENTS.md](AGENTS.md#conventions-in-this-codebase)):

1. a constant in `PermissionEnum` with the endpoint named in a comment above it, placed in exactly
   one `PermissionGroup` (the role page's groups - `PermissionGroupTest` fails otherwise);
2. `@PreAuthorize("hasAuthority('X') || hasAuthority('ADMIN')")` on the handler;
3. an `actionHistoryService.saveActionHistory(...)` call for any mutation;
4. a `globalGeneralLogging.detail(...)` line with what the request line cannot say (an id, a
   name) - `LoggingInterceptor` already logs who called what.

## Running the application

If you start it to check something, **stop it before you finish**. `spring-boot:run` holds port
8122 until the process dies, and the next attempt to start it fails with a port conflict whose
error message says nothing about the real cause. The commands are in
[AGENTS.md](AGENTS.md#commands); the rule is part of the
[definition of done](AGENTS.md#definition-of-done).

Note also that the application talks to a **real** PostgreSQL - `localhost:5434` by default, the
port `compose.yaml` publishes (the tests do not — they start their own through Testcontainers). Anything you do while it is running is done to real
data.

## Scope discipline

This repository has a lot of visible debt and an explicit plan for it. When asked to make a change:

* do the change that was asked;
* if you notice something else from `docs/issues.md` in the file you are touching, mention it —
  do not silently fold it into the diff;
* if you find something genuinely new, add it to `docs/issues.md` with a file reference and a
  severity, rather than fixing it unasked.

## Language

Code, comments, commit messages and documentation are in English. User-facing UI strings are in
Persian and live in `messages.properties` - `UiMessages` in a controller, `#{...}` in a template;
no Persian sentence goes into Java ([issue 26](docs/issues.md#26-persian-ui-strings-hardcoded-in-java--s3)
is resolved). A refusal the person should read is an `InvalidDataException` with a message code.
