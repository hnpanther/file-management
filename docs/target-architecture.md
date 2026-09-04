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
              └──────────────────────────┘   │   · S3BlobStore              │
                                             │   · (future) TieredBlobStore │
                                             └──────────────────────────────┘
```

Package names follow the existing `com.hnp.filemanagement` root:

```
com.hnp.filemanagement
├── catalog/            the taxonomy: GeneralTag → Category → SubCategory → MainTag
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

The single most important interface in the redesign. It replaces the path-shaped
`FileStorageService` (issue 2 in [arch.md](arch.md#13-known-structural-weaknesses)) with something
both a filesystem and an object store can implement honestly:

```java
public interface BlobStore {

    /** Stream bytes in. Returns the checksum and byte count the store actually persisted. */
    StoredBlob put(StorageKey key, InputStream data, BlobMetadata metadata);

    /** Stream bytes out. */
    InputStream open(StorageKey key);

    /** For the download path: lets S3 hand the client a pre-signed URL and the
     *  filesystem fall back to streaming through the application. */
    Optional<URI> presignedGet(StorageKey key, Duration ttl, ContentDisposition disposition);

    boolean exists(StorageKey key);

    void delete(StorageKey key);

    /** Copy without round-tripping through the application (S3 server-side copy). */
    void copy(StorageKey from, StorageKey to);
}
```

`StorageKey` is an opaque, storage-neutral string built once at upload time and stored on the row:

```
files/{fileInfoId}/v{version}/{fileDetailsId}.{ext}
```

It is derived from immutable identifiers, not from names. Renaming a category no longer invalidates
anything (issue 35), and the same key works unchanged on a disk (as a relative path under
`base-dir`) and in a bucket (as an object key).

`StoredBlob` carries `checksumSha256` and `sizeBytes` computed **while streaming**, which closes
issues 6 and 7 in one pass.

### Two-phase write

To close issue 3 (non-atomic storage/DB writes):

```
1. PUT bytes to  staging/{uuid}          ← outside the transaction, before it opens
2. BEGIN
3.   INSERT file_details (storage_key = files/…, checksum, size, status = PENDING)
4.   register TransactionSynchronization:
        afterCommit   → BlobStore.copy(staging → final); UPDATE status = ACTIVE
        afterRollback → BlobStore.delete(staging)
5. COMMIT
```

A crash at any point leaves either a staged orphan (swept by a scheduled job that deletes
`staging/` objects older than 24 h) or a `PENDING` row (swept by the same job). Neither state is
visible to users, because every read filters on `status = ACTIVE`.

Deletes invert it: mark `status = DELETING` and commit, then delete bytes asynchronously. A failed
byte-delete is retried, never silently dropped.

## Domain model changes

| Change | Closes | Notes |
|---|---|---|
| `user` table → `app_user` | 30 | mandatory for PostgreSQL |
| `state`/`enabled` `Integer` → `Visibility` and `LifecycleStatus` enums, `@Enumerated(STRING)` + CHECK constraints | 22 | `PUBLIC`, `PRIVATE`, `RESTRICTED`; `ACTIVE`, `PENDING`, `DELETING`, `DISABLED` |
| `file_size INT` → `BIGINT`, `Integer` → `long` | 6 | |
| add `checksum_sha256`, `storage_key`, `storage_backend` | 7, 35 | drop `file_path` / `relative_path` |
| `LocalDateTime` → `Instant`, `DATETIME` → `TIMESTAMPTZ` | 24 | with JPA auditing |
| `@Data` → `@Getter @Setter` + explicit `equals`/`hashCode` on id | 2 | |
| all `@ManyToOne` → `LAZY`, add `@EntityGraph` per use case | 20 | |
| `Integer` ids → keep (no gain in churning them), but add a public `external_id UUID` | 7 | API exposes the UUID, never the sequence value |

`storage_backend` on the row is what makes a gradual filesystem → S3 migration possible: old rows say
`FILESYSTEM`, new rows say `S3`, and a background job rewrites them one at a time.

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
  unreachable bucket must fail readiness (issue 41).
* Structured JSON logging to stdout in containers; the `D:/files/logs` appender becomes a
  profile-scoped, property-driven option (issue 40).
* Micrometer timers on upload, download and blob-store operations, tagged by backend.

## Configuration

One `@ConfigurationProperties("filemanagement")` record tree with `@Validated` constraints, replacing
the scattered `@Value` injections and the two competing prefixes (issue 27):

```yaml
filemanagement:
  storage:
    backend: s3                 # filesystem | s3
    filesystem:
      base-dir: /var/lib/file-management
    s3:
      endpoint: https://minio.internal:9000
      bucket: file-management
      region: us-east-1
      path-style-access: true   # required by MinIO and most S3-compatible stores
      presigned-url-ttl: 15m
  upload:
    max-file-size: 200MB
    allowed-types: [ application/pdf, image/png, ... ]
  paging:
    default-page-size: 30
```

Secrets come from the environment, never from a committed file (issue 11).

## Packaging and deployment

Executable JAR instead of WAR (issue 28), built as a layered container image
(`spring-boot:build-image` or a multi-stage Dockerfile), with a `compose.yaml` bringing up
PostgreSQL + MinIO + the application for local development.

## Testing strategy

| Level | Tooling | What it covers |
|---|---|---|
| Unit | JUnit 6, AssertJ, Mockito | domain services with ports stubbed |
| Slice | `@DataJpaTest` + Testcontainers PostgreSQL | repositories, queries, migrations |
| Storage contract | one abstract test class run against **both** `FilesystemBlobStore` and `S3BlobStore` (MinIO container) | guarantees the two backends behave identically |
| Web slice | `@WebMvcTest` + `spring-security-test` | permissions, validation, error mapping |
| Smoke | `@SpringBootTest` + Testcontainers | the context actually starts (issue 36) |

Everything runs on CI with no hand-provisioned infrastructure (issues 37, 38).
