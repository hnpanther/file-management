# Target Architecture

Where the project is going. The current state is described in [arch.md](arch.md); the defects that
motivate each change are numbered in [issues.md](issues.md); the order of work is in
[roadmap.md](roadmap.md).

## Principles

1. **One domain core, several adapters.** Business rules live in one place and know nothing about
   HTTP, JPA, the filesystem or S3. Everything else plugs into it.
2. **Storage is an interface, not a path.** The domain addresses bytes by an opaque *storage key*.
   Whether that key resolves to a file on a disk or an object in a bucket is a deployment decision.
3. **The database is the source of truth for metadata; the blob store is the source of truth for
   bytes.** Where they can diverge, there is an explicit reconciliation mechanism — never an
   assumption that they cannot.
4. **Fail closed.** Authorization is decided in the domain, on the resource, not on the URL.
5. **Everything that runs in production is observable and reproducible.** Containerised, health-checked,
   metered, and built by CI.

## Layering

```
                          ┌─────────────────────────────────────────┐
   Thymeleaf UI ─────────►│  web/                (inbound adapters) │
   REST API v1 ──────────►│  api/                                   │
                          │   · request/response records            │
                          │   · ProblemDetail error mapping         │
                          │   · no business logic                   │
                          └────────────────┬────────────────────────┘
                                           ▼
                          ┌─────────────────────────────────────────┐
                          │  domain/             (the core)         │
                          │   · FileCatalogService                  │
                          │   · FileVersionService                  │
                          │   · AccessPolicy                        │
                          │   · commands, results, domain errors    │
                          │   depends on ports only                 │
                          └────────┬──────────────────────┬─────────┘
                                   ▼                      ▼
              ┌──────────────────────────┐   ┌──────────────────────────────┐
              │ port: FileMetadataRepo   │   │ port: BlobStore              │
              └────────────┬─────────────┘   └───────────┬──────────────────┘
                           ▼                             ▼
              ┌──────────────────────────┐   ┌──────────────────────────────┐
              │ persistence/  (JPA,      │   │ storage/                     │
              │   PostgreSQL, Flyway)    │   │   · FilesystemBlobStore      │
              └──────────────────────────┘   │   · S3BlobStore (2.6.0)      │
                                             └──────────────────────────────┘
```

Package names follow the existing `com.hnp.filemanagement` root:

```
com.hnp.filemanagement
├── folder/             the tree: Folder at any depth (ROOT, FOLDER, PROFILES, USER_HOME), TagGroup, grants
│   ├── domain/         entities + services
│   ├── persistence/    repositories
│   └── web/            controllers + API
├── file/               FileInfo / FileDetails, upload, versioning, download
│   ├── domain/
│   ├── persistence/
│   └── web/
├── storage/            BlobStore port + filesystem and S3 adapters
├── identity/           users, roles, permissions, authentication
├── audit/              ActionHistory as an aspect
└── shared/             ProblemDetail handling, PageResponse, configuration properties
```

Slicing by feature rather than by technical layer keeps a change to "how versions work" inside one
directory instead of spread across `controller/`, `service/`, `repository/`, `dto/` and `entity/`.

## The storage port

The single most important interface in the redesign - **in place since Phase 2**. It replaced the
path-shaped `FileStorageService` with something both a filesystem and an object store can
implement honestly:

```java
public interface BlobStore {

    /** Stream bytes in; never overwrites. Returns the byte count and SHA-256 actually written. */
    StoredBlob put(StorageKey key, InputStream data);

    /** Stream bytes out. */
    Resource open(StorageKey key);

    boolean exists(StorageKey key);

    void delete(StorageKey key);

    /** Everything under a prefix - a whole file's revisions. */
    void deleteDirectory(String prefix);
}
```

Its promises are written in `BlobStoreContractTest`, which every implementation passes:
`FilesystemBlobStore` and, since 2.6.0, `S3BlobStore` - against a real SeaweedFS in the suite.
One setting, `filemanagement.storage.backend`, picks the one an installation uses.

