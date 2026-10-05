# The S3-compatible API (2.9.0)

An S3 surface at **`/s3`**, for the clients that speak S3 - the AWS SDKs and CLI, `rclone`, `boto3`,
the S3 node of n8n - so an integration files documents here as it would into any object store. It is
the first part of roadmap [9.10](roadmap.md#910-an-s3-compatible-mode--in-progress-290-authentication-upload-download-delete);
what is still to come is listed there. API v1 ([api-v1.md](api-v1.md)) is unchanged and separate.

## Connecting

| Setting in the client | Value |
|---|---|
| endpoint | `https://files.example/s3` (or a host of its own mapped onto `/s3/`) |
| addressing | **path-style** (`forcePathStyle`, `s3ForcePathStyle`, `force_path_style`) |
| region | `us-east-1` |
| access key id | the `FM…` id shown when the key was made |
| secret access key | the 40 characters shown with it - **once**; lost, make a new key |

The key is an **S3 key**: chosen as such on the API key form (*نوع کلید: سازگار با S3*). A v1
(`fmk_…`) key is refused here, and an S3 key is refused on v1. The server needs its master key,
`FILEMANAGEMENT_S3_SECRET_ENCRYPTION_KEY` - made once with `openssl rand -base64 32`, set and kept as
[deployment.md](deployment.md#the-s3-keys-master-key-290) says - before an S3 key can be made.

**`/s3` is not `/api/v2`.** The old v2 (`/api/v2/{bucket}/{key}`, a bearer `fmk_…` key, JSON) is still
served, unchanged, beside it; it is to be retired once nothing uses it - which the download log
tells apart, `API_V2` for it and `S3` for this (roadmap 9.10.12, steps 0 and 6).

```bash
aws --endpoint-url https://files.example/s3 s3 cp contract.pdf s3://erp/P-1234/contracts/C-5678/contract.pdf
```

## Buckets and keys

* **A bucket is a top-level folder.** For now it is found by its name, without case, with `_` read as
  `-`: the folder `ERP` is the bucket `erp`, `IMS_Document_System` is `ims-document-system`. (A bucket
  name of its own per folder is to come.)
* **A key is the folders below the bucket, then the file's name**: `P-1234/contracts/C-5678/scan.pdf`.
  Folder names are compared as the application compares them - without case, folded - so two keys
  that differ only so name the same folder. A key ending in `/` names a folder. Empty segments, `.`
  and `..` are refused.
* The file's name is its title and extension; the extension must be one the upload policy allows for
  the key's creator, and the bytes must be what it says.

## Authentication

AWS Signature Version 4, as every S3 client computes it:

* the `Authorization: AWS4-HMAC-SHA256 …` header, with `host` and `x-amz-content-sha256` among the
  signed headers, and `x-amz-date` within **15 minutes** of the server's clock;
* or a **pre-signed URL** (`X-Amz-Algorithm`, `X-Amz-Credential`, `X-Amz-Signature`, …), valid for at
  most **7 days** and no longer than it says.

The body is checked against what was signed before anything is stored: a hex SHA-256 against the
bytes; `UNSIGNED-PAYLOAD` taken as it is; the streamed forms (`STREAMING-AWS4-HMAC-SHA256-PAYLOAD`,
`…-TRAILER`, `STREAMING-UNSIGNED-PAYLOAD-TRAILER`) chunk by chunk, each chunk's signature and the
trailer's, and an `x-amz-checksum-crc32` / `-crc32c` / `-sha1` / `-sha256` trailer against the bytes.

A request authenticated so runs **as the key's creator**, and is recorded with the key beside them,
as v1's are.

## The operations

| Request | Does | Needs |
|---|---|---|
| `PUT /s3/{bucket}/{key}` | **upload**: a new file, or - when the folder already holds that title - **a new version of it** | `WRITE` on the folder |
| … with `If-None-Match: *` | the same, but `412 PreconditionFailed` if the title is there | `WRITE` |
| … whose folders do not exist | creates them, all or none, with the file | **may create folders** and `WRITE` on the deepest folder that exists |
| `PUT /s3/{bucket}/{key}/` (empty body) | creates the folder (and those above it) | **may create folders** and `WRITE` |
| `GET /s3/{bucket}/{key}` | **download** the latest version; `?versionId=` an older one; `Range` | `READ` |
| `HEAD /s3/{bucket}/{key}` | the same, without the body | `READ` |
| `DELETE /s3/{bucket}/{key}` | **deletes the file with every version** (no delete markers) | **may delete files** and `WRITE` |
| `DELETE /s3/{bucket}/{key}/` | deletes the folder if it is empty; `409 FolderNotEmpty` otherwise | **may delete folders** and `WRITE` on its parent |

`READ` and `WRITE` are the key's folder grants (the key form's folder tree); the three capabilities
are the checkboxes beneath them, an S3 key's only. A deletion of a key that names nothing is a `204`,
as in S3. A key without a grant on a bucket gets `404 NoSuchBucket`, not a hint that it exists.

What an upload answers: `200`, `ETag`, `x-amz-version-id` (the revision's external id). A download:
`Content-Type`, `Content-Length`, `ETag`, `Last-Modified`, `x-amz-version-id`; it is recorded in the
download log with the channel `S3` - apart from the old v2's `API_V2`.

## Errors

S3's XML (`<Error><Code>…</Code><Message>…</Message><Resource>…</Resource></Error>`):

| Status | Code | When |
|---|---|---|
| 400 | `InvalidArgument` | a name, an extension, bytes that are not what they say, a depth beyond the limit |
| 400 | `XAmzContentSHA256Mismatch` | the body is not the one whose hash was signed |
| 400 | `IncompleteBody` | a chunk, a chunk signature or a checksum trailer that does not hold; a body cut short |
| 400 | `AuthorizationHeaderMalformed`, `AuthorizationQueryParametersError` | a signature that cannot be read |
| 403 | `SignatureDoesNotMatch` | a wrong secret, or a request changed after it was signed |
| 403 | `InvalidAccessKeyId` | no such S3 key, or one disabled, revoked or expired |
| 403 | `RequestTimeTooSkewed`, `AccessDenied` (expired URL) | the clock, or a pre-signed URL past its time |
| 403 | `AccessDenied` | no grant or no capability for what was asked |
| 404 | `NoSuchBucket`, `NoSuchKey` | |
| 409 | `FolderNotEmpty` | a folder deleted with something in it |
| 412 | `PreconditionFailed` | `If-None-Match: *` and the title is there |
| 400 | `EntityTooLarge` | above the server's upload cap (as S3 answers it) |
| 503 | `ServiceUnavailable` | the storage is failing; try again |

## Where it differs from S3

* **Folders are real**: they outlive their last file, are created and deleted on purpose, and keep
  the tree's depth limit and name rules.
* **A `PUT` of a title already there adds a version** and never replaces bytes; every version stays
  readable by its `versionId`.
* **`DELETE` of a file removes it and all its versions.**
* **A title is one per folder whatever its extension**; names are compared without case and folded.
* **The bytes are checked** against their extension and the upload policy.
* **The `ETag` is not an MD5**: it is the first 32 hex digits of the revision's SHA-256 - stable while
  the object is, the same in an upload's answer and a download's. Clients that compare it with an
  MD5 they computed must not (the AWS SDKs validate with their own checksums instead).
* **Not yet**: listing (`ListBuckets`, `ListObjectsV2`), `HeadBucket`, `DeleteObjects`, multipart
  upload, `x-amz-meta-*`. A client that lists before it writes (`aws s3 sync`, `rclone sync`) does not
  work yet; `cp`, `copyto`, `put`, `get` and `rm` do.
