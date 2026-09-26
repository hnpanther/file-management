# Database schema

The database as it is **now** — every table, column, key and index — after all migrations have
run. PostgreSQL only since release C (2.1.0): `src/main/resources/db/migration/V3.0__Baseline.sql`
is the baseline every database starts from, and the only thing that changes the schema is the next
`V3.x` beside it; this file is the present, so nobody has to replay them in their head.

**The table section below is generated, not written.** `SchemaDocumentationTest` migrates a fresh
PostgreSQL with Flyway, reads its catalogue (`information_schema`, and `pg_index` / `pg_constraint`
for expression indexes and which index is a key), renders it, and fails the build if what is
committed here differs. When a migration changes the schema, regenerate and commit both together:

```bash
./mvnw test -Dtest=SchemaDocumentationTest -DargLine=-Dschema.doc.write=true
```

**Where it differs from the MySQL the application ran on until 2026-09-26** - so that nothing below
reads as an accident: timestamps are `timestamp(0)`, keeping whole seconds as MySQL's `DATETIME`
did; ids are identities; the one flag is a `boolean`; a name MySQL's collation compared without
case is unique on `upper(column)` (`uq_user_username`, `uq_user_email`, `uq_role_role_name`,
`uq_tag_group_name`, `uq_tag_name_per_group`, `uq_folder_sibling_name`,
`uq_file_info_name_per_folder`, `uq_file_details_version_format`); `ix_folder_path` is
`varchar_pattern_ops`, for prefix `LIKE`; and every foreign key has its own index, named after the
constraint (`fk_...`), because PostgreSQL makes none by itself.

