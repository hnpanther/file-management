# The S3-compatible API (2.9.0 - 2.14.0)

An S3 surface at **`/s3`**, for the clients that speak S3 - the AWS SDKs and CLI, `rclone`, `boto3`,
the S3 node of n8n - so an integration files documents here as it would into any object store. It is
the first part of roadmap [9.10](roadmap.md#910-an-s3-compatible-mode--in-progress-290---2120-authentication-objects-listing);
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
| `GET /s3/` | `ListBuckets`: the top-level folders the key can see | - |
| `HEAD /s3/{bucket}` | `HeadBucket`: `200` with `x-amz-bucket-region`, or `404` | sight of the bucket |
| `GET /s3/{bucket}?location` | `GetBucketLocation`: `us-east-1` (n8n's S3 node asks it before every operation) | sight of the bucket |
| `GET /s3/{bucket}?versioning` | `GetBucketVersioning`: always `Enabled` | sight of the bucket |
| `GET /s3/{bucket}?list-type=2` | `ListObjectsV2` (2.12.0): the keys under a prefix, a page at a time - [Listing](#listing) | `READ` on a folder for its files; sight of it for its name |
| `GET /s3/{bucket}` | `ListObjects`, the older form of the same, paged by `marker` | as above |
| `PUT /s3/{bucket}/{key}` | **upload**: a new file, or - when the folder already holds that title - **a new version of it** | `WRITE` on the folder |
| … with `If-None-Match: *` | the same, but `412 PreconditionFailed` if the title is there | `WRITE` |
| … whose folders do not exist | creates them, all or none, with the file | **may create folders** and `WRITE` on the deepest folder that exists |
| `PUT /s3/{bucket}/{key}/` (empty body) | creates the folder (and those above it) | **may create folders** and `WRITE` |
| `GET /s3/{bucket}/{key}` | **download** the latest version; `?versionId=` an older one; `Range` | `READ` |
| `HEAD /s3/{bucket}/{key}` | the same, without the body | `READ` |
| `DELETE /s3/{bucket}/{key}` | **deletes every version of the key** - the title in that format (no delete markers); the file goes with its last version | **may delete files** and `WRITE` |
| `DELETE /s3/{bucket}/{key}?versionId=` | deletes **that version only** (2.12.0) | **may delete files** and `WRITE` |
| `DELETE /s3/{bucket}/{key}/` | deletes the folder if it is empty; `409 FolderNotEmpty` otherwise | **may delete folders** and `WRITE` on its parent |
| `POST /s3/{bucket}?delete` | `DeleteObjects` (2.12.0): up to 1000 keys - [Deleting many](#deleting-many) | as each `DELETE` |
| `POST /s3/{bucket}/{key}?uploads` | `CreateMultipartUpload` (2.14.0) - [Multipart upload](#multipart-upload-2140) | as the `PUT` it ends in |
| `PUT /s3/{bucket}/{key}?partNumber=&uploadId=` | `UploadPart` | the key that began the upload |
| `POST /s3/{bucket}/{key}?uploadId=` | `CompleteMultipartUpload`: the parts become the object, as a `PUT` would store it | as the `PUT` |
| `DELETE /s3/{bucket}/{key}?uploadId=` | `AbortMultipartUpload` | the key that began it |
| `GET /s3/{bucket}/{key}?uploadId=` | `ListParts` | the key that began it |
| `GET /s3/{bucket}?uploads` | `ListMultipartUploads`: the key's own uploads in progress | sight of the bucket |

`READ` and `WRITE` are the key's folder grants (the key form's folder tree); the three capabilities
are the checkboxes beneath them, an S3 key's only. A deletion of a key that names nothing is a `204`,
as in S3. A key without a grant on a bucket gets `404 NoSuchBucket`, not a hint that it exists.

What an upload answers: `200`, `ETag`, `x-amz-version-id` (the revision's external id). A download:
`Content-Type`, `Content-Length`, `ETag`, `Last-Modified`, `x-amz-version-id`; it is recorded in the
download log with the channel `S3` - apart from the old v2's `API_V2`.

## Listing

`ListObjectsV2` (`?list-type=2`, what every current client sends) and `ListObjects` answer as S3 does,
so `aws s3 ls`, `aws s3 sync`, `rclone ls` / `sync` and n8n's *Get Many* work as against S3.

* **An object is a key**: a file's title in one format (`report.pdf`), listed once, as its newest
  version in that format - the size, `ETag`, time and version `GetObject` would answer. A title kept
  as `pdf` and `txt` is two keys. Older versions are not listed (there is no `ListObjectVersions`;
  they stay readable by `versionId`).
* **In S3's order**: by the bytes of the key's UTF-8, as `aws s3 sync` requires - `a/b/x.pdf` before
  `a/b0.pdf`, `B` before `a`, Persian after Latin.
* **`delimiter=/`** lists one folder: its child folders as `CommonPrefixes` (`a/b/`) - **empty ones
  too**, a folder being real here - and its files as `Contents`. Without a delimiter, every file at any
  depth below the prefix. A folder is never an object: `a/b/` itself is not among `Contents`. A
  delimiter other than `/` is a `501`.
* **`prefix`** is matched **without case**, as a key is resolved (`Software/` lists `software/`);
  the keys come back spelled as stored. A prefix may stop part of the way into a name (`a/b` lists
  `a/b/…`, `a/b0.pdf` and `a/B-upper.pdf`).
* **Pages**: `max-keys` up to **1000** (and by default); `IsTruncated` and `NextContinuationToken`
  (opaque - pass it back unchanged); `start-after`; for version 1, `marker` (and `NextMarker` with a
  delimiter). `encoding-type=url` percent-encodes the keys and prefixes, `/` kept - the AWS CLI asks
  for it. A key or prefix holding a character XML 1.0 cannot carry (U+FFFE, U+FFFF, half a surrogate
  pair) is answered only so: without it, `400 InvalidArgument`, never an answer the client cannot read.
* **Only what the key may see**: files only in folders it may `READ`; a folder's name where it may at
  least pass through to a grant below it, as on the web (roadmap 6.6). A key granted
  `ERP/P-1234/` sees `P-1234/` at the top of `erp`, and nothing beside it.
* **What a page costs** is what it returns, not what the bucket holds: folders are read in key order
  off an index, a page's worth at a time (V3.9). Measured on 20,000 folders and 41,000 objects
  (`S3ListingScaleTest`): a page of 1000 in under 200 ms through the SDK, a key granted one of the
  twenty thousand folders answered in about 50 ms. The one thing sorted whole is the files directly
  in one folder, for a listing of that folder.

## Multipart upload (2.14.0)

What `aws s3 cp`, `aws s3 sync` and `rclone` switch to by themselves for a file above 8 MB (their
`multipart_threshold`, `--s3-upload-cutoff`) - and the SDKs' transfer managers: an upload begun, its
parts sent - in parallel, in any order, any of them again - then completed into one object, or
aborted. Nothing to configure in the client.

* **The object is stored as a `PUT` stores it.** Completed, the parts are read as one body and go
  through the same path: the checks of the bytes against the extension and the upload policy, a title
  already there a new version, folders created only by a key that may, `If-None-Match: *` (on the
  completion), the metadata (sent with `CreateMultipartUpload`, as S3 takes it), the history and the
  download log. Its `ETag` is the same as a `PUT`'s of those bytes would be - not S3's
  `md5-of-md5s-N` (the SDKs do not check it).
* **Refused at the start where the `PUT` would be**: no `WRITE`, folders the key may not create, a
  folder's key, an extension the key's creator may not upload - before a part is sent, so a client is
  not told after gigabytes.
* **The parts wait on the server's disk** - in the upload temporary directory
  (`FILEMANAGEMENT_UPLOAD_TEMP_DIR`, under `s3-multipart/`), never in memory - until the completion or
  the abort. Each part's `ETag` is its MD5, as S3's; a `Content-MD5` sent with it is checked
  (`BadDigest`); a part sent again replaces the one before, whole.
* **Bounded**: the parts may not hold more than the server's upload cap, or the key creator's limit
  for the extension if smaller - asked as each part arrives (`EntityTooLarge`; a part sent again
  counts once); 10,000 parts; `filemanagement.s3-api.multipart.max-open-uploads` uploads in progress
  per key (20; `InvalidArgument` past it). **An upload neither completed nor aborted is removed after
  `expire-hours` (24)**, its parts with it - what S3 leaves to a lifecycle rule, done by itself.
* **One key's**: an upload is reached by its id and the key that began it. With another key - even
  one granted the same folders - it is `NoSuchUpload`, and `ListMultipartUploads` lists only the
  key's own.
* **A completion** names its parts in ascending order (`InvalidPartOrder`), each with the `ETag` it was
  answered (`InvalidPart` otherwise); parts not named are discarded. A refused completion leaves the
  upload as it was, to complete again or abort; two completions at once make one object, the other is
  `NoSuchUpload`.

Not served: `UploadPartCopy` (a part copied from another object), `ListMultipartUploads` with a
`delimiter` - each a `501`. A server whose upload temporary directory is not kept across a restart
(`FILEMANAGEMENT_UPLOAD_TEMP_DIR` unset: Tomcat's own, new at every start) loses the parts in
progress: their completion is `InvalidPart`, and the client sends the file again.

## Deleting many

`POST /s3/{bucket}?delete` with S3's `<Delete>` body - `aws s3 rm --recursive`, `aws s3 sync --delete`,
`rclone delete` send it. Up to **1000** `<Object>`s, each a `<Key>` and optionally a `<VersionId>`; each
deleted as its own `DELETE` would delete it - the same capabilities, grants and records - and **in a
transaction of its own**, so one refused does not keep the others. The answer lists each as
`<Deleted>` or as an `<Error>` with its code (`AccessDenied`, `FolderNotEmpty`, `InvalidArgument`,
`ServiceUnavailable`); with `<Quiet>true</Quiet>`, the errors only. A key that names nothing is
`Deleted`, as in S3. The body's hash is checked against its signature as an upload's is, and it is
read with no DTD and no entity (a `<!DOCTYPE` is refused), at most 2 MiB.

## Metadata (2.13.0)

S3's **user metadata** is kept as the revision's metadata document - the one v1 and the pages show
and search ([api-v1.md](api-v1.md#metadata-2130)):

* **Sent** with a `PUT` as `x-amz-meta-{name}: value`, one key each, the name lower-cased as S3
  keeps it: `x-amz-meta-contract-no: C-5678` is `{"contract-no": "C-5678"}` (`aws s3 cp --metadata
  contract-no=C-5678`, boto3's `Metadata=`, the SDKs' `metadata(Map)`). At most **2 KB** of names and
  values together, S3's own limit: above it, `400 MetadataTooLarge` and nothing stored.
* **A value that is not ASCII** - Persian - is sent as S3 requires, RFC 2047:
  `=?UTF-8?B?2LnZhNuM?=` (base64 of its UTF-8) or `=?UTF-8?Q?...?=`, and stored decoded. Sent raw,
  the SDKs put `?` in its place, its signature does not match, and the `PUT` is a `403` with nothing
  stored - as S3 would answer it.
* **A document S3's flat strings cannot say** - nested, typed, Persian keys - goes whole in one
  header of this server's own, `x-fm-metadata`: the JSON, base64-encoded; not beside `x-amz-meta-*`.
  The same rules as every document (an object, 16 KB, 5 levels).
* **Answered** on `GET` and `HEAD`: each key whose value is a string, a number or a boolean and whose
  name a header can carry, as `x-amz-meta-*` (a value not ASCII in RFC 2047), up to 2 KB of header
  lines and 50 keys - a document set through v1 may hold hundreds, more than an HTTP client takes; how many
  keys were left out in S3's own `x-amz-missing-meta`; and then the whole document in
  `x-fm-metadata` too, when it fits in 4 KB - beyond that, read it through v1.
* **Each version carries its own**: a `PUT` without metadata stores a version without it, as in S3,
  never the version before's. (Through v1 and the pages, a new version takes the current one.)
  Metadata changed later through v1 or the pages is what the next `GET` answers; the bytes and the
  version are the same.

A folder's metadata is not an object here (S3 has no folders): it is the pages' and v1's.

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
| 404 | `NoSuchBucket`, `NoSuchKey`, `NoSuchUpload` | |
| 400 | `InvalidPart`, `InvalidPartOrder`, `BadDigest` | a multipart completion naming a part not sent or out of order; a part not matching its `Content-MD5` |
| 409 | `FolderNotEmpty` | a folder deleted with something in it |
| 412 | `PreconditionFailed` | `If-None-Match: *` and the title is there |
| 400 | `EntityTooLarge` | above the server's upload cap (as S3 answers it) |
| 400 | `MetadataTooLarge` | more than 2 KB of `x-amz-meta-*` |
| 400 | `MalformedXML` | a `DeleteObjects` body that is not S3's `<Delete>`, has a DTD, or names no key or more than 1000 |
| 501 | `NotImplemented` | what this surface does not do - see below |
| 503 | `ServiceUnavailable` | the storage is failing; try again |

## Where it differs from S3

* **Folders are real**: they outlive their last file, are created and deleted on purpose, and keep
  the tree's depth limit and name rules.
* **A `PUT` of a title already there adds a version** and never replaces bytes; every version stays
  readable by its `versionId`.
* **`DELETE` of a key removes every version of it** - of that title in that format - rather than
  adding a delete marker; with `versionId`, that version alone. The file goes with its last version.
  (Before 2.12.0 a `versionId` was ignored and the file went whole, every format with it.)
* **A title is one per folder whatever its extension**; names are compared without case and folded.
* **The bytes are checked** against their extension and the upload policy.
* **The `ETag` is not an MD5**: it is the first 32 hex digits of the revision's SHA-256 - stable while
  the object is, the same in an upload's answer and a download's. Clients that compare it with an
  MD5 they computed must not (the AWS SDKs validate with their own checksums instead).
* **A listing shows folders as they are**: an empty folder is a `CommonPrefix` with a delimiter, and
  a folder is never an object without one.
* **Not yet**: `ListObjectVersions`, `CopyObject`, `UploadPartCopy`, and every other
  bucket sub-resource (`?uploads`, `?acl`, `?policy`, `?tagging`, …) - each answered
  `501 NotImplemented`, as are `CreateBucket` and `DeleteBucket` (a bucket is a top-level folder,
  made and removed on the web).
