# CLAUDE.md

The working agreement for this repository lives in **[AGENTS.md](AGENTS.md)** — read it first and
follow it. This file adds only the points worth repeating for an AI assistant working here.

## Orientation

| Question | Read |
|---|---|
| How does this application work? | [docs/arch.md](docs/arch.md) |
| Is this thing I found already known? | [docs/issues.md](docs/issues.md) — **check before "fixing"** |
| What is it becoming? | [docs/target-architecture.md](docs/target-architecture.md) |
| In what order? | [docs/roadmap.md](docs/roadmap.md) |
| How do I work in it? | [AGENTS.md](AGENTS.md) |

## Verify, don't infer

* **`pom.xml` says Spring Boot 3.2.1, not 3.5.5.** The commit log claims 3.5.5 because merge
  `08db773` discarded the upgrade
  ([issue 1](docs/issues.md#1-the-spring-boot-upgrade-was-silently-reverted-by-a-merge--s1)). Read
  the file before stating any version, here or in generated code.
* **The tests need a live MySQL** at `localhost:3306/file_management_test` and a writable
  `D:/files/test/`. On most machines `./mvnw test` cannot run. If you did not run it, say you did
  not run it — do not describe a change as verified.
* **`docs/issues.md` is a catalogue, not a backlog of things to fix now.** Each entry has a phase in
  the roadmap. Fixing one out of order can conflict with a later step (e.g. the `@Data` entity fix
  must precede the Spring Boot upgrade; checksum backfill must precede the S3 migration).

## Traps specific to writing code here

* **Never put a JPA entity in a log statement or a string concatenation.** `FileInfo` ↔
  `FileDetails` are bidirectional and both are `@Data`, so `toString()` recurses until the stack
  overflows ([issue 2](docs/issues.md#2-data-on-bidirectional-jpa-entities--s1)).
* **`FileStorageService`'s signature is path-shaped** (`address`, `version`, `extension`). It cannot
  express an object-store key. If you are tempted to add an S3 method to it, read
  [the storage port design](docs/target-architecture.md#the-storage-port) instead.
* **Adding a field to `ModelConverterUtil` can add joins to every list page**, because every
  `@ManyToOne` is `EAGER`.
* **`schema-db/schema.sql` begins with `DROP DATABASE IF EXISTS file_management;`.** Never execute
  it, never suggest executing it, and never use it as the schema reference — Flyway owns the schema.
* **`base-dir` is concatenated, not resolved.** It must end with a separator, and there is no
  path-containment check.

## When adding an endpoint

Four things, all required (details in [AGENTS.md](AGENTS.md#conventions-in-this-codebase)):

1. a constant in `PermissionEnum` with the endpoint named in a comment above it;
2. `@PreAuthorize("hasAuthority('X') || hasAuthority('ADMIN')")` on the handler;
3. an `actionHistoryService.saveActionHistory(...)` call for any mutation;
4. the `globalGeneralLogging.controllerLogging(...)` preamble, matching the surrounding file.

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
