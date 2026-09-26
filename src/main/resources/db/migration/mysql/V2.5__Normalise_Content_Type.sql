-- file_details.content_type becomes the server's word, not the client's (issues 12 and 13).
--
-- Until now the column held whatever Content-Type the uploading client put on the multipart part
-- - a value nothing verified - and a download replayed it to the browser. From this version the
-- application stores the type it derives from the file's extension and first bytes, and serves a
-- download with the type the extension maps to, whatever the row says. This statement brings the
-- existing rows in line with that, so the column and the served type agree again.
--
-- Rows whose extension is not one the application accepts are left as they are: there is no
-- server-side word for them. They are served as application/octet-stream, as attachments, which
-- is what an unrecognised file should be.
--
-- Verify afterwards - every accepted extension carries its canonical type:
--
--     SELECT LOWER(file_extension) AS ext, content_type, COUNT(*)
--     FROM file_details
--     GROUP BY LOWER(file_extension), content_type
--     ORDER BY ext;

UPDATE file_details
SET content_type = CASE LOWER(file_extension)
    WHEN 'pdf'  THEN 'application/pdf'
    WHEN 'png'  THEN 'image/png'
    WHEN 'jpg'  THEN 'image/jpeg'
    WHEN 'jpeg' THEN 'image/jpeg'
    WHEN 'docx' THEN 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
    WHEN 'xlsx' THEN 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    WHEN 'pptx' THEN 'application/vnd.openxmlformats-officedocument.presentationml.presentation'
    WHEN 'mp4'  THEN 'video/mp4'
    WHEN 'mp3'  THEN 'audio/mpeg'
    WHEN 'txt'  THEN 'text/plain'
    END
WHERE LOWER(file_extension) IN ('pdf', 'png', 'jpg', 'jpeg', 'docx', 'xlsx', 'pptx', 'mp4', 'mp3', 'txt');
