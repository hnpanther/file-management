# File Management

A Spring Boot web application for managing files: uploading them into a category taxonomy, creating
new **versions** and alternative **formats** of the same logical file, publishing a subset of them
publicly, and downloading them again — with a per-endpoint permission model and a full audit trail.

Files are stored on the local filesystem; metadata lives in MySQL. A small REST API
(`/api/v1/files`) exists for programmatic access.

> **Status.** This branch (`redesign-arch`) is being restructured. Before adding features, read
> [docs/issues.md](docs/issues.md) — several of the entries there are things you will otherwise
> trip over. The plan is in [docs/roadmap.md](docs/roadmap.md).

## Documentation

| Document | What it covers |
|---|---|
| [docs/arch.md](docs/arch.md) | How the application is built **today** — domain model, layers, security, storage layout, request flows |
| [docs/issues.md](docs/issues.md) | Catalogued defects, security risks and technical debt, with file references |
| [docs/target-architecture.md](docs/target-architecture.md) | The architecture being migrated to |
| [docs/roadmap.md](docs/roadmap.md) | Sequenced plan: platform upgrade → restructuring → PostgreSQL → S3 |
| [AGENTS.md](AGENTS.md) | Conventions and guardrails for anyone (human or AI) changing this code |

## Requirements

* **Java 21 or 25** — the build targets 21; 25 works and is where
  [Phase 1](docs/roadmap.md#phase-1--platform-upgrade) is heading
* **Docker** — required to run the tests, optional for running the application
* **MySQL 8** to run the application (being migrated to PostgreSQL). `compose.yaml` provides one
* Maven — use the bundled wrapper, no local install needed

## Running locally

### 1. Database

```bash
docker compose up -d
```

That starts MySQL 8 on port 3306 with the `file_management` database and user already created.
Flyway creates the tables on first application start. If you prefer your own MySQL, create the
database and user yourself — nothing else is needed.

### 2. Configuration

Nothing in the repository holds a credential. Every setting is an environment variable with a
sensible default, except the database password, which has none so that a misconfigured deployment
fails at startup instead of silently trying a password from the git history.

```bash
export FILEMANAGEMENT_DB_PASSWORD=file_management
export FILEMANAGEMENT_BASE_DIR=D:/LocalStorage/Project/Data/main/
export FILEMANAGEMENT_LOG_PATH=D:/LocalStorage/Project/Data/logs
```

Or copy `src/main/resources/application-local.properties.example` to
`application-local.properties` (gitignored) and run with the `local` profile:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod,local
```

`FILEMANAGEMENT_BASE_DIR` **must already exist** and **must end with a separator** — the storage
service concatenates rather than resolves. The application creates category and sub-category
directories underneath it as you create them in the UI.

### 3. Run

```bash
./mvnw spring-boot:run
```

The application listens on **http://localhost:8122**.

The `prod` profile (the default) seeds the permission table, the `ADMIN` and `USER` roles, and an
administrator account:

| Username | Password |
|---|---|
| `Admin` | `admin` |

**Change it immediately on any non-local deployment** — it is created unconditionally at every
startup.

### 4. Build a deployable artefact

```bash
./mvnw clean package
```

Produces `target/file-management.jar`, a self-contained executable:

```bash
java -jar target/file-management.jar
```

(A container image is still [Phase 1](docs/roadmap.md#phase-1--platform-upgrade).)

## Running the tests

```bash
./mvnw verify
```

**A running Docker daemon is the only requirement.** The suite starts its own throwaway MySQL
through Testcontainers and uses `./target/test-storage/` as the storage root, so there is nothing
to provision and nothing machine-specific to configure.

87 tests: one `@SpringBootTest` smoke test that proves the whole context starts, and eight
`@DataJpaTest` service suites. The shared plumbing lives in
`src/test/java/com/hnp/filemanagement/support/`.

## Concepts

**The taxonomy.** Files are filed five levels deep:

```
GeneralTag → FileCategory → FileSubCategory → MainTagFile → FileInfo → FileDetails
```

`FileCategory` and `FileSubCategory` become real directories, so their names may not contain `.`,
spaces or `/`.

**FileInfo vs FileDetails.** A `FileInfo` is the *logical* file — "the Q3 report". A `FileDetails`
is one concrete artefact of it: a specific version in a specific format. Uploading `report.pdf`
creates one `FileInfo` and one `FileDetails` (v1, pdf). Uploading `report.docx` as a *format* of v1
adds a second `FileDetails` at the same version. Uploading a revised `report.pdf` as a *version*
adds a `FileDetails` at v2 and bumps `FileInfo.lastVersion`.

Uploaded file names must match the `FileInfo` name and contain exactly one `.` and no spaces.

**Public vs private.** `FileInfo.state = 0` is public, `-1` is private. A file is downloadable
without authentication (`/files/public-download/{id}`) only when both its `FileInfo` and its
`FileDetails` have `state = 0`.

**Permissions.** Authorities are fine-grained per-endpoint constants from `PermissionEnum`, granted
through roles. A user in a role named `ADMIN` additionally receives the synthetic `ADMIN` authority,
which satisfies every check.

**Active Directory.** Optional, off by default:

```properties
filemanagement.auth.ldap.activedirectory.enabled=true
filemanagement.auth.ldap.activedirectory.domain=your.domain
filemanagement.auth.ldap.activedirectory.url=ldap://your-dc
```

`User.loginType` controls which backend may authenticate a given account: `0` either, `1` local
database only, `2` Active Directory only.

## Storage layout

```
{base-dir}/{Category}/{SubCategory}/{fileNameWithoutExtension}/v{version}/{fileName}.{ext}
```

## Configuration reference

| Property | Default | Meaning |
|---|---|---|
| `server.port` | `8122` | |
| `spring.profiles.active` | `prod` | `prod` seeds permissions, roles and the admin user |
| `spring.datasource.*` | — | MySQL connection |
| `spring.jpa.hibernate.ddl-auto` | `validate` | schema is owned by Flyway |
| `file.management.base-dir` | `./TempFiles/files/main/` | storage root; must exist and end with a separator |
| `spring.servlet.multipart.max-file-size` | `20MB` | per-file upload cap |
| `spring.servlet.multipart.max-request-size` | `20MB` | per-request upload cap |
| `filemanagement.default.page-size` | `30` | rows per page in list views |
| `filemanagement.default.element-size` | `30` | items per dropdown |
| `filemanagement.auth.ldap.activedirectory.enabled` | `false` | |

## REST API

`/api/v1/files`, HTTP Basic, stateless. Requires the matching `API_*` permission or `ADMIN`.

| Method | Path |
|---|---|
| `GET` | `/api/v1/files/health-test` |
| `POST` | `/api/v1/files` — multipart; `?public-file=0` stores it private |
| `DELETE` | `/api/v1/files/file-info/{fileInfoId}/file-details/{fileDetailsId}` |
| `GET` | `/api/v1/files/file-info/{fileInfoId}/file-details/{fileDetailsId}/download` |

Full endpoint inventory, including the UI's own `/resource/**` endpoints, is in
[docs/arch.md](docs/arch.md#endpoint-inventory).

## Supported upload types

`pdf`, `txt`, `docx`, `xlsx`, `pptx`, `jpeg`, `png`, `mp4`, `mp3`.

Validation currently trusts the `Content-Type` the client sends — see
[issue 12](docs/issues.md#12-file-type-validation-trusts-the-client--s1).

## Repository

```
├── .github/workflows/    CI: build and test on JDK 21 and 25
├── docs/                 architecture, issues, roadmap
├── compose.yaml          MySQL for local runs
├── src/main/java/        application code
├── src/main/resources/
│   ├── db/migration/     Flyway migrations — the only source of schema
│   ├── templates/        Thymeleaf views
│   └── static/           CSS and the public landing page
└── src/test/java/
    ├── support/          Testcontainers and storage-root plumbing
    └── service/          service integration tests
```
