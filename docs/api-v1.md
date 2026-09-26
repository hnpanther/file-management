# API v1 — the guide for a client

The machine-facing API the PL/SQL (Oracle APEX) clients call: upload a file, download a revision,
delete a revision. This is the contract as a client sees it, and how to move a client from the
numeric ids to the external ids. How it is built is in [arch.md](arch.md#6-http-layers); the S3-style
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

**Every path segment that takes an id takes either**, and a UUID in any case. The numbers keep
working for as long as a client uses them. The external id is the one to move to: it cannot be
guessed or counted through, and it does not depend on this database's numbering - which the move
to PostgreSQL keeps, but a later one need not. An external id is a name, not a permission: the
folder check is the same whichever id is used.

A segment that is neither a number (1-9 digits) nor a UUID is `400` with `"title": "InvalidParameter"`,
naming the parameter and not echoing the value - the same answer a non-number always got. An id no
file or revision has is `404`, for either kind.

## Endpoints

| Method | Path | Permission | Answers |
|---|---|---|---|
| GET | `/api/v1/files/health-test` | `API_HEALTH_TEST` | `200`, text |
| POST | `/api/v1/files` (multipart) | `API_SAVE_NEW_FILE` | `200` and the ids (below) |
| GET | `/api/v1/files/file-details/{fileDetailsId}/download` | `API_DOWNLOAD_FILE` | the revision's bytes |
| GET | `/api/v1/files/file-info/{fileInfoId}/file-details/{fileDetailsId}/download` | `API_DOWNLOAD_FILE` | the same; the file id is only checked for its form |
| GET | `/api/v1/files/file-info/{fileInfoId}/download` (`?version=`, `?format=`) | `API_DOWNLOAD_FILE` | the file's latest version, or the one named (1.9.0) |
| DELETE | `/api/v1/files/file-details/{fileDetailsId}` | `API_DELETE_FILE_DETAILS` | `200` `{"outcome":"DELETED"}`; the last revision takes the file with it |
| DELETE | `/api/v1/files/file-info/{fileInfoId}/file-details/{fileDetailsId}` | `API_DELETE_FILE_DETAILS` | the same; the two must name the same file, or `404` |

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
only additions, so a client that reads fields by name is unaffected. Refusals: `400` for a type
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

### Errors

Every failure is an RFC 9457 problem document, `application/problem+json`:

```json
{ "type": "…", "title": "InvalidDataException", "status": 400, "detail": "…", "instance": "/api/v1/…" }
```

`detail` is English and meant for a log, not for a person. A client should branch on the status
code - `400`, `401`, `403` (no permission, or no access to that file's folder), `404`, `409` - and
not on the wording, which may change.

## Moving a client to the external ids

Nothing forces the move, and it can be done one client at a time:

1. **New files first.** Store `fileExternalId` and `fileDetailsExternalId` from the upload's
   answer beside the numbers already stored. Nothing else changes yet.
2. **Use them.** Call the downloads and deletes with the external ids. The numbers still work,
   so a row that has not got its external id yet keeps working with its number.
3. **Fill in the old rows.** For each row that has only numbers:
   `HEAD /api/v1/files/file-details/{fileDetailsId}/download` answers `X-File-External-Id` and
   `X-File-Details-External-Id` without sending the file. Or, done once by an administrator
   with read access to the database, the whole mapping at a time:

   ```sql
   SELECT fd.id AS file_details_id, fd.external_id AS file_details_external_id,
          fi.id AS file_id, fi.external_id AS file_external_id
   FROM file_details fd JOIN file_info fi ON fi.id = fd.file_info_id;
   ```

4. **Stop sending the numbers.** When every row has its external ids, the numbers can be dropped
   from the client. They stay valid on the server; nothing on this side needs to change.

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
| 2.0.0 | nothing: the same API whichever database runs behind it. When the service moves to PostgreSQL, every id and external id a client holds is copied unchanged, and the next id continues after the largest one |
