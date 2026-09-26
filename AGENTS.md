# LumoVault — instructions for agents

Native Android app: Kotlin + Jetpack Compose + Material 3. [`prd.md`](prd.md) is the master
specification and the source of truth. Read it before implementing anything.

## Never build locally

The developer's machine cannot compile Android. Do not run `./gradlew` at all — no `assembleDebug`,
no `build`, no `test`, no Android Studio build. GitHub Actions
([`.github/workflows/build.yml`](.github/workflows/build.yml)) is the only thing that compiles this
project. Validate locally with static checks only (imports, package/path agreement, resource
references, Gradle and YAML inspection), then push and read the CI result.

Never report a successful build without a green run to point at.

## Phase discipline

Implemented in 10 phases (PRD section 80). Implement only the assigned phase. Preserve earlier
working code rather than regenerating it, and if something in the tree appears to contradict
`prd.md`, inspect it, keep what is deliberate, and report the conflict instead of silently rewriting
it.

Rules that have already caused a mistake here:

- **Room is the single source of persisted state.** Do not add DataStore or SharedPreferences
  alongside it.
- **No placeholder schema.** Phase 1 shipped `@Database(entities = [])` and the first CI run rejected
  it (`@Database annotation must specify list of entities`). Add only the PRD-named columns a phase
  actually reads, as a real migration. `room.schemaLocation` is configured, but the JSON it exports
  exists only in the runner's workspace — nothing is generated here, so a hand-written `CREATE TABLE`
  is **not** machine-checked against Room's own DDL. A mismatch surfaces as a schema-validation crash
  when an existing install opens the database, not as a red build: mirror Room's column order,
  nullability and defaults exactly and say so in the migration's comment.
- **No pass-through layers.** `domain/usecase/` stays absent until logic spans two repositories.
  DI is a small hand-written container (`AppContainer.kt`) until WorkManager needs injection.
- **Report live state, not remembered state.** Permissions can be revoked outside the app; read them
  from the system each time the screen is shown.
- **Never fake a success state in UI.** If a capability is unavailable in a build, it must say so.
- **Room gives an `INSERT` no row count.** An `@Query` insert that returns `Int` fails KSP with
  "INSERT query functions must either return void or long"; count rows on either side of the statement
  instead. `UPDATE`/`DELETE` may return `Int`. `INSERT … ON CONFLICT(pk) DO UPDATE` compiles and is the
  right shape for a write that must touch only some columns — `@Upsert` replaces the whole row, so it
  cannot express "record the hash, leave the state this worker owns alone".
- **New entity columns go last.** `ALTER TABLE … ADD COLUMN` can only append, so a column declared in the
  middle of an entity leaves a fresh install and an upgraded one disagreeing about column order. Phase 6's
  `content_hash` and its snapshot columns are appended in both `backup_queue` and `cloud_media`, and the
  hand-written migration lists them in the entity's order.
