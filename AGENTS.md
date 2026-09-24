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
  actually reads, as a real migration, with the schema exported to `app/schemas`.
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
user-visible strings (`TdErrorMapper` is the boundary).

## Version pinning

Versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). Kotlin is held on the 2.3
line because KSP has no 2.4.x release; advancing Kotlin past KSP breaks Room's annotation processor.
Since nothing can be compiled locally, change versions by reading real metadata (Google Maven
`group-index.xml`, Maven Central, Gradle's release feed, a library's `.module` `kotlin-stdlib`
requires) rather than from memory.

## Git

`gradlew` must keep mode 100755 and LF endings, or the Linux runner cannot execute it;
`.gitattributes` enforces this. The wrapper jar is checksum-pinned to Gradle's published value.
Push only when the work is meant to be built.
