# Running the Application as a Service — Windows and Linux

The application is a single executable JAR. Everything below is about making it start with the
machine, restart when it dies, write its logs somewhere a person can find them, and keep its two
halves of state — the MySQL rows and the files on disk — backed up as the pair they are.

For how the application is built, see [arch.md](arch.md); for the commands and the working
agreement, [AGENTS.md](../AGENTS.md).

---

## Before anything else

| | |
|---|---|
| Java | **21 or later** (`<java.version>21</java.version>`). A JRE is enough to run; the JDK is only needed to build |
| MySQL | 8.0 or later. Verified on 8.0.36 (the version `compose.yaml` and the test containers use) and 8.4 |
| Artifact | `target/file-management.jar` — `<finalName>` is the artifact id, so the name has no version in it and the service definition does not change every release |
| Listens on | `8122` by default (`FILEMANAGEMENT_PORT`) |
| Profile | `prod`, which is the shipped default and is what enables the first-run seeding |

**Build on a machine with network access and deploy the JAR.** The target host needs neither Maven
nor a route to a repository mirror, and should not have one.

```bash
./mvnw clean package
# target/file-management.jar
```

**A JDK and a network route to a Maven mirror are the whole list.** Node is not needed to build,
and `node_modules/` can be absent: the Tailwind stylesheet is compiled by hand during development
and its output is committed, so the build has no front-end step at all. A test
(`UiResourceTest.nothingInTheBuildRequiresNode`) fails if that ever stops being true — see
[ui.md](ui.md) for why it is arranged this way.

### The artefact is a fat jar

`target/file-management.jar` is a Spring Boot **executable ("fat") jar**: the application, every
dependency (`BOOT-INF/lib/`, about a hundred of them) and the embedded Tomcat, launched by
`org.springframework.boot.loader.launch.JarLauncher`. `java -jar file-management.jar` is the whole
runtime. There is no `war`, no application server to install, no `lib/` directory to ship beside
it, and no classpath to assemble — the `spring-boot-maven-plugin` `repackage` goal builds it so,
and `pom.xml` says so explicitly. Copy one file.

### Configuring it from outside the jar

The jar carries `application.properties` with defaults, and every setting that must differ per
installation reads an environment variable (`FILEMANAGEMENT_DB_PASSWORD`, `FILEMANAGEMENT_BASE_DIR`,
…). Environment variables are the primary way, and both service definitions below set them. But
a properties file **beside the jar** works too, and is sometimes tidier — Spring Boot reads, in
this order, each one overriding the one before:

| Where | Notes |
|---|---|
| `application.properties` inside the jar | the shipped defaults |
| `./config/application.properties` | a `config/` directory next to the jar — the conventional place |
| `./application.properties` | directly beside the jar |
| `./config/*/application.properties` | any subdirectory of `config/`, for splitting settings across files |
| `--spring.config.additional-location=file:D:/somewhere/` | anywhere else, given on the command line |
| environment variables | `FILEMANAGEMENT_*` as documented; or any property name in relaxed form, e.g. `SPRING_DATASOURCE_URL` |
| `--property=value` on the command line | highest |

Three things to know before relying on a file:

* **`./` is the working directory, not the jar's directory.** Both service definitions set it
  explicitly (`WorkingDirectory=` / `<workingdirectory>`); a `config/` beside the jar is only found
  if the service starts there.
* **An external file overrides property by property**; it does not replace the packaged one. Put
  only what differs in it. The packaged `spring.config.import` (the actuator and OpenAPI settings)
  still applies.
* **Profile-specific files work the same way**: `./config/application-prod.properties` is read
  because `prod` is the active profile, and `application-local.properties` beside the jar is read
  if you start with `--spring.profiles.active=prod,local` (the repository ships an `.example`).

So a Windows installation can be laid out as `D:\MyApp\file-management\config\application.properties`
holding the database URL, the directories and the port, with the password alone in an `<env>`
entry — or everything in `<env>`. Either is fine; do not do both for the same key and then wonder
which won (the environment does).

### The settings that must not stay as they ship

Do this **before** the first start.

