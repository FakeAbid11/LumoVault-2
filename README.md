# LumoVault-Android

Native Android rewrite of [LumoVault](../LumoVault-main) — a photo/video backup
app that stores media in a user-owned Telegram channel via TDLib.

Stack: Jetpack Compose, Hilt, Room, WorkManager, ONNX Runtime, Coil, Media3,
Navigation Compose.

## Status

Active rewrite. The TDLib substrate (client, connection manager, auth), the
metadata sync engine, the Room layer, the upload queue, and the gallery pipeline
are ported and unit-tested. See the plan in the parent project's notes for the
phase breakdown.

## Requirements

- Android SDK 35, build-tools 35.0.0
- JDK 17
- Gradle 8.11.1 (wrapper included)
- NDK is **not** required — TDLib is prebuilt and committed under
  `tdlib-prebuilt/` by `.github/workflows/tdlib-build.yml`

## Telegram API credentials

`BuildConfig.TELEGRAM_API_ID` / `TELEGRAM_API_HASH` are read from Gradle
properties and default to empty. **Do not put them in the repo's
`gradle.properties` — that file is tracked.** Set them in either:

- `~/.gradle/gradle.properties` (user-level, outside the repo), or
- repository Actions secrets `TELEGRAM_API_ID` / `TELEGRAM_API_HASH`, which the
  CI workflow writes to the runner's `~/.gradle/gradle.properties`.

Until they are set, `TdLibConfig.hasCredentials` is false and the client fails
with a clear `API_ID_INVALID` instead of an obscure TDLib error.

## Building

```
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

## Model assets

The five ONNX models (~151 MB) and the CLIP tokenizer live in
`app/src/main/assets/` and are bundled in the APK, matching the Flutter app.
They are copied verbatim from the Flutter project's `assets/` directory and
must not be regenerated from `tool/export_*.py` — those scripts target
MobileCLIP-S1 and do not reproduce the shipped MobileCLIP2-S0 files.
