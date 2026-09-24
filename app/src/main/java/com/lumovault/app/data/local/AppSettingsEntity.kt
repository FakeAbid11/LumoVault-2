package com.lumovault.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The `UserSettings` row from PRD section 61, one row for the whole app. Only `themeMode` exists
 * in Phase 1; the remaining UserSettings columns (onboardingCompleted, backupEnabled,
 * notificationPreference, sourceSelection) are added by the phases that read them, as a migration.
 *
 * Enums are stored as stable string keys rather than ordinals so a stored value survives code
 * changes, and so Room needs no TypeConverter here.
 */
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ROW_ID,
    val themeMode: String,
) {
    companion object {
        const val SINGLETON_ROW_ID = 1
    }
}
