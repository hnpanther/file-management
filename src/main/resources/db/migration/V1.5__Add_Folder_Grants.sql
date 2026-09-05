-- Folder-level access: the second of the two questions authorization has to answer (roadmap 6.5).
--
-- The first question - "may this user perform this operation at all?" - is the existing
-- PermissionEnum on each endpoint, and it does not change. This adds "may this user touch *this*
-- folder?", which is inherited: a grant on a folder covers everything beneath it, found by a prefix
-- scan on folder.path. Both questions must pass.
--
-- There is deliberately no per-folder verb and no deny rule. One grant, inherited. Adding
-- "read here, write there" later is a column on these two tables rather than a new model - but it is
-- not added before something actually needs it.
--
-- A role therefore carries two things: a set of permissions (permission_role, already there) and a
-- set of folders (role_folder, here). A user's reachable folders are their roles' grants plus their
-- own direct grants.

CREATE TABLE role_folder
(
    role_id   INT NOT NULL,
    folder_id INT NOT NULL,
    PRIMARY KEY (role_id, folder_id),
    -- ON DELETE CASCADE on both sides: a grant that names a folder or a role that no longer exists
    -- is meaningless, and nothing maps this table as an entity, so the database is the only thing
    -- that can clean it up. Without it, deleting a mirrored folder would fail on a foreign key -
    -- which would let the access tables block a legitimate taxonomy delete.
    CONSTRAINT fk_role_folder_role FOREIGN KEY (role_id) REFERENCES role (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_folder_folder FOREIGN KEY (folder_id) REFERENCES folder (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE TABLE user_folder
(
    user_id   INT NOT NULL,
    folder_id INT NOT NULL,
    PRIMARY KEY (user_id, folder_id),
    CONSTRAINT fk_user_folder_user FOREIGN KEY (user_id) REFERENCES user (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_folder_folder FOREIGN KEY (folder_id) REFERENCES folder (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- Resolving a user's access reads every grant they have, on every request that lists anything.
CREATE INDEX ix_role_folder_role ON role_folder (role_id);
CREATE INDEX ix_user_folder_user ON user_folder (user_id);

-- ---------------------------------------------------------------- not done here, and why
--
-- Roadmap 6.7 also asks for a Home/{username} folder per user, created with the user and granted to
-- them. It is deliberately not created here:
--
--   * it could not hold anything. A file hangs off a main tag, not off a folder, until roadmap 6.8
--     makes `folder` authoritative - so every user home would be an empty folder that no upload can
--     ever reach;
--   * `folder.name` is documented as directory-safe (no '.', no ' ', no '/') because 6.8 turns these
--     rows into real directories, and six of the eight usernames in this installation contain a dot
--     (f.zakeri, h.mirzaeizadeh, ...). Writing them as folder names would either break that contract
--     immediately or need every user home renamed later.
--
-- The USER_HOME kind and the owner_user_id column stay in place for when 6.8 gets there. Whether
-- usernames should be constrained to directory-safe characters is recorded as issue 74.
