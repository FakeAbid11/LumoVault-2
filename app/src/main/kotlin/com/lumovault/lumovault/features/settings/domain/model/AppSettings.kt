package com.lumovault.lumovault.features.settings.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Grid size for gallery display. Maps to a base column count. */
enum class GridSize(val columns: Int) {
    small(5),
    medium(4),
    large(3),
    ;

    companion object {
        /** Legacy ordinal used by the Flutter app: large=0, medium=1, small=2. */
        fun fromLegacyIndex(index: Int): GridSize = when (index) {
            0 -> large
            2 -> small
            else -> medium
        }
    }
}

enum class GallerySortOrder { newestFirst, oldestFirst, nameAsc, sizeDesc }

enum class GalleryFilterType { all, photosOnly, videosOnly, favoritesOnly }

enum class ThemeMode { system, light, dark }

/**
 * Column count for the gallery grids, derived from [GridSize] and compact mode.
 *
 * Ported from app_settings.dart's `galleryCrossAxisCount`: medium is the
 * long-standing default look, and compact mode adds one column.
 */
fun galleryCrossAxisCount(size: GridSize, compact: Boolean): Int =
    if (compact) size.columns + 1 else size.columns

/**
 * Central application settings model.
 *
 * Ported from lib/features/settings/data/models/app_settings.dart. Persisted as
 * a single JSON blob under key `lumovault_settings` in EncryptedSharedPreferences
 * (Keystore-backed), mirroring the Flutter app's `FlutterSecureStorage` blob.
 *
 * Every field has a default so a corrupt or partial blob degrades to the
 * default app rather than throwing — the original's `fromJsonString` caught
 * everything and returned defaults for the same reason.
 *
 * **Serialization is NOT stable yet.** These names match the Flutter format for
 * readability, but the rewrite is a clean break from the Flutter wire format, so
 * nothing is obligated to keep them. Do not write a migration on this format
 * until Phase 5 pins it.
 */
@Serializable
data class AppSettings(
    // -- General --
    @SerialName("languageCode") val languageCode: String = "en",
    @SerialName("onboardingCompleted") val onboardingCompleted: Boolean = false,

    // -- Backup --
    @SerialName("autoBackupEnabled") val autoBackupEnabled: Boolean = true,
    @SerialName("wifiOnly") val wifiOnly: Boolean = true,
    @SerialName("chargingOnly") val chargingOnly: Boolean = false,
    @SerialName("minBatteryLevel") val minBatteryLevel: Int = 20,
    @SerialName("backgroundBackupEnabled") val backgroundBackupEnabled: Boolean = true,
    @SerialName("maxParallelUploads") val maxParallelUploads: Int = 3,
    @SerialName("backupVideos") val backupVideos: Boolean = true,
    @SerialName("backupPhotos") val backupPhotos: Boolean = true,
    @SerialName("includedFolders") val includedFolders: List<String> = emptyList(),
    @SerialName("excludedFolders") val excludedFolders: List<String> = emptyList(),
    @SerialName("excludedFileHashes") val excludedFileHashes: List<String> = emptyList(),
    @SerialName("uploadBatchSize") val uploadBatchSize: Int = 10,
    @SerialName("uploadDelayMs") val uploadDelayMs: Int = 2000,
    @SerialName("maxFileSizeBytes") val maxFileSizeBytes: Long = 0,

    /** Telegram channel id used as the backup destination, once resolved. */
    @SerialName("storageChannelId") val storageChannelId: Long? = null,

    /** Timestamp (epoch millis) of the last completed backup run. */
    @SerialName("lastBackupAt") val lastBackupAt: Long? = null,

    /** Timestamp (epoch millis) of the last completed device scan. */
    @SerialName("lastScanAt") val lastScanAt: Long? = null,

    // -- Storage --
    @SerialName("trashDurationDays") val trashDurationDays: Int = 30,

    // -- Appearance --
    @SerialName("themeMode") val themeMode: ThemeMode = ThemeMode.dark,
    @SerialName("useDynamicColor") val useDynamicColor: Boolean = false,
    @SerialName("gridSize") val gridSize: GridSize = GridSize.medium,
    @SerialName("compactMode") val compactMode: Boolean = false,
    @SerialName("animationsEnabled") val animationsEnabled: Boolean = true,

    // -- Gallery --
    @SerialName("gallerySortOrder") val gallerySortOrder: GallerySortOrder = GallerySortOrder.newestFirst,
    @SerialName("galleryFilterType") val galleryFilterType: GalleryFilterType = GalleryFilterType.all,

    // -- Privacy --
    @SerialName("biometricLockEnabled") val biometricLockEnabled: Boolean = false,
    @SerialName("pinLockEnabled") val pinLockEnabled: Boolean = false,
    @SerialName("pinHash") val pinHash: String? = null,
    @SerialName("requireAuthOnAppOpen") val requireAuthOnAppOpen: Boolean = false,

    // -- Notifications --
    @SerialName("backupProgressNotification") val backupProgressNotification: Boolean = true,
    @SerialName("backupCompletedNotification") val backupCompletedNotification: Boolean = true,
    @SerialName("backupFailedNotification") val backupFailedNotification: Boolean = true,
    @SerialName("restoreCompletedNotification") val restoreCompletedNotification: Boolean = true,
    @SerialName("storageWarningNotification") val storageWarningNotification: Boolean = true,

    // -- Developer --
    @SerialName("debugMode") val debugMode: Boolean = false,

    // -- Auto-scan --
    @SerialName("aiScanEnabled") val aiScanEnabled: Boolean = false,
    @SerialName("faceScanEnabled") val faceScanEnabled: Boolean = false,
) {
    companion object {
        const val STORAGE_KEY = "lumovault_settings"

        val defaults: AppSettings get() = AppSettings()
    }
}

/**
 * Mirrors the original's `clearPinHash` escape hatch: a nullable pinHash in
 * copyWith cannot distinguish "unchanged" from "cleared", so the clear is
 * explicit.
 */
fun AppSettings.withClearedPin(): AppSettings = copy(pinHash = null)
