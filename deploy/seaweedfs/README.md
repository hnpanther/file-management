# SeaweedFS for file-management

The object store of roadmap Phase 4 (4.5, 4.6). **Phase one: one host, one volume server, one
data disk** - SeaweedFS 4.48, the filer's metadata in PostgreSQL, S3 for the application, the
admin UI and a maintenance worker. `compose.yaml` says what each service is for; this file is how
to run it.

**Verified on 2026-09-30** with this exact file, on Docker, with the AWS CLI: the bucket created
and versioned with the `operator` key; the application's key writing with `x-amz-checksum-sha256`
and reading the same checksum back; a byte-identical download; a 40 MB multipart upload both ways;
the lifecycle rules below stored; the application's key refused `CreateBucket`; anonymous requests
refused; the admin UI behind its password with the worker connected; three volumes created per
collection, not seven.

## One copy, so

With one volume server there is **one copy of every file**. The data disk is the only place the
bytes are, so:

* **RAID 1 (or better) under `VOLUME_DIR`**, so one failed disk loses nothing;
* **the scheduled mirror of the bucket to a second store** (Backups, below), so a lost host,
  a failed array or a mistake does not either;
* **the filer database backed up with the application's** - without it the bytes are nameless.

Versioning (below) protects against an object deleted or overwritten by mistake - not against a
lost disk.

## What the host needs

Little CPU or memory - **the disk is the resource**. For phase one: **4 vCPU, 8-16 GB RAM**, Linux
with Docker, and a data disk sized as

```
usable space ≈ data × (1 + the share kept as old versions) × 1.4
```

- every file today plus two to three years of growth; the old versions versioning keeps for 30
days; and 40 % that is not the data's, for space a vacuum has not yet freed and for free volume
slots (below). 3 TB today growing 1 TB a year, for two years, is about **7 TB usable**. HDD is fine
for the data (large files, read and written in sequence); the filer database and the volume
indexes are better on SSD. 1 Gbit/s to the application's host at least.

| Container | RAM | |
|---|---|---|
| master | ~0.5 GB | |
| volume | 1-2 GB | an in-memory index per volume - small, since the files are large and few |
| filer, s3 | 1-2 GB | streaming and checksums; CPU under load |
| filer-db | 1-2 GB | metadata only |
| admin, worker | ~0.5 GB | more CPU during a vacuum |