`StorageKey` is an opaque string built once at upload time and stored on the row
(`file_details.storage_key`), never rebuilt:

```
files/{shard}/{fileInfoId}/{name}/v{version}/{name}.{ext}      files/s000/123/report/v1/report.pdf
```

It is derived from the file's own id, not from any folder name. Renaming or moving a folder, or
moving the file, changes nothing here (issue 35, Phase 7.1), and the same key works unchanged on
a disk (a relative path under `base-dir`) and in a bucket (an object key) - which is why moving
to an object store needs no migration. Keys written by the two earlier layouts keep working as
they are.

`StoredBlob` carries the SHA-256 and the byte count computed **while streaming**; the checksum is
stored on the row (`checksum_sha256`, 1.8.0, issue 7) and is what proves a copy arrived whole.

**What the object store needed, and how 2.6.0 answered it** without changing the port:

* an object store wants the length before the first byte, and the port gives a stream - so
  `S3BlobStore` reads up to one part (16 MB) first: a file that ends within it is one
  `PutObject`, a larger one a multipart upload of parts that size. One part in memory per upload,
  whatever the file's size; no length on the port, no second temporary file;
* still to come: an optional `presignedGet(key, ttl, disposition)`, so a download can be handed to
  the store instead of streaming through the application - `Optional.empty()` from the filesystem.
  Until then a download streams through, and a `Range` request is a ranged `GetObject`.

A server-side `copy` is **not** needed: the write path below writes to the final key.

### Writes that cannot outlive their transaction

Issue 3 (non-atomic storage and database writes) is closed by `StorageWriter` (roadmap 2.3,
1.6.1), not by the staging key an earlier draft planned:

```
1. record the key in file_storage_write, in a transaction of its own
2. put the bytes at the final key
3. the file_details row is written in the caller's transaction
4. afterCommit   → remove the file_storage_write record
   afterRollback → delete the bytes, remove the record
```

A process killed in between leaves a `file_storage_write` record; `StorageSweeper` settles it later
against `file_details` - bytes a revision claims are kept, bytes nothing claims are removed. Nothing
here depends on the kind of store.

### Large files

Files will be many, of every size, **around 100 MB on average**. What that asks of the path the
bytes take:

* **Never hold a file in memory.** Uploads stream from the request (Tomcat spools a multipart
  part to a temporary file) through the digest to the store; downloads stream from the store.
