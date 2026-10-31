# Webhooks — what a receiver is sent, and how to check it

Since 2.19.0 (roadmap 13.18) the application can call another system - n8n, the ERP - when something happens to the
files of a folder, instead of that system listing the folder over and over to find what changed. This is the
receiver's manual: what arrives, how to tell it is genuine, what to do with a repeat, and what is not promised. How
a subscription is made is at the end; the operators' part (switching it on, the allowed hosts) is in
[deployment.md](deployment.md#upgrading-from-2180-to-2190--webhooks).

## What arrives

One `POST` per event and subscription, `Content-Type: application/json`, with these headers:

| Header | What |
|---|---|
| `X-FM-Event` | the event, as in the table below (`FILE_CREATED`, ...); `TEST` for the page's test button |
| `X-FM-Event-Id` | the event's id - **the same on every retry and for every subscription the event was sent to**: drop a second copy by it |
| `X-FM-Delivery-Id` | this subscription's delivery of it (also the same on a retry) |
| `X-FM-Signature` | `t=<unix seconds>,v1=<hex>` - see "Checking it is genuine" |
| `User-Agent` | `file-management-webhook/1` |
| yours | the subscription's own credential, if it has one: a header you named (`X-Api-Key: ...`, `Authorization: Bearer ...`), or `Authorization: Basic ...` |

Answer **any 2xx**, quickly (within `timeout-seconds`, 10 by default) - do the work after answering if it is long.
Anything else - another status, a redirect (never followed), no answer in time, no connection - is a failure and the
delivery is tried again (below).

### The events

| `X-FM-Event` | `Records[].eventName` | When |
|---|---|---|
| `FILE_CREATED` | `s3:ObjectCreated:Put` | a new file uploaded |
| `REVISION_CREATED` | `s3:ObjectCreated:Put` | a new version of a file, or a new format of a version |
| `FILE_DELETED` | `s3:ObjectRemoved:Delete` | a file deleted (into the recycle bin) - also each file of a folder deleted with everything in it |
| `REVISION_DELETED` | `s3:ObjectRemoved:Delete` | one version or format deleted (into the bin) |
| `RESTORED` | `fm:ObjectRestored` | a file or a revision brought back from the bin - also each file of a folder restored |
| `PURGED` | `fm:ObjectPurged` | removed from the bin for good (by hand, or the nightly purge) |
| `MOVED` | `fm:ObjectMoved` | a file moved to another folder - sent to a subscription on where it left **and** on where it went |
| `METADATA_CHANGED` | `fm:MetadataChanged` | a revision's metadata document replaced |

Every way of making the change is covered - the form, v1, v2, the S3 surface, the recycle bin - because every one of
them records the file's history, and the history is where an event is born. **Not sent**: a folder made, renamed,
moved or deleted empty (no file changed); a description changed, a file published, a share link - the history keeps
those for itself. Anything in the recycle bin is in no folder, so nothing that happens there is sent; its delete,
restore and purge are events of the folder it was deleted from.

### The body

The shape of an S3 event notification, so that what reads MinIO's or AWS's reads this - with one record per object
the event touched - and an `fm` block of what only this application knows:

```json
{
  "Records": [{
    "eventVersion": "2.1",
    "eventSource": "fm:file-management",
    "eventTime": "2026-10-31T08:00:00.123Z",
    "eventName": "s3:ObjectCreated:Put",
    "userIdentity": { "principalId": "n8n-user" },
    "s3": {
      "s3SchemaVersion": "1.0",
      "configurationId": "ERP contracts",
      "bucket": { "name": "erp", "arn": "arn:aws:s3:::erp" },
      "object": {
        "key": "PartyCompany/A1/Contract/C1/Documents/Report+1.pdf",
        "size": 48213,
        "versionId": "5dcce572-9767-4822-946b-d20d752f7a6a",
        "sequencer": "00000000000A1F33"
      }
    }
  }],
  "fm": {
    "eventId": "6d807364-0cce-4fb0-938e-f769fc49eac6",
    "event": "FILE_CREATED",
    "fileEvent": "FILE_UPLOADED",
    "historyId": 663347,
    "occurredAt": "2026-10-31T08:00:00.123Z",
    "file": {
      "externalId": "8a16e6f1-8ec2-4e57-8bb3-684427a801d2",
      "name": "Report 1",
      "objects": [{ "key": "PartyCompany/A1/Contract/C1/Documents/Report 1.pdf", "version": 1, "format": "pdf",
                    "size": 48213, "revisionExternalId": "5dcce572-9767-4822-946b-d20d752f7a6a" }]
    },
    "folder": { "id": 812, "title": "ERP / PartyCompany / A1 / ...", "bucket": "erp",
                "keyPrefix": "PartyCompany/A1/Contract/C1/Documents/" },
    "movedFrom": null,
    "actor": { "username": "n8n-user", "apiKeyId": 4 },
    "webhook": { "id": 3, "name": "ERP contracts" }
  }
}
```

* **`s3.bucket.name`** is the top-level folder as the S3 surface names it (lower case, `_` as `-`); **`s3.object.key`**
  its folders below it and the file's name with its extension - URL-encoded as S3 writes it (a space a `+`); `fm.file.objects[].key`
  is the same key as it reads.
* **`versionId`** is the revision's external id - what v1 and v2 name a revision by; `fm.file.externalId` the file's.
* **An event about the whole file** - `FILE_DELETED`, `RESTORED` of a file, `PURGED` of a file, `MOVED` - has **one
  record per format**, each at its latest version (a `.docx` and a `.pdf`: two records). An event about a revision has
  one.
* **`MOVED`** carries `fm.movedFrom`: the folder it left (`id`, `title`, `bucket`, `keyPrefix`) and the `keys` it had
  there, in the order of the records.
* **The key is the key when the event happened.** A folder renamed or moved afterwards changes the keys of everything
  beneath it, and no event is sent for that: name a file by `fm.file.externalId`, never by its key, if you keep it.
* **Never in a body**: the file's bytes, its text, or a metadata value - a document may be personal; read it with
  v1 (`GET .../metadata`), with a key of your own, if you need it. The folder's title and the file's name are in it:
  a subscription sends the names of a folder's files to its receiver, whoever may read the folder.
* The JSON is stored in PostgreSQL before it is sent, and sent as stored: its keys may come in another order than
  above, and without the spaces. Parse it; never compare it as text.

## Checking it is genuine

`X-FM-Signature: t=1793433600,v1=13509c71...` - `v1` is the hex of an **HMAC-SHA256, under the subscription's signing
secret, of `t`, a dot, and the raw body** exactly as received. After the secret is rotated there are two `v1` for an
hour, one under each secret: accept the request if **any** of them matches. Refuse one whose `t` is far from now
(five minutes, say) - a recorded request played again later.

The signing secret (`whsec_...`) is shown **once**, when the subscription is made or its secret rotated. Checking it is
the receiver's choice: a receiver that does not check still receives. A receiver inside the network can rely on the
subscription's own credential instead (n8n's *Header Auth*), and check the signature as well where it matters.

