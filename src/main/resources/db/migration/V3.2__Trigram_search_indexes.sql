-- Indexes that serve the searches (issue 21, 2.2.0).
--
-- Every search in the application is a fragment of a folded key: SearchKey folds the name, the
-- description and the folder labels in Java (case, Persian and Arabic digits, the half-space,
-- the marks), and a query asks for REPLACE(key, ' ', '') LIKE '%term%'. A B-tree cannot serve a
-- LIKE that begins with a wildcard, so each search read every row - and the file list read, for
-- every file, every folder above it: 73 seconds for one search of 200,000 files.
--
-- pg_trgm's GIN index serves exactly that LIKE, on the same expression the queries use, and
-- leaves what matches exactly as it was: the index only narrows the rows, and PostgreSQL checks
-- each against the LIKE itself. Full-text search (tsvector) was the other candidate and is not
-- this: it matches words and their stems, not fragments - "1403" would no longer find
-- "report-1403-final" - and PostgreSQL has no Persian dictionary to stem with. It is the tool
-- for the text inside documents, which is Phase 8's.
--
-- The expression must stay character for character what Hibernate writes for
-- REPLACE(x.searchName, ' ', ''), or the planner will not match it to the index; the test
-- SearchIndexTest asks PostgreSQL for the plan of each search and fails if an index is not used.
--
-- pg_trgm is a trusted extension (PostgreSQL 13 and later): the database's owner - the account
-- the application connects as (docs/deployment.md) - may create it without being a superuser.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX ix_file_info_search_name_trgm
    ON file_info USING gin (replace(search_name, ' ', '') gin_trgm_ops);
CREATE INDEX ix_file_info_search_description_trgm
    ON file_info USING gin (replace(search_description, ' ', '') gin_trgm_ops);

CREATE INDEX ix_file_details_search_name_trgm
    ON file_details USING gin (replace(search_name, ' ', '') gin_trgm_ops);
CREATE INDEX ix_file_details_search_description_trgm
    ON file_details USING gin (replace(search_description, ' ', '') gin_trgm_ops);

CREATE INDEX ix_folder_search_name_trgm
    ON folder USING gin (replace(search_name, ' ', '') gin_trgm_ops);
CREATE INDEX ix_folder_search_display_name_trgm
    ON folder USING gin (replace(search_display_name, ' ', '') gin_trgm_ops);