* **The upload cap is a setting** (`spring.servlet.multipart.max-file-size`, and the reverse
  proxy's `client_max_body_size` and timeouts to match); the per-kind limits of the upload policy
  sit under it.
* **Multipart upload to the store** above one part (16 MB, `part-size-mb`), so a 1 GB file is not
  one request that fails at 900 MB (2.6.0).
* **Downloads by pre-signed URL** once the bytes are in an object store - the biggest single
  saving: the application stops being the pipe every download flows through. A recorded download
  (roadmap 9.2) is written before the redirect.
* **Range requests** keep working for both backends, so a client can resume.

## Searching the contents of files

Roadmap Phase 11. A search should find a file by what is inside it - not only by its name - across
what this system actually holds: PDFs with and without a text layer, Word, Excel and PowerPoint,
images and photographed forms, Visio diagrams and AutoCAD drawings, in Persian and English. The
order of work, the AutoCAD and mixed-PDF details and the sizing are in
[roadmap Phase 11](roadmap.md#phase-11--searching-the-contents-of-files--planned); the hosts in
[deployment.md](deployment.md#the-hosts-and-what-each-needs).

```
 application host                                         Tika host (Docker, its own)
 ┌───────────────────────────────────────────────┐       ┌──────────────────────────────┐
 │ upload ── commit ──► file_details             │       │ tika-text   parsers, no OCR  │
 │                      + file_content(PENDING)  │       │             2 cores, ~4 GB   │
 │                        (one transaction)      │       │                              │
 │ ContentWorker   FOR UPDATE SKIP LOCKED        │ HTTP  │ tika-ocr    parsers +        │
 │   text lane ×N ── document, text PDF ─────────┼──────►│             Tesseract fas+eng│
 │   OCR lane  ×M ── image, scanned PDF pages ───┼──────►│             the other cores  │
 │   dxf: read in process                        │       │ (dwg → dxf converter, here)  │
 │   dwg: converter → dxf ───────────────────────┼──────►│                              │
 │   anything else: SKIPPED                      │       └──────────────────────────────┘
 │   bytes streamed from the BlobStore; a timeout│
 │   and a cap on every call                     │
 │   text folded as SearchKey folds names        │
 │     → file_content: text, page starts,        │
 │       tsvector, state                         │
 │                                               │
 │ ContentSearch (port)                          │
 │   PostgreSQL: tsvector('simple') + GIN,       │
 │   folder scoping in the query                 │
 │   later, if needed: OpenSearch (Persian)      │
 └───────────────────────────────────────────────┘
```

**Reading is not searching.** The two happen at different times and fail separately: an upload
writes a *pending* row in its own transaction and is done; reading the text is a background job
that may take seconds (a document) or minutes (OCR of a long scan), may fail, and is retried. A
search reads only the database, whatever the state of the readers.

**The tool is chosen when the file is read, not when it is uploaded.** The worker detects the kind
from the bytes and routes it. So a reader added later (DWG, say), or OCR switched on after a year,
applies to old files by re-queueing their `SKIPPED` rows - no re-upload, no migration of choices.
OCR is one route among several, not the pipeline: a text PDF or a Word document never goes near it.

**Two lanes.** The text lane is fast and has a few workers; the OCR lane is slow and has as many
workers as the OCR container has cores. Each worker reads one file at a time; the lanes keep a long
scan from holding up everything queued behind it. **A PDF that mixes text and scanned pages** is
read twice: once in the text lane, at once searchable by its text pages, then - if it has pages
with no text and OCR is on - in the OCR lane with Tika's `auto` strategy, which recognises only
those pages; the complete text replaces the first. A cap on pages marks the row *partial*.

**One table**, `file_content`, one row per revision: its state (`PENDING`, `DONE`, `EMPTY`,
`SKIPPED`, `FAILED`, and *partial* beside `DONE`), the lane, the attempts and the next one, the
reader used and the language found, the text (capped) with where each page starts, and a generated
`tsvector` over its first part, GIN-indexed. It belongs to the revision - `ON DELETE CASCADE`,
unlike the history and the download records, which outlive it. The queue is this table: a partial
index on the pending rows is all the workers read.

**Tika runs on a host of its own, in two containers, never in the application**: `tika-text` (the
plain image) and `tika-ocr` (the full image with **Tesseract inside it** and Persian added - Tika
runs `tesseract` as a local process, so it can live nowhere else). Forked mode, so a parser that
dies is restarted; a memory limit per container; no authentication of its own, so only the
application's host may reach it. The application talks to each over HTTP with a timeout, sends the
OCR settings (languages, PDF strategy, time limit) as headers with each request, and keeps only
`tika-core`. Down, it leaves rows pending; nothing else notices. Not on the object store's host:
it parses untrusted files and, while recognising, takes every core.

**AutoCAD.** DXF is text and is read by a small streaming parser in the application - notes
(`TEXT`, `MTEXT`) and, above all, the title block's attributes (`ATTRIB`). DWG is converted to DXF
first, by the ODA File Converter on the Tika host. Persian written in a TrueType font reads as
Unicode; Persian written in one of the Persian SHX fonts is stored as Latin letters and needs a
mapping per font - which the drawings use is measured before it is promised.

**Persian.** PostgreSQL has no Persian dictionary or stemmer, so the text is indexed with the
`simple` configuration after the same folding as names (Arabic ي/ك to Persian, the half-space and
marks dropped, digits to ASCII, upper case, and Presentation Forms to their letters) - and the query
is folded the same way. Words and phrases match; inflected forms do not. That is the reason for the
`ContentSearch` port: OpenSearch's Persian analyzer is the upgrade if relevance proves to matter,
and only the adapter changes.

**Access: each person sees only the documents they may open.** A result, a snippet and a count are
all the file's contents, so the rule is the download's: the endpoint's permission, then the
reader's folder access - `file_info.folder_id IN (readable folders)` in the same statement as the
text match, never a filter on the page afterwards, and taken from where the file is now, so a move
or a revoked grant applies to the next search with nothing to re-index. A public file or a share
link grants nothing here; an API key sees only its own folders; the workers, which read everything,
expose nothing but through this filtered search. The snippet is made (`ts_headline`) only for the
rows of the page shown, over a bounded prefix of the text, and names the page it is on. The rule
in full, and its tests:
[roadmap Phase 11, "Who sees what"](roadmap.md#who-sees-what-only-the-documents-each-person-may-open).

```yaml
filemanagement:
  content:
    enabled: true
    tika-text-url: http://tika-host:9998    # the plain image
    tika-ocr-url: http://tika-host:9999     # the image with Tesseract and Persian
    max-bytes: 200MB            # larger files are SKIPPED
    max-text: 5MB               # text kept per revision; the tsvector covers its first part
    timeout: 120s               # per file in the text lane; the OCR lane's grows with the pages
    workers: { text: 2, ocr: 4 }   # ocr: the cores given to tika-ocr
    ocr:
      enabled: false
      languages: fas+eng
      pdf-strategy: auto        # auto: only pages without text; all: every page, at full cost
      max-pages: 50             # beyond it the row is DONE and marked partial
    dwg-converter: ""           # the ODA File Converter on the Tika host; empty - DWG is SKIPPED
```

## Domain model changes

| Change | Closes | Notes |
|---|---|---|
| `user` table → `app_user` | 30 | mandatory for PostgreSQL; **done** (`V2.14`, 1.7.0) |
| `state`/`enabled` `Integer` → `Visibility` and `LifecycleStatus` enums, `@Enumerated(STRING)` + CHECK constraints | 22 | `PUBLIC`, `PRIVATE`, `RESTRICTED`; `ACTIVE`, `PENDING`, `DELETING`, `DISABLED` |
| `file_size INT` → `BIGINT`, `Integer` → `long` | 6 | **done** (`V2.15`, 1.7.0) |
| add `checksum_sha256`, `storage_key`; drop `file_path` / `relative_path` | 7, 35 | **done** (`storage_key` Phase 7.1, `checksum_sha256` `V2.16` 1.8.0). No `storage_backend`: see below |
| `LocalDateTime` → `Instant`, `DATETIME` → `TIMESTAMPTZ` | 24 | **done** (`V3.1`, 2.2.0); the timestamps written by Hibernate since the architecture pass, the zone they are shown in a setting (`filemanagement.time-zone`) |
| `@Data` → `@Getter @Setter` + explicit `equals`/`hashCode` on id | 2 | |
| all `@ManyToOne` → `LAZY`, add `@EntityGraph` per use case | 20 | |
| `Integer` ids → keep (no gain in churning them), but add a public `external_id UUID` | 7 | API exposes the UUID, never the sequence value |

**No `storage_backend` column.** It was planned for a gradual move - old rows on the disk, new
rows in the bucket, a composite store reading from the right one. Roadmap Phase 4 decided
otherwise: one bucket per environment, every object at its row's `storage_key` unchanged, and a
copy in two passes - most of it while the service runs, since a revision never changes once
written - with a window of minutes to switch. The whole installation is on one backend at a time,
named by a setting.

## Authorization

Replace the ~70 endpoint-named `PermissionEnum` constants (issue 19) with a two-part model:

* **Coarse permissions** — a small set of verbs per aggregate: `file:read`, `file:write`,
  `file:delete`, `catalog:manage`, `user:manage`, `role:manage`. Roles bundle these.
* **Resource policy** — an `AccessPolicy` consulted *inside the domain service*, which answers
  "may this principal read this `FileDetails`?" using the file's `Visibility`, its owner, and any
  explicit grants on its category.

This closes issue 14: `downloadFile` becomes

```java
var details = repository.findActive(id).orElseThrow(FileNotFound::new);
accessPolicy.requireRead(principal, details);      // ← the check that does not exist today
return blobStore.open(details.storageKey());
```

`@PreAuthorize` stays for the coarse check on the endpoint; the resource check is never in an
annotation.

## Error handling

One `@RestControllerAdvice` producing RFC 9457 `ProblemDetail` for all REST surfaces, one
`@ControllerAdvice` rendering `error.html` for the Thymeleaf surface, and a correlation id
(`traceId`) in every response and every log line. Domain exceptions map to status codes in exactly
one table. No exception message ever reaches a client verbatim (issue 15).

## Observability

* `spring-boot-starter-actuator` with `/actuator/health/liveness`, `/readiness`, `/prometheus`,
  `/info` (build + git metadata).
* Custom health indicators for the database **and** the configured `BlobStore` — a full disk or an
  unreachable bucket must fail readiness (issue 41). The actuator and its readiness group exist
  since 9.5; the `BlobStore` indicator comes with Phase 4.
* Structured JSON logging to stdout in containers; the `D:/files/logs` appender becomes a
  profile-scoped, property-driven option (issue 40).
* Micrometer timers on upload, download and blob-store operations, tagged by backend.

## Configuration

One `@ConfigurationProperties("filemanagement")` record tree with `@Validated` constraints, replacing
the scattered `@Value` injections and the two competing prefixes (issue 27):

```yaml
filemanagement:
  storage:
    backend: s3                 # filesystem | s3 - one backend for the whole installation (2.6.0)
    s3:
      endpoint: http://storage-host:8333           # SeaweedFS's S3 gateway, or whichever store 4.6 chose
      bucket: file-management-prod                 # one bucket per environment
      access-key: ...                              # FILEMANAGEMENT_S3_ACCESS_KEY
      secret-key: ...                              # FILEMANAGEMENT_S3_SECRET_KEY
      prefix: ""                                   # optional, for a bucket shared with something else
      region: us-east-1
      path-style-access: true   # required by every self-hosted S3-compatible store
      part-size-mb: 16          # a larger file goes up in parts of this size
  base-dir: /var/lib/file-management               # the filesystem backend's root
  upload:
    max-file-size: 2GB          # the server's cap; the upload policy's per-kind limits sit under it
    allowed-types: [ application/pdf, image/png, ... ]
  paging:
    default-page-size: 30
```

Secrets come from the environment, never from a committed file (issue 11).

## Packaging and deployment

Executable JAR instead of WAR (issue 28), built as a layered container image
(`spring-boot:build-image` or a multi-stage Dockerfile), with a `compose.yaml` bringing up
PostgreSQL + the object store chosen in roadmap 4.6 + the application for local development.
(MinIO is no longer the default choice: its community edition was archived in 2026.)

## Testing strategy

| Level | Tooling | What it covers |
|---|---|---|
| Unit | JUnit 6, AssertJ, Mockito | domain services with ports stubbed |
| Slice | `@DataJpaTest` + Testcontainers PostgreSQL | repositories, queries, migrations |
| Storage contract | one abstract test class run against **both** `FilesystemBlobStore` and `S3BlobStore` (a container of the chosen store) | guarantees the two backends behave identically |
| Web slice | `@WebMvcTest` + `spring-security-test` | permissions, validation, error mapping |
| Smoke | `@SpringBootTest` + Testcontainers | the context actually starts (issue 36) |

Everything runs on CI with no hand-provisioned infrastructure (issues 37, 38).
