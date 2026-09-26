-- The stored object gets a key of its own, independent of the taxonomy (roadmap 7.1).
--
-- ---------------------------------------------------------------- what this changes
--
-- Today the structure *is* the path. A download resolves where the bytes live by walking the
-- taxonomy at read time - `FileService.directoryOf()` builds
-- `{categoryName}/{subCategoryName}` from the rows as they are *now* - and then appends the file
-- name, the version and the extension. So the location of every stored byte is a function of names
-- that Phase 7 is about to make editable and movable.
--
-- After this column, a read resolves the location from the row that was written when the bytes were
-- written. Renaming or moving a folder becomes a metadata change: no byte moves, and nothing has to
-- rewrite `file_path` and `relative_path` across two tables to keep the files findable - which is
-- exactly the migration shape that loses files (issues.md, issue 35).
--
-- ---------------------------------------------------------------- why the backfill is safe
--
-- `file_details.relative_path` already holds precisely this value. `FileService` writes it as
-- `{categoryName}/{subCategoryName}/{fileName}/v{n}/{fileName}.{ext}`, which is the whole relative
-- path of the stored object, and it is written at the moment the bytes are written.
--
-- It also cannot have drifted from the path a read derives, because **nothing renames a category or
-- a sub-category**: `FileCategoryService.updateCategoryNameDescription` and
-- `FileSubCategoryService.updateFileSubCategory` change the *description* only, and the technical
-- name is set once at creation. So for every existing row the stored value and the derived value are
-- the same string, and this migration cannot move a file that reads today into a location that does
-- not read tomorrow.
--
-- Verify it anyway, before deploying, with:
--
--     SELECT fd.id, fd.relative_path
--     FROM file_details fd
--         JOIN file_info fi ON fi.id = fd.file_info_id
--         JOIN main_tag_file mt ON mt.id = fi.main_tag_file_id
--         JOIN file_sub_category sc ON sc.id = mt.file_sub_category_id
--         JOIN file_category c ON c.id = sc.file_category_id
--     WHERE fd.relative_path <> CONCAT(c.category_name, '/', sc.sub_category_name, '/',
--                                      fi.file_name, '/v', fd.version, '/', fd.file_name);
--
-- An empty result means every row's stored location and derived location agree, which is what
-- `FileServiceStorageKeyTest` asserts on generated data.
--
-- ---------------------------------------------------------------- what is deliberately not here
--
-- No UNIQUE constraint. The key is unique by construction - file names are unique per sub-category
-- (`uq_file_info_name_per_sub_category`) and a format cannot be stored twice at one version
-- (`existsByFileInfoAndVersionAndFormat`) - but roadmap 7.4 is explicit that a constraint goes on
-- *after* a pre-flight query has been run against the real data, not before. Adding it here would
-- turn a data problem nobody has looked at into a failed migration.
--
-- Note for whoever adds it: `VARCHAR(1000)` in utf8mb4 is 4000 bytes, past MySQL's 3072-byte index
-- limit, so it needs a prefix index or a narrower column. The width here mirrors `relative_path`
-- exactly so that the backfill cannot truncate.
--
-- No index either: nothing looks a row up *by* key. The key is read from a row that has already
-- been found, so an index would cost writes and serve no query.

ALTER TABLE file_details
    ADD COLUMN storage_key VARCHAR(1000) NOT NULL DEFAULT '' AFTER relative_path;

UPDATE file_details SET storage_key = relative_path;

-- The default existed only to let the column be added NOT NULL to a table with rows in it. Dropping
-- it means a future insert that forgets the key fails loudly instead of storing an empty string
-- that would read as "the base directory itself".
ALTER TABLE file_details
    ALTER COLUMN storage_key DROP DEFAULT;