- **Never hang a cascading foreign key off `media`.** Room's `@Upsert` is `INSERT OR REPLACE`, which
  deletes and re-inserts the row, so a child that cascades from a table the scanner rewrites is emptied on
  every sync — an album that vanishes after a pull-to-refresh is this, not a bug in the album code. New
  relations to media are therefore keyed by id and joined, with the cleanup written as an explicit sweep
  that runs once per scan (`… WHERE media_store_id NOT IN (SELECT media_store_id FROM media)`, never a
  `NOT IN (:ids)` list a large library would blow past SQLite's parameter limit). `media_metadata`
  (Phase 8's EXIF) follows the same rule: an extracted read survives a scan and is swept after a prune.
- **EXIF coordinates are read, never inferred, and a null is a fact with a shelf life.** `media` is
  rewritten by every scan, so GPS goes in `media_metadata`, not a `media` column. Reading it needs the
  runtime `ACCESS_MEDIA_LOCATION` permission — without it MediaProvider serves a *redacted* file whose GPS
  block is simply absent, and `MediaStore.setRequireOriginal` throws `UnsupportedOperationException` when
  the uri is opened. So: only record what a read actually found, and when the grant arrives, discard the
  stored "nothing there" reads (`DELETE … WHERE latitude IS NULL`), or the map stays empty forever.
  `Images.ImageColumns.LATITUDE/LONGITUDE` are deprecated since 29 and always null — do not "fix" the
  reader by reaching for them.
- **Two `vararg` overloads of one name break the whole call group.** `fun ByteArray.hasPrefix(vararg Int)`
  and `…(vararg Char)` compile separately; used together, every call site is reported as
  "None of the following candidates is applicable" with mismatches pointing at arguments that were never
  passed. One spelling, and `.code` at the call site. Relatedly: `InputStream.readNBytes` is API 34 and
  `ByteArray` has no `startsWith` — check the API level of any `java.io` call this app makes on a 29 floor.
- **`(key to value) in concurrentMap` is an error here, not a warning.** `ConcurrentHashMap` inherits a Java
  `contains` that means `containsValue`, so Kotlin's `in` operator is ambiguous and the compiler refuses it
  (KT-18053). Write `containsKey(…)`.
- **A `@Test` must be public and must return void.** JUnit reports a private or non-void test as
  `initializationError` for the *whole class*, so thirteen tests can vanish behind one `= runBlocking {`
  whose last expression is an `assertThrows` (it returns the throwable). Write `runBlocking<Unit>`.
- **A `strings.xml` apostrophe has to be escaped, and AAPT2 blames something else.** `\'` — an unescaped
  one is reported as `Invalid unicode escape sequence in string`, which sends you looking for a `\u` that
  is not there. Same file, same class of miss: `…` is fine, `%` is not (it becomes a format specifier).
- **Phase 9's rule, in one line: a download is verified by TDLib's completion flag, the file's own length
  and a full MediaStore write — not by the manifest hash.** Telegram stores photos and videos in containers of
  its own, so the hash of what comes back differs from what went out, routinely. Writing the manifest's hash as
  the restored file's identity, or refusing a restore because the two disagree, are both wrong in ways that look
  careful. Record the hash of the bytes that landed; compare and *report* the manifest's.
- **Automatic backup goes through the queue, and the queue's states are the contract.** Enqueue, and the
  existing worker sends. A second upload path would bypass hashing, the manifest and duplicate detection, and
  the two paths would disagree about what `backed_up` means. `cancelled` is not a backlog: never re-queue it.
- **Wi-Fi-only and charging-only bind the unattended pass and never a hand-tapped backup.** The tap is the
  agreement; preferences applied to it would leave a button doing nothing on mobile. Changing either toggle
  re-installs the periodic work, because constraints live on the WorkManager request, not on the row.
- **A restored file is settled against the message it came from**, or Phase 6 correctly treats it as new
  content and uploads the user's own download back to them. Free Up Space re-checks eligibility at
  confirmation rather than trusting the review list, and deletes only through
  `MediaStore.createDeleteRequest` — never by a `File.delete()` on media the app does not own.
- **`TdApi.Object.toString()` is a *native* method.** Never interpolate a TDLib object into a string — not a
  log line, and not a test's assertion message either. Off-device there is no library to answer, so the
  message throws `UnsatisfiedLinkError` and a test that would have reported one wrong value reports a
  linkage crash instead (`eachKindOfMediaIsAskedForUnderTheTypeItWasStoredAs` did exactly this). Print
  `?.javaClass?.simpleName`, or the field you actually care about. Kotlin data classes that *contain* only
  Kotlin values are safe; `assertEquals(aTdApiFile, anotherTdApiFile)` is not.
- **Kotlin's overload errors concentrate in the two places a script decides.** In `app/build.gradle.kts`:
  `const val` is illegal at script top level, and `String.filter { s -> s.contains("{z}") }` binds `s` to a
  **Char**, so the predicate body fails to resolve on a value that is obviously a string — use
  `takeIf`/`let` over the whole value. In generic stdlib selectors, a bound `Map<Long, Long>::get` is
  `(Long) -> Long?` and does **not** satisfy `maxOfOrNull(selector: (T) -> R)` where `R : Comparable<R>`;
  write `{ id -> map[id] ?: 0L }`. Both are compile errors that no static check catches locally.

- **A red CI test has to explain itself.** `app/build.gradle.kts` sets `exceptionFormat = FULL` on failed
  tests, because GitHub Actions is the only place anything runs: Gradle's default one-line summary prints
  neither the assertion message nor the values, so without it each failure costs a whole build cycle.
- **`<provider>`, `<service>` and `<activity>` go inside `<application>`.** As a direct child of
  `<manifest>` AAPT rejects the build with "unexpected element".
- **An `IN (…)` list has a ceiling, and a growable window will reach it.** Room binds one placeholder per
  element, and the SQLite shipped with API 29 stops at 999 variables per statement — so every read or write
  that takes a collection of ids has to chunk against `data/local/QueryChunk.kt`'s `MAX_IDS_PER_QUERY`. A
  timeline window starts at 300 and only ever gets bigger, which makes this a fourth-page-of-scrolling crash
  on a 1,200-photo library, not a theoretical one. A DAO method taking `List<Entity>` for `@Upsert`/`@Insert`
  is *not* affected: Room compiles that to one statement executed in a loop.
- **Manual and unattended work must not share a unique work name.** `APPEND_OR_REPLACE` on one name puts a
  hand-tapped backup into the chain the unattended pass already occupies, and a chain waits for the
  constraints of the work inside it — so "Wi-Fi only" began blocking a backup the user started on mobile
  data, which is the one thing `BackupPreferences` promises cannot happen.
- **A `CoroutineWorker` that launches an infinite collector as a child of `supervisorScope` never finishes
  `doWork`.** The scope waits for its children and a Room flow has no last value, so the foreground service
  and its notification outlive the queue. Keep the `Job` and cancel it in a `finally`.
- **Delete the whole expression, not just the clause.** Removing a `catch` from `val x = try { … } catch …`
  leaves a bare `try`, which is a Kotlin syntax error, not a style one; and moving a top-level function to
  another file leaves every previous same-package call site needing an `import`. Both were CI's to find.
- **A claim that names an API is a claim to check.** Phase 10's audits asked for `MapView.destroy()` — which
  osmdroid 6.1.20 does not have (`onPause`, `onResume`, `onDetach`, `setDestroyMode` are the whole surface,
  read from the artifact's sources jar) — and for `combining(Iterable<Flow<T>>)`, which kotlinx-coroutines
  1.11.0 does not either; the collection overload is the ordinary `combine(flows) { Array<T> -> R }`, while
  `debounce` is still `@FlowPreview` and `sample` is not.

- **An empty Telegram answer is not an answer about Telegram.** `searchChats` is *offline*: on a fresh
  install TDLib knows no chats, so it returns nothing, and `SynchronizeCloudUseCase` used to read that as
  "this account has no storage channel" and create one — orphaning every backup in the real channel, which
  no later sync repairs because the association now points at the empty new one. Discovery therefore returns
  found / conclusively-absent / could-not-conclude, and only the middle one may create anything. Absence means
  `loadChats` reported its documented 404 *and* `searchChatsOnServer` answered cleanly in the same round.
  Related: TDLib's two request classes that share a field name have no common typed supertype — building
  `if (x) TdApi.SearchChatsOnServer() else TdApi.SearchChats()` and then setting `.query` does not compile,
  because the inferred type is `Function` and `Function` has no fields.

- **A new case in a sealed interface is a compile error at every `when` that reads it.** Kotlin reports the
  first one and stops, so the fix is to grep the type's name for `when (` before pushing — adding
  `ViewerTarget.Folder` costs one branch in `MediaViewerViewModel.contentsOf` and one in
  `AlbumDetailViewModel.contentsOf`, and CI only names the first. Nothing in the local checks can see it.

- **Two kinds of album, two identities, never one table.** A user album is a stored list — `albums` plus
  `album_media`, keyed by a row id. A device folder is a derivation — `GROUP BY media.relative_path`, keyed
  by the normalized relative path, with `SystemAlbum.covers` deciding which folders the Library section
  already claims. Inserting a folder into `albums` because it exists, giving one a synthetic `albumId`
  (negative, huge, hashed), or addressing a folder by a `LIKE` prefix instead of equality — which would
  merge `Pictures/WhatsApp/` with `Pictures/WhatsApp/Images/` — each break a different one of those
  invariants, and all three look like a shortcut.

- **Grep back every `R.string` you add; an unreferenced one is usually an unwired feature.** AAPT does not
  complain about dead copy, so the miss surfaces as a screen with the wrong text rather than as a red
  build. Phase 7's first pass had a string no Kotlin read *and* one that was read — the add-media sheet was
  titled "New album". `strings.xml` well-formedness and `R.string.*` / `R.plurals.*` resolution are both
  checkable without compiling; run them before pushing.

## Secrets and sensitive data

Telegram `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` are build inputs supplied via Gradle properties or
environment variables and exposed through `BuildConfig`; absent values must produce a clear
"not configured" state, never a crash and never a placeholder that looks real. Never commit or log a
credential, api hash, session value, phone number, verification code or password. `TelegramCredentials`
overrides `toString` for this reason — keep it that way, and keep raw TDLib error text out of
user-visible strings (`TdErrorMapper` is the boundary). Local media metadata is personal too: every
logging call in the app records an exception's class name or a digit-masked message, never a MediaStore
message, a file path or a TDLib object.

## Toolchain

| Component | Version | Notes |
| --- | --- | --- |
| Kotlin / KSP | 2.3.21 / 2.3.12 | KSP has no 2.4.x release; do not advance Kotlin past it |
| Compose BOM | 2026.09.00 | Material 3 1.4.0, ui 1.12.1 |
| Room | 2.8.5 | KSP processor; schema export on |
| Coil | 3.6.3 | `coil-compose` + `coil-video` + `coil-gif`; no network artifact. **Without `coil-gif` an animated GIF does not animate** — it registers `AnimatedImageDecoder` through ServiceLoader, so no ImageLoader setup is needed |
| androidx.exifinterface | 1.4.2 | NOT `android.media.ExifInterface`: the platform class does not parse HEIF/HEIC or PNG containers. `ExifInterface(FileDescriptor)` reads a header, not a file |
| osmdroid | 6.1.20 | `org.osmdroid:osmdroid-android`; its POM declares **no** dependencies. No clustering anywhere in the library, so pins are clustered in `domain/map/MapClustering.kt` |
| libphonenumber | 9.0.40 | country codes + E.164 |
| WorkManager | 2.12.0 | `work-runtime`; CoroutineWorker + ForegroundInfo for the backup queue |
| compileSdk / targetSdk / **minSdk** | 37 / 37 / **29** | see below |

**minSdk is 29 on purpose.** `MediaStore.Files`, `RELATIVE_PATH` and `IS_PENDING` all begin at API
29, so the scanner is one query with no version branches. Supporting API 26-28 would require
guessing which columns the platform returns on those versions — which nothing here can verify.
Do not lower it without re-checking that reasoning.

## Verify third-party APIs before using them

Nothing in this project can be compiled locally, so an API remembered wrongly becomes a red CI run
(or, worse, a runtime crash that tests cannot catch because the contract was never exercised).
Before writing code against TDLib, Coil, Material 3, MediaStore or androidx, check the real thing:

- Artifact lists and versions: Google Maven `group-index.xml`, Maven Central directory listings.
- **Class and method names: download the artifact's published `-sources.jar` for the exact version
  and read the declarations.** Coil's and Material 3's sources settled questions memory got wrong;
  guessing about TDLib instead cost three failed builds.
- TDLib method and field names come from
  `td/generate/scheme/td_api.tl` — check it, and re-check against the tag the binary is pinned to.
- **What you read in `td_api.tl` is not what the Java interface calls them.** `td/generate/tl_writer_java.cpp`
  decides the generated spelling: a TL constructor becomes a class with a capital first letter
  (`authorizationStateWaitCode` → `TdApi.AuthorizationStateWaitCode`) and each field is camelCased
  (`code_info` → `codeInfo`), so a snake_case field copied straight out of the scheme does not compile.
  Every generated class also has a no-argument constructor alongside the full one, which is the safer
  way to build a request with many nullable fields.
- An empty grep output is not confirmation. If a lookup returns nothing, say so; do not report the
  value as verified.

## Git

`gradlew` must keep mode 100755 and LF endings, or the Linux runner cannot execute it;
`.gitattributes` enforces this. The wrapper jar is checksum-pinned to Gradle's published value.
Push only when the work is meant to be built.
