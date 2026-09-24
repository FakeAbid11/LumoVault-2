package com.lumovault.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AppSettingsEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class LumoVaultDatabase : RoomDatabase() {
    abstract fun appSettingsDao(): AppSettingsDao

    companion object {
        /**
         * Phase 1 shipped v1 with only `theme_mode`. These columns are PRD section 61's remaining
         * `UserSettings` fields, added as an explicit migration so an existing install keeps its
         * theme choice instead of losing it to a destructive fallback.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `onboarding_completed` INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `telegram_linked` INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `backup_enabled` INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `notification_preference` TEXT NOT NULL DEFAULT 'not_asked'",
                )
                database.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `background_backup_preference` TEXT NOT NULL DEFAULT 'not_asked'",
                )
                database.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `source_selection` TEXT",
                )
                database.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `selected_folders` TEXT NOT NULL DEFAULT ''",
                )
            }
        }
    }
}
