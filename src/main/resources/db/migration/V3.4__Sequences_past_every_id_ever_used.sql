-- Every identity sequence past every id its table has ever used (issue 98, 2.5.0).
--
-- The copy of the cut-over (2.0.0, roadmap 3.5) set each sequence after the largest id left in its
-- table. MySQL's AUTO_INCREMENT had gone further - past rows since deleted - so on PostgreSQL the
-- ids of the last rows deleted before the move were free again, and new rows took them: a new file
-- got the number of one deleted days earlier, and the audit trail, which names rows by number,
-- joined the two.
--
-- The rows are gone, but action_history still names every one it recorded: an entity id it holds
-- is an id that table has used. Each sequence is moved to the larger of its table's largest id
-- and the largest id the audit trail names for it - and only ever forward: a sequence already past
-- both is left as it is, and so is an empty table with no history (a fresh database keeps giving
-- 1 first). Safe to run on any database, as many times as it takes.
--
-- Only the entities whose action_history.entity_id is an id of a table that still exists are
-- listed; the taxonomy's (FileCategory and the like) named tables dropped in V2.8, and the join
-- rows (UserRole, PermissionRole) are not named by an id of their own.

DO
$$
DECLARE
    entry     RECORD;
    sequence  TEXT;
    highest   BIGINT;
    last      BIGINT;
    called    BOOLEAN;
    next_id   BIGINT;
BEGIN
    FOR entry IN
        SELECT *
        FROM (VALUES ('FileInfo', 'file_info'),
                     ('FileDetails', 'file_details'),
                     ('Folder', 'folder'),
                     ('User', 'app_user'),
                     ('Role', 'role'),
                     ('ApiKey', 'api_key'),
                     ('UploadPolicy', 'upload_policy'),
                     ('ContentKind', 'content_kind'),
                     ('TagGroup', 'tag_group'),
                     ('AppSetting', 'app_setting'),
                     ('FileShareLink', 'file_share_link')) AS mapping (entity, table_name)
    LOOP
        sequence := pg_get_serial_sequence(entry.table_name, 'id');
        EXECUTE format('SELECT greatest((SELECT coalesce(max(id), 0) FROM %I),
                                        (SELECT coalesce(max(entity_id), 0) FROM action_history WHERE entity_name = %L))',
                       entry.table_name, entry.entity)
            INTO highest;
        EXECUTE format('SELECT last_value, is_called FROM %s', sequence) INTO last, called;
        next_id := CASE WHEN called THEN last + 1 ELSE last END;

        IF highest >= next_id THEN
            PERFORM setval(sequence, highest, true);
            RAISE NOTICE '% moved from % to %: its next id was used before', sequence, next_id, highest + 1;
        END IF;
    END LOOP;
END
$$;
