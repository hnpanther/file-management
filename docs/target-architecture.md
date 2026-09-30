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
