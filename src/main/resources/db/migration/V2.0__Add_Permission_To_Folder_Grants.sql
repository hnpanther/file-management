-- Folder grants gain a verb (roadmap 9.1).
--
-- V1.5 created these two tables with one grant and no verb, and said so:
--
--     "There is deliberately no per-folder verb and no deny rule. One grant, inherited. Adding
--      'read here, write there' later is a column on these two tables rather than a new model -
--      but it is not added before something actually needs it."
--
-- Two things now need it. An API key has to be able to say "read this folder, write that one"
-- (roadmap 9.2), and uploading is still not checked against folder access at all - a user granted
-- one department's folder can file a document into any other by naming its tag on the form
-- (issues.md, issue 76). Neither can be expressed while a grant is a single boolean.
--
-- WRITE implies READ. It is one column rather than two rows or two tables precisely so that the
-- two cannot drift apart: there is no way to represent "may write but may not read", which is a
-- state nothing wants and every check would have to defend against.
--
-- Existing rows become READ, which is exactly what they mean today, so this migration changes no
-- behaviour on its own. The DEFAULT does the backfill in the same statement and is kept afterwards:
-- it costs nothing and makes a hand-written INSERT default to the weaker grant rather than the
-- stronger one.

ALTER TABLE role_folder
    ADD COLUMN permission VARCHAR(10) NOT NULL DEFAULT 'READ' AFTER folder_id;

ALTER TABLE user_folder
    ADD COLUMN permission VARCHAR(10) NOT NULL DEFAULT 'READ' AFTER folder_id;

-- Stored as a string rather than an ordinal, for the same reason `folder.kind` is: adding a value
-- later cannot silently renumber the rows already written (issues.md, issue 22).
--
-- No CHECK constraint. MySQL 8 enforces them, but the two tables are also read by the folder-access
-- resolver on every request that lists anything, and an unknown string there has to fail closed in
-- Java regardless - a constraint would only move the failure earlier for rows this application
-- writes, and it writes them from an enum.
