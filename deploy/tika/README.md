# Apache Tika for file-management

The text extraction and OCR of roadmap **Phase 11** (searching the contents of files): Apache Tika's
server, in two containers - one reading documents that have text, one reading images and scanned
pages with Tesseract, in **Persian and English**. `compose.yaml` says what each container is for;
this file is how to build it, run it and try it.

**The application does not call it yet.** This is here to try Tika on real files - how well it reads
Persian text PDFs, Word files, screenshots and scans, and how fast - before step 11.1 wires it in.
Nothing in the application changes by running it.

**Not yet verified on a running Docker**: written to the official image's layout (Tika 3.3.2, the
version of the application's `tika-core`). The first build is where it is checked - "Building it"
below says what must hold.

## What it needs

| | CPU | RAM | Disk |
|---|---|---|---|
| To try it | 2 cores | 6 GB free for Docker | ~2 GB for the images |
| In service | roadmap Phase 11, "Where Tika runs, and on what" | | |

A **Linux host with Docker** in service (roadmap 11 - a host of its own, not the application's nor the
object store's); **Docker Desktop on Windows** is fine for trying it. No GPU: Tesseract runs on the
CPU. No volume, no backup: Tika holds no data.

## Building it

```bash
cd deploy/tika
cp .env.example .env
docker compose build tika-ocr
docker compose run --rm --entrypoint tesseract tika-ocr --list-langs
```

The last command must list **`fas`** and **`eng`** (among others) - the build itself stops if
either is missing. The build downloads the official `apache/tika:3.3.2.0-full` image (about 1 GB)
and Ubuntu's `tesseract-ocr-fas` package; `tika-text` uses `apache/tika:3.3.2.0` as it is.

If the tag does not exist on Docker Hub when you build, take the nearest `3.x.y.z` and `-full` pair
listed at hub.docker.com/r/apache/tika/tags and set `TIKA_VERSION` in `.env` - and note the version
for roadmap 11.1, which must match the application's `tika-core`.

## Running it

```bash
docker compose up -d
docker compose ps                       # both "healthy" within a minute or two
curl http://127.0.0.1:9998/version      # tika-text  -> Apache Tika 3.3.2
curl http://127.0.0.1:9999/version      # tika-ocr   -> Apache Tika 3.3.2
```

On Windows use `curl.exe` (PowerShell's `curl` is another command), and paths like `.\scan.png`.

## Trying it

Every request is the file's bytes as the body (`-T`), the answer plain text (`Accept: text/plain`).
`/tika` gives the text; `/rmeta/text` gives the text and the metadata as JSON (the content type
Tika detected, the page count, the author, ...); `/detect/stream` only the type.

**A document with text** - Word, Excel, PowerPoint, a PDF made from Word - on the text lane:

```bash
curl -T contract.docx -H "Accept: text/plain" http://127.0.0.1:9998/tika
curl -T report.pdf    -H "Accept: text/plain" http://127.0.0.1:9998/tika
curl -T report.pdf    -H "Accept: application/json" http://127.0.0.1:9998/rmeta/text
```

**An image of Persian text** - a screenshot, a photo of a page - on the OCR lane:

```bash
curl -T scan.png -H "Accept: text/plain" http://127.0.0.1:9999/tika
```

**A scanned PDF**, or one with both text and scanned pages - page by page, a page with a text layer
read as text, one without rendered at 300 dpi and recognised:

```bash
curl -T scanned.pdf -H "Accept: text/plain" http://127.0.0.1:9999/tika
```

**Settings for one request**, as the application will send them (roadmap 11: "The OCR settings
travel with each request"):

```bash
# English only, a stricter time limit
curl -T scan.png -H "Accept: text/plain" -H "X-Tika-OCRLanguage: eng" -H "X-Tika-OCRtimeoutSeconds: 120" http://127.0.0.1:9999/tika
# Every page of the PDF through OCR, text layer or not (stamps, pasted scans)
curl -T mixed.pdf -H "Accept: text/plain" -H "X-Tika-PDFOcrStrategy: ocr_and_text_extraction" http://127.0.0.1:9999/tika
# The text layer only, no OCR - what the text lane does
curl -T mixed.pdf -H "Accept: text/plain" -H "X-Tika-PDFOcrStrategy: no_ocr" http://127.0.0.1:9999/tika
```

Save an answer to a file to look at it in an editor that shows Persian right to left:
`curl ... -o result.txt`. On Windows, `curl.exe ... -o result.txt` and open it in VS Code or Notepad.

### What to look at - what roadmap 11.4 needs from this

1. **Persian text PDFs**: are the letters in order, or reversed? joined forms (`ﻛﺘﺎﺏ` instead of
   `کتاب`)? nothing at all (a font with no Unicode map - 11's `auto` then OCRs the page)?
2. **Scans**: how much of a typical page is right - Persian and English, tables, stamps, handwriting
   (which Tesseract does not read).
3. **Time**: how long one A4 page takes on this machine - `time curl ...` on a one-page scan, then on
   ten pages. Roadmap 11 estimates 2-6 s a page per core in Persian; the real number sizes the host.
4. **Drawings**: what a DWG gives (only its metadata, as roadmap 11 expects) and what a DXF and a
   Visio give.

Write what you find - with a few sample pages - into roadmap 11.4; it is what decides whether OCR is
switched on and on what host.

## A better Persian model

Ubuntu's `tesseract-ocr-fas` is Tesseract's standard model. `tessdata_best` has a larger one that
reads Persian more accurately, two to three times more slowly. To try it:

1. Download `fas.traineddata` from github.com/tesseract-ocr/tessdata_best (about 10 MB) into
   `deploy/tika/tessdata/`.
2. In `compose.yaml`, uncomment the `tessdata` line under `tika-ocr`'s `volumes`.
3. `docker compose up -d tika-ocr`, and read the same pages again.

## Security

* **tika-server has no authentication.** Whoever reaches its port can have it parse anything, and
  parsing is CPU and memory. The ports are on loopback (`TIKA_BIND=127.0.0.1`). In service set the
  host's private address and let the firewall admit **only the application's host** to 9998 and
  9999 - as port 8122 is kept to the proxy.
* **It opens whatever people upload**, and parsers have had vulnerabilities. So the containers run as
  the image's unprivileged user, on a read-only filesystem with only `/tmp` writable (in memory, and
  bounded), with every capability dropped, on a network of their own that reaches no other
  container - and Tika parses in a child JVM the server restarts if it hangs, dies or runs out of
  memory.
* **No data**: nothing here is kept; a container can be removed and rebuilt at any time.

## When it does not start

| Sign | Likely cause |
|---|---|
| `docker compose build` stops at `grep -qx fas` | `tesseract-ocr-fas` did not install - look at the apt step's output above it |
| a container restarts, its log mentions a read-only file system | something writes outside `/tmp`: remove `read_only: true` from `compose.yaml` to try, and note what it was |
| `unhealthy`, the log ends in `OutOfMemoryError` | the child's `-Xmx` in `config/*.xml` does not fit in `TIKA_*_MEMORY` - lower the one or raise the other |
| an answer is empty for a scan sent to 9998 | as it should be: the text lane does no OCR - send it to 9999 |
| a request ends after a long time with an error | `taskTimeoutMillis` in `config/*.xml` (30 minutes on the OCR lane) - a long scan needs more, or fewer pages |
