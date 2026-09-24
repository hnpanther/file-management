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
| How do I work in it? | [AGENTS.md](AGENTS.md) |

## Verify, don't infer

* **Read the version in `pom.xml`; never take it from the log.** It is Spring Boot 4.1.1 on Java
  25 now, but a merge once discarded an upgrade and left the commit log claiming a version the
  build never had ([issue 1](docs/issues.md#1-the-spring-boot-upgrade-was-silently-reverted-by-a-merge--s1)).
* **The tests need only a Docker daemon.** `support/MySqlSupport` starts a MySQL container and
  `support/StorageRootSupport` uses `./target/test-storage/`. Run `./mvnw test`; if you did not run
  it, say you did not run it — do not describe a change as verified.
* **On MySQL, a query can be wrong and pass every test.** The `utf8mb4_unicode_ci` collation makes
  `=` and `LIKE` on text case-insensitive by itself; PostgreSQL does not. A case test proves
  nothing on MySQL alone - release A's were also run on PostgreSQL
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
* **Log an id, not an entity.** `AbstractEntity.toString` prints `Type#id` and no longer recurses
  ([issue 2](docs/issues.md#2-data-on-bidirectional-jpa-entities--s1) is fixed), but an entity in a
  message says less than its id.
* **Bytes are written through `StorageWriter`, never through `BlobStore.put` directly.** It is
  what makes a write disappear with a transaction that does not commit, and what records it in
  `file_storage_write` so `StorageSweeper` can settle what a killed process left (roadmap 2.3,
  [issue 3](docs/issues.md#3-storage-writes-are-not-atomic-with-the-database--s1)). Reads and
  deletes go straight to the port.
* **Bytes go through `BlobStore`** (`put`, `open`, `exists`, `delete`, `deleteDirectory`), whose
  only implementation today is `FilesystemBlobStore`. One opaque `StorageKey` names one object;
  do not add a method that takes a directory, a version and an extension, and do not rebuild a
  location from folder names — `file_details.storage_key` is the only record of where the bytes
  are, and folders are renamed without moving them. Anything the port promises is written in
  `BlobStoreContractTest`, which every implementation must pass.
* **A general tag is not a folder.** `Folder` kinds are `ROOT`, `FOLDER`, `PROFILES` (the one
  folder the personal folders sit under; takes nothing by hand) and `USER_HOME` (a user's own,
  renamed only with the user, moved and deleted by nobody, usually carrying `quota_bytes`); the
  tree goes to `filemanagement.folders.max-depth`, every folder below the root holds folders and
  files, and only a top-level folder carries a `TagGroup` (the old general tag) in
  `folder.tag_group_id`. Do not treat a tag group as a place. A chain of any depth cannot be
  fetch-joined: load a page's ancestors with `FolderService.ancestryOf`, off the materialised
  path, never by walking `getParent()` per row.
* **New files are stored by their own id** (`files/{shard}/{file id}/…`, `StorageLayout` —
  the one place the shape is written); 1.4.0 stored them flat under `files/{file id}/`, and
  older ones sit under the names of the folders above them as they stood. None is ever rebuilt
  from the tree, and `files` is a reserved top-level name. Do not move bytes on a rename or a
  move — what each operation may touch (tree, keys, bytes, tags) is tabulated in
  [docs/arch.md](docs/arch.md#what-each-operation-touches); keep it true.
* **Adding a field to `ModelConverterUtil` that follows an association costs a query per row** -
  every association is `LAZY` and `open-in-view` is off. Fetch it in the repository query.
* **Flyway owns the schema; `docs/schema.md` describes it.** The old `schema-db/schema.sql`
  (which began with `DROP DATABASE IF EXISTS file_management;`) was deleted in Phase 0. Never
  recreate it, and never suggest a `DROP DATABASE` against anything but a throw-away local
  database — the one place the reset is written down, with that warning, is
  [docs/schema.md](docs/schema.md#a-throw-away-developer-database).
* **Every storage path is resolved by `FilesystemBlobStore.within`** against the absolute,
  normalised `base-dir`, and refused if it lands outside it or on the root itself. Do not build a
  `Path` from `baseDir` anywhere else. The root is resolved, never concatenated, so it means the
  same directory with or without a trailing separator.

## When adding an endpoint

Four things, all required (details in [AGENTS.md](AGENTS.md#conventions-in-this-codebase)):

1. a constant in `PermissionEnum` with the endpoint named in a comment above it;
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

Note also that the application talks to a **real** MySQL on `localhost:3306` (the tests do not —
they start their own through Testcontainers). Anything you do while it is running is done to real
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
Persian and currently hardcoded in the controllers
([issue 26](docs/issues.md#26-persian-ui-strings-hardcoded-in-java--s3)) — match the surrounding
style when editing an existing file.
