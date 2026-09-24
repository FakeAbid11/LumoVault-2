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

## Secrets and sensitive data

Telegram `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` are build inputs supplied via Gradle properties or
environment variables and exposed through `BuildConfig`; absent values must produce a clear
"not configured" state, never a crash and never a placeholder that looks real. Never commit or log a
credential, api hash, session value, phone number, verification code or password. `TelegramCredentials`
overrides `toString` for this reason — keep it that way, and keep raw TDLib error text out of
user-visible strings (`TdErrorMapper` is the boundary). Local media metadata is personal too: the one
logging call in the app records an exception's class name, never a MediaStore message or file path.

## Toolchain

| Component | Version | Notes |
| --- | --- | --- |
| Kotlin / KSP | 2.3.21 / 2.3.12 | KSP has no 2.4.x release; do not advance Kotlin past it |
| Compose BOM | 2026.09.00 | Material 3 1.4.0, ui 1.12.1 |
| Room | 2.8.5 | KSP processor; schema export on |
| Coil | 3.6.3 | `coil-compose` + `coil-video`, no network artifact |
| libphonenumber | 9.0.40 | country codes + E.164 |
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
- An empty grep output is not confirmation. If a lookup returns nothing, say so; do not report the
  value as verified.

## Git

`gradlew` must keep mode 100755 and LF endings, or the Linux runner cannot execute it;
`.gitattributes` enforces this. The wrapper jar is checksum-pinned to Gradle's published value.
Push only when the work is meant to be built.
