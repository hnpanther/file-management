# SeaweedFS for file-management

The object store of roadmap Phase 4 (4.5, 4.6): one host, two disks, SeaweedFS 4.48, the filer's
metadata in PostgreSQL. `compose.yaml` says what each service is for; this file is how to run it.

**Verified on 2026-09-30** with this exact file, on Docker, with the AWS CLI: the application's key
wrote with `x-amz-checksum-sha256` and read the same checksum back; downloads were byte-identical,
`Range` answered; a 40 MB multipart upload round-tripped; `ListObjectsV2` with a prefix; versioning
on, a delete leaving a delete marker and the version; the lifecycle rules below stored; the
application's key refused outside its bucket and refused `CreateBucket`; anonymous requests and a
wrong secret refused; both volume servers holding the same bytes (replication `001`); the admin UI
behind its password with the worker connected. Two things that test found are already in the file:
the filer's database needs byte collation (`C`), and `weed admin` needs `-ip` for its worker port.

## Before the first start

1. **A Linux host with Docker**, and **two physical disks** mounted for `VOLUME1_DIR` and
   `VOLUME2_DIR`. Each disk holds a full copy of everything. Size each for all the files, their
   versions and formats, and growth - and at least `6 x VOLUME_SIZE_LIMIT_MB` (180 GB at the
   default 30 GB volumes; see `.env.example` for why).
2. `cp .env.example .env` - directories and two random passwords (`openssl rand -base64 24`).
3. `cp s3.json.example s3.json` - two keys: `file-management` (the application, its bucket only)
   and `operator` (buckets, backups, a GUI client). Random: `openssl rand -hex 10` for an access
   key, `openssl rand -base64 30` for a secret. `chmod 600 s3.json .env`.
   Changing `s3.json` later: edit, then `docker compose restart s3`.
4. `docker compose up -d`, then `docker compose ps` - everything `Up`, `master` and `filer-db`
   `healthy`.

## The bucket, once

With the `operator` key (the AWS CLI, or any S3 client pointed at `http://<host>:8333`,
path-style, region `us-east-1`):

```bash
aws --endpoint-url http://HOST:8333 s3 mb s3://file-management-prod
aws --endpoint-url http://HOST:8333 s3api put-bucket-versioning --bucket file-management-prod \
    --versioning-configuration Status=Enabled
aws --endpoint-url http://HOST:8333 s3api put-bucket-lifecycle-configuration --bucket file-management-prod \
    --lifecycle-configuration '{"Rules":[
      {"ID":"expire-noncurrent","Status":"Enabled","Filter":{"Prefix":""},"NoncurrentVersionExpiration":{"NoncurrentDays":30}},
      {"ID":"abort-multipart","Status":"Enabled","Filter":{"Prefix":""},"AbortIncompleteMultipartUpload":{"DaysAfterInitiation":1}}]}'
```

Versioning is the safety net (roadmap 4.2): an object deleted or overwritten by mistake keeps its
previous version for 30 days. The application's own versions are rows, not these.

## Graphical interfaces

| What | Where | For |
|---|---|---|
| **Admin UI** (`weed admin`, in this file) | `http://127.0.0.1:23646` on the host - from another machine through an SSH tunnel: `ssh -L 23646:127.0.0.1:23646 HOST` | the cluster: volumes and disks, buckets, a file browser, maintenance (vacuum, balance, replication repair), the workers. User `admin`, `ADMIN_UI_PASSWORD`. Its **Users** page does not apply here: keys come from `s3.json`, which takes precedence over keys made in the UI |
| Master's page | `http://127.0.0.1:9333` on the host | topology at a glance, read-only |
| An S3 desktop client - **Cyberduck** or **WinSCP** (free), S3 Browser | the operator's own machine, with the `operator` key, path-style | looking at, fetching or restoring a single object by hand |
| **Grafana** with SeaweedFS's dashboard (`other/metrics/grafana_seaweedfs.json` in its repository) | a Prometheus scraping the `-metricsPort` of each service on the compose network | disk use, request rates, errors over time - the one to alert from |

People who use the documents never see any of these: they use the application.

## Backups

* **The filer's database** - without it the bytes are there but nameless. `pg_dump` it with the
  application's database, in the same job:
  `docker compose exec -T filer-db pg_dump -U seaweedfs -Fc seaweedfs_filer > filer-$(date +%F).dump`
* **The bytes**: one host is one machine. A mirror of the bucket to a second store or site, on a
  schedule - `rclone sync` with the `operator` key, or SeaweedFS's own `weed filer.backup`. The
  two volume disks protect against a failed disk, not against a lost host or a mistake.
* `MASTER_DIR`, `ADMIN_DIR` and `WORKER_DIR` hold nothing that cannot be rebuilt.

## Upgrading SeaweedFS

Read the release notes of every version between the running one and the new one; change the tag
in `compose.yaml`; `docker compose pull && docker compose up -d`. Master first is what `up -d`
does by the dependencies. Take the filer database dump first.

## Growing

A third disk: a `volume3` service like the other two. Erasure coding (`ec.encode`) only from four
volume servers on separate hosts - with two, losing one loses half of every encoded volume's shards,
more than erasure coding survives (compose.yaml, and roadmap 4.6).
