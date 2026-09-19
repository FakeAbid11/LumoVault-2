# Phase 3/4 port status

Phase 3 ports the Flutter app's screens. Phase 4 ports the engines: backup,
restore, and the device-folder detail screen. Every screen below is wired into
`NavGraph.kt` and resolves to real Compose, not the
`NotImplementedScreen` stub.

## Routes resolved (34 of 34)

| Area | Routes | Notes |
| --- | --- | --- |
| Onboarding | welcome, permissions, background-permissions, folders, telegram | Chained flow; Telegram route doubles as `connect-telegram` |
| Gallery | timeline, media_viewer, favorites, hidden, archive, trash, duplicates, search, map | Viewer pages the collection the grid passed |
| Albums | albums, albums/{albumId}, albums/folder/{bucketId} | Device folders from MediaStore buckets |
| People | people, people/{personId} | Scans disabled until the engine phase |
| Backup | backup, backup/settings, backup/stats | **Live engine**: Start enqueues the worker |
| Restore | restore, restore/progress | **Live engine**: scan → download → rebuild |
| Settings | settings + 10 sub-screens | Hub, account, about, general, media, storage, storage-insights, appearance, privacy, notifications, developer |

## Phase 4: engines

- **Backup engine** (`features/backup/data/service/BackupEngine.kt`): hashes
  pending rows (SHA-256, streamed from MediaStore), dedups against rows
  already uploaded, uploads original files as *documents* (never recompressed)
  into the user's private "LumoVault Backup" channel, and carries the
  [CaptionMetadata](WIRE-FORMAT.md) JSON as the message caption. Folder and
  media-type settings are applied at pick-up; de-selected rows are excluded,
  fixing the Flutter engine's "kept uploading de-selected tasks" defect.
- **Backup worker** (`features/backup/data/work/BackupWorker.kt`): Hilt
  `CoroutineWorker`; periodic every 6 h plus a manual one-shot from the
  dashboard. Wi-Fi-only is a WorkManager network constraint; the schedule is
  re-synced on every settings change.
- **Restore engine** (`features/restore/data/service/RestoreEngine.kt` +
  `RestoreController.kt`): scans the channel history, downloads each file via
  TDLib, rebuilds catalog rows from the caption JSON (hash-matching existing
  rows first), and best-effort exports into the user gallery. Reads the same
  caption wire format as the Flutter app, so backups are portable across both.
- **Storage channel** (`core/tdlib/StorageChannelService.kt`): verifies the
  cached channel id, falls back to a local/server search, and creates the
  channel on first run.

## Defects fixed rather than ported

These were real bugs in the Flutter original; the port deliberately does not
reproduce them.

| Original defect | Where it was | How the port avoids it |
| --- | --- | --- |
| `reconnect()` was a no-op after a transient failure (status flipped to `reconnecting` before the timer fired, so the close was skipped and `initialize()` early-returned) | `tdlib_connection_manager.dart` | `disconnect()` closes the client; the ladder can climb past rung 1 |
| 2FA accounts "connected" while TDLib sat in `WaitPassword` | `telegram_connect_screen.dart` | Repository subscribes to the state flow *before* sending; only `AuthResult.PasswordRequired` routes to the password step |
| A timeout was read as success | same | Timeout re-reads the authorization state; only `Ready` yields Success |
| `initialize()` swallowed failures, showing a signed-in user as signed out | `auth_service.dart` | No catch-all; failure surfaces |
| Skip on the permissions screen bypassed required media access silently | `permissions_screen.dart` | Skip now asks, explaining gallery/backup will not work |
| PIN/biometric could be disabled with no auth challenge | `privacy_settings_screen.dart` | Flags only for now; comment marks where verify-before-disable lands |
| Lockout used wall-clock time, so changing the system clock cleared it | `pin_attempt_throttle.dart` | Live deadline anchored to `SystemClock.elapsedRealtime()` |
| Backup engine kept uploading tasks the user had de-selected | `backup_engine.dart` | Not ported yet (Phase 4) — noted so it is not reintroduced |
| Restore never stored the fetched manifest (self-assignment no-op) | `restore_engine.dart` | Not ported yet (Phase 4) |
| Map rebuilt every marker on each frame | `map_screen.dart` | Overlay set only mutated when the photo list identity changes |
| Search clear button had no label | `search_screen.dart` | Tooltip + content description |
| AI-scan failure surfaced only as a transient snackbar | same | Persistent inline state |

## Remaining work

- Phase 5 hardening: Room migrations (replace `fallbackToDestructiveMigration`),
  instrumented tests, R8, APK size pass.
- Face-scan background controller (the ML services exist; the scan scheduling
  does not run yet).
- Metadata manifest/partition upload scheduling into the channel (the sync
  engine is ported; wiring it into the periodic worker is pending).