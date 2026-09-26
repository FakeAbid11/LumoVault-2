package com.lumovault.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lumovault.app.data.local.backup.BackupHealthDao
import com.lumovault.app.data.local.backup.BackupQueueDao
import com.lumovault.app.data.local.backup.BackupQueueEntity
import com.lumovault.app.data.local.backup.FreeUpSpaceDao
import com.lumovault.app.data.local.cloud.CloudChannelDao
import com.lumovault.app.data.local.cloud.CloudChannelEntity
import com.lumovault.app.data.local.cloud.CloudMediaDao
import com.lumovault.app.data.local.cloud.CloudMediaEntity
import com.lumovault.app.data.local.media.MediaDao
import com.lumovault.app.data.local.media.MediaEntity
import com.lumovault.app.data.local.metadata.MediaMetadataDao
import com.lumovault.app.data.local.metadata.MediaMetadataEntity
import com.lumovault.app.data.local.organization.AlbumDao
import com.lumovault.app.data.local.organization.AlbumEntity
import com.lumovault.app.data.local.organization.AlbumMembershipEntity
import com.lumovault.app.data.local.organization.MediaOrganizationDao
import com.lumovault.app.data.local.organization.MediaOrganizationEntity
import com.lumovault.app.data.local.organization.SystemAlbumDao
import com.lumovault.app.data.local.restore.MediaRestoreDao
import com.lumovault.app.data.local.restore.MediaRestoreEntity

/**
 * Phase 1 shipped v1 with only `theme_mode` (an entity-free database is rejected by Room's
 * processor), Phase 2 added the rest of PRD section 61's `UserSettings`, and Phase 3 adds the
 * media index. Each step is an explicit migration: an installed app must not lose its theme or
 * its recorded setup choices, and no destructive fallback is used anywhere.
 */
/**
 * The schema version the compiled entities describe, in one place.
 *
 * It is a `const` rather than the literal in the annotation below so [MigrationChainTest] can compare the
 * migration chain against it. Room keeps `@Database` with BINARY retention, so the annotation is invisible
 * at runtime — which without this constant leaves nothing in the build able to notice that an entity gained
 * a column and no migration produces it. That mismatch is not a warning; it is a crash on the first launch
 * after an upgrade, on a person's own library.
 */
internal const val LUMOVAULT_SCHEMA_VERSION = 10

@Database(
    entities = [
        AppSettingsEntity::class,
        MediaEntity::class,
        CloudMediaEntity::class,
        CloudChannelEntity::class,
        BackupQueueEntity::class,
        AlbumEntity::class,
        AlbumMembershipEntity::class,
        MediaOrganizationEntity::class,
        MediaMetadataEntity::class,
        MediaRestoreEntity::class,
    ],
    version = LUMOVAULT_SCHEMA_VERSION,
    exportSchema = true,
)
abstract class LumoVaultDatabase : RoomDatabase() {
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun mediaDao(): MediaDao
    abstract fun cloudMediaDao(): CloudMediaDao
    abstract fun cloudChannelDao(): CloudChannelDao
    abstract fun backupQueueDao(): BackupQueueDao
    abstract fun albumDao(): AlbumDao
    abstract fun mediaOrganizationDao(): MediaOrganizationDao
    abstract fun systemAlbumDao(): SystemAlbumDao
    abstract fun mediaMetadataDao(): MediaMetadataDao
    abstract fun mediaRestoreDao(): MediaRestoreDao

    /**
     * Query-only: Free Up Space reads five tables and owns none, so it adds no entity and no
     * schema step. The database version stays at 9 for that reason.
     */
    abstract fun freeUpSpaceDao(): FreeUpSpaceDao

    /** Query-only, like [FreeUpSpaceDao]: Backup Health and Diagnostics read five tables and own none. */
    abstract fun backupHealthDao(): BackupHealthDao

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

