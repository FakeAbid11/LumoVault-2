package com.lumovault.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lumovault.app.data.local.media.MediaDao
import com.lumovault.app.data.local.media.MediaEntity

/**
 * Phase 1 shipped v1 with only `theme_mode` (an entity-free database is rejected by Room's
 * processor), Phase 2 added the rest of PRD section 61's `UserSettings`, and Phase 3 adds the
 * media index. Each step is an explicit migration: an installed app must not lose its theme or
 * its recorded setup choices, and no destructive fallback is used anywhere.
 */
@Database(
    entities = [AppSettingsEntity::class, MediaEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class LumoVaultDatabase : RoomDatabase() {
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun mediaDao(): MediaDao

    companion object {
        /**
         * Phase 1 shipped v1 with only `theme_mode`. These columns are PRD section 61's remaining
         * `UserSettings` fields; the defaults match the entity so the schema Room validates
         * against is the schema the migration produces.
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

        /**
         * Mirrors Room's generated DDL for [MediaEntity] column-for-column and in declaration
         * order: a schema that differs from the compiled one fails validation when the database is
         * opened on an existing install, not at build time.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media` (
                        `media_store_id` INTEGER NOT NULL,
                        `content_uri` TEXT NOT NULL,
                        `media_type` TEXT NOT NULL,
                        `mime_type` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `relative_path` TEXT NOT NULL DEFAULT '',
                        `size_bytes` INTEGER NOT NULL,
                        `date_added_seconds` INTEGER NOT NULL,
                        `date_modified_seconds` INTEGER NOT NULL,
                        `width` INTEGER NOT NULL DEFAULT 0,
                        `height` INTEGER NOT NULL DEFAULT 0,
                        `duration_millis` INTEGER,
                        `last_seen_scan_id` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`media_store_id`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_date_added_seconds` ON `media` (`date_added_seconds`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_media_type` ON `media` (`media_type`)",
                )
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