| Variable | Default | Why it matters |
|---|---|---|
| `FILEMANAGEMENT_DB_PASSWORD` | `file_management` | A password published in this repository |
| `FILEMANAGEMENT_BASE_DIR` | `./TempFiles/files/main/` | Where every uploaded file is written. The default is inside the working tree, **and `TempFiles/` is in `.gitignore`** — so on a real host the data lands in a directory the repository deliberately ignores ([issue 45](issues.md#45-the-prod-profile-writes-into-the-working-tree--s3)) |
| `FILEMANAGEMENT_LOG_PATH` | `./logs` | Same problem: relative to the working directory |
| `filemanagement.bootstrap.admin-password` | *(empty)* | With nothing set, `DataInitializer` generates a random password for the `Admin` account and prints it **once**, at WARN, on the first boot. Miss that line and the account is unusable |

> **`FILEMANAGEMENT_BASE_DIR` with or without a trailing separator - both work, since 1.1.0.**
> The storage service used to concatenate strings (`baseDir + address + "/" + …`), so
> `/srv/file-management/files` without the slash produced `/srv/file-management/filesIMS/…`. Worse,
> from 1.1.0 half of it resolves paths properly (uploads, downloads) while the other half still
> concatenates (deleting a whole file): with `E:\FileManagementSystem\files\main` an upload landed
> in `main\IMS\…` and the delete of that same file looked in `mainIMS/…` and answered 404. The
> constructor now appends the separator when it is missing, so both halves name the same root.
> Write it with the separator anyway; it is what every example here shows. The path-containment
> check ([issue 16](issues.md#16-no-path-containment-check-at-the-storage-boundary--s2)) is a
> separate matter and still Phase 2.

Generate the admin password rather than inventing one:

```bash
# Linux
openssl rand -base64 24
```

```powershell
# Windows
[Convert]::ToBase64String((1..24 | ForEach-Object { Get-Random -Max 256 }))
```

### The directories the service owns

Both are relative to the **working directory** the service starts in unless set to absolute paths,
which is why every service definition below sets the working directory explicitly. A service that
starts in `C:\Windows\System32` will happily create `C:\Windows\System32\TempFiles` and nobody will
find it again.

| Path | Holds | Back up? |
|---|---|---|
| `FILEMANAGEMENT_LOG_PATH` | `app_log.log` and `archived/` | No — rotated daily / 10 MB, 10 kept |
| `FILEMANAGEMENT_BASE_DIR` | every uploaded file, as `{Category}/{SubCategory}/{FileName}/v{n}/{file}.{ext}` | **Yes, with the database** |

> **The file directory and the database must be backed up together.** The rows and the bytes are
> only meaningful as a pair: `file_info` and `file_details` hold the paths, never the content. A
> restore of one without the other leaves rows pointing at files that are gone, or files nothing
> references. See [Backups](#backups--one-job-both-halves).

### One decision to make deliberately: folder access

`filemanagement.folder-access.enabled` decides whether folder-level access control is enforced
(roadmap [Phase 6](roadmap.md#phase-6--two-tier-authorization-endpoint-permissions-and-folder-access)).

**Turn it on only after the grants exist.** With it on and no `role_folder` rows, every
non-administrator sees an empty tree and an empty file list — the model fails closed, which is
correct and also indistinguishable from "the application is broken" if you were not expecting it.
The order is: grant folders on the role edit page, check what each role reaches, then set it.

Two things that are easy to miss when you do turn it on:

* a role needs `FILE_TREE_PAGE` to reach the tree at all;
* the machine account used by `/api/v1/files` has no folder grants by default, so its downloads
  stop working until its role is granted something.

---

# Linux — systemd

## 1. User, directories, permissions

A dedicated unprivileged account. The service never needs root: it binds 8122, not 443.

```bash
sudo useradd --system --home /opt/file-management --shell /usr/sbin/nologin filemgmt
sudo mkdir -p /opt/file-management/{files,logs}
sudo cp file-management.jar /opt/file-management/app.jar
sudo chown -R filemgmt:filemgmt /opt/file-management
sudo chmod 750 /opt/file-management
```

## 2. The environment file

Keep secrets out of the unit file: a unit is world-readable, an environment file does not have to be.

```bash
sudo install -o root -g filemgmt -m 640 /dev/null /etc/file-management.env
sudo nano /etc/file-management.env
```

```ini
# /etc/file-management.env  — chmod 640, owned root:filemgmt
FILEMANAGEMENT_DB_URL=jdbc:mysql://localhost:3306/file_management
FILEMANAGEMENT_DB_USERNAME=file_management
FILEMANAGEMENT_DB_PASSWORD=<a real password>

# Must end with a separator — see the warning above.
FILEMANAGEMENT_BASE_DIR=/opt/file-management/files/
FILEMANAGEMENT_LOG_PATH=/opt/file-management/logs
FILEMANAGEMENT_PORT=8122

# Only on the very first boot, to avoid hunting for the generated password in the log.
FILEMANAGEMENT_BOOTSTRAP_ADMIN_PASSWORD=<a real password>

# Leave false until the folder grants exist.
FILEMANAGEMENT_FOLDER_ACCESS_ENABLED=false

# Active Directory — off unless the site uses it. See "Active Directory behind a load balancer".
FILEMANAGEMENT_AD_ENABLED=false
FILEMANAGEMENT_AD_DOMAIN=
FILEMANAGEMENT_AD_URL=
FILEMANAGEMENT_AD_TRUSTSTORE=
FILEMANAGEMENT_AD_TRUSTSTORE_PASSWORD=
FILEMANAGEMENT_AD_VERIFY_HOSTNAME=true
FILEMANAGEMENT_AD_VERIFY_CERTIFICATE=true
```

`systemd` does **not** expand shell syntax in these files. `A=$B` is the literal string `$B`, and
quotes become part of the value. Write plain literals.

On the Active Directory block specifically, two failures that point somewhere else:

* **`FILEMANAGEMENT_AD_DOMAIN` is the UPN suffix, not the domain controller's hostname.** The bind is
  `username@<domain>`, so it has to be the suffix Active Directory itself accepts. Wrong here, every
  user gets an ordinary *invalid credentials* error and it reads as a password problem.
* **A user must already exist in the `user` table.** AD verifies the password; it does not create
  accounts. `user.login_type` then decides which backend may accept that account — `0` either,
  `1` local only, `2` AD only.

### `ldaps://` — "Connection to LDAP server failed", and what is actually failing

Plain `ldap://…:389` sends every password across the network in the clear; `ldaps://…:636` is
what production should use. But it adds a TLS handshake to the login, and when that handshake
fails the log says only

```
InternalAuthenticationServiceException: Connection to LDAP server failed
  Caused by: CommunicationException: simple bind failed: <host>:636
    Caused by: SocketException: Connection or outbound has closed
```

— every user is refused, and nothing in that message says *why* the TLS layer gave up. The JVM
is the client here, and it is the JVM's rules that decide. Three causes cover nearly every case:

1. **The host in the URL is not the name on the domain controller's certificate.** The JVM
   verifies that the hostname matches the certificate (endpoint identification is on by default
   for LDAPS). A certificate on a DC names that DC — `dc01.site.example` — and a URL that uses the
   **domain** name (`ldaps://site.example:636`, which DNS answers with any DC) fails the check,
   whereas `ldap://` on the same name worked because there was no certificate to check. Use a
   DC's fully-qualified hostname, exactly as it appears in the certificate's Subject Alternative
   Names.
2. **The certificate is not trusted.** Domain controllers usually carry a certificate from the
   organisation's own CA, which the JVM does not know. Import that CA's certificate (not the DC's
   own, which rotates) into a truststore beside the jar and point the JVM at it:

   ```powershell
   keytool -importcert -alias corp-ca -file corp-root-ca.cer `
     -keystore D:\MyApp\file-management\config\truststore.p12 -storetype PKCS12 -storepass changeit
   ```

   then, in `<arguments>` before `-jar`:
   `-Djavax.net.ssl.trustStore=D:\MyApp\file-management\config\truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12`.
   A file beside the jar rather than the JDK's own `cacerts`: the JDK gets upgraded, and its
   `cacerts` goes with it.
3. **The domain controller only offers TLS 1.0 / 1.1**, which the JDK has disabled for years.
   The fix belongs on the DC (enable TLS 1.2); re-enabling old TLS in the JVM is the wrong side.

**Find out which before changing anything.** Start the jar once by hand with handshake
debugging and try one login:

```powershell
& "…\java.exe" -Djavax.net.debug=ssl:handshake -jar file-management.jar
```

The reason appears in plain words in that output — `No subject alternative DNS name matching …`
(cause 1), `PKIX path building failed` / `unable to find valid certification path` (cause 2), or
`protocol_version` / `handshake_failure` (cause 3). Or, without the application, inspect what the
DC presents: `openssl s_client -connect dc01.site.example:636 -showcerts`.

Meanwhile, `FILEMANAGEMENT_AD_URL=ldap://dc01.site.example:389` restores logins exactly as they
worked before — with the password on the wire in the clear, so as a stopgap only.

### Active Directory behind a load balancer, with self-signed certificates

The usual production shape is not one controller with a public certificate. It is a domain name
(`site.example`) that DNS answers with any of several controllers, each with its own self-signed
certificate, and not all of them necessarily listening on 636. Configured naïvely as
`ldaps://site.example:636` that fails three ways at once: the name is not on any certificate, no
certificate is trusted, and a controller without 636 turns some logins into a wait for a TCP
timeout. The provider is built for exactly this shape; the settings map onto it one to one.

**1. List the controllers, not the balancer.** JNDI takes a space- or comma-separated list and
tries each in order until one connects, so the application does its own fail-over:

```
FILEMANAGEMENT_AD_URL=ldaps://dc01.site.example:636 ldaps://dc02.site.example:636 ldaps://dc03.site.example:636
```

A controller that does not listen on 636 refuses the connection in milliseconds and the next is
tried; one that is down but not refusing is bounded by `FILEMANAGEMENT_AD_CONNECT_TIMEOUT_MS`
(default 5 s) instead of the operating system's twenty-odd seconds. Put the controllers most
likely to be up first. Do **not** mix `ldap://` into the list to cover a controller without TLS:
every login that fails over to it sends the password in the clear, and the start-up log warns
about any `ldap://` entry for that reason. Fix that controller instead.

**2. Pin the certificates.** Export each controller's certificate once and put them in one
PKCS12 beside the jar. On a machine that can reach the controllers:

```powershell
# one per controller; -servername matters when a controller carries several names
openssl s_client -connect dc01.site.example:636 -servername dc01.site.example -showcerts </dev/null `
  | openssl x509 -outform PEM > dc01.cer
keytool -importcert -noprompt -alias dc01 -file dc01.cer `
  -keystore D:\MyApp\file-management\config\ad-truststore.p12 -storetype PKCS12 -storepass <password>
```

or, on the controller itself, export the certificate from the local machine store
(`certlm.msc` → Personal → Certificates → the one issued to the controller → Export, without the
private key, DER or Base-64). If the controllers' certificates were issued by an internal CA,
import that CA's certificate instead — one entry, and it survives the controllers' renewals.
Then:

```
FILEMANAGEMENT_AD_TRUSTSTORE=D:\MyApp\file-management\config\ad-truststore.p12
FILEMANAGEMENT_AD_TRUSTSTORE_PASSWORD=<password>
```

The JVM's own `cacerts` is not touched, and nothing goes on the command line. Self-signed
certificates expire and get replaced: when a controller's changes, logins through that
controller start failing over to the others (the log shows the handshake failure) until its new
certificate is imported. Put "re-export after renewing a DC certificate" wherever that renewal
is tracked.

**3. Only if you must use the balancer name:** `FILEMANAGEMENT_AD_URL=ldaps://site.example:636`
works with `FILEMANAGEMENT_AD_VERIFY_HOSTNAME=false` — the hostname check is switched off, and
*only* that check. The certificate is still verified against the truststore, and since the
truststore holds nothing but the controllers' own certificates, that verification is what proves
which server answered. Without a truststore the switch is refused at start-up: encryption to a
server whose identity nothing checks is not worth having. Prefer step 1 anyway; a balancer in
front of LDAP adds a hop and hides which controller misbehaved.

**4. Or verify nothing.** `FILEMANAGEMENT_AD_VERIFY_CERTIFICATE=false` accepts any certificate
and any name — exactly what a Python `ldap3` client does with `ssl.CERT_NONE`, and the reason
such a script "just works" against the same controllers. Logins then succeed against anything
that speaks TLS on 636, including a machine on the path pretending to be a controller, which
would read every password. The connection is encrypted; the other end is unverified. Take it
as a conscious decision on a network you consider closed, not as the fix for a handshake error
whose real cause is one export command away (step 2). The start-up log says so, in capitals.

**What the start-up log says**, so this can be checked without a login attempt:

```
Active Directory TLS trust: D:\MyApp\file-management\config\ad-truststore.p12 (PKCS12)
Active Directory authentication: domain=site.example, servers=ldaps://dc01.site.example:636 ldaps://dc02.site.example:636, connect timeout 5000 ms, read timeout 10000 ms
```

plus a WARN line per `ldap://` server and one if hostname verification is off.

## 3. The unit

```ini
# /etc/systemd/system/file-management.service
[Unit]
Description=File management application
# Wants=, not Requires=: MySQL usually lives on this host, but if it moves to another machine the
# unit must still start and wait rather than refuse.
Wants=mysql.service network-online.target
After=mysql.service network-online.target

[Service]
Type=simple
User=filemgmt
Group=filemgmt
WorkingDirectory=/opt/file-management
EnvironmentFile=/etc/file-management.env

ExecStart=/usr/bin/java -Xms256m -Xmx1g -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Tehran \
          -jar /opt/file-management/app.jar

# Restart on any exit including a clean one — a clean exit here means something stopped the
# application, and this service has no reason to end on its own.
Restart=always
RestartSec=10s
# Do not give up after repeated fast failures: a database down for twenty minutes must not leave
# the application dead once it comes back.
StartLimitIntervalSec=0

ProtectSystem=full
ProtectHome=true
PrivateTmp=true
NoNewPrivileges=true
ReadWritePaths=/opt/file-management

# Logback already writes a rotated file under FILEMANAGEMENT_LOG_PATH. The journal keeps only what
# the JVM prints before Logback starts.
StandardOutput=journal
StandardError=journal
SyslogIdentifier=file-management

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now file-management
sudo systemctl status file-management
sudo journalctl -u file-management -f
```

## 4. Confirm it is actually up

Three probes, and they answer different questions. **Point a load balancer at readiness**, not at
the other two.

```bash
# Readiness: can it serve traffic? Includes the database.
curl -sS http://localhost:8122/actuator/health/readiness    # {"status":"UP"}

# Liveness: is the process alive? A DOWN here is what a restart fixes.
curl -sS http://localhost:8122/actuator/health/liveness     # {"status":"UP"}

# The aggregate, which is what most tooling defaults to.
curl -sS http://localhost:8122/actuator/health              # {"status":"UP"}

# Which build is actually running - the question to ask after every upgrade.
curl -sS http://localhost:8122/actuator/info
```

The three health URLs need no credential, so a probe does not need one either. They answer `UP` or
`DOWN` and nothing else: component names and failure reasons are switched off deliberately, in
`management.properties`, because whoever can reach the port should not be able to read the shape of
the inside of the process. **When something is DOWN the reason is in the log**, not in the response.

Everything else under `/actuator` answers 403, whether or not it has been exposed.

> **Do not use `GET /login` as a health check.** It renders without touching the database, so it
> answers 200 with MySQL down - which is how a load balancer comes to send traffic to an
> application that cannot serve any. It was the best substitute available before the actuator
> existed; it is not one now.

On the first boot, find the generated administrator password if you did not set one:

```bash
grep -n "random password was generated" /opt/file-management/logs/app_log.log
```

It is printed **once**. If it has scrolled out of a rotated file, the account has to be reset in the
database.

## 5. Upgrades

```bash
sudo systemctl stop file-management
sudo cp file-management.jar /opt/file-management/app.jar
sudo chown filemgmt:filemgmt /opt/file-management/app.jar
sudo systemctl start file-management
```

**Take a database backup first.** Flyway applies any new migration on the next boot, and a migration
is not undone by putting the old JAR back — the old code then meets a schema it does not know.
`ddl-auto=validate` turns that into a refusal to start rather than silent damage, which is the
failure you want, but it is still a stopped application.

### Upgrading from 1.0.0 to 1.1.0

This is not a jar swap. 1.1.0 carries the Spring Boot 4 upgrade, eight migrations
(`V1.3` to `V2.4`), fourteen new permissions and folder-level access control. Read this section
to the end before stopping the service; the whole upgrade is one stop-start, but three of the
steps happen *before* it and two *after*.

**Before — on a copy of the production database, or read-only against it:**

1. **Java 21 on the host**, as before — 1.0.0 already required it. `java -version` on the
   target, not on your machine.

2. **Confirm what Flyway thinks is applied.** The plan below assumes `V1.0`–`V1.2`:

   ```sql
   SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
   ```

3. **`V1.3` adds four unique constraints and fails, cleanly, if existing rows violate one.** Find
   them first; each query must return nothing:

   ```sql
   SELECT file_category_id, sub_category_name, COUNT(*) FROM file_sub_category
   GROUP BY file_category_id, sub_category_name HAVING COUNT(*) > 1;

   SELECT file_sub_category_id, tag_name, COUNT(*) FROM main_tag_file
   GROUP BY file_sub_category_id, tag_name HAVING COUNT(*) > 1;

   SELECT file_sub_category_id, file_name, COUNT(*) FROM file_info
   GROUP BY file_sub_category_id, file_name HAVING COUNT(*) > 1;

   SELECT file_info_id, version, file_extension, COUNT(*) FROM file_details
   GROUP BY file_info_id, version, file_extension HAVING COUNT(*) > 1;
   ```

   Names compare case-insensitively (the tables collate `utf8mb4_unicode_ci`), so `HSED` and
   `hsed` under one parent are a duplicate. Resolve any hit in the application *before* the
   upgrade; a failed migration leaves Flyway stopped with the version marked failed, and the
   service will not start until it is repaired.

4. **`V2.4` merges same-named taxonomy rows into one tag.** This is by design (roadmap 7.3) but
   it is a decision about real data, so look at it: the pre-flight query at the top of
   `V2.4__Add_Tags.sql` lists every name that will become a single tag and which rows it comes
   from. Nothing is deleted or renamed — the folders stay distinct — but know what it will do.

5. **Back up the database and the file directory together** ([Backups](#backups--one-job-both-halves)).

**The upgrade:** stop, replace the jar, start. Watch the log for `Successfully applied 8
migrations` and for the `seeded 14 new permission(s)` line from `DataInitializer`. If Flyway
fails, the application does not start and the database is exactly as far as the last successful
migration — restore the backup, fix the cause, try again.

**After — with the service up:**

6. **Verify the backfills.** Each of these must return nothing; they are the same queries the
   migrations document and the test suite runs on every build:

   ```sql
   -- V1.4: every category, sub-category and main tag has exactly one folder
   SELECT 'category' AS kind, c.id FROM file_category c
     LEFT JOIN folder f ON f.source_type = 'CATEGORY' AND f.source_id = c.id WHERE f.id IS NULL
   UNION ALL
   SELECT 'sub_category', sc.id FROM file_sub_category sc
     LEFT JOIN folder f ON f.source_type = 'SUB_CATEGORY' AND f.source_id = sc.id WHERE f.id IS NULL
   UNION ALL
   SELECT 'main_tag', mt.id FROM main_tag_file mt
     LEFT JOIN folder f ON f.source_type = 'MAIN_TAG' AND f.source_id = mt.id WHERE f.id IS NULL;

   -- V2.2: the storage key is the relative path
   SELECT id FROM file_details WHERE storage_key <> relative_path OR storage_key = '';

   -- V2.3: every file is in the folder that mirrors its main tag
   SELECT fi.id FROM file_info fi LEFT JOIN folder f ON f.id = fi.folder_id
   WHERE fi.folder_id IS NULL OR f.source_type <> 'MAIN_TAG' OR f.source_id <> fi.main_tag_file_id;
   ```

   and the `V2.4` verification query from the top of `V2.4__Add_Tags.sql`.

7. **Grant the new permissions.** `DataInitializer` inserts a `permission` row for every new
   constant but grants none of them to any role — `ADMIN` bypasses the check and needs nothing.
   On the roles page, give:

   | To whom | Permissions |
   |---|---|
   | anyone who should browse | `FILE_TREE_PAGE`, `REST_GET_FILE_TREE`, `REST_SEARCH_FILE_TREE`, `FILE_EXPLORER_PAGE`, `REST_GET_FOLDER_CONTENT`, `REST_SEARCH_FOLDER_CONTENT` |
   | whoever issues API keys | `GET_ALL_API_KEY_PAGE`, `CREATE_API_KEY_PAGE`, `SAVE_NEW_API_KEY`, `UPDATE_API_KEY_PAGE`, `SAVE_UPDATED_API_KEY`, `REVOKE_API_KEY` |
   | whoever reads the API documentation | `VIEW_API_DOCS` (`/swagger-ui/index.html`) |

   `API_KEY` is not for people: every API key carries it and no user should.

8. **Folder access stays off until you turn it on.** `FILEMANAGEMENT_FOLDER_ACCESS_ENABLED`
   defaults to `false` and the upgrade changes nothing anyone can reach. When you are ready:
   grant folders to each role on its edit page, check what each role reaches, then set the
   variable to `true` and restart — see
   [the decision above](#one-decision-to-make-deliberately-folder-access). With it on and no
   grants, every non-administrator sees an empty tree.

**What users will notice on day one**, with folder access still off: the file explorer and tree
menu entries (once granted), a preview button on the file page, Jalali dates, and the API keys
screen. `/api/v1/files` is unchanged. `/actuator/health` answers without a credential (for the
load balancer); everything else under `/actuator` is refused.

**Rollback.** Put the 1.0.0 jar back **and** restore the pre-upgrade database backup in the same
operation: the old code's Flyway sees eight migrations it does not know and refuses to start,
and the old schema validation would refuse the new columns anyway. A rollback after users have
uploaded files loses those uploads — which is why the pre-flight above exists.

### Upgrading from 1.1.0 to 1.2.0

A jar swap with three migrations and a handful of behaviour changes worth knowing about. Take the
database backup first, as always. Watch the log for `Successfully applied 3 migrations` and for
the `seeded 5 new permission(s)` line.

1. **`V2.5` rewrites `file_details.content_type`** from the file extension, for the nine
   accepted kinds. Nothing structural; the verification query is at the top of
   `V2.5__Normalise_Content_Type.sql` and must return nothing afterwards. **`V2.6` adds the
   upload policy** - two small tables, and a system-wide row seeded with the nine kinds the form
   always offered, each at 20 MB, so nothing changes until somebody edits it. **`V2.7` adds
   `content_kind`**, empty: the custom file types an administrator may add later.

2. **Uploads are judged by their bytes.** The type a client declares is ignored; the extension
   must be one of `pdf png jpg jpeg docx xlsx pptx mp4 mp3 txt`, and the first bytes must match
   it. An integration that uploads a renamed file, or a `.html` / `.svg` / `.xml`, gets a `400`
   whose `detail` says which rule it broke - it always should have. Downloads are served with
   the extension's type, `nosniff`, and a `Content-Security-Policy`; `?inline=1` is honoured only
   for PDF, images, text, mp4 and mp3.

3. **Active Directory logins answer with a reason.** A disabled account is refused as disabled
   and is no longer re-tested against its local password. An account marked local-only (`login_type
   = 1`) is handed to the local provider. If the directory is unreachable, local accounts -
   the administrator's included - can still sign in, and the outage is in the log at `ERROR`;
   previously an unreachable directory locked everyone out.

4. **`/api/v1/files` uploads that still send `fileCategoryId` / `fileSubCategoryId` /
   `mainTagFileId` get a `Deprecation: true` response header** and a log line. They still work;
   the header and the log are how you find out who has not moved to `folderId` before Phase 7
   step 4 removes the triple - see [the 1.3.0 pre-flight](#upgrading-from-120-to-130--phase-7-step-4). The
   delete and the download gained id-only routes (`/api/v1/files/file-details/{id}`, same
   permissions), and **the v1 delete now checks folder access** on the file's folder, as the
   download always did: with folder access on, the `api` account can only delete inside the
   folders it holds a `WRITE` grant on. **An API key is now scoped to its grants whether or not
   folder access is on** - before, with the flag off, a key reached every folder. If you have
   keys in use with the flag off, check their grants before upgrading; a key without grants
   will reach nothing.

5. **Every storage path is checked to lie inside `base-dir`** before it is touched. No change for
   data the application wrote; a row whose path somehow climbed out would now be refused rather
   than acted on.

6. **Set `server.forward-headers-strategy=native`** if your external `application.properties`
   overrides the shipped one - it is in the shipped file - and check that the proxy sends
   `X-Forwarded-Proto` ([section 9](#9-windows-firewall)).

7. **Grant the new permissions** to whoever manages settings (`ADMIN` needs nothing):
   `UPLOAD_POLICY_PAGE` and `SAVE_UPLOAD_POLICY` unlock `/settings/upload` - which kinds of file
   may be uploaded and how large, for the whole system - and the same table on each role's edit
   page for a role's own policy. Until edited, every role is governed by the system-wide policy,
   which starts as the old behaviour. The catalogue also grew: `gif csv doc xls ppt zip rar 7z`
   can now be *allowed*, but are not until you tick them. `CONTENT_KIND_PAGE`,
   `SAVE_CONTENT_KIND` and `DELETE_CONTENT_KIND` unlock `/settings/content-kinds`, where a file
   type the catalogue lacks can be added from a sample - the page says what the sample is
   (extension, media type from its bytes, the first bytes as hex) and prefills the definition.
   A type added there still has to be ticked in the upload policy before anyone may upload it.

**Rollback:** the 1.1.0 jar starts against the 1.2.0 database, since `V2.5` changed data and not
structure - but the content types it rewrote stay rewritten, which is harmless.

### Upgrading from 1.4.0 to 1.5.0 — sharded storage, explorer downloads and deletes, personal folders

A jar swap with one migration (`V2.11`). **Take the database backup first**, as always: this
release is not rolled back by putting the old jar back (below). Watch the log for
`Successfully applied 1 migration` and, on the same start,
`seeded 1 new permission(s): [REST_DELETE_FOLDER_TREE]` - the two other new permissions come
from the migration itself. None of the three is held by anyone until an administrator grants
it.

**Before:** one look, which changes nothing if it comes back empty:

```sql
SELECT f.id, f.name, f.kind, (SELECT COUNT(*) FROM file_info fi WHERE fi.folder_id = f.id) AS files
FROM folder f WHERE f.depth = 1 AND LOWER(f.name) = 'profiles';
```

`V2.11` creates a top-level folder named `Profiles` for the personal folders. If one already
exists - made by hand - the migration **adopts** it instead: its kind becomes `PROFILES`, the
folders it holds stay, and from then on nothing can be created, renamed, moved or deleted in
it by hand. It **refuses to run** while that folder holds files directly (`files` above
greater than zero), because a `PROFILES` folder lists no files and they would vanish from the
explorer with no way to move them: move the files out or rename the folder first (from the
explorer, on 1.4.0), then start 1.5.0 again. If adoption is not what you want at all, rename
the folder first.

**What changes:**

1. **Storage is sharded.** A file uploaded from 1.5.0 on is stored under
   `{base-dir}/files/{shard}/{file id}/…`, the shard being `s` and the id divided by a thousand
   (`files/s000/123/`, `files/s001/1234/`, `files/s999/999999/`, `files/s1000/1000000/`), instead of
   flat under `files/{file id}/`. The application never lists that directory, so this is not for
   it: it is so that Explorer, `dir`, the backup job and a virus scanner never meet a directory
   with a million children. Files stored by 1.4.0 flat under `files/{id}/`, and files stored
   before 1.4.0 under folder names, stay exactly where they are and keep working — a version's
   `storage_key` is what is read, never a shape. A `base-dir` that saw all three releases holds
   three layouts side by side; reading one by hand, `files/s000/…` is 1.5.0, `files/123/…` is
   1.4.0, anything else is older. Also from 1.5.0, deleting a file stored under `files/` removes
   its whole id directory, not only the `{name}/` directory inside it.

2. **The explorer downloads.** Every file row, every search hit and the details pane offer the
   file's latest revision - the format of the latest version uploaded last - through the same
   download the file page has, under `DOWNLOAD_FILE`. Nothing to configure.

3. **A folder can be deleted with everything in it.** A new permission,
   `REST_DELETE_FOLDER_TREE`, separate from `REST_DELETE_FOLDER` (empty folders only) on
   purpose: grant it to the roles that should be able to erase a subtree, and to no other. The
   explorer shows "delete with contents" on a full folder to a holder, asks with the counts,
   and removes rows first and bytes last - and a directory that cannot be removed (locked, or
   already missing) never undoes the delete: it is logged with its address, named in the audit
   row, and left as an orphan to remove by hand. One call is bounded by
   `filemanagement.folders.max-delete-files` (default `1000`; `FILEMANAGEMENT_FOLDERS_MAX_DELETE_FILES`):
   a larger tree is refused with the count and is deleted in parts. Raise it only knowing that
   one request then holds one transaction and one pass over the disk for that many files.

4. **Personal folders and quotas.** The new-user form has a box, ticked by default, that
   creates `Profiles/{username}` with the user - a folder the user has `WRITE` on directly,
   whatever their roles and whether or not folder access is switched on; the user's page has
   a button for a user made without one, and a field for the folder's quota (megabytes, blank
   for none), which can be raised or lowered at any time. Nothing is created on a sign-in:
   whether a person gets a folder is decided on one of those two screens. A user who has one
   lands in it after signing in (if they may open the explorer). Grant `CREATE_USER_HOME` and
   `SET_FOLDER_QUOTA` to the roles that manage users. `filemanagement.profiles.default-quota-mb`
   (`FILEMANAGEMENT_PROFILES_DEFAULT_QUOTA_MB`, default `0` = none) is the quota a new folder
   starts with; a quota caps every revision of every file anywhere beneath the folder, on
   every upload and every move in, and an upload over it is refused with the numbers - in
   the forms in Persian, on the APIs as a 409. Homes are never renamed by hand (they follow
   the username), moved or deleted; a disabled user's home stays, to be emptied with "delete
   with contents" if wanted.

**Rollback** is the pre-upgrade backup plus the 1.4.0 jar, not the jar alone: `V2.11` adds a
column 1.4.0 does not know (harmless) and a folder of a kind it does not know (`PROFILES`),
which 1.4.0 cannot read - the explorer's root listing would fail on it. Files uploaded on
1.5.0 under the sharded layout stay readable on 1.4.0 after the restore, as far as the restored
rows know them. The seeded permission rows are harmless.

### Upgrading from 1.3.0 to 1.4.0 — folders of any depth

A jar swap with two migrations (`V2.9`, `V2.10`). Take the database backup first, as always.
Watch the log for `Successfully applied 2 migrations`; the new permissions are inserted by the
migrations themselves, so no `seeded` line follows.

**Before:** a JDK 25 on the host, and one check, which the migration also makes and refuses
to run on. 1.4.0 is compiled for Java 25 (`-release 25`); on a JDK 21 the jar fails at start
with `UnsupportedClassVersionError ... class file version 69.0`. Install Temurin 25 (LTS) beside
the 21, point the service's `<executable>` and `JAVA_HOME` at it (the WinSW section below), and
only then swap the jar. The check:

```sql
SELECT id, name FROM folder WHERE depth = 1 AND LOWER(name) = 'files';
SELECT COUNT(*) FROM file_details WHERE storage_key LIKE 'files/%';
```

Both must be empty. `files/` is where every file uploaded from 1.4.0 on is stored — by the
file's own id, not by any name — and it shares `base-dir` with the old layout. A top-level
folder of that name has to be renamed first (from the explorer, on 1.3.0).

**What changes:**

1. **The tree has no fixed levels any more.** Every folder below `Home` takes folders and files
   alike, down to `filemanagement.folders.max-depth` (default 6; set it in
   `application.properties` or `FILEMANAGEMENT_FOLDERS_MAX_DEPTH`). The explorer offers "new
   folder" wherever the limit allows, "upload here" on any folder but `Home`, a new "انتقال"
   (move) for the folder on screen and for a selected file, each picking its target with a
   folder chooser, and a details pane and search for folders. Existing folders keep their
   places; only their `kind` becomes `FOLDER`. **Names are no longer restricted to
   "no dot, no space"**: a folder or file name may hold spaces, dots and Persian; what is still
   refused is what a file system refuses (`/ \ < > : " | ? *`, a trailing dot or space, `CON`
   and its kin), and a file still needs an extension of letters and digits.

2. **New files are stored under `{base-dir}/files/{file id}/…`** (1.5.0 adds a shard
   directory between the two). Files stored before stay where they are and keep working — a
   version's `storage_key` is what is read, never a name. A backup of `base-dir` now has two
   layouts side by side; that is expected. Renaming or
   moving a folder, or moving a file, changes **nothing** on disk and no stored key, only
   `folder` / `file_info` rows and the derived tags; the exact effect of every operation on the tree, the keys and the
   bytes is tabulated in [arch.md](arch.md#what-each-operation-touches). The consequence for a
   backup is unchanged and worth repeating: the directory tree is not a mirror of the folder
   tree, so `base-dir` without the database is a heap of files nobody can place.

3. **A file name is unique per folder.** The per-sub-category rule that 1.3.0 kept because of the
   old disk layout is gone: the same name under a sibling folder is another file.

4. **Grant `REST_MOVE_FOLDER`** to whoever should move folders — the migration gives it to every
   role that holds `REST_RENAME_FOLDER`. The tag-group settings page (`/settings/tag-groups`,
   the general tags' form) is behind `TAG_GROUP_PAGE`, `SAVE_TAG_GROUP` and `DELETE_TAG_GROUP`;
   `ADMIN` needs nothing.

5. **The public files can be closed to visitors.** `V2.10` adds `app_setting`, seeded open, so
   nothing changes until an administrator unticks "نمایش و دانلود فایل‌های عمومی بدون ورود" on
   `/settings/general` (`GENERAL_SETTINGS_PAGE` / `SAVE_GENERAL_SETTINGS`; `ADMIN` needs
   nothing). Off, a visitor is sent to the login form and the login form loses its public-files
   link; anyone signed in still sees the page. Takes effect on the next request.

6. **The upload form asks for the target with a folder chooser** instead of three selects; the
   file pages show the folder path instead of category / sub-category / tag. Nothing changes
   for `/api/v1/files` or `/api/v2`: `folderId` may now be any folder below the root, and a v2
   key may have any number of folder segments (including none - a file directly in the bucket).

**Rollback:** restore the database backup and start the 1.3.0 jar; files uploaded on 1.4.0 are
under `base-dir/files/` but not in the restored database. `V2.9` changes one column's values
and adds permissions, so on a database that has run it the 1.3.0 jar would fail on the unknown
`FOLDER` kind — the backup is the way back.

### Upgrading from 1.2.0 to 1.3.0 — Phase 7 step 4

**This is the one upgrade in this project that a restored backup is the only way back from.**
`V2.8` drops the four taxonomy tables (`general_tag`, `file_category`, `file_sub_category`,
`main_tag_file`), the columns that pointed at them, and the `file_path` / `relative_path`
columns. The 1.2.0 jar does not start against the 1.3.0 database. Every earlier migration added
alongside the old structure; this one removes it.

The migration is ordered so that everything that can fail on the data fails **before the first
`DROP`**, and a database that is not ready is left exactly as it was. Still: check first, back up,
then upgrade.

**Before (pre-flight):**

1. **Folder access is on, and has been, with the grants you mean.** After step 4 the folder
   grants are the only structure; there is no taxonomy to fall back on. If
   `filemanagement.folder-access.enabled` is still `false`, turn it on and run 1.2.0 with it for
   long enough to know the grants are right. This is the step that cannot be checked by a query.

2. **Every file has a folder.** Must return zero (the `V2.3` backfill; its `UPDATE` can be re-run
   by hand if not):

   ```sql
   SELECT COUNT(*) FROM file_info WHERE folder_id IS NULL;
   ```

3. **Every category has a tag group.** Both must return nothing. The first names a category
   whose general tag has no `tag_group` row of the same name (re-run the `V2.4` backfill); the
   second names a category folder with no general tag at all, which only a row edited by hand
   can produce:

   ```sql
   SELECT f.id, f.name FROM folder f JOIN general_tag gt ON gt.id = f.general_tag_id
     LEFT JOIN tag_group g ON g.name = gt.tag_name WHERE g.id IS NULL;

   SELECT f.id, f.name FROM folder f WHERE f.kind = 'CATEGORY' AND f.general_tag_id IS NULL;
   ```

4. **No two files share a name inside one folder.** On data the application wrote this is empty
   by construction (a folder is narrower than a sub-category, whose rule stays); the query is
   here for anything moved behind the services:

   ```sql
   SELECT folder_id, file_name, COUNT(*) FROM file_info
   GROUP BY folder_id, file_name HAVING COUNT(*) > 1;
   ```

5. **No integration still uploads by the triple.** Every 1.2.0 upload that sent
   `fileCategoryId` / `fileSubCategoryId` / `mainTagFileId` without `folderId` logged one line
   with a fixed marker; over a period that covers every integration's schedule this must find
   nothing:

   ```powershell
   Select-String -Path "D:\MyApp\file-management\logs\*.log" -Pattern "v1-upload-by-triple"
   ```

   ```bash
   grep -r "v1-upload-by-triple" /var/log/file-management/
   ```

   After the upgrade a v1 upload without `folderId` is a `400` whose `detail` names the
   parameter; the triple is ignored if sent. The folder that stood for a tag is the `folder_id`
   the integration already looked up while 1.2.0 was running - do that lookup **before** this
   upgrade, because `source_id` goes with `V2.8`:

   ```sql
   SELECT id AS folder_id, source_id AS main_tag_file_id FROM folder WHERE source_type = 'MAIN_TAG';
   ```

   The response (`fileId`, `fileDetailsId`), the delete and download routes, and the credential
   (Basic or Bearer) are unchanged.

6. **Back up both halves** - the database and `base-dir` - and keep the backup until the new
   version has run for as long as you need to trust it.

**The upgrade:** stop, replace the jar, start. Watch the log for `Successfully applied 1
migration`; no `seeded ... permission(s)` line follows, because `V2.8` inserts the four new
permissions itself so that it can map them onto roles. If `V2.8` fails, the log names the statement: it is one of the four checks above, the
database is untouched, and the 1.2.0 jar starts again as before.

**After:**

1. **The taxonomy pages are gone** (`/file-categories`, `/file-sub-categories`, `/main-tags`,
   `/general-tags`, and the taxonomy section of the sidebar). The tree is managed from the
   explorer: *new folder*, *rename* and *delete folder* appear on a folder the person holds
   `WRITE` on. A category (a folder under `Home`) is created with a tag group - the label group
   that used to be called a general tag - chosen from the existing ones or named anew; nothing
   below a category carries one. The tree is three levels deep and stays so: a tag folder holds
   files, not folders. Renaming moves no byte; deleting takes an empty folder only.

2. **Permissions were re-mapped by the migration.** Roles that held the taxonomy's create
   permissions now hold `REST_CREATE_FOLDER` (and `REST_GET_TAG_GROUPS`); the update ones,
   `REST_RENAME_FOLDER`; the delete ones, `REST_DELETE_FOLDER`. The 27 taxonomy constants and
   every role's rows for them are deleted - a permission row whose name is not in the enum would
   break the login of anyone holding it. Check the roles that manage the tree hold the three,
   and `FILE_EXPLORER_PAGE`.

3. **A file name is unique per sub-category, as before** - not per folder. The bytes of every
   file under a sub-category live at `{category}/{subCategory}/{name}/`, with no tag segment, so
   a second file of the same name under a sibling tag is refused (`409`), on every upload route.

4. **The `action_history` rows that name category, sub-category and main-tag ids stay as they
   are.** They are a log of what happened; the ids in them no longer resolve to anything, and
   that is accepted rather than rewritten (roadmap 7.4).

5. **A category created from the explorer has no directory until its first upload** -
   `saveByKey` creates the parents. Nothing to do; noted because the taxonomy pages used to
   create the directory on the spot.

**Rollback:** restore the database backup and start the 1.2.0 jar. Files uploaded after the
upgrade are on disk under `base-dir` but not in the restored database; keep the 1.3.0 database
backup too if you need to recover them.

---

# Windows — WinSW

Windows has no supervisor for a plain `java -jar`, and `sc.exe create` pointed at `java.exe` does
not work: a Windows service must talk the Service Control Manager protocol and the JVM does not, so
the service registers and is then killed after 30 seconds for never reporting that it started. Task
Scheduler starts the process but nothing restarts it when it dies.

**WinSW** wraps the process and registers it properly. Use **v3 in bundled mode**: the WinSW
executable is renamed for the service, and an XML file with the same base name sits beside it.
Download `WinSW-x64.exe` from a v3 release on GitHub (`winsw/winsw`); v3 is self-contained and
needs no .NET Framework on the host, which v2 did. Rename it to `FileManagement.exe`.

## 1. Layout

```text
D:\MyApp\file-management\
│
├── FileManagement.exe          ← the renamed WinSW executable, NOT the application
├── FileManagement.xml          ← the service definition; base name must match the .exe
├── file-management.jar         ← the Spring Boot fat jar - the whole application
├── config\                     ← optional: application.properties with the settings that differ
│   └── application.properties     from the shipped defaults (see "Configuring it from outside the jar")
│
├── logs\                       ← WinSW's own capture: .out.log / .err.log / .wrapper.log
├── ProdLog\                    ← the application's own app_log.log and archived\
└── files\                      ← FILEMANAGEMENT_BASE_DIR — every uploaded file
```

> **`logs\` and `ProdLog\` are different things and both matter.** WinSW captures what the JVM
> prints on stdout/stderr, which once Logback starts is almost nothing. The application's own log is
> `ProdLog\app_log.log`. When something fails at startup the answer is in
> `logs\FileManagement.wrapper.log`; when something fails at runtime it is in `ProdLog\app_log.log`.
> Looking in the wrong one is an hour lost.

`ProdLog\` and `files\` are created relative to `<workingdirectory>`, which is why that element is
not optional.

## 2. The service definition

`FileManagement.xml`, beside `FileManagement.exe`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<service>
    <id>FileManagement</id>
    <name>File Management</name>
    <description>File management - Spring Boot application</description>

    <!-- An absolute path on purpose: a service must not depend on an interactive user's PATH.
         When the JDK is upgraded, this and JAVA_HOME below both have to change. -->
    <executable>C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe</executable>
    <arguments>-Xms256m -Xmx1g -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Tehran -jar "D:\MyApp\file-management\file-management.jar"</arguments>
    <workingdirectory>D:\MyApp\file-management</workingdirectory>
    <env name="JAVA_HOME" value="C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"/>

    <env name="FILEMANAGEMENT_DB_URL" value="jdbc:mysql://localhost:3306/file_management"/>
    <env name="FILEMANAGEMENT_DB_USERNAME" value="file_management"/>
    <env name="FILEMANAGEMENT_DB_PASSWORD" value="a real password"/>
    <!-- Must end with a separator. -->
    <env name="FILEMANAGEMENT_BASE_DIR" value="D:\MyApp\file-management\files\"/>
    <env name="FILEMANAGEMENT_LOG_PATH" value="D:\MyApp\file-management\ProdLog"/>
    <env name="FILEMANAGEMENT_PORT" value="8122"/>
    <env name="FILEMANAGEMENT_FOLDER_ACCESS_ENABLED" value="false"/>

    <startmode>Automatic</startmode>

    <logpath>D:\MyApp\file-management\logs</logpath>
    <log mode="roll-by-size">
        <!-- WinSW reads sizeThreshold in KB, so this is ~10 MB per file. -->
        <sizeThreshold>10240</sizeThreshold>
        <keepFiles>10</keepFiles>
    </log>

    <!-- Escalating backoff. A database down for twenty minutes must not become a restart storm,
         and must not leave the application dead once it comes back. -->
    <onfailure action="restart" delay="10 sec"/>
    <onfailure action="restart" delay="30 sec"/>
    <onfailure action="restart" delay="60 sec"/>
    <resetfailure>1 hour</resetfailure>

    <stoptimeout>30 sec</stoptimeout>
</service>
```

### Why each JVM argument is there

| Argument | Reason |
|---|---|
| `-Xms256m -Xmx1g` | This application holds no large caches; the heap is dominated by the multipart buffer, capped at 20 MB per request |
| `-XX:+ExitOnOutOfMemoryError` | A JVM that has hit `OutOfMemoryError` is not reliably usable. Exiting turns an invisible sick process into a visible failure the service recovery can act on |
| `-Dfile.encoding=UTF-8` | Every user-visible string here is Persian, and so are most document titles in the database |
| `-Duser.timezone=Asia/Tehran` | `created_at` / `updated_at` are written by Hibernate against the JVM's zone |

The `prod` profile is **not** passed on the command line: it is already the default in
`application.properties`. Passing `--spring.profiles.active=prod` does no harm, but leaving it out is
not a mistake. Application arguments go **after** `-jar "…jar"` inside `<arguments>`; JVM flags go
before it:

```xml
<arguments>-Xms256m -Xmx1g … -jar "D:\MyApp\file-management\file-management.jar" --spring.profiles.active=prod,local</arguments>
```

### Where the secrets go

Three places work; pick one per setting.

1. **`<env>` entries in `FileManagement.xml`**, as above. Simplest, and what the file shows.
2. **`D:\MyApp\file-management\config\application.properties`** — read automatically because the
   service's working directory is `D:\MyApp\file-management`. Holds any property, in the same
   names as the packaged file (`spring.datasource.password=…`, `file.management.base-dir=…`). No
   flag is needed. Details in [Configuring it from outside the jar](#configuring-it-from-outside-the-jar).
3. **`application-local.properties` beside the jar**, activated with
   `--spring.profiles.active=prod,local` in `<arguments>`. The repository ships
   `application-local.properties.example`; the real file is gitignored.

Whichever file holds the database password, restrict it to the service account and administrators:

```powershell
icacls "D:\MyApp\file-management\FileManagement.xml" /inheritance:r `
  /grant "Administrators:(R,W)" "SYSTEM:(R)"
# and the same for config\application.properties if the password lives there
```

If the service runs as a dedicated account rather than `LocalSystem` (add `<serviceaccount>` to
the XML), grant that account read on the files instead of `SYSTEM`, plus modify on `files\`,
`ProdLog\` and `logs\`.

## 3. Install and start

Run PowerShell **as Administrator**.

```powershell
cd "D:\MyApp\file-management"
.\FileManagement.exe install
.\FileManagement.exe start
.\FileManagement.exe status
```

## 4. Commands

| Action | Command |
|---|---|
| Install | `.\FileManagement.exe install` |
| Start | `.\FileManagement.exe start` |
| Stop | `.\FileManagement.exe stop` |
| Restart | `.\FileManagement.exe restart` |
| Status | `.\FileManagement.exe status` |
| Re-read the XML without reinstalling | `.\FileManagement.exe refresh` |
| Uninstall | `.\FileManagement.exe uninstall` |
| Force-terminate a wedged wrapper | `.\FileManagement.exe dev kill` |

`refresh` updates the registered service properties from the XML without an uninstall/install cycle
— but a change that affects the **running child process** (arguments, JVM flags, environment, the
JAR path) still needs a restart to take effect. A change to `config\application.properties` needs
only `restart`; the XML did not change, so no `refresh`.

`dev kill` is a troubleshooting fallback, never the normal stop. Stop first, uninstall second:
removing the executable or XML while the service is still registered leaves an entry Windows cannot
clean up.

## 5. Confirm it is actually up

The same three probes as on Linux — readiness is the one that includes the database, and the one
a load balancer should watch:

```powershell
(Invoke-WebRequest http://localhost:8122/actuator/health/readiness -UseBasicParsing).Content   # {"status":"UP"}
(Invoke-WebRequest http://localhost:8122/actuator/health/liveness  -UseBasicParsing).Content   # {"status":"UP"}
(Invoke-WebRequest http://localhost:8122/actuator/info             -UseBasicParsing).Content   # which build is running
```

No credential is needed for the three health URLs. Do not use `GET /login` as a check: it renders
without touching the database and answers 200 with MySQL down.

First boot, if no admin password was set:

```powershell
Select-String -Path "D:\MyApp\file-management\ProdLog\app_log.log" `
  -Pattern "random password was generated"
```

## 6. Deploying a new JAR

```powershell
cd "D:\MyApp\file-management"

.\FileManagement.exe stop

# Back up the JAR you are replacing — this is what a rollback needs.
Copy-Item ".\file-management.jar" ".\file-management.jar.bak" -Force

# Copy the new build in as file-management.jar, then:
.\FileManagement.exe start
Get-Content ".\ProdLog\app_log.log" -Tail 100 -Wait
```

> **Take a database backup before this, not after** — same reason as the Linux section.

## 7. Rollback

```powershell
.\FileManagement.exe stop

# Keep the failed build rather than deleting it; you will want it to diagnose.
Move-Item ".\file-management.jar" ".\file-management.jar.failed" -Force
Move-Item ".\file-management.jar.bak" ".\file-management.jar" -Force

.\FileManagement.exe start
```

A rollback is only safe if the migrations have not moved. If they have, restore the database backup
taken before the upgrade, in the same operation.

## 8. When it will not start

Work through the logs in this order — each answers a different question.

```powershell
cd "D:\MyApp\file-management"

# 1. Did WinSW manage to launch anything at all?
Get-Content ".\logs\FileManagement.wrapper.log" -Tail 200

# 2. What did the JVM print before Logback took over?
Get-Content ".\logs\FileManagement.err.log" -Tail 200

# 3. What did the application itself say?
Get-Content ".\ProdLog\app_log.log" -Tail 200
```

Then reproduce it outside the service, which separates "WinSW is misconfigured" from "the
application cannot start":

```powershell
& "C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot\bin\java.exe" `
  -Dfile.encoding=UTF-8 -jar "D:\MyApp\file-management\file-management.jar"
```

## 9. Windows firewall

Only if browsers reach 8122 directly. With a reverse proxy in front, **do not open 8122 at all** —
the proxy connects over loopback.

The proxy is also where TLS ends. The application speaks plain HTTP on 8122 and is meant to be
reached only through a proxy (IIS with ARR, nginx, or the load balancer) that holds the
certificate, listens on 443 and forwards to `127.0.0.1:8122` with the standard headers -
`X-Forwarded-Proto`, `X-Forwarded-For`, `X-Forwarded-Host`. The application honours those
headers from loopback and private addresses (`server.forward-headers-strategy=native`), and once
it knows a request came in over https it marks the session cookie `Secure`, redirects to `https://`
after login, and sends `Strict-Transport-Security` on its own. API credentials - Basic and Bearer
alike - cross the wire in the clear on any hop that is not TLS, which is the whole reason for the
rule above about not opening 8122.

```powershell
New-NetFirewallRule -DisplayName "File Management 8122" -Direction Inbound -LocalPort 8122 `
  -Protocol TCP -Action Allow -Profile Domain,Private
```

---

## MySQL as a service

Both installers register the database themselves; there is nothing to write. What matters is that it
starts **before** the application, which the unit's `After=mysql.service` handles on Linux and
Windows handles through automatic-start ordering plus the service's own restart-on-failure.

```bash
sudo systemctl enable --now mysql
```

```powershell
Set-Service MySQL80 -StartupType Automatic
Start-Service MySQL80
```

Create the database and a dedicated user — the application must not connect as `root`:

```sql
CREATE DATABASE file_management
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER 'file_management'@'localhost' IDENTIFIED BY 'a real password';
GRANT ALL PRIVILEGES ON file_management.* TO 'file_management'@'localhost';
FLUSH PRIVILEGES;
```

Why each line is what it is — the collation, the scope of the grant, when `@'localhost'` is wrong —
is in [schema.md](schema.md#creating-the-database-and-its-user). Flyway creates the schema on
first boot. Nothing else is run by hand.

> **Set `lower-case-table-names=0`.** MySQL folds identifiers on Windows but not on Linux, so a
> query that misspells a table works on one and fails on the other — which is exactly how
> [issue 47](issues.md) hid until the suite ran on Linux. `compose.yaml` and the test containers
> both set it; a hand-installed server should match. It can only be set when the data directory is
> initialised, not afterwards.

---

## Backups — one job, both halves

The data lives in **two places and neither is complete without the other**: the rows in MySQL, and
the uploaded files under `FILEMANAGEMENT_BASE_DIR`, laid out as
`{Category}/{SubCategory}/{FileName}/v{n}/{file}.{ext}`. `file_details` holds the *path*, never the
content. A backup of the database alone restores a system in which every download is a broken
reference; a backup of the files alone is a pile of anonymous bytes.

**Both halves, one run, restored as the pair they were taken as.**

### Order: database first, then files

The service stays up during a backup, so someone may upload a file between the two steps. The order
decides how that upload ends up incomplete — and only one answer is survivable:

| Order | The file uploaded mid-backup | Result |
|---|---|---|
| **Database, then files** | Its row is not in the dump; its bytes are in the archive | **Orphan file — harmless** |
| Files, then database | Its row is in the dump; its bytes are not | **Broken reference — data lost** |

Note that this application has **no orphan sweep**: an orphan file stays on disk until somebody
removes it. That is a tidiness problem, where the other order is a data-loss problem.

### The dump, and one command that silently corrupts it

> **Never pipe a dump through PowerShell.**
> ```powershell
> mysqldump ... | Out-File -Encoding utf8   # WRONG — corrupts the dump
> ```
> A PowerShell pipe carries **text**, not bytes: the output is re-encoded, a BOM is prepended and
> line endings become CRLF. On a database full of Persian text the result is a plausible-looking
> file that fails on restore, and you find out on the day you need it. Use
> `--result-file=<path>` and let `mysqldump` write the file itself.

Always pass `--default-character-set=utf8mb4`. Without it the client may negotiate `latin1` and the
Persian text in the dump is mangled without any error.

`--single-transaction` takes a consistent snapshot without locking the application out, so no
downtime is needed. It works because every table is InnoDB.

### Linux

```bash
# /usr/local/bin/file-management-backup.sh
#!/usr/bin/env bash
# -e stops on error, -u on an unset variable, pipefail on a failure mid-pipe. Without these a
# failed step is skipped in silence and the job still reports success.
set -euo pipefail

APPFILES=/opt/file-management/files
DEST=/backup/file-management
KEEP_DAYS=30
DB_NAME=file_management

stamp=$(date +%F_%H%M%S)
RUN="$DEST/$stamp"
PREV=$(find "$DEST" -mindepth 1 -maxdepth 1 -type d -name '20*' | sort | tail -n 1)

mkdir -p "$RUN"
exec > >(tee -a "$RUN/backup.log") 2>&1
echo "run $stamp — previous: ${PREV:-none}"

# 1) Database first. Credentials come from ~/.my.cnf (chmod 600), never the command line —
#    anything passed as an argument is visible in the host process list.
mysqldump --defaults-file=/opt/file-management/.my.cnf \
    --single-transaction --quick --default-character-set=utf8mb4 \
    --databases "$DB_NAME" --result-file="$RUN/db.sql"
gzip "$RUN/db.sql"

# 2) Files second. --link-dest makes an unchanged file a hardlink to the previous run's copy
#    instead of a second copy of the bytes: the folder is still a complete tree, at the cost of
#    only the day's difference. $RUN and $PREV must be on the same filesystem.
if [ -n "$PREV" ] && [ -d "$PREV/files" ]; then
    rsync -a --delete --link-dest="$PREV/files" "$APPFILES/" "$RUN/files/"
else
    rsync -a --delete "$APPFILES/" "$RUN/files/"
fi

# 3) Prove the dump is readable now, not on the day it is needed.
gzip -t "$RUN/db.sql.gz"

# 4) Rotation — whole run folders.
find "$DEST" -mindepth 1 -maxdepth 1 -type d -name '20*' -mtime +"$KEEP_DAYS" -exec rm -rf {} +

echo "BACKUP OK $stamp — $RUN"
```

```ini
# /opt/file-management/.my.cnf  — chmod 600, owned by the user the timer runs as
[mysqldump]
user=file_management_backup
password=a real password
host=127.0.0.1
```

Give the job its own read-only account:

```sql
CREATE USER 'file_management_backup'@'localhost' IDENTIFIED BY '…';
GRANT SELECT, LOCK TABLES, SHOW VIEW, EVENT, TRIGGER
  ON file_management.* TO 'file_management_backup'@'localhost';
```

**A systemd timer, not cron.** The timer records the last run's status, sends output to the journal,
`Persistent=true` makes up a run the machine was off for, and `Type=oneshot` prevents two copies
overlapping.

```ini
# /etc/systemd/system/file-management-backup.timer
[Unit]
Description=Daily file-management backup at 01:00

[Timer]
OnCalendar=*-*-* 01:00:00
Persistent=true
RandomizedDelaySec=5m

[Install]
WantedBy=timers.target
```

### Windows

Everything the script needs is in the block at the top: the same database URL and files
directory the service runs with (copy them from the WinSW `<env>` block), a backup account and
its password, and where the runs go. Nothing else has to exist beforehand - no defaults file,
no `pgpass`-style lookup.

```powershell
# D:\MyApp\scripts\backup-file-management.ps1
$ErrorActionPreference = 'Stop'

# ---------------- settings ----------------------------------------------------------------
$MySqlBin   = 'C:\Program Files\MySQL\MySQL Server 8.0\bin'
$DbUrl      = 'jdbc:mysql://localhost:3306/file_management'   # the service's FILEMANAGEMENT_DB_URL, verbatim
$DbUser     = 'file_management_backup'                        # a read-only account - see below
$DbPassword = 'a real password'
$AppFiles   = 'D:\MyApp\file-management\files'                # the service's FILEMANAGEMENT_BASE_DIR
$Dest       = 'D:\Backup\file-management'                     # where every run goes, one folder each
$KeepDays   = 7
# ------------------------------------------------------------------------------------------

# Host, port and database come out of the JDBC URL, so the value is copied from the service
# definition rather than typed a second time and allowed to drift from it.
if ($DbUrl -notmatch '^jdbc:mysql://([^:/]+)(?::(\d+))?/([^?]+)') { throw "cannot parse DbUrl: $DbUrl" }
$DbHost = $Matches[1]
$DbPort = if ($Matches[2]) { $Matches[2] } else { '3306' }
$DbName = $Matches[3]

# Date and time to the second. Everything this run produces goes in here and nowhere else,
# so a run can neither disturb nor be confused with any other.
$stamp = Get-Date -Format 'yyyy-MM-dd_HHmmss'
$Run   = "$Dest\$stamp"
New-Item -ItemType Directory -Force -Path $Run | Out-Null
Start-Transcript -Path "$Run\backup.log" | Out-Null

try {
    # 1) Database first (see "Order" above). --result-file, never a pipe: a PowerShell pipe
    #    re-encodes the dump. The password reaches mysqldump through MYSQL_PWD, an environment
    #    variable only the child process sees - never as --password=, which every user on the
    #    host can read from the process list for as long as the dump runs.
    $dump = "$Run\db.sql"
    $env:MYSQL_PWD = $DbPassword
    try {
        & "$MySqlBin\mysqldump.exe" `
            --host=$DbHost --port=$DbPort --user=$DbUser `
            --single-transaction --quick --default-character-set=utf8mb4 `
            --databases $DbName --result-file=$dump
        if ($LASTEXITCODE -ne 0) { throw "mysqldump failed with $LASTEXITCODE" }
    }
    finally {
        Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
    }

    # 2) Files second, into this run's own folder - a full copy.
    #    /E copies the tree including empty directories. Not /MIR: the destination is new,
    #    so there is nothing to mirror away, and /MIR on a fresh folder only invites a typo
    #    in $Run to delete something else. /MT:8 uses eight threads.
    robocopy $AppFiles "$Run\files" `
        /E /R:2 /W:5 /MT:8 /NP /NDL /NFL /LOG:"$Run\robocopy.log"
    # robocopy reports 0-7 for success and 8+ for a real failure, so it cannot be tested like
    # an ordinary command. Reset the code or the next check inherits it.
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed with $LASTEXITCODE" }
    $global:LASTEXITCODE = 0

    # 3) Prove the dump is complete now, not on the day it is needed. mysqldump writes its
    #    last line only after every table went out; a dump cut short by a lost connection or a
    #    full disk has the right name, a plausible size, and no such line.
    $last = Get-Content -Path $dump -Tail 1
    if ($last -notmatch '^-- Dump completed') { throw "dump is incomplete: $dump (last line: $last)" }
    if ((Get-Item $dump).Length -lt 10KB) { throw "dump is implausibly small: $((Get-Item $dump).Length) bytes" }

    # 4) Rotation - whole run folders.
    #    Age comes from the folder NAME, not its timestamp: a directory's LastWriteTime changes
    #    whenever anything inside it does. A name that does not parse is left alone, so nothing
    #    unexpected in this directory is ever deleted - including the run this script is writing.
    $cutoff = (Get-Date).AddDays(-$KeepDays)
    Get-ChildItem $Dest -Directory | Where-Object {
        $parsed = [datetime]::MinValue
        [datetime]::TryParseExact($_.Name, 'yyyy-MM-dd_HHmmss', $null,
            [Globalization.DateTimeStyles]::None, [ref]$parsed) -and $parsed -lt $cutoff
    } | Remove-Item -Recurse -Force

    Write-Output "BACKUP OK $stamp - $Run"
    Stop-Transcript | Out-Null
    exit 0
}
catch {
    Write-Output "BACKUP FAILED: $_"
    Stop-Transcript | Out-Null
    exit 1      # non-zero, so Task Scheduler records it as failed
}
```

Each run produces one folder:

```text
D:\Backup\file-management\2026-09-16_010000\
├── db.sql           the database, one consistent snapshot, utf8mb4
├── files\           every uploaded file, the tree as it is under FILEMANAGEMENT_BASE_DIR
├── backup.log       everything the script printed
└── robocopy.log     what was copied
```

**The script holds the database password, so restrict it** to the account the task runs as and
administrators; and give the job its own read-only account rather than the application's:

```powershell
icacls "D:\MyApp\scripts\backup-file-management.ps1" /inheritance:r `
  /grant "Administrators:(R,W)" "SYSTEM:(R)"
```

```sql
CREATE USER 'file_management_backup'@'localhost' IDENTIFIED BY '...';
GRANT SELECT, LOCK TABLES, SHOW VIEW, EVENT, TRIGGER
  ON file_management.* TO 'file_management_backup'@'localhost';
```

Run it once by hand before scheduling it and read `backup.log`; the first run is the one that
finds a wrong path or a refused login, and it should find it while somebody is watching.

> **Save this script as ASCII, or as UTF-8 *with* a BOM.** Windows PowerShell 5.1 assumes the system
> ANSI code page for a `.ps1` with no BOM, so a UTF-8 file saved without one is decoded as
> Windows-1252. An em dash then becomes `â€”`, whose last character `”` **PowerShell accepts as a
> string delimiter** — one em dash inside a double-quoted string ends it early and the rest of the
> file misparses, with errors that point at the end of the file rather than the cause. The script
> above is deliberately pure ASCII.

```powershell
$action = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "D:\MyApp\scripts\backup-file-management.ps1"'
$trigger = New-ScheduledTaskTrigger -Daily -At 01:00
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
    -ExecutionTimeLimit (New-TimeSpan -Hours 4)

Register-ScheduledTask -TaskName 'File Management Backup' `
    -Action $action -Trigger $trigger -Settings $settings
```

> **A job that fails every night looks exactly like one that succeeds every night** — until the day
> you need it. Add a second task that checks each morning that today's dump exists and is a
> plausible size.

### Off this machine

> **A backup on the same disk is not a backup.** A failed disk takes the data and the backup
> together, and ransomware encrypts the destination drive first.

| Cycle | Copies | Where |
|---|---|---|
| Daily run folders | 30 on Linux (hardlinks), 7 on Windows (full copies) | Local backup disk |
| Weekly — move one run folder | 12 | NAS or a second server |
| Monthly — move one run folder | 12 | Off-site |

Back up the **environment file** too (`/etc/file-management.env`, or the WinSW `<env>` block),
separately and encrypted. It holds the database password; putting it in the same archive that goes
off-site means anyone who obtains that archive owns the live system.

---

## Restoring

Take the dump and the file archive **from the same run**. If you are restoring because something
broke, copy the current state aside first — a broken system sometimes holds data the backup does not.

1. **Stop the service.** A running application keeps writing, and Flyway may apply a migration
   mid-restore.

2. **Restore the database.** Recreating it is the reliable way — a restore over a live schema leaves
   whatever the dump does not mention.
   ```bash
   mysql -u root -p -e "DROP DATABASE file_management;
                        CREATE DATABASE file_management
                          CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
   gunzip -c /backup/file-management/2026-09-11_010007/db.sql.gz \
     | mysql -u root -p --default-character-set=utf8mb4 file_management
   ```

3. **Restore the files** to whatever `FILEMANAGEMENT_BASE_DIR` points at.
   ```bash
   rsync -a --delete /backup/file-management/2026-09-11_010007/files/ /opt/file-management/files/
   chown -R filemgmt:filemgmt /opt/file-management/files
   ```
   ```powershell
   robocopy "D:\Backup\file-management\2026-09-11_010007\files" `
            "D:\MyApp\file-management\files" /MIR
   ```

4. **Start, and watch it come up.** `ddl-auto=validate` means a successful boot is itself proof that
   every entity matches the restored schema. A `Migration checksum mismatch` here means the dump and
   the JAR are from different versions — restore the matching pair rather than editing
   `flyway_schema_history`.

5. **Verify.** A restore nobody checked is a guess.

### Verifying a restore

```sql
SELECT 'file_info', COUNT(*) FROM file_info
UNION ALL SELECT 'file_details', COUNT(*) FROM file_details
UNION ALL SELECT 'folder',       COUNT(*) FROM folder
UNION ALL SELECT 'user',         COUNT(*) FROM user;

SELECT version, description, success
FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;
```

Then check that every stored version has its file. `relative_path` is the path under
`FILEMANAGEMENT_BASE_DIR`:

```sql
SELECT CONCAT(fi.relative_path, '/v', fd.version, '/', fd.file_name, '.', fd.file_extension)
FROM file_details fd JOIN file_info fi ON fi.id = fd.file_info_id;
```

**Rows with no file must be zero.** Files with no row are the harmless orphans from the order table
above.

Finally, sign in and download one file through the interface: it is the only check that exercises
row, path, bytes, permissions and service together.

### The monthly drill

Most organisations that lose data had backups. What they did not have was evidence those backups
could be restored. Once a month, half an hour:

1. Take a run folder from **last week**, not the newest — this tests rotation too.
2. Restore it into a scratch database.
3. Run the count queries above and compare them with that day's figures.
4. Spot-check a few paths against the file archive of the same date.
5. Drop the scratch database, and **write down the date, which backup, how long it took, and what
   was wrong**. That duration is your real recovery time, and the drill is the only way to know it.

---

## When it will not start

| Symptom | Cause |
|---|---|
| `Port 8122 was already in use` | A previous instance is still running — usually the result of starting the JAR by hand and then starting the service |
| `Migration checksum mismatch` | The JAR's migrations disagree with `flyway_schema_history`; the JAR and the database are from different versions |
| `Schema-validation: missing table/column` | `ddl-auto=validate` doing its job: the JAR is older than the database, or a migration did not run |
| Service starts then stops immediately, no application log | Almost always the working directory: it cannot create the log directory. Check `WorkingDirectory` / `<workingdirectory>` |
| Starts, but every upload fails | `FILEMANAGEMENT_BASE_DIR` does not exist, is not writable, or does not end with a separator |
| Uploads over ~20 MB rejected | `spring.servlet.multipart.max-file-size` — 20 MB by default |
| Every non-administrator sees an empty tree | `filemanagement.folder-access.enabled=true` with no grants made yet |
| Cannot sign in as `Admin` on a fresh install | The generated password was printed once at WARN on first boot; search `app_log.log` for "random password was generated" |
| Persian text renders as `????` | The connection or the dump negotiated `latin1` — check `--default-character-set=utf8mb4` and the database collation |

---

## Related

- [arch.md](arch.md) — how the application is built, and what the configuration keys do
- [issues.md](issues.md) — known defects, including [45](issues.md) (`base-dir` concatenation)
- [roadmap.md](roadmap.md) — where this is going; Phase 3 replaces MySQL with PostgreSQL and Phase 4
  moves file storage to S3, both of which change this document
- [AGENTS.md](../AGENTS.md) — the working agreement, including the commands
