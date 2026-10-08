# API v1 — the guide for a client

The machine-facing API the PL/SQL (Oracle APEX) clients call: upload a file, download a revision,
delete a revision - and since 2.13.0 read, set and search the metadata of files and folders. This is the contract as a client sees it. **Since 2.4.0 every path takes the
external ids only** - the clients moved over from the numbers, and the numbers are refused. How it is built is in [arch.md](arch.md#6-http-layers); the S3-style
API for API keys is v2, also there.

## Signing in

* **HTTP Basic** with the shared machine account, which holds the `API_*` permissions
  (`API_HEALTH_TEST`, `API_SAVE_NEW_FILE`, `API_DOWNLOAD_FILE`, `API_DELETE_FILE_DETAILS`, and since
  2.13.0 `API_GET_METADATA`, `API_SET_METADATA`, `API_SEARCH_METADATA` - the `API_V1` group on the
  role page); or
* **`Authorization: Bearer fmk_…`**, an API key, which reaches only the folders it was granted.

A request without credentials is `401` with a `WWW-Authenticate` challenge, never a redirect to
the login page. `GET /api/v1/files/health-test` answers `hello from endpoint` - a liveness probe
that also proves the credentials and the permission still work.

## Two ids for everything

Every file and every revision (one version in one format) has two ids:

| | the number | the external id |
|---|---|---|
| file | `fileId` | `fileExternalId` |
| revision | `fileDetailsId` | `fileDetailsExternalId` |
| looks like | `42` | `3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d` |
| given | at upload, forever | at upload, forever (since 1.8.0; files stored before were given one then) |

**Every path segment that takes an id takes the external id**, a UUID in any case - and since
2.4.0 nothing else. From 1.8.0 to 2.3.0 it took either, while the clients moved over; they have.
The external id cannot be guessed or counted through, does not depend on this database's
numbering, and is never given to another row - a number can be, once its row is deleted
([issue 98](issues.md#98-the-cut-over-set-each-sequence-after-the-largest-id-left-so-the-ids-of-the-last-deleted-rows-are-handed-out-again--s2)).
It is a name, not a permission: the folder check is the same.

A segment that is not a UUID - **a number included** - is `400` with `"title": "InvalidParameter"`,
naming the parameter and not echoing the value; for a number, the `detail` adds that the parameter
takes the external id (`invalid value for parameter 'fileDetailsId': it takes the external id (a
UUID), not the numeric id`). An external id no file or revision has is `404`.

The numbers are still **answered**: the upload returns `fileId` and `fileDetailsId` beside the
external ids, a delete answers the revision's number as `id`, and every download sends
`X-File-Details-Id`. They are for a client's own records and logs; they cannot be sent back.

## Endpoints

| Method | Path | Permission | Answers |
|---|---|---|---|
| GET | `/api/v1/files/health-test` | `API_HEALTH_TEST` | `200`, text |
| POST | `/api/v1/files` (multipart) | `API_SAVE_NEW_FILE` | `200` and the ids (below) |
| GET | `/api/v1/files/file-details/{fileDetailsId}/download` | `API_DOWNLOAD_FILE` | the revision's bytes |
| GET | `/api/v1/files/file-info/{fileInfoId}/file-details/{fileDetailsId}/download` | `API_DOWNLOAD_FILE` | the same; the file's id is only checked for its form |
| GET | `/api/v1/files/file-info/{fileInfoId}/download` (`?version=`, `?format=`) | `API_DOWNLOAD_FILE` | the file's latest version, or the one named (1.9.0) |
| DELETE | `/api/v1/files/file-details/{fileDetailsId}` | `API_DELETE_FILE_DETAILS` | `200` `{"outcome":"DELETED"}`; the last revision takes the file with it |
| DELETE | `/api/v1/files/file-info/{fileInfoId}/file-details/{fileDetailsId}` | `API_DELETE_FILE_DETAILS` | the same; the two must name the same file, or `404` |
| GET | `/api/v1/files/file-info/{fileInfoId}/metadata` | `API_GET_METADATA` | the file's metadata - [Metadata](#metadata-2130) |
| PUT | `/api/v1/files/file-info/{fileInfoId}/metadata` | `API_SET_METADATA` | set, replace or clear it |
| GET, PUT | `/api/v1/files/file-details/{fileDetailsId}/metadata` | the same two | one revision's |
| GET | `/api/v1/files/search?metadata=…&folderMetadata=…` | `API_SEARCH_METADATA` | files by their metadata, or their folders' |
| GET, PUT | `/api/v1/folders/{folderId}/metadata` | `API_GET_METADATA`, `API_SET_METADATA` | a folder's metadata |
| GET | `/api/v1/folders/{folderId}/undescribed` | `API_GET_METADATA` | its children without metadata, newest first |
| GET | `/api/v1/folders/search?metadata=…` | `API_SEARCH_METADATA` | folders by their metadata |

The paths keep their parameter names, and **`{fileInfoId}` is the file's external id
(`fileExternalId`), `{fileDetailsId}` the revision's (`fileDetailsExternalId`)**. For example,
`DELETE /api/v1/files/file-details/9a0b1c2d-3e4f-4a5b-9c6d-7e8f9a0b1c2d`.

### Upload

`POST /api/v1/files`, `multipart/form-data`:

| Field | |
|---|---|
| `multipartFile` | the file; its name is the stored name, and must carry an extension |
| `description` | required |
| `folderId` | the folder, by number; required |
| `public-file` | `1` or `true` to list it on the public files page. **Anything else, absent included, keeps it private** (since 1.7.0; before, it was public unless `0`) |
| `metadata` | optional (2.13.0): what is known about the document that it does not say, as a JSON object - `{"contractNo":"C-5678","party":{"code":"P-1234"}}`. Checked as [Metadata](#metadata-2130) says; a document the rules refuse refuses the upload, and nothing is stored |

The answer:

```json
{
  "fileId": 42,
  "fileDetailsId": 97,
  "fileExternalId": "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
  "fileDetailsExternalId": "9a0b1c2d-3e4f-4a5b-9c6d-7e8f9a0b1c2d",
  "checksumSha256": "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
  "fileName": "report.pdf",
  "fileExtension": "pdf",
  "contentType": "application/pdf",
  "description": "...",
  "metadata": {"contractNo": "C-5678", "party": {"code": "P-1234"}}
}
```

`metadata` is in the answer when the upload sent one (2.13.0).

The first two fields are as they always were; the three in the middle arrived in 1.8.0 and are
only additions, so a client that reads fields by name is unaffected. **Keep
`fileDetailsExternalId`** (and `fileExternalId`, to download a file's latest version): they are
what the downloads and the deletes take. Refusals: `400` for a type
the uploader may not store, a file larger than their limit, bytes that are not what the
extension says, a name that cannot be stored or a missing field (the `detail` says which);
`409` for a name the folder already holds (compared the way the search folds names: `گزارش‌ها`
and `گزارشها` are one name).

### Downloading by the file's id

`GET /api/v1/files/file-info/{fileInfoId}/download` - for a client that keeps the file's id and
not a revision's:

* **the version**: the file's latest, unless `?version=N` names another;
* **the format**: the version's only one, unless `?format=pdf` picks one (without case, `.PDF`
  too). A version with several formats and no `?format=` is `400`, and the `detail` lists them -
  it never picks one for you;
* a version or a format the file does not have is `404`, and the `detail` says what it has
  (`its latest is 3`, `it has: docx, pdf`); `?version=0` is `400`.

### What every download answers

The bytes, as an attachment under the stored name (`Content-Disposition` with an RFC 6266
`filename*`, so a Persian name arrives intact), the type the extension says, and which revision
was served:

| Header | |
|---|---|
| `X-File-External-Id` | the file's external id |
| `X-File-Details-Id` | the revision's number |
| `X-File-Details-External-Id` | the revision's external id |
| `X-File-Version` | its version |
| `X-Checksum-SHA256` | lower-case hex SHA-256 of the stored bytes - absent only for a revision stored before 1.8.0 that the start-up backfill could not read |

A client can compare `X-Checksum-SHA256` with the SHA-256 of what it received. A **`HEAD`** to
any download answers the same headers, with the file's size as `Content-Length`, and no body -
the server does not read the file for it.

A person can read a revision's external id off the file page too, with a copy button, when their
role holds `VIEW_FILE_EXTERNAL_ID` (the group "دیدن شناسهٔ خارجی نسخه‌ها"; ADMIN holds it) - for
setting up an integration by hand.

### Metadata (2.13.0)

A **document of metadata** is a JSON object: what an integration knows about a document - the ERP's
attachment id, a contract's number, a party's code and name - or about a folder, such as who
`ERP/P-1234/` is. Any keys; values of any JSON type, nested. One per **revision** of a file (a file's
is its newest revision's, and a new version sent without one through v1 or the pages takes it),
one per **folder**.

| Rule | |
|---|---|
| an object at the top | an array, a string or a number is refused |
| size | at most 16 KB as compact UTF-8 (`filemanagement.metadata.max-bytes`) |
| depth | at most 5 levels of objects and arrays, the document itself the first (`max-depth`) |
| keys | 1-100 characters, no control character; a key given twice is refused |
| numbers | kept as written (never rounded), at most 1000 characters written out - `1e999999` is refused |
| none | `{}` - sent, it clears the document |

A refused document is `400`; the `detail` names the rule, never the content. Metadata is read with
the file or folder: `READ` on its folder; it is written with `WRITE` there. A personal folder's is
its own user's; the root and `Profiles` take none. **It is never logged**, and every change is in
the file's history (or the folder's) with the document before and after.

**Reading** answers the document and its tag, also in the `ETag` header:

```http
GET /api/v1/files/file-info/3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d/metadata

200  ETag: "4c1e…"
{"fileId": 42, "fileExternalId": "3f2b…", "fileDetailsId": 97,
 "fileDetailsExternalId": "9a0b…", "version": 3, "fileExtension": "pdf",
 "metadata": {"contractNo": "C-5678"}, "etag": "\"4c1e…\""}
```

`metadata` is `null` for none. A folder's: `GET /api/v1/folders/{folderId}/metadata` -
`{"folderId", "name", "metadata", "etag"}`.

**Writing** is a `PUT` of the document as the body (`Content-Type: application/json`): on a file it
goes to every format of its newest version; on a revision to that one; on a folder to the folder.
**Conditioned, so nothing is overwritten unknowingly:**

| Header | The write is done only… | Otherwise |
|---|---|---|
| `If-None-Match: *` | where there is no document yet - **filling in what is missing** | `412`, nothing changed |
| `If-Match: "<etag>"` | if the document is still the one read | `412`, nothing changed - read again |
| neither | always: a plain replacement, recorded | |

Two writers at once are taken one after the other: the second waits for the first and then answers to
what it wrote - two integrations both filling in what is missing never both write it. The tag is of
the document itself (keys sorted, no space), so the one read back is the one to send.

So an integration that adds metadata to files or folders created without it - by their external id,
or the folder's id - sends `If-None-Match: *`, and never overwrites what a person has written since:

```http
PUT /api/v1/files/file-info/3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d/metadata
If-None-Match: *
Content-Type: application/json

{"source": "erp", "attachmentId": 99812}
```

```http
PUT /api/v1/folders/1234/metadata
If-None-Match: *
Content-Type: application/json

{"fullName": "علی رضایی", "nationalCode": "0012345678"}
```

The answer is the document as stored, its new tag, `changed` (false when it was the same document),
and the revisions written. An empty body is `400`: clearing is `{}`, never an accident.

**Searching** is by containment: a hit holds every key asked for, with that value - a nested part
or an array member included; values compare exactly (`"1500"` is not `1500`):

```http
GET /api/v1/files/search?metadata={"contractNo":"C-5678"}
GET /api/v1/files/search?folderMetadata={"nationalCode":"0012345678"}
GET /api/v1/folders/search?metadata={"nationalCode":"0012345678"}
```

(the query parameter URL-encoded; the request log shows it as `metadata=***`.) `metadata` matches a file's current document; `folderMetadata`
any folder above the file, at any depth - "every document of the person whose national code is X" -
and only a folder the caller may read itself: a key granted `ERP/P-1234/contracts/` alone cannot find
the files there by `P-1234`'s document; both may be given. Only what the caller may read, newest first, `page` from 0 and `size` up to 200,
answered as `{"items": [...], "page", "size", "hasNext"}` - no total. Served by indexes, whatever the
number of files.

**What is still to be described**: `GET /api/v1/folders/{folderId}/undescribed` lists the folder's
children without metadata, newest first, paged the same way - the person folders the ERP's uploads
made under `ERP`, before anyone has written who each is.

### Searching the text of files (2.15.0)

`GET /api/v1/files/content-search?q=...` - files by the text inside them, read by Apache Tika: a PDF's
pages (a scanned page's words too, by OCR, Persian and English), a Word document, the slides of a
presentation, the sheets of a spreadsheet. Needs `API_SEARCH_FILE_CONTENTS` - an API key holds it
by itself, as it holds the download - and a `404` while the installation has the search switched off.
Only files the caller may read are found - a key, only in its own folders.

| Parameter | |
|---|---|
| `q` | the words: every one required, each matched as the beginning of a word (`کتاب` finds `کتاب‌ها`), all on one page; a part in double quotes is a phrase, its words in that order. Arabic `ي`/`ك`, Persian or Arabic digits and the half-space make no difference. At most 200 characters, 12 words |
| `allVersions` | `true` to search every version of a file; otherwise its latest version (every format of it) |
| `page`, `size` | a page at a time, `size` at most 200 (20 by default) |

```json
{"items": [{"fileId": "0b6f...", "fileNumber": 41, "fileName": "report", "fileDetailsId": "7c1e...",
            "fileDetailsNumber": 97, "version": 2, "latestVersion": true, "extension": "pdf",
            "folderId": 12, "folderName": "P-1234", "matchedPages": 5,
            "pages": [{"page": 3, "unit": "PAGE", "label": null, "source": "TEXT",
                       "snippet": "... بر اساس قرارداد اجاره شماره ...", "matches": [[12, 7]]}]}],
 "page": 0, "size": 20, "hasNext": false, "limited": false}
```

* `pages` - the first three pages the file matched on, in page order; `matchedPages` counts them all.
  `unit` is `PAGE` (a PDF's page, from 1), `SLIDE`, `SHEET` (its name in `label`) or `WHOLE` (a document
  without pages, `page` 0). `source` says where the text came from: `TEXT`, `TEXT_REVERSED` (a text layer
  stored backwards, put right), `OCR` (recognised from the page's image - a word or two may be off), or
  `BOTH`.
* `snippet` is a few lines of the page; `matches` the `[offset, length]` of each word found in it.
* `limited: true` - the words are on more than 20,000 pages, and the results come from the newest of
  them: add a word to narrow the search.
* A file is found only once its text has been read - minutes after an upload, longer for a long scan.
  `q` empty, or nothing in it a word: `400`.

### Errors

Every failure is an RFC 9457 problem document, `application/problem+json`:

```json
{ "type": "…", "title": "InvalidDataException", "status": 400, "detail": "…", "instance": "/api/v1/…" }
```

`detail` is English and meant for a log, not for a person. A client should branch on the status
code - `400`, `401`, `403` (no permission, or no access to that file's folder), `404`, `409`,
`412` (a metadata write whose condition failed), `413` (above the upload cap), `503` - and not on
the wording, which may change.

**`503 Service Unavailable` means "send it again later"** (2.7.1): the request was fine, and the
file storage behind it did not answer - with a `Retry-After` header in seconds (`30`). An upload
answered 503 was not stored: no row, no file; sending it again is safe. Before 2.7.1 the same
failure was `417 Expectation Failed`, which reads as the client's mistake and is not worth retrying.

## A client that still holds numbers

The move to the external ids (1.8.0 to 2.3.0) is done, and since 2.4.0 a number in a path is a
`400`. A client, or a row, that was missed shows up as that `400` with `it takes the external id`
in the `detail`. The `HEAD` that used to turn a number into its external ids takes the external
id itself now, so the mapping for the rows such a client stored comes from the database, read
once by an administrator:

```sql
SELECT fd.id AS file_details_id, fd.external_id AS file_details_external_id,
       fi.id AS file_id, fi.external_id AS file_external_id
FROM file_details fd JOIN file_info fi ON fi.id = fd.file_info_id;
```

For a client that stores only a file per record and always wants its current content, keep
`fileExternalId` and download with `file-info/{fileExternalId}/download` - the latest version,
whatever it is when the request is made.

## Changes that concern a client, by release

| Release | |
|---|---|
| 2.15.0 | **search in contents**: `GET content-search` above - new, needs `API_SEARCH_FILE_CONTENTS`. Nothing else changes |
| 2.13.0 | **metadata**: an optional `metadata` field on the upload, `metadata` in its answer; the metadata endpoints, conditioned writes and search above; `412` for a failed condition. Nothing else changes - a client that sends none is unaffected |
| 1.6.x | the id-only forms (`file-details/{id}/download`, `DELETE file-details/{id}`); `folderId` on the upload |
| 1.7.0 | a Persian file name arrives intact; **uploads private unless `public-file=1`**; a refused upload says why in `detail` |
| 1.8.0 | external ids and `checksumSha256` in the upload's answer; every id segment takes the external id |
| 1.9.0 | `file-info/{id}/download` by the file's id, with `?version=` and `?format=`; the `X-File-*` and `X-Checksum-SHA256` headers on every download; deleting or changing a file checks the file's folder (issue 90) |
| 2.7.1 | **a storage failure is `503` with `Retry-After`**, no longer `417`: retry it. Nothing else changes |
| 2.7.0 | nothing. (Every download is recorded - with the API key used and the client's address.) |
| 2.5.0 | nothing. (What a client uploads, changes or deletes appears in the file history, under the API key it used.) |
| 2.4.0 | **every path takes the external ids only**: a number in `{fileInfoId}` or `{fileDetailsId}` is `400 InvalidParameter`, saying the parameter takes the external id. Routes, methods, fields, answers and headers are otherwise unchanged - the numbers are still answered |
| 2.3.0 | nothing. (What a request made with an API key uploads or deletes is now recorded as that key's, and the file page names the key.) |
| 2.2.0 | nothing: the v1 API carries no times. (The v2 API's JSON now gives `lastModified` as an instant in UTC - `2026-09-19T13:03:55Z` rather than the server's wall clock `2026-09-19T16:33:55`; see [deployment.md](deployment.md#upgrading-from-210-to-220--instants-and-indexed-search).) |
| 2.1.0 | nothing |
| 2.0.0 | nothing: the same API whichever database runs behind it. When the service moves to PostgreSQL, every id and external id a client holds is copied unchanged, and the next id continues after the largest one |
