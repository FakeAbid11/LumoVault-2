# LumoVault

A private Android photo, video and GIF library with cloud backup powered by the user's own
Telegram account. Your photos. Your storage.

[`prd.md`](prd.md) is the master specification and the source of truth for every product decision.
This README records build and architecture status only.

## Status

**Phase 1 — foundation:** complete, verified green (run
[36024569535](https://github.com/FakeAbid11/LumoVault-2/actions/runs/36024569535)).

**Phase 2 — onboarding + Telegram authentication:** complete, verified green (run
[36038113299](https://github.com/FakeAbid11/LumoVault-2/actions/runs/36038113299)). The six
onboarding screens, the searchable country selector, E.164 phone normalization, the authentication
state machine, its nine human-readable failures, persisted setup choices, and launch gating are
built and compiling; live Telegram sign-in is not (see *Telegram status*).

**Phase 3 — local photo library:** complete, verified green (run
[36046308629](https://github.com/FakeAbid11/LumoVault-2/actions/runs/36046308629), 69 unit tests,
17.1 MB debug APK published as an artifact).

- A MediaStore scanner that indexes photos, videos and GIFs from metadata only — it never opens file
  bytes, and it identifies GIFs by MIME type rather than treating every image as static
- A Room `media` table carrying the columns PRD section 61 names, indexes chosen for the two queries
  the timeline actually runs, and a v2→v3 migration
- Incremental sync: rows are tagged with a scan id, upserted in chunks, and rows the scan did not see
  are removed — no rebuild, and no `NOT IN (…)` list a large library would overflow
- The Photos screen: day-grouped lazy grid, windowed loading instead of one in-memory list, Coil
  thumbnails decoded to the cell's size, video play/duration and GIF badges
- One sealed `PhotosUiState`, so "this device has no media" and "the index is not built yet" cannot
  be confused; permission, scanning, empty, error and pull-to-refresh states
- The Phase 2 folder picker now lists real folders from the index instead of an empty state

**Still not built, by design:** the photo viewer and EXIF metadata (Phase 8), restoring a cloud original
to the device and freeing up space (Phase 9), Settings, and the map (Phase 10). Later phases added the
backup engine, recognition and the library's own organisation — see the sections below, which are the
current record.

## Build in the cloud — never locally

The development machine is not expected to compile Android. Do not run `gradlew assembleDebug`,
`gradlew build`, `gradlew test`, or an Android Studio build locally.

```
commit + push  →  GitHub Actions  →  assembleDebug + testDebugUnitTest  →  LumoVault-debug-apk
```

`.github/workflows/build.yml` installs SDK platform 37, assembles the debug APK and runs the JVM
unit tests, then uploads `app-debug.apk` as an artifact (30 days). Download it from the run's
**Artifacts** section. A workflow run that has not gone green is not a build.

Phases 2 and 3 did not change the workflow: nothing new needs a CI-side tool.

### Telegram credentials

`app/build.gradle.kts` reads two build inputs and exposes them through `BuildConfig`:

| Source | Property | Secret |
| --- | --- | --- |
| Gradle property or env var | `TELEGRAM_API_ID` | `TELEGRAM_API_ID` |
| Gradle property or env var | `TELEGRAM_API_HASH` | `TELEGRAM_API_HASH` |

Both default to absent, which is a supported state: the app reports
`TelegramAuthState.NotConfigured` and the onboarding screen explains it. No credential, phone
number, code, password or session value is committed, hardcoded, or logged. The api hash is
filtered to hex before it reaches generated source, so a stray quote cannot break the build and a
value cannot inject code.

To build an APK that can sign in, locally:

```
-PPTELEGRAM_API_ID=... -PTELEGRAM_API_HASH=...
```

On CI the same two values are read from repository secrets when they exist. They are not secrets in
any meaningful sense once compiled in — anything distributed inside an APK can be lifted out of it —
so a debug artifact built with them set is a build of a client that is shareable, and
`build.yml` states on each run whether they were present.

## Backup status — Phase 5

Manual backup is implemented: select in Photos, a persistent queue in Room, one item at a time on a
WorkManager foreground worker, and the original sent as the message type it already is — photo, video
or animation — with the bytes staged unmodified rather than re-encoded.

What that means precisely, because the phrase "original quality" is easy to over-claim: LumoVault
performs no resizing, recompression or transcoding of its own, and hands TDLib the file as staged.
Telegram's own photo and video containers are the server's to encode, and an animation may be shown as
a looping video. Nothing claims the stored bytes match the bytes that went in: the content hash Phase 6
added identifies *what this device sent*, and no code downloads a backup to compare it against.

States are `QUEUED → PREPARING → UPLOADING → BACKED_UP`, with `FAILED` and `CANCELLED`, persisted in
`backup_queue` keyed by `media_store_id`. PRD section 48's `HASHING` and `VERIFYING` are still absent —
hashing is a step *about* an item rather than a state it waits in, and nothing reads a stored object back
to verify it. Phase 6 did add that table's hash columns and its remote manifest, and PRD section 48's
`NOT_BACKED_UP` with them.

**Run on a device, backup has not been verified by me.** The queue, the worker, the foreground
promotion, offline waiting and a real upload into Telegram are all unexercised — unit tests stop at the
`TelegramClient` boundary and never call JNI. See the next section for what was and was not confirmed
on a phone.

## Backup recognition — Phase 6

**Phase 6 — backup recognition:** complete and CI-verified green (run
[36150518196](https://github.com/FakeAbid11/LumoVault-2/actions/runs/36150518196),
219 unit tests across 24 classes, debug APK published).

LumoVault can now tell whether a local file is already stored, without uploading it to find out.

```
local file → size + mtime unchanged? ──yes→ reuse the hash on record (no file read)
                   │no
                   ↓
            streamed SHA-256 → cloud index → match: adopt that message, no upload
                                            → miss:  eligible for Phase 5's queue, and the
                                                     upload now carries a manifest
```

- **Content identity** is SHA-256 of the file's own bytes, streamed a buffer at a time and never loaded
  into memory — not a thumbnail, not a decoded bitmap, not a resized copy. Photos, videos and GIFs are
  hashed the same way, from `content://` for recognition and from the staged copy when a send is about to
  happen, so the hash and the bytes uploaded are the same bytes.
- **The record** lives in `backup_queue` (v5→v6): the hash, the size and modification time it was taken
  against, and when. Indexed on the hash, because "have these bytes been stored" is asked once per file.
  A file that cannot be read is recorded as *attempted with no hash*, which keeps it out of the next
  pass's candidates without ever making it look backed up.
- **The remote manifest** is the backup message's own caption — `LUMOVAULT_META v1 h=… s=… m=… n=…`. It
  travels in the message it describes, so the association cannot drift and a reinstall that loses Room
  loses nothing that the channel cannot restate. It is read back by the history walk Phase 4 already
  runs page by page; recognising a backup costs no original download.
- **Duplicate prevention** is the hash lookup, in two places: the recognition pass marks a match
  `BACKED_UP` against the message that holds it, and the upload path asks the same question after staging
  and before sending, so tapping "Back Up" on a photo the channel already has closes the row instead of
  posting a copy.
- **Changed media** gets a new identity and loses the old association; the message that holds the earlier
  bytes is left alone in Telegram, because deleting a user's stored photo is not a scan's business.
- **Local + cloud** on the Cloud screen is now a content match where one exists: a message whose manifest
  a local backup record claims, and whose media row is still in the index, is the same photo in both
  libraries. Name-and-size remains the fallback for the messages that carry no manifest, which is the
  Phase 4 heuristic rather than a new one.
- **Scale**: the pass only hashes while the remote index holds manifests no local record claims, so a
  settled library of 100,000 items costs zero file reads. It works in stages of 20, yields the moment the
  queue has anything to deliver, and stops at a five-minute budget rather than running into an upload.
- **Race safety**: recognition writes identity columns and never state, and its one state write is a
  conditional `UPDATE` that excludes `preparing` and `uploading`, so a scan cannot settle a row a worker
  owns or withdraw one the user queued.

**What this does not do.** A backup uploaded before Phase 6 carries no manifest, so after a reinstall it
cannot be recognised by content and will be uploaded once more — this time with a manifest. Nothing
matches those messages heuristically on file name and size, because a guess there is a ✓ on a photo that
may not be stored. PRD section 9's `dateTaken`, `latitude` and `longitude` are absent from the manifest
too: the local index reads no EXIF, so there is no source for them until the metadata work Phase 8 asks
for. And `VERIFYING` remains unbuilt — recognition confirms that a message declares this content, which
is a different claim from having read the stored bytes back.

**Verified by CI, not by a phone:** the same caveat as Phase 5 applies, more strongly here. Reinstall
recovery, duplicate prevention and changed-media detection are the three behaviours a device has to
confirm, and none of them has been run on one.

## Organisation — Phase 7

**Phase 7 — albums and organisation:** complete and CI-verified green (run
[36160991063](https://github.com/FakeAbid11/LumoVault-2/actions/runs/36160991063),
260 unit tests across 28 classes, debug APK published).

The library can now be arranged without touching the files or the backups in it.

```
media  ──1:1──  media_organization   favorite · archived · trashed_at
   │
   └──<:">──  album_media  >──1──  albums        (user albums only)

system albums = queries over the two tables above, never rows of their own
```

- **Eight system albums, zero stored rows.** Camera, Screenshots, Downloads and Videos are answers about
  the file (`RELATIVE_PATH`, `media_type`); Favorites, Archive and Trash are answers about what the user
  decided; Recently Added is a 30-day window over `date_added_seconds`. Each is derived at query time,
  because a stored membership for those would be a second copy that nothing recomputes. The folder
  patterns are a heuristic and are stated as one — `IS_SCREENSHOT` needs API 31 and nothing at all says
  "from the camera" on the API 29 this app supports.
- **Three independent dimensions, one sparse row each.** Every write is a single-column upsert
  (`INSERT … ON CONFLICT DO UPDATE`), so favouriting cannot disturb an archive mark and neither can
  disturb a pending upload. A 100,000-item library whose owner never pressed anything keeps 0 rows here.
- **Backup state is not organisation state and neither is Telegram's.** No repository in this phase has a
  handle on `backup_queue`: an album, a favourite, an archive or a Trash entry queues nothing, and a
  photo already `BACKED_UP` keeps its message id through all of them.
- **Deleting an album deletes the list.** `album_media` cascades from `albums` and nothing else — not the
  media row, not the organisation, not the backup. `album_media` pointedly has no foreign key to `media`:
  the index is rewritten with `INSERT OR REPLACE` on every scan, and a cascade from a parent the scanner
  replaces would empty every album on the next sync. Orphans are instead swept once per sync, in the same
  transaction as the prune.
- **Trash is a mark, not a deletion.** `trashed_at` hides an item from the timeline and every album view
  while leaving the file alone, and the timestamp is what makes "restore" mean something after a restart.
  Permanent deletion asks Android for consent — `MediaStore.createDeleteRequest`, which names the files to
  the user — and only a `RESULT_OK` clears the local rows. On API 29 that request does not exist and
  LumoVault does not hold a write grant, so the screen says the file has to go in the device's own gallery
  rather than pretending a button worked. Nothing in any of these paths calls a Telegram delete: a local
  file leaving the phone still leaves the cloud copy, which PRD section 72 treats as the point.
- **Phase 6 is untouched.** `content_hash`, the manifest format, `UploadState` and the ☁/↑/✓/↻ glyphs all
  still mean what they did; the timeline query gained a `LEFT JOIN` and a `WHERE`, not a new state.
- **Scale**: counts and covers come from subqueries in the list statement rather than a query per album,
  every organisation predicate is indexed, and each screen reads a window (`LIMIT 300`, widened on
  scroll) instead of a materialised library.

**What this does not do.** No album reordering, no per-album cover choice, no pull-to-refresh on the
album grid, and no undo snackbar — the confirmation dialogs are the only safety net. Trash has no
automatic expiry: `trashed_at` records how long an item has been sitting, and nothing acts on it yet.
Cloud-side organisation remains the single "LumoVault Backup" channel — a Telegram folder is not an album,
and nothing here presents it as one. The free-up-space flow (PRD section 74) is Phase 9's.

**Verified by CI, not by a phone:** the grid layout of two new screens, the consent dialog itself, and
what a restored, archived or trashed item looks like after a reinstall all need a device. The behaviours
are covered by unit tests over fakes that mirror the SQL; the pixels are not covered by anything.

## Telegram status — what is real and what is deferred

The client is TDLib, behind interfaces, over TDLib's **own Java binding** rather than its JSON one:

```
Kotlin → TelegramClient → org.drinkless.tdlib.Client → org.drinkless.tdlib.TdApi → libtdjni.so → Telegram
```

Already implemented and unit-tested off-device: the authorization-state mapping, error mapping
including `FLOOD_WAIT_<n>`, the cloud message mapper, the channel-marker rules, and the flow rules
that decide which screen appears. The fakes build real `TdApi` objects, because those are plain data
until a request is actually sent — so the mapping is tested against the same classes production uses,
not against a stand-in grammar.

**The native binary is no longer the seam.** [`build-tdlib.yml`](.github/workflows/build-tdlib.yml)
produced `libtdjni.so` for arm64-v8a and armeabi-v7a plus the generated `Client.java` and `TdApi.java`
from TDLib `ea97bcd`, and [`build.yml`](.github/workflows/build.yml) downloads them by pinned run id,
checks each file against a pinned SHA-256 and asserts the ELF class and machine per ABI. `isUsable` is
still the honest guard: an APK built without those artifacts, or without the api credentials, reports
Telegram as unavailable rather than crashing or pretending.

On 2026-09-25 the developer confirmed on a real Android device that the APK installs, TDLib's Java
interface loads, Telegram sign-in works and LumoVault creates the "LumoVault Backup" channel. That is
their observation, not a measurement this repository can reproduce — nothing here has a device, and no
automated test has ever loaded the native library.

Because the API is generated from TDLib's scheme, every request and field name here was read out of
`td/generate/scheme/td_api.tl` at the pinned revision `ea97bcd`, and the generated Java's naming came
from `td/generate/tl_writer_java.cpp` — class names are the TL constructor with a capital first letter
and field names are camelCase, so `authorizationStateWaitCode.code_info` is
`TdApi.AuthorizationStateWaitCode.codeInfo`. That is also what corrected three earlier guesses: the
phone-code request is `setAuthenticationPhoneNumber` (with a `PhoneNumberAuthenticationSettings`
object), `setTdlibParameters` takes its fields flat rather than as a nested `tdlib_parameters` object,
and the code length arrives as `codeInfo.type.length` rather than a separate `codeLength`. When the
pin moves, re-check them against that revision; a renamed field is now a compile error rather than a
login that silently never finishes.

## Toolchain

| Component | Version |
| --- | --- |
| Android Gradle Plugin | 9.4.1 |
| Gradle | 9.7.1 (wrapper jar checksum-pinned) |
| Kotlin | 2.3.21 (AGP 9 built-in Kotlin; no `kotlin-android` plugin) |
| KSP | 2.3.12 |
| Compose BOM | 2026.09.00 (Material 3) |
| Room | 2.8.5 |
| libphonenumber | 9.0.40 |
| Coil | 3.6.3 (`coil-compose`, `coil-video`; no network artifact) |
| TDLib | pinned revision `ea97bcd`, Java interface (`libtdjni.so`) |
| compileSdk / targetSdk / minSdk | 37 / 37 / 29 |

Versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). Kotlin stays on the 2.3
line because KSP has no 2.4.x release; moving Kotlin first would break Room's annotation processing.

Two dependencies were added deliberately, each for a job that hand-rolling would do worse:
libphonenumber (calling codes, example numbers, E.164 parsing) and Coil (thumbnail decode and cache
for `content://` URIs). TDLib contributes no dependency at all: its generated Java sources and its
`libtdjni.so` are build inputs fetched by CI, not artifacts resolved from a repository.

## Architecture

```
app/src/main/java/com/lumovault/app/
├── LumoVaultApplication.kt   entry point; owns the AppContainer
├── AppContainer.kt           lazy dependencies + the application-scoped coroutine scope
├── MainActivity.kt           edge-to-edge host, nothing else
├── data/
│   ├── local/                Room database (v7) and its DAOs: settings, media index, cloud index,
│   │                         backup queue, albums + organisation, MediaStore scanning
│   ├── media/                staged copies for upload, and streamed content hashing
│   ├── backup/               the WorkManager queue runner and its notification
│   ├── remote/telegram/      TDLib's typed Client/TdApi layer, auth repository, credentials, error mapping
│   └── repository/           Room / libphonenumber / permission implementations
├── domain/
│   ├── model/                ThemeMode, Country, Media, SystemAlbum, onboarding state + checklist derivation
│   ├── organization/         Album, MediaOrganization repository interfaces
│   ├── backup/               upload state, identity, hashing and staging interfaces
│   ├── repository/           Settings, Onboarding, Permission, Country, Media interfaces
│   ├── telegram/             TelegramAuthRepository, auth state, auth failure, code channel, manifest
│   └── usecase/              the two flows that span repositories: cloud sync + recognition, the queue
└── ui/
    ├── LumoVaultRoot.kt      launch decision: onboarding or main
    ├── LumoVaultApp.kt       the four-tab shell
    ├── onboarding/           the six screens, their flow host, and flow state
    ├── navigation/           main destinations and routes
    ├── components/           shared composables (country picker, placeholders)
    ├── theme/                Color.kt, Theme.kt, Type.kt
    └── screens/              Photos, Albums, Cloud, Map
```

Composables never touch a database, network or file storage; the Activity only hosts Compose; and
each screen reads one immutable UI state. Room owns all persisted state — there is no second
preference mechanism. `domain/usecase/` holds exactly what earns it: a flow that spans two repositories
(cloud synchronisation, backup recognition), and none that spans one — the queue's policy lives with its
repository rather than in a wrapper that would only add a hop.

Decisions worth knowing about:

- **One settings row, one writer.** `AppSettingsStore` performs every change as a
  read-modify-write inside a transaction, so the theme toggle and the onboarding flow sharing one
  row cannot overwrite each other.
- **Room v7, upgraded by hand-written migrations only.** Phase 1 shipped a v1 the first cloud build
  rejected (an entity-free `@Database` is illegal), so PRD section 61's `UserSettings` row became the first
  entity. Every step since is explicit — settings fields, `media`, the cloud index, `backup_queue`, its
  Phase 6 identity columns, and Phase 7's three organisation tables — and each one mirrors the DDL Room
  compiles, because a schema the entities describe and no migration produces is a crash on upgrade rather
  than a build failure. `fallbackToDestructiveMigration` appears nowhere.
- **Nothing cascades from `media`.** The index is rewritten with `INSERT OR REPLACE` on every scan, so a
  child table with a cascading foreign key on it is emptied on every sync. `backup_queue`, `album_media`
  and `media_organization` therefore relate to media by id and a join, with an explicit sweep for orphans
  once a scan has decided what exists.
- **Permission state is read live, decisions are stored.** A remembered "granted" would be wrong the
  moment the user revokes access in system settings.
- **The country list is derived, not bundled.** `Locale.getISOCountries()` for names,
  libphonenumber for calling codes, emoji flags from the ISO code — no dataset to rot, no bitmaps.
- **DI is still a hand-written container.** Hilt earns its place when Phase 5's WorkManager workers
  need constructor injection across processes.

## Validation performed

Static checks locally: package declarations against paths, every `com.lumovault.app.*` import
against an actual declaration, all `R.string` references against `strings.xml` (and the reverse, for
orphans), catalog accessors against build scripts, XML/YAML parsing, brace balance, unused imports.
Android compilation happens only in GitHub Actions — and Phase 1 proved that this is where the
real mistakes get caught.