Everything above the marker is written by hand and kept short: what the tables are *for* is in
[arch.md](arch.md#4-the-domain-model), and why each one is shaped the way it is lives in the
comment block at the top of `V3.0` and of the MySQL migration that first created it (in git
history since release C).

## Creating the database and its user

The one thing Flyway cannot do is create the database it runs in, or the account it connects as.
That is done once, by hand, as a PostgreSQL superuser, before the first start - an account that
owns its database and nothing else, the database `UTF8` with ICU's root locale. The statements,
what each clause is for, what goes into the configuration afterwards and how to check the result
are in [deployment.md](deployment.md#creating-the-database-and-its-account), the one place they
are written down.

Flyway does everything after that on the first boot, and `DataInitializer` seeds the permissions,
the two roles and the `Admin` account. Nothing else is run by hand.

### A throw-away developer database

`compose.yaml` brings up a disposable PostgreSQL with the right settings; `docker compose down -v`
throws it away, data and all, with nothing to type. For a database on a server you intend to
destroy and recreate by hand, as a superuser:

```sql
DROP DATABASE IF EXISTS file_management;
DROP ROLE IF EXISTS file_management;
```

**Never on a server with real data.** A file that begins by dropping the database, carries no
warning, and can be run against the wrong connection is a live footgun - which is why the old
`schema-db/schema.sql` that began that way was deleted in Phase 0
([issue 32](issues.md#32-schema-dbschemasql-is-a-live-footgun--s1)). The reset is documented here;
it is not shipped as a script, on purpose.

## How the tables relate

```
                       user ──< user_role >── role ──< permission_role >── permission
                        │                       │
                        │ owner / grants        │ grants
                        ▼                       ▼
                   user_folder             role_folder                api_key ──< api_key_folder
                        └──────────────┬────────┘                        │            │
                                       ▼                                 │            ▼
                                     folder  ◄───────────────────────────┴──── (folder)
                                       │        one tree, any depth up to a limit:
                                       │        Home > FOLDER > FOLDER > ... (V2.9)
                                       │        (a depth-1 row carries a tag_group_id;
                                       │         Home > Profiles > {username} is a
                                       │         user's own, owner_user_id + quota_bytes)
                              folder_id│
                                       ▼
                                   file_info ──< file_details ──< file_share_link
                                       │                          (a temporary link to
                                       │                           one revision, V2.12)
                                       │          file_storage_write names a storage_key
                                       │          while it is being written (V2.13); no
                                       │          foreign key - it outlives the transaction
                                       └──< file_tag >── tag >── tag_group
                                            (a file's tags: every folder name on its
                                             chain, in the top-level folder's group)

      action_history            every mutation, by entity and id
      flyway_schema_history     Flyway's own ledger; not described below
```

Two groups:

| Group | Tables | State |
|---|---|---|
| **Identity and permissions** | `app_user` (`user` before `V2.14`), `role`, `user_role`, `permission`, `permission_role`, `api_key`, `api_key_folder`, `upload_policy`, `upload_policy_rule`, `content_kind`, `app_setting` | stable |
| **Where a file is** | `folder`, `role_folder`, `user_folder`, `file_info.folder_id` | authoritative since `V2.8` (Phase 7 step 4); any depth since `V2.9`; `folder_id` is `NOT NULL` and names any folder but the root or `Profiles`. `folder.kind` is `ROOT`, `FOLDER`, `PROFILES` or `USER_HOME`; `owner_user_id` names a personal folder's user (unique) and `quota_bytes` caps what may be stored beneath any folder (`V2.11`) |
| **What a file is, and about** | `file_info`, `file_details`, `tag_group`, `tag`, `file_tag` | stable; a file's tags are derived from its folder chain, `tag_group` is the label group a category carries |
| **Who may have it without signing in** | `file_share_link` | `V2.12`: one row per temporary link - the token's SHA-256, the revision it names (cascade), expiry, optional password hash and download cap, the counters and the revocation |
| **Byte writes in flight** | `file_storage_write` | `V2.13`: one row per upload while its bytes are being written, committed before the write and removed when the transaction ends. Empty except during an upload; what is left in it is what a killed process abandoned, and `StorageSweeper` settles it against `file_details.storage_key` |

The taxonomy tables (`general_tag`, `file_category`, `file_sub_category`, `main_tag_file`) and the
columns that pointed at them (`file_info.file_sub_category_id` / `main_tag_file_id`,
`folder.general_tag_id` / `source_type` / `source_id`, and `file_path` / `relative_path` on
`file_info` and `file_details`) were dropped by `V2.8`.

Conventions that hold everywhere: `id INT AUTO_INCREMENT` primary keys; `created_at` / `updated_at`
written by Hibernate in the JVM's zone; `created_by` / `updated_by` are foreign keys to `app_user`
(nullable where a migration, not a person, may have written the row); `enabled` and `state` are
the magic-number columns described in [arch.md](arch.md#magic-number-columns).

<!-- generated from information_schema by SchemaDocumentationTest: do not edit below this line -->
_As of migration `V3.0`. Types and defaults are PostgreSQL's own; every table is in the `public` schema of a `UTF8` database with ICU's root collation ([deployment.md](deployment.md#creating-the-database-and-its-account))._

### `action_history`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `integer` | no |  | identity |
| `entity_name` | `varchar(100)` | no |  |  |
| `table_name` | `varchar(100)` | no |  |  |
| `entity_id` | `integer` | no |  |  |
| `action` | `varchar(100)` | no |  |  |
| `action_description` | `varchar(1000)` | yes |  |  |
| `description` | `varchar(1000)` | yes |  |  |
| `user_id` | `integer` | no |  |  |
| `enabled` | `integer` | no |  |  |
| `state` | `integer` | no |  |  |
| `created_at` | `timestamp(0)` | no |  |  |

* **primary key** `id`
* **foreign key** `fk_action_history_user_id` `user_id` → `app_user` (`id`)
* **index** `fk_action_history_user_id` (`user_id`)
* **index** `ix_action_history_entity` (`entity_name`, `entity_id`)

### `api_key`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `integer` | no |  | identity |
| `key_id` | `varchar(32)` | no |  |  |
| `secret_hash` | `varchar(64)` | no |  |  |
| `title` | `varchar(100)` | no |  |  |
| `description` | `varchar(500)` | yes |  |  |
| `enabled` | `integer` | no | `1` |  |
| `expires_at` | `timestamp(0)` | yes |  |  |
| `revoked_at` | `timestamp(0)` | yes |  |  |
| `last_used_at` | `timestamp(0)` | yes |  |  |
| `created_at` | `timestamp(0)` | no |  |  |
| `updated_at` | `timestamp(0)` | yes |  |  |
| `created_by` | `integer` | no |  |  |
| `updated_by` | `integer` | yes |  |  |

* **primary key** `id`
* **unique** `uq_api_key_key_id` (`key_id`)
* **foreign key** `fk_api_key_created_by_user` `created_by` → `app_user` (`id`)
* **foreign key** `fk_api_key_updated_by_user` `updated_by` → `app_user` (`id`)
* **index** `fk_api_key_created_by_user` (`created_by`)
* **index** `fk_api_key_updated_by_user` (`updated_by`)

### `api_key_folder`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `api_key_id` | `integer` | no |  |  |
| `folder_id` | `integer` | no |  |  |
| `permission` | `varchar(10)` | no | `READ` |  |

* **primary key** `api_key_id`, `folder_id`
* **foreign key** `fk_api_key_folder_folder` `folder_id` → `folder` (`id`), on delete cascade
* **foreign key** `fk_api_key_folder_key` `api_key_id` → `api_key` (`id`), on delete cascade
* **index** `fk_api_key_folder_folder` (`folder_id`)
* **index** `ix_api_key_folder_key` (`api_key_id`)

### `app_setting`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `integer` | no |  | identity |
| `setting_key` | `varchar(100)` | no |  |  |
| `setting_value` | `varchar(500)` | no |  |  |
| `updated_at` | `timestamp(0)` | yes |  |  |
| `updated_by` | `integer` | yes |  |  |

* **primary key** `id`
* **unique** `uq_app_setting_key` (`setting_key`)
* **foreign key** `fk_app_setting_updated_by_user` `updated_by` → `app_user` (`id`)
* **index** `fk_app_setting_updated_by_user` (`updated_by`)

### `app_user`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `integer` | no |  | identity |
| `username` | `varchar(150)` | no |  |  |
| `personel_code` | `integer` | no |  |  |
| `national_code` | `varchar(10)` | no |  |  |
| `email` | `varchar(150)` | yes |  |  |
| `phone_number` | `varchar(15)` | yes |  |  |
| `password` | `varchar(100)` | no |  |  |
| `first_name` | `varchar(250)` | no |  |  |
| `last_name` | `varchar(250)` | no |  |  |
| `created_at` | `timestamp(0)` | no |  |  |
| `updated_at` | `timestamp(0)` | yes |  |  |
| `login_type` | `integer` | no | `0` |  |
| `enabled` | `integer` | no |  |  |
| `state` | `integer` | no |  |  |

* **primary key** `id`
* **unique** `uq_user_email` (`upper(email)`)
* **unique** `uq_user_national_code` (`national_code`)
* **unique** `uq_user_personel_code` (`personel_code`)
* **unique** `uq_user_phone_number` (`phone_number`)
* **unique** `uq_user_username` (`upper(username)`)
* **index** `ix_user_created_at` (`created_at`)

### `content_kind`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `integer` | no |  | identity |
| `extension` | `varchar(16)` | no |  |  |
| `media_type` | `varchar(255)` | no |  |  |
| `signature_hex` | `varchar(64)` | yes |  |  |
| `signature_offset` | `integer` | no | `0` |  |
| `text_only` | `boolean` | no | `false` |  |
| `description` | `varchar(500)` | yes |  |  |
| `created_at` | `timestamp(0)` | no |  |  |
| `updated_at` | `timestamp(0)` | yes |  |  |
| `created_by` | `integer` | yes |  |  |
| `updated_by` | `integer` | yes |  |  |

* **primary key** `id`
* **unique** `uq_content_kind_extension` (`extension`)
* **foreign key** `fk_content_kind_created_by_user` `created_by` → `app_user` (`id`)
* **foreign key** `fk_content_kind_updated_by_user` `updated_by` → `app_user` (`id`)
* **index** `fk_content_kind_created_by_user` (`created_by`)
* **index** `fk_content_kind_updated_by_user` (`updated_by`)

### `file_details`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `integer` | no |  | identity |
| `file_info_id` | `integer` | no |  |  |
| `external_id` | `varchar(36)` | no |  |  |
| `file_name` | `varchar(100)` | no |  |  |
| `search_name` | `varchar(200)` | no |  |  |
| `file_extension` | `varchar(10)` | no |  |  |
| `content_type` | `varchar(100)` | no |  |  |
| `version` | `integer` | no |  |  |
| `version_name` | `varchar(100)` | no |  |  |
| `version_name_description` | `varchar(1000)` | yes |  |  |
| `description` | `varchar(1000)` | no |  |  |
| `search_description` | `varchar(2000)` | no |  |  |
| `storage_key` | `varchar(1000)` | no |  |  |
| `file_link` | `varchar(1000)` | yes |  |  |
| `file_size` | `bigint` | no |  |  |
| `checksum_sha256` | `varchar(64)` | yes |  |  |
| `enabled` | `integer` | no |  |  |
| `state` | `integer` | no |  |  |
| `created_at` | `timestamp(0)` | no |  |  |
| `updated_at` | `timestamp(0)` | yes |  |  |
| `created_by` | `integer` | no |  |  |
| `updated_by` | `integer` | yes |  |  |

* **primary key** `id`
* **unique** `uq_file_details_external_id` (`external_id`)
* **unique** `uq_file_details_version_format` (`file_info_id`, `version`, `upper(file_extension)`)
* **foreign key** `fk_file_details_created_by_user` `created_by` → `app_user` (`id`)
* **foreign key** `fk_file_details_file_info_id` `file_info_id` → `file_info` (`id`)
* **foreign key** `fk_file_details_updated_by_user` `updated_by` → `app_user` (`id`)
* **index** `fk_file_details_created_by_user` (`created_by`)
* **index** `fk_file_details_updated_by_user` (`updated_by`)
* **index** `ix_file_details_file_info_version` (`file_info_id`, `version`)
* **index** `ix_file_details_state` (`state`)

### `file_info`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `integer` | no |  | identity |
| `external_id` | `varchar(36)` | no |  |  |
| `file_name` | `varchar(100)` | no |  |  |
| `search_name` | `varchar(200)` | no |  |  |
| `code_name` | `varchar(300)` | no |  |  |
| `file_name_description` | `varchar(500)` | no |  |  |
| `description` | `varchar(1000)` | yes |  |  |
| `search_description` | `varchar(2000)` | yes |  |  |
| `file_link` | `varchar(1000)` | yes |  |  |
| `last_version` | `integer` | no |  |  |
| `folder_id` | `integer` | no |  |  |
| `enabled` | `integer` | no |  |  |
| `state` | `integer` | no |  |  |
| `created_at` | `timestamp(0)` | no |  |  |
| `updated_at` | `timestamp(0)` | yes |  |  |
| `created_by` | `integer` | no |  |  |
| `updated_by` | `integer` | yes |  |  |

* **primary key** `id`
* **unique** `uq_file_info_external_id` (`external_id`)
* **unique** `uq_file_info_name_per_folder` (`folder_id`, `upper(file_name)`)
* **foreign key** `fk_file_info_created_by_user` `created_by` → `app_user` (`id`)
* **foreign key** `fk_file_info_folder` `folder_id` → `folder` (`id`)
* **foreign key** `fk_file_info_updated_by_user` `updated_by` → `app_user` (`id`)
* **index** `fk_file_info_created_by_user` (`created_by`)
* **index** `fk_file_info_updated_by_user` (`updated_by`)
* **index** `ix_file_info_created_at` (`created_at`)
* **index** `ix_file_info_folder` (`folder_id`)
* **index** `ix_file_info_state` (`state`)

### `file_share_link`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `integer` | no |  | identity |
| `token_hash` | `varchar(64)` | no |  |  |
| `file_details_id` | `integer` | no |  |  |
| `expires_at` | `timestamp(0)` | no |  |  |
| `password_hash` | `varchar(100)` | yes |  |  |
| `max_downloads` | `integer` | yes |  |  |
| `download_count` | `integer` | no | `0` |  |
| `failed_attempts` | `integer` | no | `0` |  |
| `locked_until` | `timestamp(0)` | yes |  |  |
| `revoked_at` | `timestamp(0)` | yes |  |  |
| `created_at` | `timestamp(0)` | no |  |  |
| `created_by` | `integer` | no |  |  |

* **primary key** `id`
* **unique** `uq_file_share_link_token` (`token_hash`)
* **foreign key** `fk_file_share_link_created_by_user` `created_by` → `app_user` (`id`)
* **foreign key** `fk_file_share_link_file_details` `file_details_id` → `file_details` (`id`), on delete cascade
* **index** `ix_file_share_link_created_by` (`created_by`)
* **index** `ix_file_share_link_file_details` (`file_details_id`)

### `file_storage_write`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `integer` | no |  | identity |
| `storage_key` | `varchar(1000)` | no |  |  |
| `created_at` | `timestamp(0)` | no |  |  |

* **primary key** `id`
* **index** `ix_file_storage_write_created_at` (`created_at`)

### `file_tag`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `file_info_id` | `integer` | no |  |  |
| `tag_id` | `integer` | no |  |  |

* **primary key** `file_info_id`, `tag_id`
* **foreign key** `fk_file_tag_file_info` `file_info_id` → `file_info` (`id`)
* **foreign key** `fk_file_tag_tag` `tag_id` → `tag` (`id`)
* **index** `ix_file_tag_tag` (`tag_id`)

### `folder`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `integer` | no |  | identity |
| `parent_id` | `integer` | yes |  |  |
| `name` | `varchar(100)` | no |  |  |
| `search_name` | `varchar(200)` | no |  |  |
| `display_name` | `varchar(200)` | no |  |  |
| `search_display_name` | `varchar(400)` | no |  |  |
| `path` | `varchar(1000)` | no |  |  |
| `depth` | `integer` | no |  |  |
| `kind` | `varchar(30)` | no |  |  |
| `owner_user_id` | `integer` | yes |  |  |
| `tag_group_id` | `integer` | yes |  |  |
| `quota_bytes` | `bigint` | yes |  |  |
| `enabled` | `integer` | no |  |  |
| `state` | `integer` | no |  |  |
| `created_at` | `timestamp(0)` | no |  |  |
| `updated_at` | `timestamp(0)` | yes |  |  |
| `created_by` | `integer` | yes |  |  |
| `updated_by` | `integer` | yes |  |  |

* **primary key** `id`
* **unique** `uq_folder_owner_user` (`owner_user_id`)
* **unique** `uq_folder_sibling_name` (`parent_id`, `upper(name)`)
* **foreign key** `fk_folder_created_by_user` `created_by` → `app_user` (`id`)
* **foreign key** `fk_folder_owner_user` `owner_user_id` → `app_user` (`id`)
* **foreign key** `fk_folder_parent` `parent_id` → `folder` (`id`)
* **foreign key** `fk_folder_tag_group` `tag_group_id` → `tag_group` (`id`)
* **foreign key** `fk_folder_updated_by_user` `updated_by` → `app_user` (`id`)
* **index** `fk_folder_created_by_user` (`created_by`)
* **index** `fk_folder_tag_group` (`tag_group_id`)
* **index** `fk_folder_updated_by_user` (`updated_by`)
* **index** `ix_folder_parent` (`parent_id`)
* **index** `ix_folder_path` (`path`, for prefix `LIKE` (`varchar_pattern_ops`))

### `permission`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `integer` | no |  | identity |
| `permission_name` | `varchar(100)` | no |  |  |
| `description` | `varchar(1500)` | yes |  |  |

* **primary key** `id`
* **unique** `uq_permission_permission` (`permission_name`)

### `permission_role`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `integer` | no |  | identity |
| `role_id` | `integer` | no |  |  |
| `permission_id` | `integer` | no |  |  |

* **primary key** `id`
* **unique** `uq_permission_role` (`role_id`, `permission_id`)
* **foreign key** `fk_permission_role_permission_id` `permission_id` → `permission` (`id`)
* **foreign key** `fk_permission_role_role_id` `role_id` → `role` (`id`)
* **index** `fk_permission_role_permission_id` (`permission_id`)

### `role`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `integer` | no |  | identity |
| `role_name` | `varchar(100)` | no |  |  |

* **primary key** `id`
* **unique** `uq_role_role_name` (`upper(role_name)`)

### `role_folder`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `role_id` | `integer` | no |  |  |
| `folder_id` | `integer` | no |  |  |
| `permission` | `varchar(10)` | no | `READ` |  |

* **primary key** `role_id`, `folder_id`
* **foreign key** `fk_role_folder_folder` `folder_id` → `folder` (`id`), on delete cascade
* **foreign key** `fk_role_folder_role` `role_id` → `role` (`id`), on delete cascade
* **index** `fk_role_folder_folder` (`folder_id`)
* **index** `ix_role_folder_role` (`role_id`)

### `tag`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `integer` | no |  | identity |
| `group_id` | `integer` | yes |  |  |
| `name` | `varchar(100)` | no |  |  |
| `title` | `varchar(200)` | no |  |  |
| `enabled` | `integer` | no |  |  |
| `created_at` | `timestamp(0)` | no |  |  |
| `updated_at` | `timestamp(0)` | yes |  |  |
| `created_by` | `integer` | yes |  |  |
| `updated_by` | `integer` | yes |  |  |

* **primary key** `id`
* **unique** `uq_tag_name_per_group` (`group_id`, `upper(name)`)
* **foreign key** `fk_tag_created_by_user` `created_by` → `app_user` (`id`)
* **foreign key** `fk_tag_group` `group_id` → `tag_group` (`id`)
* **foreign key** `fk_tag_updated_by_user` `updated_by` → `app_user` (`id`)
* **index** `fk_tag_created_by_user` (`created_by`)
* **index** `fk_tag_updated_by_user` (`updated_by`)

### `tag_group`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `integer` | no |  | identity |
| `name` | `varchar(100)` | no |  |  |
| `title` | `varchar(200)` | no |  |  |
| `enabled` | `integer` | no |  |  |
| `created_at` | `timestamp(0)` | no |  |  |
| `updated_at` | `timestamp(0)` | yes |  |  |
| `created_by` | `integer` | yes |  |  |
| `updated_by` | `integer` | yes |  |  |

* **primary key** `id`
* **unique** `uq_tag_group_name` (`upper(name)`)
* **foreign key** `fk_tag_group_created_by_user` `created_by` → `app_user` (`id`)
* **foreign key** `fk_tag_group_updated_by_user` `updated_by` → `app_user` (`id`)
* **index** `fk_tag_group_created_by_user` (`created_by`)
* **index** `fk_tag_group_updated_by_user` (`updated_by`)

### `upload_policy`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `integer` | no |  | identity |
| `role_id` | `integer` | yes |  |  |
| `created_at` | `timestamp(0)` | no |  |  |
| `updated_at` | `timestamp(0)` | yes |  |  |
| `created_by` | `integer` | yes |  |  |
| `updated_by` | `integer` | yes |  |  |

* **primary key** `id`
* **unique** `uq_upload_policy_role` (`role_id`)
* **foreign key** `fk_upload_policy_created_by_user` `created_by` → `app_user` (`id`)
* **foreign key** `fk_upload_policy_role` `role_id` → `role` (`id`), on delete cascade
* **foreign key** `fk_upload_policy_updated_by_user` `updated_by` → `app_user` (`id`)
* **index** `fk_upload_policy_created_by_user` (`created_by`)
* **index** `fk_upload_policy_updated_by_user` (`updated_by`)

### `upload_policy_rule`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `integer` | no |  | identity |
| `policy_id` | `integer` | no |  |  |
| `extension` | `varchar(16)` | no |  |  |
| `max_size_bytes` | `bigint` | no |  |  |

* **primary key** `id`
* **unique** `uq_upload_policy_rule` (`policy_id`, `extension`)
* **foreign key** `fk_upload_policy_rule_policy` `policy_id` → `upload_policy` (`id`), on delete cascade

### `user_folder`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `user_id` | `integer` | no |  |  |
| `folder_id` | `integer` | no |  |  |
| `permission` | `varchar(10)` | no | `READ` |  |

* **primary key** `user_id`, `folder_id`
* **foreign key** `fk_user_folder_folder` `folder_id` → `folder` (`id`), on delete cascade
* **foreign key** `fk_user_folder_user` `user_id` → `app_user` (`id`), on delete cascade
* **index** `fk_user_folder_folder` (`folder_id`)
* **index** `ix_user_folder_user` (`user_id`)

### `user_role`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `integer` | no |  | identity |
| `user_id` | `integer` | no |  |  |
| `role_id` | `integer` | no |  |  |

* **primary key** `id`
* **unique** `uq_user_role` (`user_id`, `role_id`)
* **foreign key** `fk_user_role_role_id` `role_id` → `role` (`id`)
* **foreign key** `fk_user_role_user_id` `user_id` → `app_user` (`id`)
* **index** `fk_user_role_role_id` (`role_id`)

<!-- end of generated section -->
