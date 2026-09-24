# LumoVault

A private Android photo, video and GIF library with cloud backup powered by the user's own
Telegram account. Your photos. Your storage.

[`prd.md`](prd.md) is the master specification and the source of truth for every product
decision. This README only records build and architecture status.

## Status: Phase 1 — Project Foundation

The application currently opens into the four primary destinations with placeholder content.
Nothing reads MediaStore, Telegram or the file system yet.

Built here:

- Kotlin + Jetpack Compose + Material 3 single-module app (`com.lumovault.app`)
- Navigation shell: **Photos | Albums | Cloud | Map**, state preserved across tab switches
- Dark/light theme foundation with a persisted theme preference (System / Light / Dark)
- Layered architecture: UI → ViewModel → domain repository interface → data source
- Room + coroutine foundations, wired but intentionally without entities
- GitHub Actions cloud build producing a debug APK artifact

Deliberately **not** built here (later phases per PRD section 80): onboarding, Telegram
authentication, MediaStore scanning, backup engine, hashing, Cloud library, Map, albums,
favorites, archive, trash, restore, Settings.

## Build in the cloud — never locally

The development machine is not expected to compile Android. Do not run `gradlew assembleDebug`,
`gradlew build` or an Android Studio build locally.

```
commit + push  →  GitHub Actions  →  assembleDebug + testDebugUnitTest  →  LumoVault-debug-apk
```

`.github/workflows/build.yml` installs the SDK platform, assembles the debug APK and runs the JVM
unit tests, then uploads `app-debug.apk` as a workflow artifact (retained 30 days). Download it
from the run's **Artifacts** section and install it on the device. A workflow run that has not
gone green is not a build.

No secrets are stored in the repository. Telegram API credentials and session material are
configured through GitHub Actions secrets when Phase 2 introduces them; nothing in Phase 1 reads
them.

## Toolchain

| Component | Version |
| --- | --- |
| Android Gradle Plugin | 9.4.1 |
| Gradle | 9.7.1 (wrapper jar checksum-pinned) |
| Kotlin | 2.3.21 (AGP 9 built-in Kotlin; no `kotlin-android` plugin) |
| KSP | 2.3.12 |
| Compose BOM | 2026.09.00 (Material 3) |
| Room | 2.8.5 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |

Versions are centralized in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).
Kotlin stays on the 2.3 line because KSP has no 2.4.x release; bumping Kotlin ahead of KSP would
break Room's annotation processing.

## Architecture

```
app/src/main/java/com/lumovault/app/
├── LumoVaultApplication.kt   entry point; owns the AppContainer
├── AppContainer.kt           lazy, hand-written dependency graph
├── MainActivity.kt           edge-to-edge host, theme + navigation only
├── data/
│   ├── local/                Room database holder (no entities in Phase 1)
│   └── repository/           DataStore-backed implementations
├── domain/
│   ├── model/                ThemeMode
│   └── repository/           SettingsRepository (interface)
└── ui/
    ├── LumoVaultApp.kt       scaffold: top bar + NavigationBar + NavHost
    ├── LumoVaultViewModel.kt screen state as StateFlow
    ├── navigation/           destinations, routes, NavHost
    ├── theme/                Color.kt, Theme.kt, Type.kt
    ├── components/           shared composables
    └── screens/              Photos, Albums, Cloud, Map
```

Composables never touch a database, network or file storage. Business logic lives below the UI
layer; the Activity only hosts Compose. `domain/usecase/` is intentionally absent until there is
logic that needs more than one repository — a pass-through wrapper would only add a hop.

Two decisions worth knowing about:

- **Dependency injection is a three-node hand-written container.** Hilt (or similar) is justified
  when Phase 4's Telegram session and Phase 5's WorkManager workers need graph-wide scoping, not
  now.
- **Room ships entity-free.** The media and backup schemas belong to Phases 3 and 6 (PRD section
  61); guessing columns now would create a migration whose only job is to delete the guesses.
  Schema export turns on together with the first entity.

## Validation performed

Static only: package declarations against file paths, imports against declared dependencies,
resource references, navigation routes, workflow YAML. The Gradle build itself has only one
executor — GitHub Actions.