        /**
         * Mirrors Room's generated DDL for the Phase 6 columns on [BackupQueueEntity] and
         * [CloudMediaEntity], appending in declaration order because `ALTER TABLE` can do nothing else.
         *
         * Every existing row survives untouched, which matters more here than in any earlier migration:
         * `message_id` is the record that a user's photos are safe, and a Phase 5 install has real
         * backups in it. The new columns start empty, and empty means what it says — nothing has been
         * hashed and nothing has been recognised — so an upgrade never claims a backup it does not know
         * about. Recognition fills them in afterwards, one bounded pass at a time.
         *
         * `app/schemas` is written only in the runner's workspace, so nothing compares this text against
         * what Room compiles: a flipped default or a missing index surfaces as a schema-validation crash
         * on an existing install's first launch, not as a red build.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `backup_queue` ADD COLUMN `content_hash` TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE `backup_queue` ADD COLUMN `content_size_bytes` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `backup_queue` ADD COLUMN `content_modified_seconds` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `backup_queue` ADD COLUMN `hashed_at` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_backup_queue_content_hash` ON `backup_queue` (`content_hash`)",
                )
                db.execSQL(
                    "ALTER TABLE `cloud_media` ADD COLUMN `content_hash` TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_cloud_media_content_hash` ON `cloud_media` (`content_hash`)",
                )
            }
        }

        /**
         * Mirrors Room's generated DDL for [AlbumEntity], [AlbumMembershipEntity] and
         * [MediaOrganizationEntity] column-for-column and in declaration order, including the foreign
         * key's action clauses — a schema that differs from the compiled one fails validation when an
         * existing install opens the database, not at build time, and `app/schemas` exists only in the
         * runner's workspace.
         *
         * Three new tables and no `ALTER`, which is why this migration cannot lose anything: every row of
         * `media`, `cloud_media`, `cloud_channel`, `backup_queue` and `app_settings` is left exactly as
         * it was. That matters most for `backup_queue.content_hash` — the Phase 6 identity of a user's
         * library — and for `cloud_media`, whose scan cursor would otherwise send a Phase 7 upgrade back
         * to reading the whole channel history.
         *
         * `albums` is created before `album_media` because the child's foreign key names it, and SQLite
         * resolves that reference when the child's DDL runs.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `albums` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `album_media` (
                        `album_id` INTEGER NOT NULL,
                        `media_store_id` INTEGER NOT NULL,
                        `added_at` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`album_id`, `media_store_id`),
                        FOREIGN KEY(`album_id`) REFERENCES `albums`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_album_media_media_store_id` ON `album_media` (`media_store_id`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_organization` (
                        `media_store_id` INTEGER NOT NULL,
                        `favorite` INTEGER NOT NULL DEFAULT 0,
                        `archived` INTEGER NOT NULL DEFAULT 0,
                        `trashed_at` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`media_store_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_organization_favorite` ON `media_organization` (`favorite`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_organization_archived` ON `media_organization` (`archived`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_organization_trashed_at` ON `media_organization` (`trashed_at`)",
                )
            }
        }

        /**
         * Mirrors Room's generated DDL for Phase 8: one appended column on `media` and the whole of
         * [MediaMetadataEntity], column-for-column, in declaration order, with the index named the way Room
         * names it.
         *
         * `date_taken_seconds` is appended last in both the entity and here because `ALTER TABLE` can do
         * nothing else, and it is nullable with no default: "MediaStore did not record a capture time" is a
         * different answer from "the capture time is the epoch", and only the first one is true of a file
         * whose EXIF never said.
         *
         * No existing row is rewritten. A Phase 7 install keeps its index, its albums, its favourites, its
         * Trash and — what matters most — `backup_queue` with its content hashes and its Telegram message ids,
         * which are the record that a user's photos are safe. The new table starts empty, and empty means
         * exactly that: no file has been opened for its metadata yet.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media` ADD COLUMN `date_taken_seconds` INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_metadata` (
                        `media_store_id` INTEGER NOT NULL,
                        `latitude` REAL,
                        `longitude` REAL,
                        `altitude_meters` REAL,
                        `camera_make` TEXT,
                        `camera_model` TEXT,
                        `lens_model` TEXT,
                        `focal_length_mm` REAL,
                        `aperture_f` REAL,
                        `iso_speed` INTEGER,
                        `shutter_seconds` REAL,
                        `extracted_at` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`media_store_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_metadata_latitude_longitude` " +
                        "ON `media_metadata` (`latitude`, `longitude`)",
                )
            }
        }

        /**
         * Mirrors Room's generated DDL for Phase 9: three appended columns on `app_settings` and the whole
         * of [MediaRestoreEntity], column-for-column in declaration order, with the index named the way Room
         * names it.
         *
         * The settings columns go last in both the entity and here because `ALTER TABLE … ADD COLUMN` can
         * only append. Their defaults are the interesting part, and they are load-bearing in both
         * directions: `wifi_only` defaults to 1, which is why an upgraded install must be given 1 rather
         * than 0 — a Phase 8 user never chose to back up over mobile data, because there was no background
         * pass to choose about, so the migration has to leave them where they were. `last_scan_seconds`
         * defaults to 0, meaning "no scan recorded by this build", which the Diagnostics screen renders as
         * exactly that rather than as a date.
         *
         * Nothing existing is rewritten. `media_restore` starts empty, and empty is the right answer: on a
         * Phase 8 install no original was ever downloaded, and the first restore the user asks for creates
         * the first row.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `wifi_only` INTEGER NOT NULL DEFAULT 1",
                )
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `charging_only` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `app_settings` ADD COLUMN `last_scan_seconds` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_restore` (
                        `chat_id` INTEGER NOT NULL,
                        `message_id` INTEGER NOT NULL,
                        `tdlib_file_id` INTEGER NOT NULL DEFAULT 0,
                        `downloaded_bytes` INTEGER NOT NULL DEFAULT 0,
                        `expected_size_bytes` INTEGER NOT NULL DEFAULT 0,
                        `state` TEXT NOT NULL DEFAULT 'pending',
                        `failure` TEXT NOT NULL DEFAULT '',
                        `media_store_id` INTEGER NOT NULL DEFAULT 0,
                        `content_hash` TEXT NOT NULL DEFAULT '',
                        `requested_at` INTEGER NOT NULL DEFAULT 0,
                        `updated_at` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`chat_id`, `message_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_restore_state` ON `media_restore` (`state`)",
                )
            }
        }

        /**
         * The prune step's index.
         *
         * Every scan tags the rows it saw and then deletes every older tag — `WHERE last_seen_scan_id <`,
         * over the whole library, once per sync. Until now that ran as a full table scan: the comment on
         * [MediaEntity] always claimed the prune filter was one of the two queries the table serves, while
         * the index list only ever carried the timeline's sort and the type filter. Index-only migration:
         * no column is added, so nothing about column order can drift, and the name below is exactly the
         * one Room derives from `Index("last_seen_scan_id")` on the entity — a fresh install and an
         * upgraded one must agree on it byte for byte, or Room's schema validation rejects the database.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_media_last_seen_scan_id` " +
                        "ON `media` (`last_seen_scan_id`)",
                )
            }
        }

        val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
        )
    }
}
