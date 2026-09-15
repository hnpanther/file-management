#!/usr/bin/env python3
"""
A client for the file management API v2, in the standard library only.

It uploads ``report.txt`` from this directory, reads its metadata back, downloads it, lists
the folder, and deletes the version it created. Fill in the four settings just below the
imports - the base URL, the key, the bucket and the folder - and run it:

    python v2_client.py
    python v2_client.py --keep      upload and download, but do not delete afterwards

An environment variable of the same name (``FM_API_KEY`` and so on) overrides the value in the
file, which is how to run it without writing a real key into a file that is under version
control. **Do not commit a real key.**

What the API expects, and what this file shows:

* Authentication is a bearer token: ``Authorization: Bearer fmk_...``. There is no Signature V4,
  so this is plain HTTP - no AWS SDK is involved and none would connect.
* A *bucket* is a top-level folder, matched case-insensitively with ``_`` and ``-`` treated as
  the same character. A *key* is the path beneath it.
* To **upload**, PUT the raw bytes to ``{bucket}/{prefix}/{name}/{name}.{ext}`` - the key names
  the file but *not* a version. The server assigns the next version and answers 201 with the
  canonical key (``.../{name}/v{n}/{name}.{ext}``) and an ``x-fm-version`` header. Versions
  are immutable: a PUT to an explicit ``v{n}`` is a 409.
* To **download**, **inspect** or **delete**, use the canonical key - the one with the version.
* Errors come back as JSON problem details: ``{"status": 403, "title": ..., "detail": ...}``.
"""

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
LOCAL_FILE = HERE / "report.txt"

# ---------------------------------------------------------------- settings: edit these

FM_BASE_URL = "http://localhost:8122"   # where the application is running
FM_API_KEY = "fmk_..."                  # the credential shown once on the API keys screen
FM_BUCKET = "IMS_Document_System"       # a top-level folder
FM_PREFIX = "HSED/BL-HRM-JC-001"        # sub-category/tag: the folder the key may write to

BASE_URL = os.environ.get("FM_BASE_URL", FM_BASE_URL).rstrip("/")
API_KEY = os.environ.get("FM_API_KEY", FM_API_KEY)
BUCKET = os.environ.get("FM_BUCKET", FM_BUCKET)
PREFIX = os.environ.get("FM_PREFIX", FM_PREFIX).strip("/")


# ---------------------------------------------------------------- one request, one place

class ApiError(Exception):
    """A non-2xx answer, with the problem detail the server sent."""

    def __init__(self, status, problem):
        super().__init__(f"{status} {problem.get('title', '')}: {problem.get('detail', '')}".strip())
        self.status = status
        self.problem = problem


def request(method, path, query=None, body=None, content_type=None, headers=None):
    """
    Sends one request and returns ``(status, headers, bytes)``.

    ``path`` is the part after the base URL, already containing the bucket and key. Each segment
    is percent-encoded here so that a Persian file name travels correctly, but the slashes are
    left alone - they are the separators of the key, and encoding them as ``%2F`` is refused.
    ``query`` is a dict and is encoded separately, once: a prefix such as ``HSED/`` must arrive
    as ``HSED%2F`` in the query string, not as ``HSED%252F``.
    """
    url = BASE_URL + urllib.parse.quote(path, safe="/")
    if query:
        url += "?" + urllib.parse.urlencode(query)
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", "Bearer " + API_KEY)
    if content_type:
        req.add_header("Content-Type", content_type)
    for name, value in (headers or {}).items():
        req.add_header(name, value)

    try:
        with urllib.request.urlopen(req) as response:
            return response.status, dict(response.headers), response.read()
    except urllib.error.HTTPError as error:
        payload = error.read()
        try:
            problem = json.loads(payload)
        except ValueError:
            problem = {"title": error.reason, "detail": payload.decode("utf-8", "replace")}
        raise ApiError(error.code, problem) from None


def object_url(bucket, key):
    return f"/api/v2/{bucket}/{key}"


# ---------------------------------------------------------------- the operations

