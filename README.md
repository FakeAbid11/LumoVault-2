# LumoVault

A private Android photo, video and GIF library with cloud backup powered by the user's own
Telegram account. Your photos. Your storage.

[`prd.md`](prd.md) is the master specification and the source of truth for every product decision.
This README records build and architecture status only.

## Status

**Phase 1 — foundation:** complete, and verified by a green cloud build
(run [36024569535](https://github.com/FakeAbid11/LumoVault-2/actions/runs/36024569535), debug APK
published as an artifact).

**Phase 2 — onboarding + Telegram authentication:** implemented; see "Build status" below for
whether CI has confirmed it.

Built in Phase 2:

- The six onboarding screens, in PRD section 32's order, with Permissions/Notifications/Background
  combined onto one screen as the PRD requires
- A searchable country selector over the full ISO region list, with flags drawn from the ISO code
- Phone entry that normalizes to E.164 through libphonenumber rather than string concatenation
- A Telegram authentication state machine: phone → code → (2FA password) → authenticated, driven by
  what TDLib reports and never by which button was pressed
- Nine distinct human-readable authentication failures, including Telegram's rate-limit windows
- Onboarding completion, backup-source choice and folder selection persisted in Room
- Live media/notification/battery status, re-read on resume rather than remembered
- Launch logic that opens onboarding or the Photos shell, with the theme applied to both

**Not built yet, by design:** the TDLib native binary and its JNI binding (see *Telegram status*),
channel discovery and the Cloud library (Phase 4), the media scanner (Phase 3), the backup engine
and hashing (Phases 5-6), map, albums, favorites, archive, trash, and Settings.

## Build in the cloud — never locally

The development machine is not expected to compile Android. Do not run `gradlew assembleDebug`,
`gradlew build`, `gradlew test`, or an Android Studio build locally.

```
commit + push  →  GitHub Actions  →  assembleDebug + testDebugUnitTest  →  LumoVault-debug-apk
```

`.github/workflows/build.yml` installs SDK platform 37, assembles the debug APK and runs the JVM
unit tests, then uploads `app-debug.apk` as an artifact (30 days). Download it from the run's
**Artifacts** section. A workflow run that has not gone green is not a build.

Phase 2 did not change the workflow: nothing new needs a CI-side tool.

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

To build a signed-in-capable APK once the binary lands:

```
-PPTELEGRAM_API_ID=... -PTELEGRAM_API_HASH=...
```

## Telegram status — what is real and what is deferred

The client is TDLib, behind interfaces:

```
UI → TelegramAuthRepository → TelegramClient → TdLibNative → (libtdjson + JNI shim, not yet built)
```

Already implemented and unit-tested off-device: JSON request/response correlation over `@extra`,
the authorization-state mapping, error mapping including `FLOOD_WAIT_<n>`, and the flow rules that
decide which screen appears.

**The remaining seam is the native binary.** TDLib ships no Android artifact and nothing on Maven;
`example/android/build-tdlib.sh` cross-compiles OpenSSL and TDLib with the NDK. Until that lands and
a forwarding JNI library exposes `td_json_client_*` under names Kotlin can bind to, `isUsable` is
false, authentication is honestly reported as unavailable, and **Phase 2's acceptance criterion
"Telegram authentication works" is not met by this commit**. The Ready screen shows Telegram as
*Unavailable* rather than ticking it, and no screen pretends otherwise.

Because TDLib's JSON schema evolves between releases, the exact parameter field names in
`TelegramAuthRepositoryImpl.tdlibParameters()` must be checked against the pinned TDLib version's
`td_api.json` when the binary is built. They are kept in one function so that correction is local.

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
| kotlinx-serialization | 1.11.0 (JSON, `JsonElement` API only) |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |

Versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). Kotlin stays on the 2.3
line because KSP has no 2.4.x release; moving Kotlin first would break Room's annotation processing.

Two dependencies were added deliberately, each for a job that hand-rolling would do worse:
libphonenumber (calling codes, example numbers, E.164 parsing) and kotlinx-serialization-json
(TDLib's JSON interface, testable off-device via `JsonElement` — no compiler plugin required).

## Architecture

```
app/src/main/java/com/lumovault/app/
├── LumoVaultApplication.kt   entry point; owns the AppContainer
├── AppContainer.kt           lazy dependencies + the application-scoped coroutine scope
├── MainActivity.kt           edge-to-edge host, nothing else
├── data/
│   ├── local/                Room database, UserSettings row, DAO, single-writer store
│   ├── remote/telegram/      TDLib JSON client, auth repository, credentials, error mapping
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
preference mechanism. `domain/usecase/` remains absent on purpose: nothing yet needs logic spanning
two repositories, and a pass-through wrapper would only add a hop.

Decisions worth knowing about:

- **One settings row, one writer.** `AppSettingsStore` performs every change as a
  read-modify-write inside a transaction, so the theme toggle and the onboarding flow sharing one
  row cannot overwrite each other.
- **Room v2.** Phase 1 shipped a v1 the first cloud build rejected (an entity-free `@Database` is
  illegal), so PRD section 61's `UserSettings` row became the first entity, and Phase 2's fields are
  an explicit `MIGRATION_1_2` against the schema exported to `app/schemas`.
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
