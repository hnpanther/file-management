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

The interface is deliberately narrow and path-shaped:

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

Name rules enforced at the storage boundary: directory names must contain **zero** of `.`, ` `, `/`;
file names must contain **exactly one** `.` and zero of ` `, `/`.

## 6. HTTP layers

There are three parallel HTTP surfaces over the same services:

| Package | Base path | Returns | Auth | Purpose |
|---|---|---|---|---|
| `controller/` | `/files`, `/file-categories`, `/file-sub-categories`, `/main-tags`, `/general-tags`, `/users`, `/roles`, `/` | Thymeleaf view names | form login, session | the UI |
| `resource/` | `/resource/**` | JSON / plain text | form login, session, CSRF | AJAX called by the pages themselves |
| `api/` | `/api/v1/files` | JSON | HTTP Basic, stateless | external integrations |

### Endpoint inventory

<details>
<summary>Thymeleaf controllers</summary>

| Method | Path | Permission |
|---|---|---|
| GET | `/` | `ACCESS_HOME` |
| GET | `/login` | permitAll |
| GET / POST | `/files/create`, `/files` | `CREATE_FILE_PAGE`, `SAVE_NEW_FILE` |
| GET | `/files/public-files` | `PUBLIC_FILE_PAGE` (path is also permitAll in the chain) |
| GET | `/files/public-download/{id}` | permitAll (`?inline=1` switches Content-Disposition) |
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

</details>

<details>
<summary>REST — /api/v1/files (HTTP Basic)</summary>

| Method | Path | Permission |
|---|---|---|
| GET | `/health-test` | `API_HEALTH_TEST` |
| POST | `/` (multipart, `?public-file=0` for private) | `API_SAVE_NEW_FILE` |
| DELETE | `/file-info/{fileInfoId}/file-details/{fileDetailsId}` | `API_DELETE_FILE_DETAILS` |
| GET | `/file-info/{fileInfoId}/file-details/{fileDetailsId}/download` | `API_DOWNLOAD_FILE` |

</details>

## 7. Security model

### Two filter chains

`SecurityConfig` publishes two `SecurityFilterChain` beans:

* **`@Order(1)` `apiSecurityFilterChain`** — `securityMatcher("/api/**")`, CSRF off, CORS off,
  `SessionCreationPolicy.STATELESS`, `httpBasic()`.
* **`@Order(2)` `securityFilterChain`** — everything else. CSRF **on** (all AJAX templates read
  `_csrf` / `_csrf_header` from `<meta>` tags and set the header), form login at `/login`,
  logout at `/logout`. PermitAll list: `/`, `/favicon.ico`, `/webjars/**`, `/css/**`, `/js/**`,
  `/public-pages/**`, `/files/public-files/**`, `/files/public-download/**`.

### Authentication

`AuthenticationManagerBuilder` is assembled conditionally on
`filemanagement.auth.ldap.activedirectory.enabled`:

* **off** → `DaoAuthenticationProvider` only (BCrypt against the `user` table).
* **on** → `ActiveDirectoryCustomAuthenticationProvider` first, then `DaoAuthenticationProvider`.

`User.loginType` gates which provider may accept a user: `0` = either, `1` = local DB only,
`2` = Active Directory only. `UserDetailsServiceImpl` refuses `loginType != 0 && != 1`; the AD
provider refuses `loginType != 0 && != 2`.

### Authorization

Authorities are **not** roles — they are `PermissionEnum` constants, one per handler method
(~70 of them). `UserDetailsServiceImpl` loads a user's permissions through their roles and, if
any role is named `ADMIN`, additionally grants the synthetic `ADMIN` authority. Every handler
carries `@PreAuthorize("hasAuthority('X') || hasAuthority('ADMIN')")`.

There is no resource-scoped authorization: holding `DOWNLOAD_FILE` grants download of *every*
file, private ones included.

### Bootstrap

`FileManagementApplication.runner` runs only when `spring.profiles.active=prod`. It inserts any
missing `PermissionEnum` value, creates the `ADMIN` and `USER` roles, and creates user
`Admin` / `admin` with the `ADMIN` role if absent.

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

**Exception handling** — two `@ControllerAdvice` beans:

* `GlobalApiExceptionHandler` (`assignableTypes = FileApi.class`) → `ResponseEntity` with the raw
  exception message as the body.
* `GlobalExceptionHandler` (global) → `ModelAndView("error.html")`.

Custom exceptions also carry `@ResponseStatus` (`BusinessException` → 417, `DuplicateResourceException`
and `DependencyResourceException` → 409, `InvalidDataException` → 400, `ResourceNotFoundException` → 404),
which the advices then override.

**Mapping** — `ModelConverterUtil` holds ~300 lines of static entity→DTO methods.

## 9. Request flow — uploading a new file

```
POST /files (multipart)
  └─ FileController.saveNewFile
       ├─ @PreAuthorize SAVE_NEW_FILE || ADMIN
       ├─ @Validated(InsertValidation) → @ValidFile checks MultipartFile.getContentType()
       └─ FileService.createNewFile(dto, principalId, publicFile)          @Transactional
            ├─ MainTagFileService.getMainTagFileByIdOrTagName
            ├─ verify tag.subCategory / tag.subCategory.category match the form
            ├─ isDuplicate(baseName, subCategoryId)
            ├─ ValidationUtil.checkCorrectFileName
            ├─ build FileInfo (paths, state, lastVersion = 1)
            ├─ build FileDetails v1 (hashId = random UUID)
            ├─ fileInfoRepository.save(fileInfo)          ← cascades to FileDetails
            ├─ actionHistoryService.saveActionHistory × 2
            └─ fileStorageService.save(address, multipartFile, 1, ext)   ← disk write, LAST
```

The disk write happens inside the transaction but is not part of it — see
[issues.md](issues.md#3-storage-writes-are-not-atomic-with-the-database--s1).

## 10. Database schema

Flyway migrations in `src/main/resources/db/migration`:

| Version | Contents |
|---|---|
| `V1.0__Initial_Setup.sql` | `user`, `role`, `user_role`, `permission`, `permission_role`, `general_tag`, `file_category`, `file_sub_category`, `main_tag_file`, `file_info`, `file_details` |
| `V1.1__Add_LoginType_To_User.sql` | `user.login_type INT NOT NULL DEFAULT 0 AFTER updated_at` |
| `V1.2__Add_Action_History_Table.sql` | `action_history` |

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

`src/test/java/.../service/` holds eight service tests. They are **integration** tests:
`@ExtendWith(SpringExtension.class)`, `@TestPropertySource("classpath:application.properties")`,
`@AutoConfigureTestDatabase(replace = NONE)` — i.e. they need a live MySQL at
`localhost:3306/file_management_test` and a writable `D:/files/test/`.

`FileManagementApplicationTests` — both `@SpringBootTest` and `@Test` are commented out, so
nothing verifies that the application context starts.

## 13. Known structural weaknesses

Catalogued in full in [issues.md](issues.md). The ones that shape the architecture:

1. Three HTTP layers, three error shapes, one service layer — no shared contract.
2. `FileStorageService`'s signature is filesystem-shaped, blocking S3.
3. Storage and database mutations are not atomic in either direction.
4. `@Table(name = "user")` — a reserved word in PostgreSQL.
5. Every `@ManyToOne` is `EAGER`; `ModelConverterUtil` walks the full graph on every list page.
6. Authorization is per-endpoint, never per-resource.