def upload(bucket, prefix, local_file):
    """
    PUT the bytes; the key names the file and no version.

    The name has one dot and no spaces or slashes - that is what the server accepts as a file
    name - and the folder segment repeats it without the extension, which is how the layout is
    shaped: ``{prefix}/{name}/{name}.{ext}``.
    """
    name = local_file.stem
    write_key = f"{prefix}/{name}/{local_file.name}"

    status, headers, body = request(
        "PUT", object_url(bucket, write_key),
        body=local_file.read_bytes(),
        content_type="text/plain; charset=utf-8",
    )
    stored = json.loads(body)
    print(f"uploaded   {status}  version {headers.get('x-fm-version')}  key={stored['key']}")
    return stored["key"]


def metadata(bucket, key):
    """HEAD gives the headers; ``?metadata`` gives the same as a JSON body. Both are shown."""
    status, headers, _ = request("HEAD", object_url(bucket, key))
    print(f"head       {status}  etag={headers.get('ETag')}  "
          f"length={headers.get('Content-Length')}  modified={headers.get('Last-Modified')}")

    status, _, body = request("GET", object_url(bucket, key), query={"metadata": ""})
    info = json.loads(body)
    print(f"metadata   {status}  {json.dumps(info, ensure_ascii=False)}")
    return info


def download(bucket, key, destination):
    """GET the bytes. A ``Range`` header would be honoured (206); the whole object is taken here."""
    status, headers, body = request("GET", object_url(bucket, key))
    destination.write_bytes(body)
    print(f"downloaded {status}  {len(body)} bytes -> {destination}  "
          f"(content-type {headers.get('Content-Type')}, version {headers.get('x-fm-version')})")
    return destination


def list_objects(bucket, prefix):
    """
    Lists the keys beneath a prefix.

    With ``delimiter=/`` the listing stops at each folder boundary and reports the boundaries
    as ``commonPrefixes``, which is how to browse; without it every key beneath the prefix
    comes back, paged with ``max-keys`` and ``continuation-token``.
    """
    status, _, body = request("GET", f"/api/v2/{bucket}", query={"prefix": prefix + "/", "max-keys": 100})
    listing = json.loads(body)
    print(f"listed     {status}  {len(listing['contents'])} key(s) under {prefix}/")
    for entry in listing["contents"]:
        print(f"             {entry['key']}  ({entry['size']} bytes)")
    if listing["truncated"]:
        print(f"             ... more; continue with continuation-token={listing['nextContinuationToken']}")
    return listing


def delete(bucket, key):
    """DELETE names the version. Removing the last version of a file removes the file."""
    status, _, _ = request("DELETE", object_url(bucket, key))
    print(f"deleted    {status}  {key}")


# ---------------------------------------------------------------- putting it together

def main(argv):
    keep = "--keep" in argv

    if not API_KEY.startswith("fmk_") or API_KEY == "fmk_...":
        print("FM_API_KEY is still the placeholder: paste the credential from the API keys screen "
              "into the settings block at the top of this file", file=sys.stderr)
        return 2
    if not LOCAL_FILE.exists():
        print(f"{LOCAL_FILE} is missing", file=sys.stderr)
        return 2

    try:
        stored_key = upload(BUCKET, PREFIX, LOCAL_FILE)
        metadata(BUCKET, stored_key)
        download(BUCKET, stored_key, HERE / ("downloaded-" + LOCAL_FILE.name))
        list_objects(BUCKET, PREFIX)
        if keep:
            print(f"kept       {stored_key}")
        else:
            delete(BUCKET, stored_key)
    except ApiError as error:
        # 401: the key is wrong, disabled, revoked or expired - the server does not say which.
        # 403: the key exists but was not granted this folder, or was granted READ and this wrote.
        # 404: no such bucket, folder or object. 409: the name is taken or the key names a version.
        print(f"failed     {error}", file=sys.stderr)
        return 1
    except urllib.error.URLError as error:
        print(f"failed     cannot reach {BASE_URL}: {error.reason}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