**Nothing else on this host** - in particular not Apache Tika (roadmap Phase 11), which parses
untrusted files and takes every core while it recognises scans. Why, and the three hosts together:
[deployment.md, "The hosts, and what each needs"](../../docs/deployment.md#the-hosts-and-what-each-needs).

## Before the first start

1. **A Linux host with Docker**, and the data disk mounted for `VOLUME_DIR`. Size it for all the
   files, their versions and formats, growth, and the old versions versioning keeps for 30 days -
   and at least `6 x VOLUME_SIZE_LIMIT_MB` free at the start (180 GB at the default 30 GB volumes;
   `.env.example` says why).
2. `cp .env.example .env` - directories and two random passwords (`openssl rand -base64 24`).
3. `cp s3.json.example s3.json` - two keys:
   * `file-management` - the application's, reaching **only** `file-management-prod`;
   * `operator` - yours: creating the bucket, backups, a GUI client.

   Random values: `openssl rand -hex 10` for an access key, `openssl rand -base64 30` for a secret.
   `chmod 600 s3.json .env`. After changing `s3.json` later: `docker compose restart s3`.
4. `docker compose up -d`, then `docker compose ps` - everything `Up`, `master` and `filer-db`
   `healthy`.

## The bucket

**Once, after the first start, with the `operator` key.** One bucket for the whole application
(roadmap 4.1), named **`file-management-prod`** - exactly that: the application's key in
`s3.json` reaches that name and no other.

What to know before creating it:

* **A bucket has no size.** Nothing is given at creation; it grows until the disk is full. A quota
  exists (admin UI, or `s3.bucket.quota` in `weed shell`) but is not needed: the application
  limits what people upload - the upload policy per kind, the quota of each personal folder.
* **Create only the one bucket.** Each bucket is a collection with volumes of its own - three at a
  time, `3 x VOLUME_SIZE_LIMIT_MB` of the disk's slots - so a spare bucket takes room for nothing.
* **The key layout inside it is the application's**: every object sits at its row's
  `storage_key` (`files/s000/123/report/v1/report.pdf`). Do not put anything else in it.
* **Versioning on** - the safety net for a mistaken delete or overwrite; old versions are removed
  after 30 days by the lifecycle rule. The application's own versions are rows, not these.
* **One lifecycle rule, over the whole bucket** (an empty prefix), with three actions: a version
  that stopped being current is removed 30 days later; a delete marker with nothing left behind
  it is removed; an unfinished multipart upload is aborted after a day. **Nothing current ever
  expires** - only the application deletes a file.
* **No object lock, no quota, no bucket policy.** Object lock would stop the application deleting
  a revision and cannot be switched off again; the quota is the application's; and access is
  `s3.json`'s - a policy with `"Principal": "*"` would make the bucket public.

Three ways to do it, all with the same result - pick one.

**With the AWS CLI** (`aws configure` with the `operator` key, region `us-east-1`):

```bash
S3=http://HOST:8333

aws --endpoint-url $S3 s3 mb s3://file-management-prod

aws --endpoint-url $S3 s3api put-bucket-versioning --bucket file-management-prod \
    --versioning-configuration Status=Enabled

aws --endpoint-url $S3 s3api put-bucket-lifecycle-configuration --bucket file-management-prod \
    --lifecycle-configuration '{"Rules":[{"ID":"cleanup-old-versions","Status":"Enabled",
      "Filter":{"Prefix":""},
      "Expiration":{"ExpiredObjectDeleteMarker":true},
      "NoncurrentVersionExpiration":{"NoncurrentDays":30},
      "AbortIncompleteMultipartUpload":{"DaysAfterInitiation":1}}]}'
```

**Or with Python (boto3)** - the same three steps. Give the client the region and path-style
addressing explicitly; every self-hosted S3 store wants path-style:

```python
import boto3
from botocore.config import Config

s3 = boto3.client(
    "s3",
    endpoint_url="http://HOST:8333",
    aws_access_key_id="OPERATOR_ACCESS_KEY",
    aws_secret_access_key="OPERATOR_SECRET_KEY",
    region_name="us-east-1",
    config=Config(s3={"addressing_style": "path"}),
)

s3.create_bucket(Bucket="file-management-prod")
s3.put_bucket_versioning(Bucket="file-management-prod",
                         VersioningConfiguration={"Status": "Enabled"})
s3.put_bucket_lifecycle_configuration(Bucket="file-management-prod", LifecycleConfiguration={"Rules": [
    {"ID": "cleanup-old-versions", "Status": "Enabled", "Filter": {"Prefix": ""},
     "Expiration": {"ExpiredObjectDeleteMarker": True},
     "NoncurrentVersionExpiration": {"NoncurrentDays": 30},
     "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 1}},
]})
```

**Or in the admin UI** (`http://127.0.0.1:23646`, user `admin`) - done this way on 2026-09-30, and
read back identical to the two above:

1. **Object Store → Buckets → Create New S3 Bucket**:

   | Field | Value |
   |---|---|
   | Bucket Name | `file-management-prod` |
   | Owner | `file-management` if the list offers it; otherwise leave *No owner* - access comes from `s3.json` (`Write:file-management-prod`) either way, which the check below proves |
   | Enable Storage Quota | **off** |
   | Enable Object Versioning | **on** |
   | Enable Object Lock | **off** - it cannot be turned off again, and it stops the application deleting revisions |

2. **Bucket Policy**: leave it as it is - `"Statement": []` - and save nothing there. It is access
   control, not the lifecycle; "Use Sample Policy" is not a starting point for this bucket.
3. **Lifecycle → Add rule**:

   | Field | Value |
   |---|---|
   | ID | `cleanup-old-versions` |
   | Status | **Enabled** |
   | Prefix | **empty** - the grey `logs/` is only a placeholder; empty means the whole bucket |
   | Tags, Size greater / less than | empty |
   | Expire after … days | **off - never.** It deletes the *current* files after that many days |
   | Expire on date | **off** - the same, by date |
   | Remove expired object delete markers | **on** |
   | Limit noncurrent versions | **on**: after `30` days; keep *newest* empty |
   | Abort incomplete multipart uploads after | **on**: `1` day |

   **Save rule.** The rule as the store keeps it (the admin UI shows it as XML):

   ```xml
   <LifecycleConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Rule>
     <ID>cleanup-old-versions</ID><Status>Enabled</Status><Filter><Prefix></Prefix></Filter>
     <Expiration><ExpiredObjectDeleteMarker>true</ExpiredObjectDeleteMarker></Expiration>
     <NoncurrentVersionExpiration><NoncurrentDays>30</NoncurrentDays></NoncurrentVersionExpiration>
     <AbortIncompleteMultipartUpload><DaysAfterInitiation>1</DaysAfterInitiation></AbortIncompleteMultipartUpload>
   </Rule></LifecycleConfiguration>
   ```

   There must be no `<Days>` inside `<Expiration>` - that would expire the current files - and
   `<Prefix>` must be empty.

Stored is not the same as carried out. **The rule is carried out by the `worker` service** (its S3
lifecycle job), so the worker must run - and that SeaweedFS actually removes the old versions has
not been seen yet. The test that settles it, on a bucket of its own with a one-day rule, is in
[roadmap 4.5.1](../../docs/roadmap.md#451-the-lifecycle-rule-stored-not-yet-seen-carried-out);
until it has passed, look at the bucket's size in the admin UI a month after the first deletes.

**Then check it with the application's key**, not the operator's - that is the key the
application will use:

```bash
AWS_ACCESS_KEY_ID=APP_KEY AWS_SECRET_ACCESS_KEY=APP_SECRET \
  aws --endpoint-url $S3 --region us-east-1 s3 cp ./test.txt s3://file-management-prod/check/test.txt
AWS_ACCESS_KEY_ID=APP_KEY AWS_SECRET_ACCESS_KEY=APP_SECRET \
  aws --endpoint-url $S3 --region us-east-1 s3 rm s3://file-management-prod/check/test.txt
aws --endpoint-url $S3 s3api get-bucket-versioning --bucket file-management-prod    # "Status": "Enabled"
aws --endpoint-url $S3 s3api get-bucket-lifecycle-configuration --bucket file-management-prod
    # one rule: "Prefix": "", NoncurrentDays 30, DaysAfterInitiation 1, ExpiredObjectDeleteMarker true
```

An `AccessDenied` on the first command means the bucket's name and the one in `s3.json` differ.

## Graphical interfaces

| What | Where | For |
|---|---|---|
| **Admin UI** (`weed admin`, in this file) | `http://127.0.0.1:23646` on the host - from another machine through an SSH tunnel: `ssh -L 23646:127.0.0.1:23646 HOST` | the store: volumes and disk, buckets and their lifecycle rules, a file browser, maintenance (vacuum, balance, the lifecycle job), the worker. User `admin`, `ADMIN_UI_PASSWORD`. Its **Users** page does not apply here: keys come from `s3.json`, which takes precedence over keys made in the UI. Keep it on loopback - published on `0.0.0.0` it puts the store's administration on the network |
| Master's page | `http://127.0.0.1:9333` on the host | the topology at a glance, read-only |
| An S3 desktop client - **Cyberduck** or **WinSCP** (free), S3 Browser | the operator's own machine, with the `operator` key, path-style | looking at, fetching or restoring a single object by hand |
| **Grafana** with SeaweedFS's dashboard (`other/metrics/grafana_seaweedfs.json` in its repository) | a Prometheus scraping each service's `-metricsPort` on the compose network | disk use, request rates, errors over time - the one to alert from |

People who use the documents never see any of these: they use the application.

## Backups

* **The filer's database**, in the same job as the application's database:
  `docker compose exec -T filer-db pg_dump -U seaweedfs -Fc seaweedfs_filer > filer-$(date +%F).dump`
* **The bytes**: a mirror of the bucket to a second store or site, on a schedule - `rclone sync`
  with the `operator` key, or SeaweedFS's own `weed filer.backup`. With one copy on one host this
  is the only copy anywhere else.
* `MASTER_DIR`, `ADMIN_DIR` and `WORKER_DIR` hold nothing that cannot be rebuilt.

## Upgrading SeaweedFS

Read the release notes of every version between the running one and the new one; take the filer
database dump; change the tag in `compose.yaml`; `docker compose pull && docker compose up -d`.

## Growing

* **A second copy** (the next step): a second volume server on a second disk - or a second host -
  and replication `001` (a copy on another server in the same rack) instead of `000`, with
  `WEED_MASTER_VOLUME_GROWTH_COPY_2` in place of `_COPY_1`. Existing volumes keep their single
  copy until they are given the new replication and copied (`volume.configure.replication` and
  `volume.fix.replication` in `weed shell`) - rehearse that on a copy first; it was not part of the
  verification above.
* **Erasure coding** (`ec.encode`) only from four volume servers on separate hosts - with fewer,
  losing one loses more shards of each encoded volume than erasure coding survives.
