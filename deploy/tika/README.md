# Apache Tika for file-management

The text extraction and OCR of roadmap **Phase 11** (searching the contents of files): Apache Tika
**4.1**'s server, in two containers of one image - one reading documents that have text, one
reading images and scanned pages with Tesseract, in **Persian and English**. `compose.yaml` says what
each container is for; this file is how to build it, run it and try it, and what trying it found.

**The application does not call it yet.** This is here to try Tika on real files before step 11.1
wires it in. Nothing in the application changes by running it; the application's `tika-core`
(type detection, `pom.xml`) stays 3.x until 11.1 - it will talk to this server over HTTP, so the two
versions are independent.

**Verified on 2026-10-04** with this exact file, on Docker Desktop (Windows), Tika 4.1.0
(`apache/tika:4.1.0-1-full`, Ubuntu 26.04, Tesseract 5.5, Java 25): both containers healthy;
`tesseract --list-langs` naming `fas` and `eng`; the results in "What trying it found" below.

## What it needs

| | CPU | RAM | Disk |
|---|---|---|---|
| To try it | 2 cores per container | 6 GB free for Docker | ~2.1 GB for the image |
| In service | roadmap Phase 11, "Where Tika runs, and on what" | | |

A **Linux host with Docker** in service (roadmap 11 - a host of its own, not the application's nor
the object store's); **Docker Desktop** is fine for trying it. No GPU: Tesseract runs on the CPU.
No backup: Tika holds no data.

## Building it

```bash
cd deploy/tika
cp .env.example .env
docker pull apache/tika:4.1.0-1-full          # ~2 GB; once
docker compose build                          # adds Ubuntu's tesseract-ocr-fas: about a minute
docker compose run --rm --no-deps --entrypoint tesseract tika-ocr --list-langs
```

The last command must list **`fas`** and **`eng`** - the build itself stops if either is missing.
One image, `file-management/tika:4.1.0-1-fas`, serves both containers: the text lane runs it with
OCR switched off.

## Running it

```bash
docker compose up -d
docker compose ps                       # both "healthy" within a minute
curl http://127.0.0.1:9998/version      # tika-text -> Apache Tika 4.1.0
curl http://127.0.0.1:9999/version      # tika-ocr  -> Apache Tika 4.1.0
docker compose down                     # to stop; the /tmp volumes stay until "down -v"
```

On Windows use `curl.exe` (PowerShell's `curl` is another command), and paths like `.\scan.png`.

## Trying it

Tika 4 names the output in the path - the `Accept` header no longer chooses it:

| Request | Gives |
|---|---|
| `PUT /tika/text` | the text, plain |
| `PUT /tika` | the text as **Markdown** (4.x's default) |
| `PUT /rmeta/text` | JSON: the metadata, the text in `tk:content` |
| `PUT /rmeta/ignore` | JSON: the metadata only |
| `PUT /detect` | the content type (3.x's `/detect/stream`) |

**A document with text** - Word, Excel, PowerPoint, a PDF made from Word - on the text lane:

```bash
curl -T contract.docx http://127.0.0.1:9998/tika/text
curl -T report.pdf    http://127.0.0.1:9998/tika/text
curl -T report.pdf    http://127.0.0.1:9998/rmeta/text
```

**An image of Persian text, or a scanned PDF**, on the OCR lane - a PDF page by page: a page with a
usable text layer read as text, one without rendered at 300 dpi and recognised:

```bash
curl -T scan.png    http://127.0.0.1:9999/tika/text
curl -T scanned.pdf http://127.0.0.1:9999/tika/text
```

**Variants, by preset** - Tika 4 removed 3.x's per-request headers (`X-Tika-OCRLanguage`,
`X-Tika-PDFOcrStrategy`, ...); a request chooses one of the named presets in `config/ocr.json`
instead, in the path:

```bash
# every page through OCR as well as its text layer (a stamp, a pasted scan on a text page)
curl -T mixed.pdf http://127.0.0.1:9999/tika/preset/every-page/text
# the text layer only, no OCR
curl -T mixed.pdf http://127.0.0.1:9999/tika/preset/text-layer-only/text
# OCR only, the text layer ignored - to measure OCR on a text PDF against its own text
curl -T report.pdf http://127.0.0.1:9999/tika/preset/ocr-only/text
```

A preset is a public route - anyone who can reach the port can call it - so it is defined only in
the server's config, never by a request (`allowPerRequestConfig` stays off).

**Errors are JSON** (`{"status":"TIMEOUT"}`), and a busy server answers **`429` with
`Retry-After`** - every forked parsing JVM in use - rather than queueing without end: the
application's worker is to wait and retry a 429, and set aside a 503 (a document that broke a fork).

Save an answer to read it in an editor that shows Persian right to left: `curl ... -o result.txt`.

## What trying it found (2026-10-04)

| Test | Result |
|---|---|
| A Word document in Persian, text lane | every character right |
| Real text PDFs (IMS forms, equipment sheets), text lane | Persian in the right order, no presentation forms (`U+FE70-FEFF`), no reversed words; 0.2 s a file. Some files use Arabic `ي`/`ك` (`كد`, `کاربري`) - folded by the application's search keys already |
| Real scanned PDFs (5, 7, 10 pages, no text layer), OCR lane | **2.3-2.5 s a page** (one fork, 2 CPUs); most of the text right - `اداره پدافند غیرعامل و مدیریت بحران`, `روش اجرایی نظارت بر تصاویر و محتوا` - and some words a letter off (`دسنور`, `نموه`, `شر کت`, `فاری`): Ubuntu's standard model |
| A rendered page of Persian and English, OCR lane | with `fas+eng`: the English and most Persian right, but two Persian words read as Latin (`مبلغ` → `glo`, `ریال` → `JL)`); with `fas` alone every Persian word right and the English line garbage. Page segmentation `1` (the default here, for scans not always upright) dropped the first line of that synthetic page; `6` kept it |
| The same scanned PDF, text lane or the `text-layer-only` preset | no text, as intended |
| `rmeta` of a scanned PDF | `pdf:ocr-page-count = 1`, `pdf:chars-per-page = 0`, 4.x's `tk:` keys |

What this means for roadmap 11.4:

* **Text PDFs and Office files read well**, and fast - the text lane is worth switching on as soon as
  11.1 is built.
* **OCR is good enough to make most scanned pages findable** - a search for a word finds the page as
  long as that word was read, which most were - and not good enough to show its text as the
  document's. The accurate model (below) is the next thing to try, on the same files.
* **2.5 s a page on one fork**: 1,000 scanned pages are about 40 minutes on these 2 CPUs; the
  backfill's arithmetic in roadmap 11 holds.
* **`fas+eng` against `fas`**: measured below - `fas+eng` stays.

### The two Persian models, and `fas` alone, measured

Two measures, since a scan has no text to compare with:

* **On text PDFs, against their own text**: the same pages rendered and OCR'd with the text layer
  ignored (the `ocr-only` preset), the text lane's output taken as the truth; the score is the share
  of its Persian words (folded as the application folds names) the OCR read - 2,143 words over three
  real one-page PDFs.
* **On real scans**: a lexicon of 29,452 Persian words from the text layers of 150 of the
  development data's PDFs; the score is the share of the OCR's Persian words that are in it - a
  misread word almost never is.

| | Standard `fas` (Ubuntu, 431 KB), `fas+eng` | **Best `fas` (`tessdata_best`, 3.3 MB), `fas+eng`** | Standard, `fas` alone |
|---|---|---|---|
| Text PDFs: words read right | 89.5 / 90.5 / 91.4% | 90.7 / 90.2 / 91.0% | - / 91.5 / 92.3% |
| Scan, 5 pages: real words | 85.6% | **89.0%** | 83.3% |
| Scan, 7 pages: real words | 86.3% | 86.1% | 84.4% |
| Scan, 10 pages: real words | 81.0% | **85.0%** | 78.4% |
| Time (the three scans, 22 pages) | 69 s - 3.1 s a page | 90 s - 4.1 s a page | 48 s - 2.2 s a page |

(The times here are higher than the 2.3-2.5 s above: three OCR containers shared the machine.)

* **On clean pages the models are equal**, about 90% of words; the accurate one is only slower.
* **On real scans the accurate model reads 3-4 points more real words** on two of three, equal on
  the third, and costs about 30% more time. On noisy scans - which is what OCR is for - that is
  the better trade: the backfill takes 30% longer once; every search over those pages finds more.
* **`fas` alone** is the fastest and reads clean Persian a point better, but turns every English word
  - equipment tags, codes, names, common in these documents - into Persian noise (its scans score
  lowest). `fas+eng` stays.
* Recommended for 11.4, to confirm on a larger set of scans: **`tessdata_best`'s `fas` with
  `fas+eng`**, baked into the image at a pinned checksum rather than mounted.

## A better Persian model

Ubuntu's `tesseract-ocr-fas` is Tesseract's standard model. `tessdata_best` has a larger one that
reads Persian more accurately, more slowly. To try it:

1. Download `fas.traineddata` from github.com/tesseract-ocr/tessdata_best into
   `deploy/tika/tessdata/` (ignored by git) - 3,325,955 bytes, SHA-256
   `99e420969b5ddd2cb135b416316a7ed417c59c4faf9e0d28941348f6448114df` as measured above.
2. In `compose.yaml`, uncomment the `tessdata` line under `tika-ocr`'s `volumes`.
3. `docker compose up -d tika-ocr`, and send the same files again.

## Security

* **tika-server has no authentication** - its own log says so at every start. Whoever reaches its
  port can have it parse anything, and parsing is CPU and memory. The ports are on loopback
  (`TIKA_BIND=127.0.0.1`). In service set the host's private address and let the firewall admit
  **only the application's host** to 9998 and 9999 - as port 8122 is kept to the proxy.
* **It opens whatever people upload**, and parsers have had vulnerabilities. So the containers run as
  the image's unprivileged user, on a read-only filesystem with only `/tmp` writable (a volume of
  its own), with every capability dropped, on a network of their own that reaches no other
  container - and Tika 4 parses in forked JVMs, so a parser that crashes, hangs or runs out of
  memory takes its fork down and not the server.
* `allowPipes` and `allowPerRequestConfig` stay off: no `/pipes`, `/async` or `/config` endpoints,
  which would let a caller read files or change how documents are parsed.

## When it does not start

| Sign | Likely cause |
|---|---|
| `docker compose build` stops at `grep -qx fas` | `tesseract-ocr-fas` did not install - look at the apt step's output above it |
| `Couldn't find 'server' element` | the config lost its `"server"` section - Tika 4.1 needs it, even nearly empty |
| `Unrecognized field` / an unknown key at start | Tika 4's config rejects unknown keys - a 3.x setting, or a typo; the message names it |
| a container restarts, its log mentions a read-only file system | something writes outside `/tmp`: remove `read_only: true` from `compose.yaml` to try, and note what it was |
| `429` under load | every fork busy: raise `pipes.numClients` in `config/*.json` together with the container's CPUs (`numClients x 2 + 2 <= cores`) |
| an answer is empty for a scan sent to 9998 | as it should be: the text lane does no OCR - send it to 9999 |
