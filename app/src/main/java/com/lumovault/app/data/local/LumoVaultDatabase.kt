package com.lumovault.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Phase 1 holds only the `UserSettings` row from PRD section 61, because that is the only data the
 * foundation actually reads (the persisted theme). Media and backup tables are specified by
 * Phases 3 and 6 and arrive as migrations from here, against the exported schema.
 */
@Database(
    entities = [AppSettingsEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class LumoVaultDatabase : RoomDatabase() {
    abstract fun appSettingsDao(): AppSettingsDao
}
