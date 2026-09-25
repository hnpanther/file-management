-- Three permissions that no endpoint ever checked, removed with their constants in PermissionEnum.
--
-- ACCESS_HOME named "/", PUBLIC_FILE_PAGE the public file list and DOWNLOAD_PUBLIC_FILE its
-- download. "/" only sends a visitor on, and the two public pages are open by design - to
-- everyone, or to every signed-in person, as /settings/general says (PublicFilesAuthorizationManager).
-- Their @PreAuthorize lines had been commented out for years, so ticking them on the role page
-- granted nothing and unticking them took nothing away. A checkbox that does nothing is worse
-- than none: it says a role can be kept off the public files when it cannot.
--
-- The rows go before the constants can: a permission row whose name is not a constant any more
-- would fail to load (@Enumerated(STRING)). permission_role first, for its foreign key. No other
-- table points at a permission.

DELETE FROM permission_role
WHERE permission_id IN (SELECT id FROM permission
                        WHERE permission_name IN ('ACCESS_HOME', 'PUBLIC_FILE_PAGE', 'DOWNLOAD_PUBLIC_FILE'));

DELETE FROM permission
WHERE permission_name IN ('ACCESS_HOME', 'PUBLIC_FILE_PAGE', 'DOWNLOAD_PUBLIC_FILE');
