# API v1 — the guide for a client

The machine-facing API the PL/SQL (Oracle APEX) clients call: upload a file, download a revision,
delete a revision. This is the contract as a client sees it. **Since 2.4.0 every path takes the
external ids only** - the clients moved over from the numbers, and the numbers are refused. How it is built is in [arch.md](arch.md#6-http-layers); the S3-style
API for API keys is v2, also there.

## Signing in

* **HTTP Basic** with the shared machine account, which holds the `API_*` permissions
  (`API_HEALTH_TEST`, `API_SAVE_NEW_FILE`, `API_DOWNLOAD_FILE`, `API_DELETE_FILE_DETAILS` - the
  `API_V1` group on the role page); or
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
  "description": "..."
}
```

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

### Errors

Every failure is an RFC 9457 problem document, `application/problem+json`:

```json
{ "type": "…", "title": "InvalidDataException", "status": 400, "detail": "…", "instance": "/api/v1/…" }
```

`detail` is English and meant for a log, not for a person. A client should branch on the status
code - `400`, `401`, `403` (no permission, or no access to that file's folder), `404`, `409`,
`413` (above the upload cap), `503` - and not on the wording, which may change.

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
