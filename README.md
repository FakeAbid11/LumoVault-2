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

**Still not built, by design:** Telegram upload, the backup queue, hashing, the remote manifest,
channel discovery and the Cloud library, restore, free-up-space, map, albums, trash and Settings.
Phase 3 is entirely local — nothing leaves the device.

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
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |

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
│   ├── local/                Room database, UserSettings row, DAO, single-writer store
│   ├── remote/telegram/      TDLib's typed Client/TdApi layer, auth repository, credentials, error mapping
│   └── repository/           Room / libphonenumber / permission implementations
├── domain/
│   ├── model/                ThemeMode, Country, onboarding state + checklist derivation
│   ├── repository/           Settings, Onboarding, Permission, Country interfaces
│   └── telegram/             TelegramAuthRepository, auth state, auth failure, code channel
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
- **Room v2.** Phase 1 shipped a v1 the first cloud build rejected (an entity-free `@Database` is
  illegal), so PRD section 61's `UserSettings` row became the first entity, and Phase 2's fields are
  an explicit `MIGRATION_1_2`.
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
