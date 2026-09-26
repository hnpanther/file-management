-- PostgreSQL release A (roadmap 3.3, docs/issues.md issue 30): the table of accounts is app_user.
--
-- USER is a reserved word in PostgreSQL - an unquoted `user` there means CURRENT_USER - and the
-- plan is to stop saying anything MySQL-specific before the move rather than during it. Quoting
-- the name instead would spread into every native query and every operator's psql session, so
-- the table is renamed, here, on MySQL, where it can run in production for a while before any
-- PostgreSQL is involved.
--
-- Nothing else changes. InnoDB re-points every foreign key that referenced `user` (twenty-two of
-- them, from action_history to user_role) at app_user, keeps each one's name and its ON DELETE
-- rule, and keeps the table's own indexes and their names (uq_user_username and the rest) -
-- checked on 8.0.36 and 8.4 before this was written, and asserted after every migration run by
-- PortableSchemaMigrationTest. The ids are untouched, so everything that refers to an
-- account by id - action_history, created_by, the PL/SQL clients - keeps meaning the same thing.
--
-- Anything outside the application that queries the table by name - a report, a view in another
-- schema, a hand-written script - has to say app_user from this release on.

RENAME TABLE user TO app_user;