**n8n**: a Webhook node (POST, *Raw Body* on), then a Code node:

```javascript
const crypto = require('crypto');
const secret = $env.FM_WEBHOOK_SECRET;               // the whsec_... shown once
const header = $json.headers['x-fm-signature'];
const raw = Buffer.from($binary.data.data, 'base64').toString('utf8');   // the raw body
let t = null; const v1 = [];
for (const part of header.split(',')) {
  const [k, v] = part.split('=');
  if (k === 't') t = v; else if (k === 'v1') v1.push(v);
}
const expected = Buffer.from(crypto.createHmac('sha256', secret).update(t + '.' + raw).digest('hex'));
const fresh = Math.abs(Date.now() / 1000 - Number(t)) < 300;
const matches = s => s.length === expected.length && crypto.timingSafeEqual(Buffer.from(s), expected);
if (!fresh || !v1.some(matches)) {
  throw new Error('not from the file management system');
}
return [{ json: JSON.parse(raw) }];
```

**Java**:

```java
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256"));
String expected = HexFormat.of().formatHex(mac.doFinal((t + "." + rawBody).getBytes(UTF_8)));
boolean genuine = v1s.stream().anyMatch(v -> MessageDigest.isEqual(v.getBytes(UTF_8), expected.getBytes(UTF_8)));
```

## Repeats, order, and what is not promised

* **At least once.** A delivery is written in the same transaction as the change - a change that rolls back sends
  nothing, one that commits is sent even if the application stops a moment later - and sent afterwards. A receiver
  that answered after the sender gave up waiting is sent it again. **Drop a repeat by `X-FM-Event-Id`.**
* **Not in order.** Deliveries are sent several at a time, and a retry overtakes; `fm.historyId` (and the
  `sequencer`, the same number in hex) says which event came first, for the same installation.
* **Tried again** after 1 minute, 5, 30, 2 hours, then every 6 hours - eight attempts within a day of the event; a
  `410 Gone` ends it at once. Then the delivery is `FAILED`, kept, and sent again from the page by hand (one, or every
  failed one of the subscription).
* **Paused by itself** after `pause after` failures in a row (20 by default): a receiver gone for a day is not knocked
  on for ever. What happens meanwhile waits in the queue; resumed from the page, it is sent.
* **Not kept for ever**: a delivery finished - sent, or given up - is removed after `retention-days` (30); one still
  waiting that long (a subscription paused a month) is given up and removed. A receiver down longer than that has to
  list what it missed.

## A loop

A receiver that writes files back into the folder it listens to - n8n uploading a converted PDF where the original
arrived - is sent its own upload, and may react to it again. Give the receiver an API key of its own and put that key
in the subscription's **"not the events of these API keys"**: its own changes are never sent to it. Or narrow the
subscription by extension or by a name pattern so its own files do not match.

## Making a subscription

Settings → **وب‌هوک‌ها** (`/settings/webhooks`, `WEBHOOKS_PAGE` to see, `SAVE_WEBHOOK` to make and change,
`DELETE_WEBHOOK` to delete; administrators' by default):

| Field | What |
|---|---|
| name | for the page; unique |
| address | `http` or `https`, on a host the operators allowed (`filemanagement.webhooks.allowed-hosts`) - nothing else is accepted, now or at each send |
| where | **this folder and every folder below it** (any depth); **this folder alone**; or **the folders of a class** (Phase 14) - anywhere, or only below a folder chosen. A subscription names its folder by id: renamed or moved, it follows it |
| what | the events; optionally only some extensions (`pdf, docx`), a name pattern (`invoice-*.pdf`: `*` any run, `?` one character, without case, on the name with its extension), people only or API keys only, and API keys whose events are never sent (the loop guard) |
| credential | none, a header of the receiver's (its name and value), or Basic (a user and a password) - kept encrypted, never shown again; left blank on a change, it stays |
| pace | at most so many a minute (60), at once (2), and paused by itself after so many failures in a row (20) |

Its row on the list says whether it sends, what waits and what failed; **رویداد آزمایشی** sends a test event at once
(no record, `X-FM-Event: TEST`) and says what the receiver answered; **ارسال‌ها** lists its deliveries, newest first,
with the receiver's last answer and the next attempt; **رمز امضای تازه** makes a new signing secret, signing with
both for an hour.
