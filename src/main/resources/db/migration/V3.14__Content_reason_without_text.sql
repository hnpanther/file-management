-- 2.16.0: a reading's reason never holds any of a document's text (issue 120).
--
-- Tika 4.1 answers a document it could not finish - a parse that ran out of time, a page Tesseract
-- could not decode - with the XHTML it had read so far: the document's metadata and the beginning of
-- its text. 2.15.0 kept the first 200 characters of an error's body in the reason, taking them for
-- Tika's short JSON, so a failed reading's reason could carry a document's title or first line - into
-- file_content.reason, the status page, and the log. HttpTikaClient now drops a body that is markup;
-- this cuts what was stored back to what the reason is ("Tika could not parse the document (422)").
--
-- Every such reason ends with ": <" and the markup after it.

UPDATE file_content
SET reason = regexp_replace(reason, ':\s*<.*$', '')
WHERE reason LIKE '%: <%';
