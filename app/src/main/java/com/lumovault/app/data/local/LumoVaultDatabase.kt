package com.lumovault.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lumovault.app.data.local.backup.BackupQueueDao
import com.lumovault.app.data.local.backup.BackupQueueEntity
import com.lumovault.app.data.local.cloud.CloudChannelDao
import com.lumovault.app.data.local.cloud.CloudChannelEntity
import com.lumovault.app.data.local.cloud.CloudMediaDao
import com.lumovault.app.data.local.cloud.CloudMediaEntity
import com.lumovault.app.data.local.media.MediaDao
import com.lumovault.app.data.local.media.MediaEntity

/**
 * Phase 1 shipped v1 with only `theme_mode` (an entity-free database is rejected by Room's
 * processor), Phase 2 added the rest of PRD section 61's `UserSettings`, and Phase 3 adds the
 * media index. Each step is an explicit migration: an installed app must not lose its theme or
 * its recorded setup choices, and no destructive fallback is used anywhere.
 */
@Database(
    entities = [
        AppSettingsEntity::class,
        MediaEntity::class,
        CloudMediaEntity::class,
        CloudChannelEntity::class,
        BackupQueueEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class LumoVaultDatabase : RoomDatabase() {
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun mediaDao(): MediaDao
    abstract fun cloudMediaDao(): CloudMediaDao
    abstract fun cloudChannelDao(): CloudChannelDao
    abstract fun backupQueueDao(): BackupQueueDao

    companion object {
        /**
         * Phase 1 shipped v1 with only `theme_mode`. These columns are PRD section 61's remaining
         * `UserSettings` fields; the defaults match the entity so the schema Room validates
         * against is the schema the migration produces.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `onboarding_completed` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `telegram_linked` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `backup_enabled` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `notification_preference` TEXT NOT NULL DEFAULT 'not_asked'",
                )
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `background_backup_preference` TEXT NOT NULL DEFAULT 'not_asked'",
                )
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `source_selection` TEXT",
                )
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `selected_folders` TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /**
         * Mirrors Room's generated DDL for [MediaEntity] column-for-column and in declaration
         * order: a schema that differs from the compiled one fails validation when the database is
         * opened on an existing install, not at build time.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_date_added_seconds` ON `media` (`date_added_seconds`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_media_type` ON `media` (`media_type`)",
                )
            }
        }

        /**
         * Mirrors Room's generated DDL for [CloudMediaEntity] and [CloudChannelEntity]
         * column-for-column and in declaration order: a schema that differs from the compiled one
         * fails validation when the database is opened on an existing install, not at build time.
         *
         * Nothing here touches `media` or `app_settings`. A user upgrading from Phase 3 keeps their
         * entire local index, which is the point of migrations over destructive fallbacks: the cloud
         * index is additive, and losing local media metadata to gain it would be a worse outcome than
         * an empty Cloud screen.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cloud_media` (
                        `message_id` INTEGER NOT NULL,
                        `chat_id` INTEGER NOT NULL,
                        `media_type` TEXT NOT NULL,
                        `mime_type` TEXT NOT NULL DEFAULT '',
                        `file_name` TEXT NOT NULL DEFAULT '',
                        `size_bytes` INTEGER NOT NULL DEFAULT 0,
                        `date_seconds` INTEGER NOT NULL,
                        `date_source` TEXT NOT NULL DEFAULT 'telegram_message',
                        `width` INTEGER NOT NULL DEFAULT 0,
                        `height` INTEGER NOT NULL DEFAULT 0,
                        `duration_seconds` INTEGER,
                        `remote_file_id` TEXT NOT NULL DEFAULT '',
                        `preview_remote_file_id` TEXT NOT NULL DEFAULT '',
                        `caption` TEXT NOT NULL DEFAULT '',
                        `last_seen_scan_id` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`message_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cloud_media_date_seconds` ON `cloud_media` (`date_seconds`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cloud_media_media_type` ON `cloud_media` (`media_type`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cloud_media_last_seen_scan_id` ON `cloud_media` (`last_seen_scan_id`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cloud_channel` (
                        `id` INTEGER NOT NULL,
                        `chat_id` INTEGER NOT NULL,
                        `owner_user_id` INTEGER NOT NULL,
                        `protocol_version` INTEGER NOT NULL DEFAULT 1,
                        `last_scanned_message_id` INTEGER NOT NULL DEFAULT 0,
                        `last_sync_seconds` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Mirrors Room's generated DDL for [BackupQueueEntity] column-for-column and in declaration
         * order. `app/schemas` is written only in the runner's workspace, so nothing here compares
         * this text against what Room compiles: a column out of order, a nullability flipped or a
         * default missing surfaces as a validation crash on the first launch of an existing install,
         * not as a red build.
         *
         * It adds a table and touches nothing else. Every row of `media`, `cloud_media`,
         * `cloud_channel` and `app_settings` survives, which matters more here than in earlier phases:
         * by now an install has a real Telegram session, an adopted channel and a library the user may
         * have already backed up. `message_id` is the record that a photo is safe to consider stored,
         * and a migration that lost it would make the app forget the user's own backups.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `backup_queue` (
                        `media_store_id` INTEGER NOT NULL,
                        `state` TEXT NOT NULL DEFAULT 'queued',
                        `chat_id` INTEGER NOT NULL DEFAULT 0,
                        `message_id` INTEGER NOT NULL DEFAULT 0,
                        `attempts` INTEGER NOT NULL DEFAULT 0,
                        `failure` TEXT NOT NULL DEFAULT '',
                        `staged_path` TEXT NOT NULL DEFAULT '',
                        `queued_at` INTEGER NOT NULL DEFAULT 0,
                        `updated_at` INTEGER NOT NULL DEFAULT 0,
                        `uploaded_at` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`media_store_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_backup_queue_state_queued_at` ON `backup_queue` (`state`, `queued_at`)",
                )
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
    }
}
