# The storage copy

The tool that moves an installation's files between the storage directory and an S3-compatible
bucket - the cut-over to the object store and the way back
([roadmap 4.4](roadmap.md#44-moving-the-bytes-two-passes-and-a-short-window)). Since **2.8.0** it is
part of the application's jar.

This document is the whole of it: what it does, how to run it, what each setting and each line of
its report means, the procedure for the cut-over and the rollback, and what to do when something
is reported.

**Contents**

1. [What it is](#1-what-it-is)
2. [What it guarantees](#2-what-it-guarantees)
3. [Running it](#3-running-it)
4. [Settings](#4-settings)
5. [Modes](#5-modes)
6. [What it checks, revision by revision](#6-what-it-checks-revision-by-revision)
7. [The log, the report and the exit status](#7-the-log-the-report-and-the-exit-status)
8. [The cut-over, step by step](#8-the-cut-over-step-by-step)
9. [The way back](#9-the-way-back)
10. [When it refuses, or reports a problem](#10-when-it-refuses-or-reports-a-problem)
11. [Time, load and tuning](#11-time-load-and-tuning)
12. [Questions](#12-questions)
13. [Where it is in the code, and how it is tested](#13-where-it-is-in-the-code-and-how-it-is-tested)

---

## 1. What it is

Every revision of every file - one row of `file_details` - has its bytes stored under one key,
`file_details.storage_key`, on whichever backend the installation uses
(`filemanagement.storage.backend`: `filesystem` or `s3`). **The key is the same on both**: on the
filesystem it is a path under `FILEMANAGEMENT_BASE_DIR`, in the bucket it is the object's key
(under `FILEMANAGEMENT_S3_PREFIX`, if one is set). So moving an installation from one backend to
the other changes no row - it is a copy of the bytes to the same keys on the other side, and then a
setting.

The storage copy is that copy. It reads every row, and for each one makes sure the target holds
exactly the bytes the row's checksum (`file_details.checksum_sha256`, SHA-256) describes - copying
what is missing, checking what is already there.

* **Two directions**: `to-s3` (directory → bucket: the cut-over) and `to-filesystem` (bucket →
  directory: the way back).
* **Three modes**: `copy` (copy and check; deletes nothing), `verify` (check only; writes nothing),
  `prune` (delete the objects no row names).
* **It is not the application.** The same jar with one command-line argument, but it starts none
  of the application: no web server, no Flyway migration, no role reconciliation, no sweeper, no
  checksum backfill. It reads the service's configuration and does only the copy.
* **It never writes the database.** It reads `file_details` and `file_storage_write` through a
  small read-only connection pool.
* **It may run while the service runs** - `copy` and `verify` touch nothing the service uses
  except the disk and network they share. Only `prune` belongs in the maintenance window.

## 2. What it guarantees

These are tested on both stores (section 13), not only intended.

* **An object becomes visible in the target only once its bytes hash to the row's checksum.** The
  bytes are hashed as they are read from the source:
  * **into the bucket**, a file of one part (up to `FILEMANAGEMENT_S3_PART_SIZE_MB`, 16 MB) is one
    `PutObject` carrying its SHA-256, which the store checks against what arrives; a larger file is
    a multipart upload whose every part carries its own SHA-256, and which is **completed only
    after the whole file has hashed to the row's checksum** - otherwise it is aborted, and nothing
    is ever visible at the key;
  * **into the directory**, the file is written under a temporary name beside its target
    (`.{name}.{uuid}.copying`) and renamed into place only after it has hashed right; otherwise the
    temporary file is deleted.

  So a damaged source, a read that fails half-way, a network that drops, or the process being
  killed **never leaves a partial or wrong object** that a later run could mistake for a copy.
* **After a write, the store must report what was sent** - the size and the checksum (the file's
  own, or for a multipart object the composite of its parts) - or the object is removed and the
  revision reported.
* **Nothing is ever overwritten.** An object already at a key is checked, never replaced; if it is
  not the row's revision it is reported and left as it is.
* **A source whose bytes are not the row's is not copied** - it is reported (`SOURCE_CORRUPT`).
* **Nothing is deleted except by `prune` with `confirm=true`**, and even then never more than
  `max-prune` objects, never an object an upload in flight is writing, never one written in the
  last `quiet-minutes`.
* **Every problem is a line in the report and makes the run fail** (exit status `1`). Nothing is
  skipped silently.
* **It resumes.** A revision verified in the target is not copied again, so a second run copies
  only what the first did not finish or what was added since. A run stopped with Ctrl+C finishes
  the revisions it has in hand, writes its report, and the next run carries on.

## 3. Running it

On the **application's host**, from the **service's directory**, with the **service's
environment** - the copy reads its settings exactly as the service does, so it must see the same
ones (section 4 says which it needs):

```bat
cd /d D:\MyApp\file-management
set FILEMANAGEMENT_DB_URL=jdbc:postgresql://localhost:5432/file_management?sslmode=disable
set FILEMANAGEMENT_DB_USERNAME=file_management
set FILEMANAGEMENT_DB_PASSWORD=...
set FILEMANAGEMENT_BASE_DIR=D:\MyApp\file-management\files
set FILEMANAGEMENT_S3_ENDPOINT=http://storage-host:8333
set FILEMANAGEMENT_S3_BUCKET=file-management-prod
set FILEMANAGEMENT_S3_ACCESS_KEY=...
set FILEMANAGEMENT_S3_SECRET_KEY=...
java -jar file-management.jar --spring.profiles.active=storage-copy --filemanagement.storage-copy.direction=to-s3
```

On Linux, the same with `export` and the service's `/etc/file-management.env` sourced first.

* **`--spring.profiles.active=storage-copy` on the command line is what selects it** - and nothing
  else does: not `SPRING_PROFILES_ACTIVE`, not a line in a configuration file. A service definition
  that happened to carry the profile must not turn the next restart into a copy.
* **The jar's version**: any 2.8.0 or later. The copy reads only `file_details.storage_key`,
  `checksum_sha256`, `file_size` and `file_storage_write`, which every database since 1.8.0 has; so
  a 2.8.0 jar can run the copy against a production still on an earlier release, beside it.
* **The working directory matters**: `./application.properties` and `./config/application.properties`
  are read from where the command is started, as for the service - start it from the service's
  directory if the service is configured by file.
* **The account** that runs it needs to read `FILEMANAGEMENT_BASE_DIR` (`to-s3`), or to write it
  (`to-filesystem`, and `prune` of the directory).
* **The network**: the database, the directory and the store's S3 endpoint, as for the service.

### Where the credentials come from

Exactly where the service's do:

| What | Setting | Environment variable |
|---|---|---|
| the database | `spring.datasource.url`, `.username`, `.password` | `FILEMANAGEMENT_DB_URL`, `FILEMANAGEMENT_DB_USERNAME`, `FILEMANAGEMENT_DB_PASSWORD` |
| the directory | `filemanagement.base-dir` | `FILEMANAGEMENT_BASE_DIR` |
| the store | `filemanagement.storage.s3.endpoint`, `.bucket`, `.access-key`, `.secret-key`, `.prefix`, `.region`, `.path-style-access`, `.part-size-mb`, `.timeouts.*` | `FILEMANAGEMENT_S3_ENDPOINT`, `_BUCKET`, `_ACCESS_KEY`, `_SECRET_KEY`, `_PREFIX`, `_REGION`, `_PATH_STYLE_ACCESS`, `_PART_SIZE_MB`, `_TIMEOUT_*` |

Each may come from the environment, from an external `application.properties`, or from the
command line - in that order of preference for a secret: a secret on the command line is visible
in the shell's history and in the list of processes.

* **Use the application's key** (in SeaweedFS, the `file-management` identity of `s3.json`), not
  the `operator` key: it is the key the service will use after the switch, so a copy that works
  with it proves the service will; and it may reach this one bucket only. It needs to read, write,
  list and delete in the bucket - what `Read`, `Write` and `List` give in `s3.json`.
* **`FILEMANAGEMENT_S3_PREFIX` must be the one the service will use.** Every object's key is
  `[prefix/]storage_key`; a copy under another prefix is invisible to the service.
* `filemanagement.storage.backend` is not read: the direction says which store is the source.
* Before the cut-over the service's own definition has no `FILEMANAGEMENT_S3_*`; set them in the
  shell that runs the copy. At the switch they go into the service's definition.

## 4. Settings

Given on the command line, each as `--filemanagement.storage-copy.<name>=<value>`:

| Name | Default | Meaning |
|---|---|---|
| `direction` | *(none - required)* | `to-s3`: read the directory, write the bucket. `to-filesystem`: read the bucket, write the directory |
| `mode` | `copy` | `copy`, `verify` or `prune` - section 5 |
| `threads` | `4` | revisions handled at once, 1 to 64 |
| `deep-verify` | `false` | `true`: read every object in the target back and hash it, instead of trusting the checksum the store keeps (section 6) |
| `after-id` | `0` | handle only the revisions whose `file_details` id is greater - to carry on a long run without re-checking what it already did |
| `confirm` | `false` | `prune` deletes only with `true`; without it, it lists what it would delete |
| `max-prune` | `100` | `prune` refuses - deleting nothing - if it would delete more than this |
| `quiet-minutes` | `60` (`filemanagement.storage.unfinished-after-minutes`) | `prune` keeps every object written less than this many minutes ago, whatever the rows say |
| `report-dir` | its log directory | where the CSV report of each run is written (section 7) |

And from the service's configuration: the database, the directory and the store (section 3), and
`FILEMANAGEMENT_LOG_PATH` for where its log goes (section 7).

## 5. Modes

### `copy` (the default)

For every revision: if the target lacks it, read it from the source, verify it, write it; if the
target has it, check it (section 6). **Deletes nothing. Safe while the service runs.** Run it as
often as you like - each run copies only what is missing.

### `verify`

**Writes nothing.** For every revision, checks the target holds it and that it is the row's
revision. With `deep-verify=true`, or whenever the target is the directory, it reads every object
back. It also lists the target and counts the objects **no row names** (`ORPHAN_IN_TARGET` lines) -
when it starts from the first row (`after-id=0`). Safe while the service runs.

### `prune`

**Deletes the objects in the target that no row names** - the objects of revisions deleted after
they were copied. Without `confirm=true` it only lists them (`WOULD_DELETE`); with it, deletes them
(`DELETED`). In the bucket, versioning keeps a deleted object as a non-current version until the
lifecycle rule removes it (30 days), so a mistaken prune can be undone in that time.

What protects the objects that must stay:

1. **The rows.** Every key in `file_details` is kept.
2. **Writes in flight.** Every key in `file_storage_write` - the journal an upload writes *before*
   its bytes - is kept. The listing is read first, then the journal, then the rows: whatever the
   listing found is named by one or the other if an upload made it.
3. **Recent objects.** Anything written within `quiet-minutes` is kept (`KEPT_RECENT`), whatever
   the rows say.
4. **A limit.** More than `max-prune` objects to delete is refused with exit status `2`, deleting
   nothing - a wrong database (an empty one would name nothing) cannot empty the bucket. A
   database with no rows at all is refused outright.

**Why the window needs it - it is not a tidy-up.** A new version's number is the file's highest plus
one. Delete a file's latest version and upload again, and the new revision gets the same version
number - and the same key. If the first pass had copied the deleted revision, its object is still
in the bucket at that key, and the upload after the switch is refused as a duplicate. The prune
removes exactly those objects.

Run it **with the service stopped**. The guards above make it safe against a running service, but
there is no reason to rely on them.

## 6. What it checks, revision by revision

In id order, `threads` at a time, 200 rows read at a time:

| The target... | `copy` does | `verify` does |
|---|---|---|
| does not hold the key | reads the source, hashes it, writes it only if the hash is the row's → `COPIED`; a different hash → `SOURCE_CORRUPT`, nothing written; no source → `MISSING_AT_SOURCE` | → `MISSING_AT_TARGET` |
| holds it, and the store keeps the object's own SHA-256 (one part) | compares it with the row's → `VERIFIED`, or `MISMATCH_AT_TARGET` | the same |
| holds it as a multipart object this copy wrote | compares the SHA-256 the copy verified before completing it (`x-amz-meta-sha256`) with the row's → `VERIFIED`, or `MISMATCH_AT_TARGET` | the same |
| holds it, and nothing above applies (a multipart object the application wrote, a store that keeps no checksum) | reads it back and hashes it | the same |
| holds it in the directory | compares the size with the source's → `SAME_SIZE`, or `MISMATCH_AT_TARGET` | reads it back and hashes it |

With `deep-verify=true` every object in the target is read back and hashed, in both modes.

* **A row deleted while the copy runs** (the service is running) is `GONE` - not a problem.
* **A row with no checksum** (none should remain since 1.8.0's backfill) is checked against the
  source's own bytes instead, and listed as a `NO_RECORDED_CHECKSUM` warning.
* **A file whose size differs from `file_size`** is copied (the checksum is what matters) and listed
  as a `SIZE_DIFFERS_FROM_ROW` warning.
* `SAME_SIZE` is `copy`'s check of the directory: a file the copy wrote there was renamed into place
  only after it verified, and the directory is otherwise the service's own; `verify` reads them all.

## 7. The log, the report and the exit status

### The log

`{FILEMANAGEMENT_LOG_PATH}\storage-copy\app_log.log` - `logs\storage-copy\app_log.log` under the
working directory unless `FILEMANAGEMENT_LOG_PATH` is set - and the console. **Never the service's
own `app_log.log`**, even when an external `application.properties` sets `filemanagement.log.path`:
two processes rolling one file corrupt it, so the copy appends `\storage-copy` to whatever the
configuration says, above every configuration file.

It says what it is about to do, then a progress line at most every 30 seconds, then the summary:

```text
storage copy: copy to-s3, from the directory D:\MyApp\file-management\files to the bucket file-management-prod at http://storage-host:8333; rows from jdbc:postgresql://...; 4 thread(s)
storage copy: 200 revisions done, up to file_details id=309; 198 copied (162.1 MB), 2 problem(s)
...
  COPIED             1368
  MISSING_AT_SOURCE  2
  bytes copied       3454.8 MB
  MISSING_AT_SOURCE file_details id=39 key=Crisis-Passive-Defense/.../v1/resource_icon.png
storage copy copy to-s3 FOUND PROBLEMS in PT9M46S; the report: D:\...\logs\storage-copy\storage-copy-to-s3-copy-20261003-063638Z.csv
```

The last line says `SUCCEEDED`, `FOUND PROBLEMS` or `STOPPED`. Up to 20 problems are repeated in the
log; all of them are in the report.

### The report

One CSV per run, in `report-dir` - by default beside the log -
`storage-copy-{direction}-{mode}-{yyyyMMdd-HHmmss}Z.csv` (UTC). A summary in `#` lines, then one line
per revision or object that needs attention:

```text
# storage copy to-s3, copy, took PT9M46.05S
# COPIED: 1368
# MISSING_AT_SOURCE: 2
# bytes copied: 3622665348, warnings: 0
what,file_details_id,storage_key,detail
"MISSING_AT_SOURCE",39,"Crisis-Passive-Defense/Crisis-Management/resource_icon/v1/resource_icon.png",
```

| `what` | Problem? | Meaning |
|---|---|---|
| `COPIED`, `VERIFIED`, `SAME_SIZE`, `GONE` | no | counted only (section 6) |
| `MISSING_AT_SOURCE` | **yes** | the source does not hold the row's key |
| `SOURCE_CORRUPT` | **yes** | the source's bytes do not hash to the row's checksum; `detail` has both hashes and the size; nothing was written |
| `MISMATCH_AT_TARGET` | **yes** | the target holds something else at the key; `detail` says what and how it was found; left as it is |
| `MISSING_AT_TARGET` | **yes** | (`verify`) the target does not hold it |
| `FAILED` | **yes** | a store or the database failed for this revision; the log has the exception |
| `NO_RECORDED_CHECKSUM` | warning | checked against the source's bytes instead |
| `SIZE_DIFFERS_FROM_ROW` | warning | the bytes' length is not `file_size` |
| `ORPHAN_IN_TARGET` | no | (`verify`) an object no row names - `prune` removes it |
| `WOULD_DELETE` | no | (`prune` without `confirm`) would be deleted |
| `DELETED` | no | (`prune`) deleted |
| `KEPT_RECENT` | no | (`prune`) no row names it, but it is too recent to delete |
| `DELETE_FAILED` | **yes** | (`prune`) could not be deleted |

### The exit status

| Status | Meaning |
|---|---|
| `0` | every revision it handled is in the target and verified; for `prune`, nothing failed |
| `1` | something is not - a problem line in the report - or the run was stopped (Ctrl+C), or failed part-way; what it did stays done |
| `2` | refused before doing anything: a setting missing or wrong, the bucket or the database not there or not this application's, or `prune` over its limit. **Nothing was written or deleted** |

In a script: `if errorlevel 1 ...` (Windows) or `$?` (Linux).

## 8. The cut-over, step by step

The plan of [roadmap 4.4](roadmap.md#44-moving-the-bytes-two-passes-and-a-short-window): almost
everything is copied while the service runs, and a short window catches up. `COPY` below stands for
the command of section 3 (`java -jar file-management.jar --spring.profiles.active=storage-copy
--filemanagement.storage-copy.direction=to-s3`).

### Beforehand

1. **The bucket**, made as [deploy/seaweedfs](../deploy/seaweedfs/README.md#the-bucket) says -
   versioning on, the lifecycle rule - and checked with the application's key.
2. **The rehearsal**: the whole procedure below once on a copy of production (a restored dump and a
   copy of the directory, or a test bucket) - roadmap 4.7, step 4.

### With the service running - the first pass

3. `COPY`. Then **settle every problem line** (section 10) - the window is not the time to find a
   missing or damaged file. Run `COPY` again after each fix; it copies only what is new.
4. Optionally `COPY --filemanagement.storage-copy.mode=verify` (seconds), or with
   `--filemanagement.storage-copy.deep-verify=true` (reads everything back).

### The window

5. **The database dump**, as for every release.
6. **Stop the service.**
7. **The second pass**: `COPY`. It copies the revisions added since the first pass - usually
   seconds.
8. **Prune, listing only**:
   `COPY --filemanagement.storage-copy.mode=prune --filemanagement.storage-copy.quiet-minutes=1`.
   With the service stopped nothing is in flight, so the quiet period need not hold back what the
   second pass just checked. Read the `WOULD_DELETE` lines: every key should belong to a file
   deleted since the first pass.
9. **Prune**: the same, with `--filemanagement.storage-copy.confirm=true`.
10. **Verify**: `COPY --filemanagement.storage-copy.mode=verify`. Exit status `0` - or only the lines
    settled and accepted in step 3.
11. **Switch**: in the service's definition, `FILEMANAGEMENT_STORAGE_BACKEND=s3` and the
    `FILEMANAGEMENT_S3_*` used above
    ([deployment.md, 2.5.1 → 2.6.0](deployment.md#upgrading-from-251-to-260--an-object-store-as-the-backend-if-chosen)).
    Start. The log says `files are stored in the S3 bucket ... at ...`;
    `/actuator/health/readiness` is `UP`. Download a few files - old and new, small and large;
    upload one and delete it.
12. **The directory read-only**: the service account loses write on `FILEMANAGEMENT_BASE_DIR`. It
    is the way back for the weeks that follow (section 9).

After those weeks, the directory is archived and removed from the host.

## 9. The way back

Within the weeks the directory is kept. `BACK` stands for the command with
`--filemanagement.storage-copy.direction=to-filesystem`; the S3 settings stay as the service has
them.

1. **Stop the service**, and give its account write on the directory again.
2. **Copy back** what was written since the switch: `BACK`.
3. **Prune the directory** - the files of revisions deleted since the switch, for the reason in
   section 5: `BACK --filemanagement.storage-copy.mode=prune --filemanagement.storage-copy.quiet-minutes=1`,
   read the list, then again with `--filemanagement.storage-copy.confirm=true`. A temporary file a
   killed copy left (`.*.copying`) is listed and removed with them.
4. **Verify**: `BACK --filemanagement.storage-copy.mode=verify` - in the directory, this reads
   every file.
5. `FILEMANAGEMENT_STORAGE_BACKEND=filesystem`; start; the same checks as step 11 above.

## 10. When it refuses, or reports a problem

### Refusals (exit status 2) - nothing was done

| The log says | Cause, and what to do |
|---|---|
| `filemanagement.storage-copy.direction must be to-s3 or to-filesystem; it is not set` | add `--filemanagement.storage-copy.direction=...` |
| `... mode must be copy, verify or prune`, `... threads must be between 1 and 64`, `... must be a whole number` | a setting mistyped |
| `the object store is not configured; not set: FILEMANAGEMENT_S3_...` | set the named variables in the shell |
| `the bucket X does not exist - create it first` | the bucket's name, or the endpoint, is not the one intended |
| `the store refused the access key for the bucket X` | wrong key or secret, or a key that may not use this bucket (in `s3.json`, the bucket must be in its actions) |
| `S3 storage at ... cannot be reached` | the endpoint, or the network between the hosts |
| `the storage root ... (filemanagement.base-dir) is not a directory` | `FILEMANAGEMENT_BASE_DIR` wrong, or a share not mounted |
| `file.management.base-dir is no longer read (2.7.0)` | rename the setting to `filemanagement.base-dir` (or `FILEMANAGEMENT_BASE_DIR`) |
| `spring.datasource.url must name the application's PostgreSQL database` | `FILEMANAGEMENT_DB_URL` not set or not a PostgreSQL URL |
| `the database does not have the tables this copy reads` | not the application's database, or one older than 1.8.0 |
| `file_details has no rows: pruning against an empty database would delete everything` | `prune` pointed at the wrong database |
| `N objects would be deleted, more than filemanagement.storage-copy.max-prune (100)` | run `prune` without `confirm` and read the list; if it is right, raise `max-prune` to at least N |

### Problems (exit status 1)

* **`MISSING_AT_SOURCE`** - the row's file is not in the directory. The service already answers
  404 for it. Find it in a backup of the directory and put it back at the key, then run `COPY`
  again; or, if it is gone for good, write it down - the cut-over can go ahead with it known, and
  `verify` will keep reporting it as `MISSING_AT_TARGET`.
* **`SOURCE_CORRUPT`** - the file's bytes are not the ones uploaded: changed on disk, or damaged.
  Do not cut over with it unexplained: restore it from a backup and run `COPY` again.
* **`MISMATCH_AT_TARGET`** - the bucket holds something else at the key, which nothing in the
  normal course does. Look at the object (the admin UI, or a client with the operator key); if it
  is wrong, delete that one object by hand and run `COPY` again - the copy never overwrites.
* **`FAILED`** - a store or the database failed for that revision: the log has the exception. Run
  again; what succeeded is not repeated.
* **Stopped, or failed part-way** (`STOPPED`, or `storage copy failed; ... a second run carries
  on`) - run again.

## 11. Time, load and tuning

Measured on the development data (1,368 revisions, 3.45 GB; files of up to 35 MB) against
SeaweedFS in Docker on the same Windows machine, versioning on:

| Run | Time |
|---|---|
| first pass, `to-s3`, 4 threads | 9 min 20 s - 9 min 46 s |
| second pass, nothing new | 3 s |
| `verify` | 6 s |
| `verify` with `deep-verify`, 8 threads | 3 min 49 s |
| `to-filesystem` into an empty directory, 8 threads | 4 min 55 s |
| `verify` of that directory (reads everything), 8 threads | 1 min 23 s |

* **Memory**: one part (`FILEMANAGEMENT_S3_PART_SIZE_MB`, 16 MB) per thread at most, whatever the
  files' sizes. The default heap is enough.
* **Threads**: on many small files the time is per request, not per byte, and more threads help;
  on large files the disk or the network is the limit and they do not. Raise `threads` for the first
  pass if the store and the network allow it; the service shares both while it runs.
* **A long first pass** can be stopped (Ctrl+C) and resumed at any time, or carried on with
  `after-id` from the last id the log reported, to avoid re-checking what is done.

## 12. Questions

**Can the first pass run against production now, before the window?**
Yes. It reads the database and the directory and writes only the bucket; the service runs on.
Its report also says which files production is missing or has damaged - worth knowing early.

**What happens to files uploaded while the first pass runs?**
They are copied by the second pass in the window - or by any `COPY` run after them.

**And files deleted while it runs?**
Their rows are gone, so the copy reports them as `GONE` (if it was handling them) or never sees
them; any object it had already copied is removed by the prune in the window.

**Can it run on another machine than the application's?**
Yes, if that machine reaches the database and the store, and sees the same files at the path given
as `FILEMANAGEMENT_BASE_DIR` (a share, for instance). The application's host is simpler.

**Is it safe to run it twice at once?**
`copy` and `verify`, yes - a key written by one is checked by the other, never overwritten. Do not
run `prune` beside a `copy`.

**What does the bucket keep that the application does not use?**
`x-amz-meta-sha256` on every object the copy wrote: the SHA-256 it verified before the object
became visible. The application ignores it; a later run reads it to recognise a multipart object
as verified without reading it back.

**What about the files' history, external ids, share links?**
Rows - in the database, which the switch does not touch.

## 13. Where it is in the code, and how it is tested

| What | Where |
|---|---|
| the entry point - selected in `FileManagementApplication.main` | `storage/copy/StorageCopyCommand` |
| the copy itself, the modes, the report | `storage/copy/StorageCopy`, `StorageCopySettings` |
| what it needs of a store beyond the `BlobStore` port: `facts`, `copyIn`, `forEachObject` | `storage/CopyableStore`, implemented by `FilesystemBlobStore` and `S3BlobStore` - the application itself never uses it |
| the S3 client, built as the service builds it | `BlobStoreConfig.newS3Client`, `newS3BlobStore` |
| its own profile file (documentation of the settings) | `src/main/resources/application-storage-copy.properties` |

Tests - all against a real PostgreSQL and a real SeaweedFS (Testcontainers):

* `storage/CopyableStoreContractTest`, run on both stores (`FilesystemCopyableStoreContractTest`,
  `S3CopyableStoreContractTest`): a wrong checksum or a source that fails half-way leaves nothing at
  the key, no temporary file and no unfinished multipart upload; a taken key is refused and left
  as it was; what the store keeps for one part and for several.
* `storage/copy/StorageCopyTest`: on rows uploaded through `FileService` - the first and second
  passes, a damaged and a missing source, a wrong object in the target (by hand and by the
  application), a row without a checksum, the prune and each of its guards, the way back into an
  empty directory and a damaged file found there, a stop and a resume, a row deleted mid-run.
* `storage/copy/StorageCopyCommandTest`: selected by the argument alone; every refusal exits `2`
  with nothing written; the log never goes to the service's file, even with an external
  `application.properties`; end to end from the service's settings.

Each guard was also checked by removing it from the code and watching a test fail (ten of them,
2.8.0).
