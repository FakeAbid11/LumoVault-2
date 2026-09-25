package com.lumovault.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The `UserSettings` record from PRD section 61, kept as one row for the whole app.
 *
 * Enums are stored as their stable string keys rather than ordinals, and folder selections as
 * newline-joined relative paths, so no `TypeConverter` is needed and a renamed enum entry cannot
 * silently change what an existing install reads back. The string defaults below are the matching
 * `storageKey` values from `OptionalStepDecision` / `BackupSource`.
 *
 * `theme_mode` intentionally keeps its v1 definition (no default): adding one here would make the
 * column differ from the already-shipped schema and Room would reject the migration at runtime.
 */
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ROW_ID,
    /** No Kotlin default either: the product default is supplied by [AppSettingsStore], so this row's meaning is decided in one place. */
    val themeMode: String,
    @ColumnInfo(name = "onboarding_completed", defaultValue = "0")
    val onboardingCompleted: Boolean = false,
    @ColumnInfo(name = "telegram_linked", defaultValue = "0")
    val telegramLinked: Boolean = false,
    @ColumnInfo(name = "backup_enabled", defaultValue = "0")
    val backupEnabled: Boolean = false,
    @ColumnInfo(name = "notification_preference", defaultValue = "not_asked")
    val notificationPreference: String = "not_asked",
    @ColumnInfo(name = "background_backup_preference", defaultValue = "not_asked")
    val backgroundBackupPreference: String = "not_asked",
    @ColumnInfo(name = "source_selection")
    val sourceSelection: String? = null,
    @ColumnInfo(name = "selected_folders", defaultValue = "")
    val selectedFolders: String = "",

    /**
     * Automatic backup waits for an unmetered network.
     *
     * Defaults to on, because a library's first pass is the expensive one and nobody has agreed to spend
     * their mobile data allowance by installing a photo app. Manual backup is not governed by this: a tap
     * on "Back Up" is the agreement.
     */
    @ColumnInfo(name = "wifi_only", defaultValue = "1")
    val wifiOnly: Boolean = true,

    /** Off by default — waiting for a charger can mean a phone that never backs up. */
    @ColumnInfo(name = "charging_only", defaultValue = "0")
    val chargingOnly: Boolean = false,

    /**
     * When the local library was last reconciled with MediaStore, in epoch seconds; 0 when it has not
     * happened in this install.
     *
     * Stored because the Diagnostics screen has to answer "last scan" and the only candidate in `media` is
     * `last_seen_scan_id`, which is a tag used to decide what to prune, not a time anyone promised to keep.
     * Inventing the answer from a row count would be exactly the fake this project refuses.
     */
    @ColumnInfo(name = "last_scan_seconds", defaultValue = "0")
    val lastScanSeconds: Long = 0,
) {
    companion object {
        const val SINGLETON_ROW_ID = 1
    }
}
