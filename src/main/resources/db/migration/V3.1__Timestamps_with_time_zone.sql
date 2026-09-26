-- Every timestamp becomes an instant (issue 24, 2.2.0): TIMESTAMP(0) WITHOUT TIME ZONE to
-- TIMESTAMPTZ(0), and LocalDateTime to Instant in the entities.
--
-- Until now a timestamp was the wall clock of whatever server wrote it, with nothing to say which
-- zone that was. It held because every server so far ran on Iran Standard Time; a host or a
-- container left on UTC - the default of both - would have written the next rows three and a half
-- hours off the old ones, with nothing to tell them apart. An instant does not depend on the
-- server, and the zone the pages show it in is a setting of its own (filemanagement.time-zone).
--
-- The rows already written are read as Asia/Tehran - the zone their servers ran in, checked
-- before this was written: all 1370 revisions of a production-like copy had a created_at within
-- five seconds of the modification time of their bytes, read as Tehran time. The named zone, not
-- a fixed +03:30, so that a time from before 1401 is read with the summer time Iran then kept
-- (+04:30); in the one hour a year that repeated when the clocks went back, PostgreSQL takes the
-- standard-time reading. An installation whose servers ran elsewhere must change the zone below
-- before the first start of 2.2.0 - never after it has run, when Flyway's checksum holds it.
--
-- The precision stays whole seconds, as before. Each table is rewritten once, with its indexes
-- on these columns (ix_*_created_at); on the production database that is seconds, under the
-- exclusive lock ALTER TABLE takes - the service is stopped for the upgrade anyway.

ALTER TABLE action_history
    ALTER COLUMN created_at TYPE TIMESTAMPTZ(0) USING created_at AT TIME ZONE 'Asia/Tehran';

ALTER TABLE api_key
    ALTER COLUMN expires_at   TYPE TIMESTAMPTZ(0) USING expires_at   AT TIME ZONE 'Asia/Tehran',
    ALTER COLUMN revoked_at   TYPE TIMESTAMPTZ(0) USING revoked_at   AT TIME ZONE 'Asia/Tehran',
    ALTER COLUMN last_used_at TYPE TIMESTAMPTZ(0) USING last_used_at AT TIME ZONE 'Asia/Tehran',
    ALTER COLUMN created_at   TYPE TIMESTAMPTZ(0) USING created_at   AT TIME ZONE 'Asia/Tehran',
    ALTER COLUMN updated_at   TYPE TIMESTAMPTZ(0) USING updated_at   AT TIME ZONE 'Asia/Tehran';

ALTER TABLE app_setting
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ(0) USING updated_at AT TIME ZONE 'Asia/Tehran';

ALTER TABLE app_user
    ALTER COLUMN created_at TYPE TIMESTAMPTZ(0) USING created_at AT TIME ZONE 'Asia/Tehran',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ(0) USING updated_at AT TIME ZONE 'Asia/Tehran';

ALTER TABLE content_kind
    ALTER COLUMN created_at TYPE TIMESTAMPTZ(0) USING created_at AT TIME ZONE 'Asia/Tehran',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ(0) USING updated_at AT TIME ZONE 'Asia/Tehran';

ALTER TABLE file_details
    ALTER COLUMN created_at TYPE TIMESTAMPTZ(0) USING created_at AT TIME ZONE 'Asia/Tehran',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ(0) USING updated_at AT TIME ZONE 'Asia/Tehran';

ALTER TABLE file_info
    ALTER COLUMN created_at TYPE TIMESTAMPTZ(0) USING created_at AT TIME ZONE 'Asia/Tehran',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ(0) USING updated_at AT TIME ZONE 'Asia/Tehran';

ALTER TABLE file_share_link
    ALTER COLUMN expires_at   TYPE TIMESTAMPTZ(0) USING expires_at   AT TIME ZONE 'Asia/Tehran',
    ALTER COLUMN locked_until TYPE TIMESTAMPTZ(0) USING locked_until AT TIME ZONE 'Asia/Tehran',
    ALTER COLUMN revoked_at   TYPE TIMESTAMPTZ(0) USING revoked_at   AT TIME ZONE 'Asia/Tehran',
    ALTER COLUMN created_at   TYPE TIMESTAMPTZ(0) USING created_at   AT TIME ZONE 'Asia/Tehran';

ALTER TABLE file_storage_write
    ALTER COLUMN created_at TYPE TIMESTAMPTZ(0) USING created_at AT TIME ZONE 'Asia/Tehran';

ALTER TABLE folder
    ALTER COLUMN created_at TYPE TIMESTAMPTZ(0) USING created_at AT TIME ZONE 'Asia/Tehran',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ(0) USING updated_at AT TIME ZONE 'Asia/Tehran';

ALTER TABLE tag
    ALTER COLUMN created_at TYPE TIMESTAMPTZ(0) USING created_at AT TIME ZONE 'Asia/Tehran',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ(0) USING updated_at AT TIME ZONE 'Asia/Tehran';

ALTER TABLE tag_group
    ALTER COLUMN created_at TYPE TIMESTAMPTZ(0) USING created_at AT TIME ZONE 'Asia/Tehran',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ(0) USING updated_at AT TIME ZONE 'Asia/Tehran';

ALTER TABLE upload_policy
    ALTER COLUMN created_at TYPE TIMESTAMPTZ(0) USING created_at AT TIME ZONE 'Asia/Tehran',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ(0) USING updated_at AT TIME ZONE 'Asia/Tehran';

-- Nothing may be left behind: a timestamp column still without a zone would be read by Hibernate
-- as an instant in the JVM's zone, which is exactly the dependency this removes.
DO
$$
DECLARE
    leftover TEXT;
BEGIN
    SELECT string_agg(table_name || '.' || column_name, ', ')
    INTO leftover
    FROM information_schema.columns
    WHERE table_schema = current_schema()
      AND data_type = 'timestamp without time zone'
      AND table_name <> 'flyway_schema_history';
    IF leftover IS NOT NULL THEN
        RAISE EXCEPTION 'timestamp columns without a time zone remain: %', leftover;
    END IF;
END
$$;
