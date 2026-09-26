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

**Still not built, by design:** Settings beyond backup and storage, the About/licenses screen, and any
release or signing work. Phase 10 was the hardening pass, and it is recorded below. Every earlier phase is
recorded in the sections above, in order.

**Video playback on Media3 ExoPlayer:** complete and CI-verified (run
[36222746776](https://github.com/FakeAbid11/LumoVault-2/actions/runs/36222746776) — 475 unit tests, 0 failed,
debug APK built with both TDLib ABIs packaged). The viewer's video page plays through `media3-exoplayer`
1.11.1 with a `PlayerView`, replacing `MediaPlayer`; the application-level state machine, the generation
guard on every player callback and the failure screen are the ones the crash fix introduced, adapted rather
than removed. Playback on hardware is **not** verified — see
[Video playback is Media3, with LumoVault's own guards](#video-playback-is-media3-with-lumovaults-own-guards).

**Selected-folder backup:** complete and CI-verified (runs
[36228045612](https://github.com/FakeAbid11/LumoVault-2/actions/runs/36228045612) — red, one interface member
missing from a test fake — and
[36228240891](https://github.com/FakeAbid11/LumoVault-2/actions/runs/36228240891), 489 unit tests, 0 failed,
debug APK built with both TDLib ABIs packaged). Saving a folder selection now writes the choice, withdraws
what the narrowed selection no longer covers while it is still unsent, re-installs the periodic pass and asks
for one immediately; a pass asks for a send whenever the queue holds work, so a queue that outlived its
process resumes. Whether a folder's photos actually arrive in the Telegram channel is **not** verified on a
device — see [Folder backup — what a saved selection starts](#folder-backup--what-a-saved-selection-starts).

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

### Map tiles

The map plots photo positions with osmdroid, and osmdroid needs a tile host to draw anything behind
them. It is a **build input**, the same shape as the credentials above, because the OSM Foundation's
[tile usage policy](https://operations.osmfoundation.org/policies/tiles/) is explicit about what it
permits and what it refuses: a client must declare a meaningful User-Agent, bulk or preventive fetching
is forbidden, tiles must be cached, attribution must be shown — and an app that grows past light use is
asked to move to a third-party provider or its own server.

The **default** is OSM's standard tile host, taken under those terms rather than around them: the
traffic identifies LumoVault by name, nothing is fetched until the map screen is open, one viewport at
a time is asked for with a 250 ms debounce, tiles are cached in the app's own `cacheDir/osmdroid`, and
the credit line is drawn on the map. That is the light, personal-use case the policy describes. A build
meant for wider distribution should set its own host *and* put a contact address in the user agent —
which the policy asks of everyone — and the four values below are exactly how.

| Build input | Meaning | Default |
| --- | --- | --- |
| `MAP_TILE_URL` | a `{z}/{x}/{y}` template, `http(s)://…` | `https://tile.openstreetmap.org/{z}/{x}/{y}.png` |
| `MAP_TILE_USER_AGENT` | what the host is asked to identify the traffic as | `LumoVault/0.1 (repo URL)` |
| `MAP_TILE_ATTRIBUTION` | the credit line drawn on the map | `© OpenStreetMap contributors` |
| `MAP_TILE_MAX_ZOOM` | the deepest zoom the host allows, clamped to 1–22 | 19 |

```
./gradlew assembleDebug \
  -PMAP_TILE_URL='https://tile.example.org/{z}/{x}/{y}.png' \
  -PMAP_TILE_USER_AGENT='LumoVault/0.1 (your-contact@example.org)' \
  -PMAP_TILE_ATTRIBUTION='© Example map contributors'
```

Each value is trimmed and then dropped if it carries a `"`, a backslash or a `$` — those would fail
the build from generated `BuildConfig` source nobody reads, and a dropped value lands in the state
below instead. A template is kept only if it names `{z}`, `{x}` and `{y}`; `TileTemplate.isUsable`
applies the same test at runtime, so the build cannot accept a template the app would then refuse.

**With `MAP_TILE_URL` set empty the map still works and says it has no tiles.** Every photo position,
every cluster, the bottom strip and the viewer hand-off all render on a plain canvas, and the screen
shows a notice naming the missing build input. Fetching is turned off outright in that case rather than
left alone: osmdroid has a default tile source of its own, so an unset source would quietly mean a
server the build never chose — the one thing the policy above says not to assume. No storage permission
is involved either way, because the cache lives in the app's private directory.

Nothing is fetched from a tile host until the map screen is actually open; there is no pre-fetch of a
bounding box, and the pass that reads EXIF costs no network at all.

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
[36161483511](https://github.com/FakeAbid11/LumoVault-2/actions/runs/36161483511),
263 unit tests across 29 classes, debug APK published).

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

**Device folders, kept a separate idea.** Alongside the albums above, the Albums screen lists a section
called "On this device": every folder MediaStore files supported media into — `Pictures/WhatsApp/`,
`Movies/ScreenRecordings/`, a folder created yesterday — grouped from the existing index in one
`GROUP BY relative_path` statement, with each folder's newest photo as its cover. They are read, never
registered: no row in `albums`, none in `album_media`, and no id that could be confused with a user album's
row id, because a folder is addressed by its normalized relative path (`FolderPaths.normalize`, one
spelling per physical folder, trailing separator included). Two folders called `Telegram` under different
parents stay two albums, and the card says which is which only then. A folder already listed by a path
system album — Camera, Screenshots, Downloads — is left out, using `SystemAlbum.covers`, so the same files
are never offered twice under two counts. A folder whose last photo goes to Trash disappears from the list,
and opening one goes through the same detail grid and viewer as any album, with the same backup badge,
because it is the same `Media` records.

**What this does not do.** No album reordering, no per-album cover choice, no pull-to-refresh on the
album grid, and no undo snackbar — the confirmation dialogs are the only safety net. Trash has no
automatic expiry: `trashed_at` records how long an item has been sitting, and nothing acts on it yet.
Cloud-side organisation remains the single "LumoVault Backup" channel — a Telegram folder is not an album,
and nothing here presents it as one. The free-up-space flow (PRD section 74) is Phase 9's.

**Verified by CI, not by a phone:** the grid layout of two new screens, the consent dialog itself, and
what a restored, archived or trashed item looks like after a reinstall all need a device. The behaviours
are covered by unit tests over fakes that mirror the SQL; the pixels are not covered by anything.

## Viewer and map — Phase 8

**Phase 8 — photo viewer and map:** complete and CI-verified green (run
[36181115710](https://github.com/FakeAbid11/LumoVault-2/actions/runs/36181115710),
335 unit tests across 37 classes, debug APK published). Phase 8 added eight test classes and 72 tests.

```
media ──1:0..1── media_metadata    position · camera · lens · exposure · extracted_at
   │
   └── viewer/{mediaId}/{kind}/{argument}    kind = photos | album | system-album
```

- **One capture time, and it is not EXIF's.** `media.date_taken_seconds` comes from MediaStore's
  `DATE_TAKEN` — the column Phase 3's scan already queries — so the timeline's date costs no file I/O.
  EXIF's `DateTimeOriginal` is read but deliberately *not* stored beside it: two columns answering the same
  question would disagree on some photo forever, and nothing in the app could say which one wins.
- **The EXIF row is its own table** (v7→v8), with no foreign key to `media` for the standing reason that the
  scanner rewrites that table, every field nullable because a real photo carries only some of them, and one
  index on `(latitude, longitude)` because the map asks exactly one question of it: what is in this box.
- **A header read, not a file load.** `ExifInterface` gets the `FileDescriptor`, so a 40 MB photo costs the
  bytes around its APP1 block. The AndroidX class rather than `android.media.ExifInterface`, because the
  platform one does not parse the HEIF/HEIC and PNG containers photos actually arrive in — choosing it would
  have quietly dropped part of every library. Photos only: a GIF carries no EXIF and a video's position is
  not in an EXIF block, so opening either would spend a read that can only return nothing, and then store
  that nothing as a fact.
- **`ACCESS_MEDIA_LOCATION` is requested where it is needed** — the map screen, with the reason on screen,
  not during onboarding — and re-read from the system every time the screen resumes, because it can be
  revoked outside the app. Without it MediaProvider serves a *redacted* copy whose GPS block is simply
  absent, and `setRequireOriginal` throws when the uri is opened, so the request is made only while the
  grant is held. On grant, the stored "read, nothing there" rows are deleted: otherwise a library scanned
  before the consent would be permanent, and the map would stay empty for the one reason the user just
  fixed. `ACCESS_FINE_LOCATION` is *not* in the manifest — device location is a different permission than
  reading a coordinate a camera already wrote into your own file.
- **The pass terminates.** Stages of 12, a four-second budget, cancellable between steps, and a candidate
  list that over-asks by what it already tried this run so a permanently unreadable file at the head cannot
  starve the ones behind it. A successful or empty read is recorded, so the list only ever shrinks.
- **The viewer is a route, not a state holder**: `viewer/{mediaId}/{kind}/{argument}` survives process death
  with the back stack, and the pager rebuilds the list from the same Room query the source screen uses
  rather than from a passed-in collection. `Listing` distinguishes *not answered yet* from *the list does
  not contain that photo* — the second case says so, instead of opening index 0 of some other picture.
- **Three renderers, one pager.** A photo is zoomable; a GIF is handed to `coil-gif`, which is the only thing
  that makes an animated file move — without it a GIF is a still image that looks correct; a video plays
  through Media3's ExoPlayer over a `PlayerView` with the library's own control bar switched off, so the
  transport on screen is still LumoVault's: play/pause, seek, position, duration, and one failure sentence.
  Media3 was first declined here for the weight of it — `media3-exoplayer` arrives with six sibling modules,
  Guava and RecyclerView — and it is now carried on purpose, because the thing being bought is not a feature,
  it is a decoder lifecycle that does not throw `IllegalStateException` at a callback thread when a page is
  swiped away mid-preparation. The bytes are never re-encoded and never copied into memory, so what plays is
  the original, streamed from the content resolver. See
  [Video playback is Media3, with LumoVault's own guards](#video-playback-is-media3-with-lumovaults-own-guards).
- **The zoom arithmetic is pure** (`ViewerZoom`): 1×–5×, double-tap to 3× toward the point that was tapped,
  and a pan limited to the overhang the current scale actually has, so a photo cannot be dragged off into
  black. Pinch recognition itself cannot be tested off-device, which is why every *decision* the gesture
  makes lives in that one file.
- **The details sheet shows only what exists.** A camera, lens, focal length, aperture, ISO or shutter line
  appears if and only if the read found that tag; dimensions and file size come from the MediaStore columns
  the scanner already reads, so nothing decodes an image to describe it. The one absence the sheet names is
  location, because that one has a button attached to it. Where EXIF had no capture date at all, the sheet
  says *"Added on this device"* over `date_added` rather than presenting an import timestamp as a birthday.
- **Nothing about an action is new.** Back up enqueues on `backup_queue` through the same repository the
  Photos selection uses — same hashing, same manifest, same duplicate check; favourite, archive, trash and
  add-to-album all go through the Phase 7 repositories. The viewer has no upload path and no organisation
  table of its own.
- **The map plots what the files say.** Grid bucketing in projected pixels at the zoom on screen (a 64-px
  cell), a cluster's position taken as the mean of its members' *projected* positions, markers painted
  smallest-first so a bubble that says 400 covers the pin it overlaps rather than hiding under it, one
  bounding-box query per viewport (debounced, capped at 2,000 photos, indexed) instead of a query per pin,
  and no Google Maps SDK in any form. A cluster tap zooms in two levels and takes the cluster apart; a
  photo tap opens the viewer at that photo, and the sheet's "View on map" centres the map on the one photo.

**What this does not do.** No restore, no downloading an original back, no "free up space" — those are
Phase 9 and nothing here pretends otherwise. No search, no People/Pets/OCR, no Locked Folder. Metadata is
read once per photo and never re-read, so editing a photo's GPS in another app is not noticed; a photo whose
read failed transiently stays pending rather than being recorded as having nothing. There is no merge ladder
between cell sizes — the antimeridian is the visible cost, stated rather than papered over: two photos on
either side of ±180° are two markers at world zoom, because a cell boundary is a cell boundary wherever it
falls. Rotation tags are not applied to a video's presentation, and the viewer does not offer a
frame-accurate scrub preview.

**Verified by CI, not by a phone:** nothing in this phase has run on a device. The gestures, whether a GIF
actually animates, whether a video surface appears or stays black, whether the permission dialog reads as
the explanation it is meant to be, and whether tiles render from a configured host are all the developer's
to confirm — the unit tests cover the arithmetic and the queries behind each of them, and none of the
pixels. Three warnings worth repeating before a first run: a tile host has to be configured *or* left
blank on purpose (see *Map tiles* — a build with no host plots every photo on a plain canvas and says
so); the map is empty until photos are opened or consent is given, because positions come from EXIF that
is read on demand; and a photo taken before this build has no
`date_taken_seconds` until the next scan sees it.

## Restore, free up space, background — Phase 9

**Phase 9 — restore, free up space and background backup:** complete and CI-verified green (run
[36195740042](https://github.com/FakeAbid11/LumoVault-2/actions/runs/36195740042), 408 unit tests across
44 classes, debug APK published). Phase 9 added seven test classes and 73 tests.

```
cloud record ──user taps Download──▶ TDLib original ─▶ hash what landed ─▶ MediaStore (pending → live)
                                                                          │
                                       queue row: backed_up ◀── scan ─────┘  (cloud message untouched)
```

- **One capture time, and restored files do not get one invented.** MediaStore's own dates apply until
  EXIF says otherwise; `date_seconds` on a cloud record is frequently the *message* date, and PRD section
  28 forbids presenting that as a birthday. The Phase 8 EXIF pass fills the real value in on first open.
- **The file is written as a pending row.** `IS_PENDING = 1` means the gallery, other scanners and every
  other reader cannot see it until the bytes are complete and their length matches; every path that gives up
  deletes the row it made. No permission is needed to create it, because on API 29+ an app owns what it
  inserts.
- **Verification is TDLib's completion flag, the file's own length, and a full write.** The manifest hash is
  compared and *reported*, never required to match: Telegram stores photos and videos in containers of its own,
  so a downloaded original differs from what left the device routinely, and treating that as a failure would
  refuse almost every restore while claiming a fidelity nobody has measured. The bytes that landed are what
  gets hashed and recorded.
- **A restored file is not uploaded again.** Its queue row is written `backed_up` against the message it was
  read out of — that is the evidence, and it is stronger than a hash match — so Phase 6 has nothing to offer.
  Before anything is fetched at all, the app asks whether the content is already local (by that association or
  by the manifest hash) and says so instead of writing a second copy.
- **Downloads are polled, not listened for.** `getFile` is an offline method, so asking it on a timer is
  cheap; the alternative was collecting `updateFile`, and that flow is `DROP_OLDEST` with no replay on
  purpose — fine for a thumbnail, fatal for the one update that says a 400 MB video has arrived. A transfer
  that stops moving is cancelled before it is reported, and TDLib is handed its own file id back.
- **Free Up Space offers only what is proven.** An item must be `backed_up`, name a chat and a message, carry
  a content hash, still be in the index, not be in Trash, and have its message still present in the cloud
  index — five conditions and a join, all in SQL, all aggregate: a headline number is never a window count.
  The list the user approves is **re-checked at confirmation**, and every refusal is returned with its reason
  so a skipped item can be explained. Deletion goes through Android's own consent request, exactly as Phase 7's
  Trash does; a dismiss deletes nothing, and below API 30 the screen says the file has to go in the device's
  photos app rather than pretending. Nothing in any of these paths can delete a Telegram message.
- **Automatic backup is the same queue.** A pass reads the grant live, reads the settings, scans, takes a
  window of candidates that match the selected folders, and hands them to `enqueue` — then asks for a worker
  **whenever the table holds anything**, not only when it added some: a queue a killed process left behind has
  nothing new to discover, and a rule keyed to this pass's own inserts would leave those rows waiting forever.
  It sends nothing itself, so hashing, manifests, duplicate detection and "a started request is not a backup"
  all still apply. A `cancelled` row is never re-queued, because that was a decision.
- **Scanning and sending are two workers with two constraint sets.** Noticing a new photo needs no network and
  no charger; uploading needs a network and whatever the user asked for. Bundling them would mean a phone off
  Wi-Fi stops noticing anything, which is not what "back up automatically" means. Wi-Fi-only and charging-only
  bind the unattended path and **never** a hand-tapped backup: the tap is the agreement. Changing either
  toggle re-installs the periodic work in the same action, because constraints live on the request.
- **Saving a folder selection starts work, in one place.** `ApplyBackupSelectionUseCase` is what both the
  onboarding picker and the Settings screen call: it writes the source and the normalized folder list (the
  same `backupEnabled` column the Settings toggle reads, so no hidden second switch stands between a chosen
  folder and a backup), withdraws the items a narrowed selection no longer covers **while they are still
  unsent**, re-installs the periodic pass and asks for one scan immediately — a one-shot under its own unique
  WorkManager name, because a six-hour period is not what "back up this folder" means to a person standing in
  front of the phone. Deselecting a folder takes its unsent rows back to merely-known; nothing claimed, sent,
  failed or cancelled is touched, and no Telegram message is.
- **Two screens read aggregates, never lists.** `BackupHealth` comes from counted states, a cloud-only count,
  `MAX(uploaded_at)` and the scan stamp; "everything is backed up" is only sayable when nothing is waiting,
  failed or unaccounted for. An empty library is not called safe. The periodic pass runs every six hours —
  WorkManager's floor is fifteen minutes and its default is twelve hours, and neither is what a user means.
- **Last scan is stored because it was not knowable.** `last_seen_scan_id` is a prune tag, not a time, and
  the Diagnostics panel was asked to answer "last scan". Writing 0 means "this build has not scanned yet",
  which the screen says in those words.
- **Restore is only ever the user's doing.** PRD section 25's rule survives Phase 9: nothing in the Cloud
  grid fetches an original while rendering, and the only path into the download layer starts at a tap.

**What this does not do.** No resumable download: an interrupted restore is settled and retried from the
start, because a half file in TDLib's cache is not a thing this app can verify. No restore into the folder a
file came from — the path is unknowable after a device change, so restored items live in `Pictures/LumoVault/`
and `Movies/LumoVault/`. No background *upload* beyond the periodic pass: no MediaStore `ContentObserver`,
no push, no persistent service — the brief's "eventually discover new media" is answered by a pass every six
hours plus the scans that already happen on foreground and on pull-to-refresh. Cloud-side media is still not
deleted from anywhere. Search, People, Pets, OCR, Locked Folder and any second storage backend remain out.

**Verified by CI, not by a phone:** the eligibility rules, the second check, the state machine, the queue
write that prevents a re-upload, the download request sequence and every count are covered by unit tests;
that a restored video actually plays, that it appears in the gallery, that the consent dialog reads as
intended, that the periodic pass survives a reboot, and that v8→v9 opens over an installed library are all
device questions and none has been asked.

**Runs:** 21 in Phase 9, of which the last is green and each earlier red one named a real defect — a package
that disagreed with its path, `ByteArray.startsWith` and `InputStream.readNBytes` (API 34 on a 29 floor), two
`vararg` overloads of one name, `in` on a `ConcurrentHashMap`, a companion object closed in the middle of its
body, a `private` extension three files wanted, two fakes sharing a name, and an unescaped apostrophe that
AAPT2 reported as an invalid unicode escape.

## Hardening and polish — Phase 10

**Phase 10 — polish, hardening, testing and the final debug APK:** complete from the implementation and CI
side (run 36201745573 at `43bff96`: **411 tests, 0 failed, 0 errors, 0 skipped across 44 classes**, debug APK
published at 39,501,790 bytes, `libtdjni.so` verified present for both `arm64-v8a` and `armeabi-v7a`). This
phase added no feature and removed no earlier one: three tests went in, none came out, and no assertion was
weakened.

**What only shows up at scale.** The Photos timeline and the album screens fed their whole loaded window into
a single `IN (…)`; Room binds one placeholder per element and the SQLite shipped with API 29 stops at 999
variables per statement, so scrolling a 1,200-item library to its fourth page was a crash rather than a slow
screen. Every id-list read and write reachable from a growable window now chunks against one shared ceiling
(`data/local/QueryChunk.kt`), including the Trash's "empty everything" sweep. Two passes claiming from the same
queue each moved a different row and then both read back *the newest one in `preparing`* — one photo uploaded
twice, another stranded — and that pair is now one transaction. `purgeStale` at start-up remains the backstop,
not the plan: a staged copy is deleted on every exit from an item's run, including the ones that threw.

**What only shows up when the app is not in the foreground.** The upload worker's status collector was an
infinite child of a `supervisorScope`, which waits for its children, so `doWork` never returned after the queue
drained and the `dataSync` foreground service held its notification until the system intervened. Manual and
unattended sends shared one unique work name, so a hand-tapped backup could join the chain waiting for Wi-Fi —
the outcome the backup preferences promise is impossible; they have separate names now, and the automatic pass
retries an absent media permission a bounded number of times instead of forever. A video page prepared its
decoder when the pager composed it rather than when it was looked at, nothing paused playback when the screen
went off, and a recreated player could be left with no surface at all. (All three of those were
`MediaPlayer`'s shape: the page has since moved to Media3, and the lifecycle rules added here — one active
page, one player, pause on stop, a fresh session on return — are the ones the current code still enforces.)

**What only shows up on the second visit or the fourth screen.** Four screens drew their own app bar while the
shell drew another one titled after the tab they were not on. The map was built per visit and only ever paused.
The cloud sheet had no response to the back gesture. The viewer's pre-answer frame was black with no visible
exit. Diagnostics seeded itself with a database at version 0 and a full disk before anything had been read, the
free-space review drew a spinner for "still loading" and "nothing came back" alike, and a device that refused a
deletion request reported itself as something the user had declined.

**Verified by CI, not by a phone.** Real-device validation was **not performed** in this phase either: no
device was attached at any point. What is covered by tests is the queue's and recognition's behaviour —
including the two duplicate rules the phase made mandatory: a settled item is neither enqueued nor claimed
again, and a restored file survives the recognition pass that runs straight after it rather than being revoked
and re-uploaded. What remains a device question, and is not claimed: that a restored video plays, that the
consent dialog reads as intended, that the periodic pass survives a reboot, that the timeline is smooth at
10,000 items, and that the map's markers land where the photo was taken.

**Runs:** 6 in Phase 10, of which the last is green and each red one named a real mistake of mine rather than a
flaky check — `combine` imported in one repository and not the next, `java.util.concurrent` one package away
from `java.util.concurrent.atomic`, a `try` left with neither `catch` nor `finally`, a top-level function moved
without importing it at the call site, a new parameter never passed, and an `MapView.destroy()` that osmdroid
6.1.20 does not have.

**Still true after Phase 10:** the Phase 9 limitations are unchanged — no resumable restore, restored files go
to `Pictures/LumoVault` and `Movies/LumoVault`, local-media discovery is periodic rather than push-based, and
the free-space review still shows 400 items per visit. Three more belong to this phase's scope decisions:
osmdroid's only teardown reachable from Compose is a pause, so a map view outlives the screen that made it
until the process ends; "empty Trash" still asks Android to name every item in one consent request, because
splitting it would hide files from the dialog that the dialog exists to show; and the reason a backup failed is
recorded but never drawn — the failure copy exists in `strings.xml` with no screen reading it, which is a
product decision rather than a hardening one.

## Reinstall recovery — finding the channel that already exists

A fresh install has nothing: no Room file, no DataStore, no TDLib database, no saved association. The only
record of what was ever backed up is the Telegram account and the private "LumoVault Backup" channel inside
it — so the first thing a reinstall has to get right is *finding that channel instead of building a new one*.

That is `SynchronizeCloudUseCase`, and the rule it now enforces is that **an empty answer is not the same
answer as absence**. Discovery runs in bounded rounds, and each round asks three things of TDLib:

- `searchChatsOnServer` — Telegram searching *this account's* own chats. This is what reaches a channel that
  the fresh local database has never heard of. It is not `searchPublicChats`, which is the internet's channel
  directory and could return a stranger's channel that merely shares the name; nothing in LumoVault calls it,
  and the storage channel stays private with no username, no publish step and nothing for a user to type in.
- `loadChats` — drives TDLib's chat list forward from the server, and its documented 404 is the only answer
  that says the list is complete.
- `searchChats` — TDLib's offline search, kept because on a warm cache it is one request and it is enough.

The outcome is a three-way choice, and only one of them may create anything:

| Discovery said | What happens |
| --- | --- |
| `Found(chatId)` | adopted after the full check, association saved, history scanned |
| `Absent` | list complete *and* the server answered cleanly in the same round — one channel is created |
| `InProgress` | TDLib still loading, or Telegram refused/paused/lost the request — retryable, and creation is refused |

A candidate is still adopted only on evidence, never on a name: broadcast type, this account's ownership and
a supported marker, each of which has a rejection case of its own. If two channels pass all of that — which
is what the old bug leaves behind — the one holding messages wins deterministically, ties break to the lower
chat id, and the other channel is left exactly as it was: nothing is deleted, migrated or renamed.

`logcat -s LumoVaultCloudRecovery` says which of these happened: `CLOUD_CHANNEL_ASSOCIATION_FOUND` (the saved
channel was reused, no search at all), `CLOUD_CHANNEL_DISCOVERY_STARTED`, `_CANDIDATE_FOUND`, `_VALIDATED`,
`_AMBIGUOUS`, `_ABSENT`, `_DISCOVERY_RETRY`, `_CREATION_ALLOWED`, `_CREATED`. Chat ids and event names only —
never a chat title, a path, a phone number or a TDLib object.

**Not verified on a device.** This is exactly the kind of fix that unit tests can describe and only Telegram
can settle: that `searchChatsOnServer` answers for a freshly-created private channel on a brand-new TDLib
database, and that the rounds are enough patience on a real network. Fifteen tests cover the state machine —
seven at the TDLib boundary in `TdLibCloudRepositoryTest`, eight in the use case —
adopt-over-impostor, retry-until-found, conclusive absence, failed search never read as absence, two-channel
resolution, cancellation leaving nothing behind — and none of them is a phone.

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

## Folder backup — what a saved selection starts

A folder chosen for backup should mean the photos in it get backed up — the ones already there, and the ones
added later, without the app being open when either happens. It did not reliably do that, and the reason was
not one bug at the top of the list but four along the path, none of them visible from the settings screen:

| | |
| --- | --- |
| **The save stopped at the settings row.** | `selected_folders` and `backup_enabled` were written and nothing else. The periodic pass had been installed — or cancelled — under the *previous* answer, so the new selection was noticed only when a process restart re-read the settings, which is why the same action sometimes worked. |
| **A queue that outlived its process had nobody left.** | The pass asked for a send only when it had *added* rows. After a kill or a reboot the old rows are still `queued` and there is nothing new to find — so the work that was already promised stopped being work. |
| **Deselecting a folder did half the job.** | It stopped new items being queued and left the rows already lined up to leave the phone exactly where they were. |
| **Two scans could prune each other.** | A scan tags the rows it saw and deletes everything tagged older. Only foreground triggers existed before; a save, a schedule and a chain of retries make an overlap ordinary, and an overlap loses index rows for files that exist. |

The shape of the fix is one place that decides what a selection change means —
`domain/usecase/ApplyBackupSelectionUseCase.kt`, which both onboarding's picker and the Settings screen now
call — plus `releaseUnsentOutside` on the queue, a resume rule on the pass, a one-shot scan on its own unique
WorkManager name, and a single-flight mutex around `sync()`.

Two rules kept their shape on purpose. A withdrawal only ever touches rows still waiting to be claimed:
nothing claimed, sent, failed or cancelled is altered, and no Telegram message is — a `backed_up` row is a
record of something that happened, not a queue entry to be tidied. And the immediate scan is a *scan*: the
bytes still leave through the existing worker under the existing Wi-Fi and charging constraints, so there is
one upload pipeline, one set of identity and duplicate rules, and a hand-tapped backup still waits for a
connection and nothing more.

**Not verified on a device.** Whether a folder's photos actually land in the LumoVault Backup channel, whether
Android runs the periodic pass on a given vendor's battery policy, and whether a photo taken while the app
was closed appears within the next six hours are device questions, and they are still that. What the tests
cover is the decisions: that a save schedules and triggers, that scope admits and excludes the right items,
that a selection change withdraws the unsent and only the unsent, that a window bigger than one batch drains,
that a denied permission leaves the queue waiting rather than finished, and that the unattended send waits for
exactly what the user ticked.

## Video playback is Media3, with LumoVault's own guards

The viewer's video page used to drive `android.media.MediaPlayer` directly. That worked until it did not:
`MediaPlayer` answers nearly every method with `IllegalStateException` when asked in the wrong state, including
from the callbacks it delivers on its own thread, where no `runCatching` in a composable is anywhere near the
stack. The crash fix that preceded this change added an application-level state machine to keep that from
reaching a user; this change swaps the engine underneath it for AndroidX Media3 1.11.1's ExoPlayer and keeps
the machine, because the machine was never the part that was wrong.

Three files hold the playback path now, and each has one job:

| | |
| --- | --- |
| `ui/viewer/VideoPlayback.kt` | The states a clip may be in — `Idle → Opening → Preparing → Ready → Playing → Paused → Failed → Released` — and which transition is legal from where. No Android types, so it is tested instead of hoped for. |
| `ui/viewer/VideoSession.kt` | One page's controller. Commands go through the machine before they reach a player; every report the player makes carries the generation it was produced under, and one that does not match is dropped without touching state. |
| `ui/viewer/ExoPlayerEngine.kt` | The only file in the app that names a Media3 type. Builds one `ExoPlayer` per visit, checks the `content://` URI before handing it over, maps `PlaybackException` codes into LumoVault's failure kinds, and releases once. |

`ui/viewer/VideoStage.kt` is left holding what only Compose can hold: one session per `(clip, visit)`, the
transport row, the poster for the page the user is not looking at, and the failure sentence.

**What Media3 took over.** The surface. `PlayerView` plus `Player.setVideoSurfaceView` means the library
registers the `SurfaceHolder.Callback` itself and detaches the video output before the surface is destroyed
(`ExoPlayerImpl` does this at its own `surfaceDestroyed`), so the page no longer keeps a holder, no longer
calls `setDisplay`, and no longer has a `SurfaceLost` failure to invent a word for. Everything else the old
page guarded is guarded the same way: only the visible page owns a player, a released page that comes back
gets a new one, an inactive pager page plays nothing, backgrounding pauses, and a source that will not open is
a named failure with a Close button rather than an asynchronous native error.

**What was traded.** `media3-exoplayer` brings six sibling modules, Guava and RecyclerView with it, which is
exactly why Phase 8 declined the library. The purchase is a decoder lifecycle that reports its own state
instead of exposing one that has to be handled carefully, and a playback path that behaves the same on devices
nobody here can attach. GIF animation is still `coil-gif`, thumbnails are still `coil-video`, and the poster
behind an offscreen page is still Coil — no GIF or photo code was touched, and no permission was added.

**Not claimed.** Nothing in this change has run on a device. Whether a given clip actually draws frames,
whether the aspect ratio looks right on a given screen, and whether a hardware decoder that misbehaves on one
phone behaves better here than `MediaPlayer` did are all questions a phone has to answer. What is covered by
tests is the state machine, the controller's rules about when a player may be touched, and the error mapping.

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
| Coil | 3.6.3 (`coil-compose`, `coil-video`, `coil-gif`; no network artifact) |
| androidx.exifinterface | 1.4.2 (not `android.media.ExifInterface`; reads a `FileDescriptor`) |
| osmdroid | 6.1.20 (`org.osmdroid:osmdroid-android`, no transitive dependencies) |
| Media3 | 1.11.1 (`media3-exoplayer`, `media3-ui`, `media3-common`; one version for all three) |
| TDLib | pinned revision `ea97bcd`, Java interface (`libtdjni.so`) |
| compileSdk / targetSdk / minSdk | 37 / 37 / 29 |

Versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). Kotlin stays on the 2.3
line because KSP has no 2.4.x release; moving Kotlin first would break Room's annotation processing.

Three dependencies were added deliberately, each for a job that hand-rolling would do worse:
libphonenumber (calling codes, example numbers, E.164 parsing) and Coil (thumbnail decode and cache
for `content://` URIs). TDLib contributes no dependency at all: its generated Java sources and its
`libtdjni.so` are build inputs fetched by CI, not artifacts resolved from a repository. Phase 8 added
three more, each for the same kind of reason: `coil-gif` (nothing else in Coil 3 animates a GIF, and a
still GIF is a wrong answer that looks right), `androidx.exifinterface` (the platform class misses HEIF
and PNG), and osmdroid (drawing slippy-map tiles well is a decade of cache, decode and gesture code that
is not this app's subject). Media3 was considered for video at Phase 8 and declined there for the weight of it;
it was added later, for the decoder lifecycle — see
[Video playback is Media3, with LumoVault's own guards](#video-playback-is-media3-with-lumovaults-own-guards).

## Architecture

```
app/src/main/java/com/lumovault/app/
├── LumoVaultApplication.kt   entry point; owns the AppContainer
├── AppContainer.kt           lazy dependencies + the application-scoped coroutine scope
├── MainActivity.kt           edge-to-edge host, nothing else
├── data/
│   ├── local/                Room database (v9) and its DAOs: settings, media index, cloud index,
│   │                         backup queue, albums + organisation, EXIF, restore jobs, MediaStore scanning
│   ├── metadata/             reads one photo's EXIF through a FileDescriptor
│   ├── media/                staged copies for upload, streamed content hashing, and the writer that
│   │                         files a restored original into MediaStore
│   ├── map/                  the tile provider, and the build inputs behind it
│   ├── backup/               the WorkManager queue runner and its notification
│   ├── remote/telegram/      TDLib's typed Client/TdApi layer, auth repository, credentials, error mapping
│   └── repository/           Room / libphonenumber / permission implementations
├── domain/
│   ├── model/                ThemeMode, Country, Media, MediaMetadata, SystemAlbum, onboarding state
│   │                         + checklist derivation
│   ├── organization/         Album, MediaOrganization repository interfaces
│   ├── metadata/             the EXIF facts, and the reader interface they are read into
│   ├── map/                  Web Mercator, clustering, the tile template, the viewer→map hand-off
│   ├── backup/               upload state, identity, hashing and staging interfaces
│   ├── repository/           Settings, Onboarding, Permission, Country, Media interfaces
│   ├── telegram/             TelegramAuthRepository, auth state, auth failure, code channel, manifest
│   └── usecase/              the flows that span repositories: cloud sync + recognition, the queue, the
│                             metadata extraction pass
└── ui/
    ├── LumoVaultRoot.kt      launch decision: onboarding or main
    ├── LumoVaultApp.kt       the four-tab shell, which stands down on the chrome the viewer owns
    ├── onboarding/           the six screens, their flow host, and flow state
    ├── navigation/           main destinations, routes, and the viewer's route
    ├── viewer/               the pager, its three renderers, the details sheet, and the zoom arithmetic
    ├── backup/               the backup and storage screens, the health aggregate and the diagnostics panel
    ├── map/                  the osmdroid screen, its pins and its preview strip
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
- **Room v9, upgraded by hand-written migrations only.** Phase 1 shipped a v1 the first cloud build
  rejected (an entity-free `@Database` is illegal), so PRD section 61's `UserSettings` row became the first
  entity. Every step since is explicit — settings fields, `media`, the cloud index, `backup_queue`, its
  Phase 6 identity columns, Phase 7's three organisation tables, Phase 8's `media_metadata` with the
  capture-time column on `media`, and Phase 9's `media_restore` with the three backup-preference columns
  — and each one mirrors the DDL Room compiles, because a schema the entities
  describe and no migration produces is a crash on upgrade rather than a build failure.
  `fallbackToDestructiveMigration` appears nowhere.
- **Nothing cascades from `media`.** The index is rewritten with `INSERT OR REPLACE` on every scan, so a
  child table with a cascading foreign key on it is emptied on every sync. `backup_queue`, `album_media`,
  `media_organization` and `media_metadata` therefore relate to media by id and a join, with an explicit
  sweep for orphans once a scan has decided what exists.
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
