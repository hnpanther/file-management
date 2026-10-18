# Who may do what: access, end to end

How this application decides whether a request may do something - for the administrators who set it up and for
whoever changes the code. Everything here is true of 2.17.0; the code it names is the authority, and a change to
how access works changes this page with it.

* [The two questions](#the-two-questions)
* [Who asks](#who-asks)
* [The first question: permissions](#the-first-question-permissions)
* [The second question: folder access](#the-second-question-folder-access)
* [Folder classes and class grants](#folder-classes-and-class-grants)
* [What each operation needs](#what-each-operation-needs)
* [API keys](#api-keys)
* [Outside folder access: public files and share links](#outside-folder-access-public-files-and-share-links)
* [Recipes](#recipes)
* [Checking what someone reaches](#checking-what-someone-reaches)
* [For developers: where it is enforced, and the rules that keep it true](#for-developers-where-it-is-enforced-and-the-rules-that-keep-it-true)

---

## The two questions

Every request that touches a file or a folder is asked two independent questions, and **both must say yes**:

1. **May this person do this kind of thing at all?** - a *permission* (`PermissionEnum`), held through a role, checked
   on the endpoint before anything runs (`@PreAuthorize`). "May upload files", "may see the reports".
2. **May they do it *here*?** - *folder access*: a grant that covers the folder the file or folder is in, with the
   verb the operation needs (`READ` or `WRITE`). Checked inside the services, and pushed into every list's SQL.

Holding `DOWNLOAD_FILE` lets a person use the download button; folder access decides which files it works on.
Neither replaces the other: a grant without the permission reaches nothing, and the permission without a grant
reaches nothing either - once folder access is enforced.

A few further gates sit beside these two and narrow, never widen:

| Gate | What it decides | Where |
|---|---|---|
| **Upload policy** | which kinds of file and what size a role, or an API key, may upload | `UploadPolicyService`, the role and key pages, `/settings/upload` |
| **Quota** | how many bytes may be stored below a folder | `FolderQuotaService`, a folder's `quota_bytes` |
| **A folder's class** | whether a folder takes files directly, and which subfolders may be made in it (roadmap Phase 14) | `FolderClassRules`, `FolderService.acceptsFiles` |
| **An API key's own capabilities** | whether the key may create folders, delete files, delete folders | the key's page |
| **Administrator only** | changing an administrator's account; ending an administrator's session; unlocking an administrator's sign-in | `UserService.requireAdministratorFor` and the like |

---

## Who asks

| Who | Signs in with | Permissions from | Folder access from |
|---|---|---|---|
| **A person** | the sign-in form (local password or Active Directory) | their roles | their own grants, their roles' folder grants, their roles' class grants |
| **An administrator** (holds the role `ADMIN`) | the same | `ADMIN` holds every permission, and the `ADMIN` authority passes every `@PreAuthorize` | **none needed**: unrestricted - no grant is read |
| **An API key** (`fmk_…` Bearer for v1/v2, or an S3 key) | its secret | its own (the v1 file authorities, `API_KEY`) | **the key's own folder grants only** - never its creator's, never a class grant, never the administrator bypass |
| **The v1 machine account** (the PL/SQL clients' shared user) | HTTP Basic | its roles, like a person | its roles' grants, like a person |
| **Nobody** (not signed in) | - | - | only the public files page (if the setting allows) and a share link's own revision |

A request made with an API key **runs as the key's creator** for the record - `action_history`, the file history,
`createdBy` - and records the key beside them (`ActingApiKey`), but **reaches what the key was granted**.

---

## The first question: permissions

* **A permission is one constant per endpoint** (`PermissionEnum`, about a hundred), each named in a comment
  above it with the handler it guards. A handler carries
  `@PreAuthorize("hasAuthority('X') || hasAuthority('ADMIN')")`; `EndpointGuardTest` fails for a handler with none.
* **A role is a set of permissions** plus folder grants, class grants and optionally its own upload policy. A person
  holds any number of roles and has the union of what they hold.
* **Two roles are fixed** (`FixedRole`), redefined on every start:
  * **ADMIN** - every permission (a new one is ADMIN's at the next start), every folder (the bypass), every content
    kind up to the server's cap. Its holders' accounts are changed by administrators only.
  * **USER** - what every account is given: reading, writing, deleting files, managing folders, share links
    (`FixedRole.USER_PERMISSIONS`) - and **no grant**. With folder access on, a person with USER alone reaches their
    own personal folder (a direct `WRITE` grant) and nothing else.
  * Neither is edited; a different role is made by **copying** one (`RoleService.copyRole`): a copy holds the same
    permissions, folder grants, class grants and upload policy, and none of ADMIN's two privileges.
* **The role page groups permissions** (`PermissionGroup`) only to tick them together; nothing stores a group.

---

## The second question: folder access

### Switched on, or not

`filemanagement.folder-access.enabled` (the environment's `FILEMANAGEMENT_FOLDER_ACCESS_ENABLED`) - **off by default**.

* **Off**: every person is unrestricted; the permission is the only check (holding `DOWNLOAD_FILE` downloads every
  file). API keys are *always* restricted to their own grants, whatever this says.
* **On**: closed until granted. A person reaches only what their grants cover. Turn it on only after the grants
  exist ([deployment.md](deployment.md#one-decision-to-make-deliberately-folder-access)).

### Where a grant comes from

| Source | Stored in | Set on | Covers |
|---|---|---|---|
| **A person's own grant** | `user_folder` | (the personal folder: made with it) | one folder and everything below it |
| **A role's folder grant** | `role_folder` | the role page, tab "دسترسی پوشه‌ها" | one folder and everything below it, for every holder of the role |
| **A role's class grant** (2.17.0) | `role_class_grant` | the role page → "دسترسی بر اساس طبقهٔ پوشه" | every folder of a class - and everything below each - optionally only within a scope folder |
| **An API key's grant** | `api_key_folder` | the key's page | one folder and everything below it, for that key |

### The verbs

* **`READ`** - see the folder, list it, open and download its files, find them in every search and report, make a
  share link to a revision.
* **`WRITE`** - everything `READ` allows, and change things: upload, add versions, move, rename, delete, describe
  (metadata), make folders. **`WRITE` always includes `READ`**; "write but not read" cannot be expressed.

### Rules that hold for every grant

1. **A grant covers its folder and everything beneath it**, to any depth. There is no "this folder only".
2. **Grants add up; nothing takes away.** A person reaches the union of every grant from every source, each with the
   strongest verb any of them gives. There is no deny: to keep someone out of a subfolder, do not grant its parent -
   grant the parts they need, or give the subfolder a class of its own and grant that.
3. **The folders on the way down are shown, not opened.** A grant may sit deep in the tree (`ERP/PartyCompany/1234/
   Contract/98-001/Documents`). The folders above it appear in the explorer so the person can walk down - by name
   only: each shows just the children that lead to a grant, never its files or its other branches. These are
   "signposts" (`FolderAccess.isOnPathTo`, `visible`).
4. **A file is reached through the folder it is in.** Moving a file into a folder changes who reaches it; a file
   has no grant of its own.
5. **Write is asked where the change lands**: on the folder a file is uploaded to, on both folders of a move.

---

## Folder classes and class grants

Roadmap Phase 14 (2.17.0). A **class** is a folder's type - *contract*, *contract documents*, *statement* - and a
**class grant** gives a role every folder of a type at once, in every company, now and later.

### A class

Defined on **Settings → طبقه‌های پوشه** (`FOLDER_CLASSES_PAGE`, `SAVE_FOLDER_CLASS`, `DELETE_FOLDER_CLASS`). Each class
has a title, a name, and three settings that belong to the class - never to a single folder:

* **Container only** - no file may be put directly in a folder of the class (on the web, v1, v2, S3, multipart or by a
  move); its subfolders hold them. Files already there stay and take new versions.
* **Its template** - subfolders made, each with its class, as soon as a folder of the class is made; theirs in turn.
* **Other subfolders** - a subfolder whose name the template does not hold is made *without a class*, made *with a
  given class*, or *refused*.

A folder has **at most one class**, and **none until someone gives it one**. A new folder takes its class **from its
parent's class** - a name in the parent class's template takes that template folder's class, any other name follows
the parent class's rule - by hand, by v1 or by S3 alike (`FolderClassRules`). A folder whose parent has no class has
none. Names are compared as folder names are, without case and folded.

A folder's class is set on its page - the explorer → details → طبقه (`SET_FOLDER_CLASS` **and** `WRITE` on the
folder). The same page applies the rules to everything already below the folder (a preview first). **Giving a folder
a class gives it to every role granted that class** - which is why it takes both the permission and write access.

### A class grant

A row on a role: **a class, `READ` or `WRITE`, and a scope** - a folder named by its path of names
(`ERP/PartyCompany/1234`), or empty for everywhere. It covers a folder when

* the folder lies **within the scope** (itself or below it; everywhere without one), **and**
* the folder **or a folder above it has the class**.

So a grant on *contract documents* covers each contract's `Documents` folder and whatever is below it - and nothing
beside it: the contract's `ContractState` is another class. Class grants **add to** folder grants under the same
rules: union, no deny, signposts on the way down (the companies and contracts appear by name, each showing only the
way to the granted folders). A key never holds a class grant. A fixed role refuses them.

### Worked example: the ERP's contracts

```
ERP/                                      no class
  PartyCompany/                           ظرف شرکت‌ها    container; other subfolders -> شرکت
    1234/                                 شرکت           container; template Contract -> ظرف قراردادها
      Contract/                           ظرف قراردادها  container; other subfolders -> قرارداد
        98-001/                           قرارداد        container; template Documents, ContractState; others refused
          Documents/                      اسناد قرارداد
            contract.pdf
          ContractState/                  ظرف صورت وضعیت‌ها   other subfolders -> صورت وضعیت
            5/                            صورت وضعیت
              statement.pdf
      Letters/                            no class (a company's other folder)
```

| Role | Grant | Reaches | Sees on the way, by name only |
|---|---|---|---|
| Contracts department | class اسناد قرارداد, `READ`, everywhere | every `Documents` and its files, in every company | `ERP`, `PartyCompany`, each company, `Contract`, each contract |
| Finance | class ظرف صورت وضعیت‌ها, `READ`, everywhere | every `ContractState`, its statements and their files | the same companies and contracts |
| Contracts, one company | class اسناد قرارداد, `READ`, scope `ERP/PartyCompany/1234` | the `Documents` of company 1234's contracts only | `ERP`, `PartyCompany`, `1234`, `Contract`, its contracts |
| Company 1234's manager | folder grant on `ERP/PartyCompany/1234`, `READ` | everything of company 1234 | `ERP`, `PartyCompany` |
| n8n's S3 key | folder grant on `ERP`, `WRITE`, may create folders | everything under `ERP`, as a key - no class grant ever | - |

A person holding both the first and the fourth reaches all of company 1234 **and** every company's `Documents` - the
union.

### When things move or change

* **A folder moved** keeps its class; what is below it now sits under its new parent's classes. A grant scoped to its
  old place no longer covers it; a grant on its class still does.
* **A class taken off a folder**, or changed, changes at once who reaches it and everything below it.
* **A class changed** (its template, its rules) acts on folders made from then on; the folders made before stay as
  they are until the rules are applied again from a folder's page.
* **A class cannot be deleted** while a folder carries it, a role is granted it, or another class names it.
* **A role deleted** takes its class grants with it; **a scope folder deleted** takes the grants scoped to it.

---

## What each operation needs

"Permission" is the first question; "folder" the second. ADMIN passes both everywhere.

| Operation | Permission | Folder access |
|---|---|---|
| See the explorer, the tree, a folder's contents | `FILE_EXPLORER_PAGE` / `FILE_TREE_PAGE` / `REST_GET_FOLDER_CONTENT` | `READ` on the folder - or signposts only above a grant |
| A file's page | `FILE_INFO_PAGE` | `READ` on the file's folder |
| Download a revision | `DOWNLOAD_FILE` (web), `API_DOWNLOAD_FILE` (v1) | `READ` on the file's folder |
| The file list, its search | `GET_ALL_FILE_INFO_PAGE` | filtered to readable folders in the query |
| Search in contents, advanced search | `SEARCH_FILE_CONTENTS` / `API_SEARCH_FILE_CONTENTS` | readable folders, in the query |
| Reports | `REPORTS_PAGE` | counted over readable folders only |
| File history, downloads log | `FILE_HISTORY_PAGE`, `FILE_DOWNLOADS_PAGE` | rows of readable folders |
| Upload a new file | `SAVE_NEW_FILE` / `API_SAVE_NEW_FILE` | `WRITE` on the folder; the folder must take files (not the root, not `Profiles`, not a container class); upload policy; quota |
| Add a version or format | `SAVE_NEW_FILE_DETAILS` | `WRITE` on the file's folder; upload policy; quota |
| Move a file | `REST_MOVE_FILE_INFO` | `WRITE` on both folders; the target must take files; quota |
| Delete a file or a revision | `REST_DELETE_FILE_INFO` / `REST_DELETE_FILE_DETAILS` / `API_DELETE_FILE_DETAILS` | `WRITE` on the file's folder |
| Describe a file (metadata) | `EDIT_FILE_METADATA` / `API_SET_METADATA` | `WRITE` (read it: `READ`) |
| Make a folder | `REST_CREATE_FOLDER` (S3: the key may create folders) | `WRITE` on the parent; the parent's class may refuse the name |
| Rename a folder | `REST_RENAME_FOLDER` | `WRITE` on the folder; the parent's class may refuse the new name |
| Move a folder | `REST_MOVE_FOLDER` | `WRITE` on its old and new parent; the new parent's class may refuse it |
| Delete an empty folder / a folder with everything | `REST_DELETE_FOLDER` / `REST_DELETE_FOLDER_TREE` | `WRITE` on its parent |
| Describe a folder | `EDIT_FOLDER_METADATA` / `API_SET_METADATA` | `WRITE` on the folder |
| Switch a folder's content reading | `SET_FOLDER_CONTENT_READING` | `WRITE` on the folder |
| Set a folder's class, apply the rules below it | `SET_FOLDER_CLASS` | `WRITE` on the folder |
| Make a share link | `CREATE_SHARE_LINK` | `READ` on the revision's folder |
| Define classes; edit roles, their grants and class grants; users; keys | the settings permissions | - (administration, not files) |

---

## API keys

* A key is made on **کلیدهای API** for one integration, with **its own folder grants** - `READ` or `WRITE` per folder -
  and its own capabilities: may create folders, may delete files, may delete folders. Kind `V1` (Bearer, the v1/v2
  routes) or `S3` (Signature V4, the S3 surface).
* It reaches **exactly its grants**: not its creator's roles, not ADMIN's bypass even if its creator is an
  administrator, **never a class grant**, and regardless of the folder-access switch.
* It may upload what **its own upload policy** allows, or the system-wide policy - never its creator's.
* It can be renewed, revoked, reissued; a revoked key reaches nothing.

---

## Outside folder access: public files and share links

Two ways out deliberately bypass folder access, each narrow:

* **Public files** (`/files/public-files`): only revisions marked public, listed to signed-in people or to everyone, as the
  setting `public-files.anonymous` says.
* **A share link**: one revision, behind its token - with its expiry, optional password and download cap - made only
  by someone who may read it. Revoking it, or deleting the revision, closes it.

---

## Recipes

* **A department that sees one kind of folder in every company** - give the kind a class, give the folders that class
  (once, from the top folder's page: set the class on the container, apply the rules below), and give the
  department's role a class grant on it. New companies and contracts made by n8n carry the class from then on.
* **The same, for some companies only** - the same grant with a scope: the folder above those companies, or one grant
  per company.
* **Everything of one company** - a folder grant on the company's folder.
* **Read-only auditors** - `READ` folder grants (or class grants), and permissions without the write ones.
* **An integration** - an API key with folder grants on its bucket or folder, and only the capabilities it needs.
* **Someone must not see a subfolder of a folder they otherwise see** - there is no deny. Restructure so the subfolder
  is beside what they see rather than inside it, or give the part they see its own class and grant only that.

---

## Checking what someone reaches

Until the access report (roadmap 13.7) exists:

* Sign in as a test account holding the same roles and look - the explorer, the file list, the search show exactly
  what the account reaches.
* A role's page lists its folder grants and links to its class grants; a key's page lists its folders.
* A folder's page (explorer → details) names its class; the classes page counts the folders of each class.

---

## For developers: where it is enforced, and the rules that keep it true

### The pieces

* **`FolderAccessService.accessFor(principalId)`** resolves a request's access once, into a `FolderAccess`: unrestricted
  (ADMIN, or enforcement off for a person), or the reduced, sorted paths it may read and write. Two statements: the
  person's own grants, then their roles' folder grants and what their class grants cover - the folders of the class
  within the scope, and the scope itself when it lies under one (`FolderRepository.findGrantsThroughRoles`). An API
  key: `accessForApiKey`, its own grants only. Asked for one folder or file - `canRead`, `canWrite`, `visible`,
  `visibleChildIdsUnder` - each a lookup in a sorted set.
* **`GrantedFolderPath`** is the same in SQL: one row per (person or key, grant) - a folder grant's folder path, or a
  class grant's scope path (`/` for everywhere) with its `class_key` (`/9/`). Every list, search, report and listing
  asks "is this folder under one of the reader's rows?" against it - never the readable folders as a list of ids
  (issue 114).
* **`folder.class_path`** - the ids of the classes of the folder and every folder above it (`/3/9/`). A class grant
  covers a folder whose path is under the grant's and whose class path holds its key. Written when the folder is made,
  then **only by SQL** (`rewriteClassPaths`, from the database's values) on a move or a change of class; repaired at
  start (`ClassPathRepair`); asserted by `findRowsWhoseClassPathDisagrees`.
* **`ReadScopeSql`** writes the grants CTE and the conditions for native SQL - `granted`, `readable`,
  `grantedWithSignposts` / `visible`. The JPQL lists use the same predicate through `GrantedFolderPath`
  (`FileInfoRepository`, `FileShareLinkRepository`, `ReadableFolders`).
* **`FolderReadScope.classes`** - whether the reader holds a class grant. Without one, the statements are exactly what
  they were before class grants (class rows not read, no class test); with one, the class test is
  `strpos(class_path, class_key) > 0`. The difference is a planner's: a second `LIKE` made PostgreSQL misjudge a
  reader's folders and plan the content search 100× slower (issue 125).

### Rules for a change

1. **A new operation on a file or folder asks folder access** - `requireReadAccess` / `requireWriteAccess` on the
   folder it acts in - besides its `@PreAuthorize`. Every account holds USER, so an operation that forgets it lets
   everybody do it (issue 90).
2. **A new list filters in SQL**, with `ReadScopeSql` or the `GrantedFolderPath` predicate - including the class test -
   never by reading rows and filtering in Java, never with an `IN` of folder ids.
3. **A new way to make a folder goes through `FolderService.create`** (the class, its template, the refusal); **a new
   way to move one or change a class rewrites `class_path` by SQL** under `FolderClassLock` held exclusively (making
   a folder holds it shared), and its test asserts `findRowsWhoseClassPathDisagrees` empty. A nightly check
   (`ClassPathCheck`) sets right and reports any row that disagrees.
4. **A change to how a grant covers a folder** changes `GrantedFolderPath`, `ReadScopeSql`, the JPQL predicate and
   `FolderAccess` together - and `FolderClassAccessTest`'s random trees, which compare the Java answer, the SQL answer
   and an oracle written from the definitions, must still agree. Measure it with `-Pscale`: estimates decide plans.
5. **Keys stay out of class grants**: a key's rows in `GrantedFolderPath` are its folder grants alone
   (`FolderClassAccessTest.keysHaveNoClassGrants`).
