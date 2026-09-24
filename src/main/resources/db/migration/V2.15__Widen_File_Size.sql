-- PostgreSQL release A (roadmap 3.3, docs/issues.md issue 6): a revision's size is a 64-bit number.
--
-- file_size was a 32-bit INT, so a file past 2 GiB would have been stored as a negative number
-- (FileService cast the long it was given to an int). The 20 MB multipart cap means no row in
-- any installation is near that, and every existing value fits unchanged; the point of doing it
-- now, on MySQL, is that the PostgreSQL baseline is not the first place the entity meets the
-- wider column. quota_bytes and max_size_bytes, the other two byte counts, were BIGINT already.
--
-- MODIFY restates the whole definition: NOT NULL, no default - as V1.0 declared it.

ALTER TABLE file_details
    MODIFY COLUMN file_size BIGINT NOT NULL;
