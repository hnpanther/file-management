# Database schema

The database as it is **now** — every table, column, key and index — after all migrations have
run. The migrations in `src/main/resources/db/migration` are the history and the only thing that
changes the schema; this file is the present, so nobody has to replay them in their head.

**The table section below is generated, not written.** `SchemaDocumentationTest` migrates a fresh
MySQL with Flyway, reads `information_schema`, renders it, and fails the build if what is committed
here differs. When a migration changes the schema, regenerate and commit both together:

```bash
./mvnw test -Dtest=SchemaDocumentationTest -DargLine=-Dschema.doc.write=true
```

Everything above the marker is written by hand and kept short: what the tables are *for* is in
[arch.md](arch.md#4-the-domain-model), and why each one is shaped the way it is lives in the
comment block at the top of the migration that created it.

## Creating the database and its user

The one thing Flyway cannot do is create the database it runs in, or the account it connects as.
That is done once, by hand, as a MySQL administrator, before the first start:

```sql
CREATE DATABASE file_management
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER 'file_management'@'localhost' IDENTIFIED BY 'a real password';

GRANT ALL PRIVILEGES ON file_management.* TO 'file_management'@'localhost';
FLUSH PRIVILEGES;
```

Line by line:

* **`CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`** is not optional. Every table below is
  created with the same pair, and a database default that differs would give a table added later
  without an explicit clause a different collation — and then a `JOIN` between the two fails with
  "illegal mix of collations". `utf8mb4` is what Persian text and emoji need; `unicode_ci` is what
  makes the uniqueness constraints compare names case-insensitively, which the pre-flight queries
  in [deployment.md](deployment.md#upgrading-from-100-to-110) rely on.
* **A dedicated user.** The application must not connect as `root`. `ALL PRIVILEGES ON
  file_management.*` is the least that works — Flyway needs `CREATE`, `ALTER`, `INDEX` and
  `REFERENCES` on this schema to run migrations at start-up — and it is scoped to this one schema:
  no global privilege, no `GRANT OPTION`, nothing on `mysql.*`.
* **`@'localhost'`** is right when the application runs on the same machine as MySQL and connects
  over loopback, which both service definitions do. If the application is on another host, the
  account must be created for that host (`'file_management'@'10.0.0.5'`, or `@'%'` behind a
  firewall) — a `'localhost'` account is invisible to a remote connection and the error is a bare
  "Access denied".
* **The password** goes into `FILEMANAGEMENT_DB_PASSWORD` or the external `application.properties`
  ([deployment.md](deployment.md#configuring-it-from-outside-the-jar)) and nowhere else.
  `file_management` — the password the repository ships as a default — is published in this
  repository and must never be the real one.
* **`USE file_management;`** is for a person at the MySQL prompt about to run the verification
  queries below or in a migration's header. The application never needs it: its JDBC URL names
  the schema.

Flyway does everything after that on the first boot, and `DataInitializer` seeds the permissions,
the two roles and the `Admin` account. Nothing else is run by hand.

### A throw-away developer database

For a local database you intend to destroy and recreate, the same statements are preceded by:

```sql
DROP DATABASE IF EXISTS file_management;
DROP USER IF EXISTS 'file_management'@'localhost';
```

**Never on a server with real data.** Those two lines are the opening of the old
`schema-db/schema.sql`, which was deleted in Phase 0 precisely because a file that begins by
dropping the production database, carries no warning, and can be run against the wrong
connection is a live footgun
([issue 32](issues.md#32-schema-dbschemasql-is-a-live-footgun--s1)). They are shown here so that
the reset is documented; they are not shipped as a script on purpose. Developers should prefer
`compose.yaml`, which brings up a disposable MySQL with the right settings and no reset to type.

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
                                       │        (a depth-1 row carries a tag_group_id)
                              folder_id│
                                       ▼
                                   file_info ──< file_details
                                       │
                                       └──< file_tag >── tag >── tag_group
                                            (a file's tags: every folder name on its
                                             chain, in the top-level folder's group)

      action_history            every mutation, by entity and id
      flyway_schema_history     Flyway's own ledger; not described below
```

Two groups:

| Group | Tables | State |
|---|---|---|
| **Identity and permissions** | `user`, `role`, `user_role`, `permission`, `permission_role`, `api_key`, `api_key_folder`, `upload_policy`, `upload_policy_rule`, `content_kind`, `app_setting` | stable |
| **Where a file is** | `folder`, `role_folder`, `user_folder`, `file_info.folder_id` | authoritative since `V2.8` (Phase 7 step 4); any depth since `V2.9`; `folder_id` is `NOT NULL` and names any folder but the root |
| **What a file is, and about** | `file_info`, `file_details`, `tag_group`, `tag`, `file_tag` | stable; a file's tags are derived from its folder chain, `tag_group` is the label group a category carries |

The taxonomy tables (`general_tag`, `file_category`, `file_sub_category`, `main_tag_file`) and the
columns that pointed at them (`file_info.file_sub_category_id` / `main_tag_file_id`,
`folder.general_tag_id` / `source_type` / `source_id`, and `file_path` / `relative_path` on
`file_info` and `file_details`) were dropped by `V2.8`.

Conventions that hold everywhere: `id INT AUTO_INCREMENT` primary keys; `created_at` / `updated_at`
written by Hibernate in the JVM's zone; `created_by` / `updated_by` are foreign keys to `user`
(nullable where a migration, not a person, may have written the row); `enabled` and `state` are
the magic-number columns described in [arch.md](arch.md#magic-number-columns).

<!-- generated from information_schema by SchemaDocumentationTest: do not edit below this line -->
_As of migration `V2.11`. Types and defaults are MySQL's own; every table is InnoDB, `utf8mb4` / `utf8mb4_unicode_ci` unless a column says otherwise._

### `action_history`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `int` | no |  | auto-increment |
| `entity_name` | `varchar(100)` | no |  |  |
| `table_name` | `varchar(100)` | no |  |  |
| `entity_id` | `int` | no |  |  |
| `action` | `varchar(100)` | no |  |  |
| `action_description` | `varchar(1000)` | yes |  |  |
| `description` | `varchar(1000)` | yes |  |  |
| `user_id` | `int` | no |  |  |
| `enabled` | `int` | no |  |  |
| `state` | `int` | no |  |  |
| `created_at` | `datetime` | no |  |  |

* **primary key** `id`
* **foreign key** `fk_action_history_user_id` `user_id` → `user` (`id`)
* **index** `ix_action_history_entity` (`entity_name`, `entity_id`)

### `api_key`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `int` | no |  | auto-increment |
| `key_id` | `varchar(32)` | no |  |  |
| `secret_hash` | `varchar(64)` | no |  |  |
| `title` | `varchar(100)` | no |  |  |
| `description` | `varchar(500)` | yes |  |  |
| `enabled` | `int` | no | `1` |  |
| `expires_at` | `datetime` | yes |  |  |
| `revoked_at` | `datetime` | yes |  |  |
| `last_used_at` | `datetime` | yes |  |  |
| `created_at` | `datetime` | no |  |  |
| `updated_at` | `datetime` | yes |  |  |
| `created_by` | `int` | no |  |  |
| `updated_by` | `int` | yes |  |  |

* **primary key** `id`
* **unique** `uq_api_key_key_id` (`key_id`)
* **foreign key** `fk_api_key_created_by_user` `created_by` → `user` (`id`)
* **foreign key** `fk_api_key_updated_by_user` `updated_by` → `user` (`id`)

### `api_key_folder`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `api_key_id` | `int` | no |  |  |
| `folder_id` | `int` | no |  |  |
| `permission` | `varchar(10)` | no | `READ` |  |

* **primary key** `api_key_id`, `folder_id`
* **foreign key** `fk_api_key_folder_folder` `folder_id` → `folder` (`id`), on delete cascade
* **foreign key** `fk_api_key_folder_key` `api_key_id` → `api_key` (`id`), on delete cascade
* **index** `ix_api_key_folder_key` (`api_key_id`)

### `app_setting`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `int` | no |  | auto-increment |
| `setting_key` | `varchar(100)` | no |  |  |
| `setting_value` | `varchar(500)` | no |  |  |
| `updated_at` | `datetime` | yes |  |  |
| `updated_by` | `int` | yes |  |  |

* **primary key** `id`
* **unique** `uq_app_setting_key` (`setting_key`)
* **foreign key** `fk_app_setting_updated_by_user` `updated_by` → `user` (`id`)

### `content_kind`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `int` | no |  | auto-increment |
| `extension` | `varchar(16)` | no |  |  |
| `media_type` | `varchar(255)` | no |  |  |
| `signature_hex` | `varchar(64)` | yes |  |  |
| `signature_offset` | `int` | no | `0` |  |
| `text_only` | `tinyint(1)` | no | `0` |  |
| `description` | `varchar(500)` | yes |  |  |
| `created_at` | `datetime` | no |  |  |
| `updated_at` | `datetime` | yes |  |  |
| `created_by` | `int` | yes |  |  |
| `updated_by` | `int` | yes |  |  |

* **primary key** `id`
* **unique** `uq_content_kind_extension` (`extension`)
* **foreign key** `fk_content_kind_created_by_user` `created_by` → `user` (`id`)
* **foreign key** `fk_content_kind_updated_by_user` `updated_by` → `user` (`id`)

### `file_details`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `int` | no |  | auto-increment |
| `file_info_id` | `int` | no |  |  |
| `hash_id` | `varchar(300)` | no |  |  |
| `file_name` | `varchar(100)` | no |  |  |
| `file_extension` | `varchar(10)` | no |  |  |
| `content_type` | `varchar(100)` | no |  |  |
| `version` | `int` | no |  |  |
| `version_name` | `varchar(100)` | no |  |  |
| `version_name_description` | `varchar(1000)` | yes |  |  |
| `description` | `varchar(1000)` | no |  |  |
| `storage_key` | `varchar(1000)` | no |  |  |
| `file_link` | `varchar(1000)` | yes |  |  |
| `file_size` | `int` | no |  |  |
| `enabled` | `int` | no |  |  |
| `state` | `int` | no |  |  |
| `created_at` | `datetime` | no |  |  |
| `updated_at` | `datetime` | yes |  |  |
| `created_by` | `int` | no |  |  |
| `updated_by` | `int` | yes |  |  |

* **primary key** `id`
* **unique** `uq_file_details_hash_id` (`hash_id`)
* **unique** `uq_file_details_version_format` (`file_info_id`, `version`, `file_extension`)
* **foreign key** `fk_file_details_created_by_user` `created_by` → `user` (`id`)
* **foreign key** `fk_file_details_file_info_id` `file_info_id` → `file_info` (`id`)
* **foreign key** `fk_file_details_updated_by_user` `updated_by` → `user` (`id`)
* **index** `ix_file_details_file_info_version` (`file_info_id`, `version`)
* **index** `ix_file_details_state` (`state`)

### `file_info`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `int` | no |  | auto-increment |
| `file_name` | `varchar(100)` | no |  |  |
| `code_name` | `varchar(300)` | no |  |  |
| `file_name_description` | `varchar(500)` | no |  |  |
| `description` | `varchar(1000)` | yes |  |  |
| `file_link` | `varchar(1000)` | yes |  |  |
| `last_version` | `int` | no |  |  |
| `folder_id` | `int` | no |  |  |
| `enabled` | `int` | no |  |  |
| `state` | `int` | no |  |  |
| `created_at` | `datetime` | no |  |  |
| `updated_at` | `datetime` | yes |  |  |
| `created_by` | `int` | no |  |  |
| `updated_by` | `int` | yes |  |  |

* **primary key** `id`
* **unique** `uq_file_info_name_per_folder` (`folder_id`, `file_name`)
* **foreign key** `fk_file_info_created_by_user` `created_by` → `user` (`id`)
* **foreign key** `fk_file_info_folder` `folder_id` → `folder` (`id`)
* **foreign key** `fk_file_info_updated_by_user` `updated_by` → `user` (`id`)
* **index** `ix_file_info_created_at` (`created_at`)
* **index** `ix_file_info_folder` (`folder_id`)
* **index** `ix_file_info_state` (`state`)

### `file_tag`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `file_info_id` | `int` | no |  |  |
| `tag_id` | `int` | no |  |  |

* **primary key** `file_info_id`, `tag_id`
* **foreign key** `fk_file_tag_file_info` `file_info_id` → `file_info` (`id`)
* **foreign key** `fk_file_tag_tag` `tag_id` → `tag` (`id`)
* **index** `ix_file_tag_tag` (`tag_id`)

### `folder`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `int` | no |  | auto-increment |
| `parent_id` | `int` | yes |  |  |
| `name` | `varchar(100)` | no |  |  |
| `display_name` | `varchar(200)` | no |  |  |
| `path` | `varchar(1000)` | no |  | ascii |
| `depth` | `int` | no |  |  |
| `kind` | `varchar(30)` | no |  |  |
| `owner_user_id` | `int` | yes |  |  |
| `tag_group_id` | `int` | yes |  |  |
| `quota_bytes` | `bigint` | yes |  |  |
| `enabled` | `int` | no |  |  |
| `state` | `int` | no |  |  |
| `created_at` | `datetime` | no |  |  |
| `updated_at` | `datetime` | yes |  |  |
| `created_by` | `int` | yes |  |  |
| `updated_by` | `int` | yes |  |  |

* **primary key** `id`
* **unique** `uq_folder_owner_user` (`owner_user_id`)
* **unique** `uq_folder_sibling_name` (`parent_id`, `name`)
* **foreign key** `fk_folder_created_by_user` `created_by` → `user` (`id`)
* **foreign key** `fk_folder_owner_user` `owner_user_id` → `user` (`id`)
* **foreign key** `fk_folder_parent` `parent_id` → `folder` (`id`)
* **foreign key** `fk_folder_tag_group` `tag_group_id` → `tag_group` (`id`)
* **foreign key** `fk_folder_updated_by_user` `updated_by` → `user` (`id`)
* **index** `ix_folder_parent` (`parent_id`)
* **index** `ix_folder_path` (`path`)

### `permission`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `int` | no |  | auto-increment |
| `permission_name` | `varchar(100)` | no |  |  |
| `description` | `varchar(1500)` | yes |  |  |

* **primary key** `id`
* **unique** `uq_permission_permission` (`permission_name`)

### `permission_role`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `int` | no |  | auto-increment |
| `role_id` | `int` | no |  |  |
| `permission_id` | `int` | no |  |  |

* **primary key** `id`
* **unique** `uq_permission_role` (`role_id`, `permission_id`)
* **foreign key** `fk_permission_role_permission_id` `permission_id` → `permission` (`id`)
* **foreign key** `fk_permission_role_role_id` `role_id` → `role` (`id`)

### `role`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `int` | no |  | auto-increment |
| `role_name` | `varchar(100)` | no |  |  |

* **primary key** `id`
* **unique** `uq_role_role_name` (`role_name`)

### `role_folder`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `role_id` | `int` | no |  |  |
| `folder_id` | `int` | no |  |  |
| `permission` | `varchar(10)` | no | `READ` |  |

* **primary key** `role_id`, `folder_id`
* **foreign key** `fk_role_folder_folder` `folder_id` → `folder` (`id`), on delete cascade
* **foreign key** `fk_role_folder_role` `role_id` → `role` (`id`), on delete cascade
* **index** `ix_role_folder_role` (`role_id`)

### `tag`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `int` | no |  | auto-increment |
| `group_id` | `int` | yes |  |  |
| `name` | `varchar(100)` | no |  |  |
| `title` | `varchar(200)` | no |  |  |
| `enabled` | `int` | no |  |  |
| `created_at` | `datetime` | no |  |  |
| `updated_at` | `datetime` | yes |  |  |
| `created_by` | `int` | yes |  |  |
| `updated_by` | `int` | yes |  |  |

* **primary key** `id`
* **unique** `uq_tag_name_per_group` (`group_id`, `name`)
* **foreign key** `fk_tag_created_by_user` `created_by` → `user` (`id`)
* **foreign key** `fk_tag_group` `group_id` → `tag_group` (`id`)
* **foreign key** `fk_tag_updated_by_user` `updated_by` → `user` (`id`)

### `tag_group`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `int` | no |  | auto-increment |
| `name` | `varchar(100)` | no |  |  |
| `title` | `varchar(200)` | no |  |  |
| `enabled` | `int` | no |  |  |
| `created_at` | `datetime` | no |  |  |
| `updated_at` | `datetime` | yes |  |  |
| `created_by` | `int` | yes |  |  |
| `updated_by` | `int` | yes |  |  |

* **primary key** `id`
* **unique** `uq_tag_group_name` (`name`)
* **foreign key** `fk_tag_group_created_by_user` `created_by` → `user` (`id`)
* **foreign key** `fk_tag_group_updated_by_user` `updated_by` → `user` (`id`)

### `upload_policy`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `int` | no |  | auto-increment |
| `role_id` | `int` | yes |  |  |
| `created_at` | `datetime` | no |  |  |
| `updated_at` | `datetime` | yes |  |  |
| `created_by` | `int` | yes |  |  |
| `updated_by` | `int` | yes |  |  |

* **primary key** `id`
* **unique** `uq_upload_policy_role` (`role_id`)
* **foreign key** `fk_upload_policy_created_by_user` `created_by` → `user` (`id`)
* **foreign key** `fk_upload_policy_role` `role_id` → `role` (`id`), on delete cascade
* **foreign key** `fk_upload_policy_updated_by_user` `updated_by` → `user` (`id`)

### `upload_policy_rule`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `int` | no |  | auto-increment |
| `policy_id` | `int` | no |  |  |
| `extension` | `varchar(16)` | no |  |  |
| `max_size_bytes` | `bigint` | no |  |  |

* **primary key** `id`
* **unique** `uq_upload_policy_rule` (`policy_id`, `extension`)
* **foreign key** `fk_upload_policy_rule_policy` `policy_id` → `upload_policy` (`id`), on delete cascade

### `user`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `int` | no |  | auto-increment |
| `username` | `varchar(150)` | no |  |  |
| `personel_code` | `int` | no |  |  |
| `national_code` | `varchar(10)` | no |  |  |
| `email` | `varchar(150)` | yes |  |  |
| `phone_number` | `varchar(15)` | yes |  |  |
| `password` | `varchar(100)` | no |  |  |
| `first_name` | `varchar(250)` | no |  |  |
| `last_name` | `varchar(250)` | no |  |  |
| `created_at` | `datetime` | no |  |  |
| `updated_at` | `datetime` | yes |  |  |
| `login_type` | `int` | no | `0` |  |
| `enabled` | `int` | no |  |  |
| `state` | `int` | no |  |  |

* **primary key** `id`
* **unique** `uq_user_email` (`email`)
* **unique** `uq_user_national_code` (`national_code`)
* **unique** `uq_user_personel_code` (`personel_code`)
* **unique** `uq_user_phone_number` (`phone_number`)
* **unique** `uq_user_username` (`username`)
* **index** `ix_user_created_at` (`created_at`)

### `user_folder`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `user_id` | `int` | no |  |  |
| `folder_id` | `int` | no |  |  |
| `permission` | `varchar(10)` | no | `READ` |  |

* **primary key** `user_id`, `folder_id`
* **foreign key** `fk_user_folder_folder` `folder_id` → `folder` (`id`), on delete cascade
* **foreign key** `fk_user_folder_user` `user_id` → `user` (`id`), on delete cascade
* **index** `ix_user_folder_user` (`user_id`)

### `user_role`

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `int` | no |  | auto-increment |
| `user_id` | `int` | no |  |  |
| `role_id` | `int` | no |  |  |

* **primary key** `id`
* **unique** `uq_user_role` (`user_id`, `role_id`)
* **foreign key** `fk_user_role_role_id` `role_id` → `role` (`id`)
* **foreign key** `fk_user_role_user_id` `user_id` → `user` (`id`)

<!-- end of generated section -->
