package com.lumovault.app.data.local

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The migration chain opened against a real database file, at version 1, the way an installed app
 * meets it.
 *
 * [MigrationChainTest] checks the chain's bookkeeping — no gaps, no doubles, ending at the compiled
 * version — but never runs a statement. The one thing no build here can check otherwise is whether
 * the hand-written DDL in [LumoVaultDatabase]'s migrations produces the schema the entities compile
 * to: `room.schemaLocation` writes `app/schemas` only in the runner's workspace, so the exported
 * JSON a `MigrationTestHelper` would read does not exist to compare against. Opening the database
 * instead makes Room the judge — it runs the whole chain and then validates every table against the
 * entities, naming the table and both spellings when they disagree.
 *
 * The seed is Phase 1's entire database: `app_settings` with its two v1 columns, one row the user
 * set, and `user_version = 1`. What the test then asserts is the promise migrations exist to keep —
 * the choices made before and between the steps survive, and the defaults a later step invented for
 * columns the user never had come out as that step chose them. The column is spelled `themeMode`
 * because [AppSettingsEntity] declares no `@ColumnInfo` for it and Room's default is the field name;
 * if Phase 1 actually shipped `theme_mode`, that surfaces here, in a build log, rather than on
 * somebody's first launch after an upgrade.
 *
 * The application class is overridden deliberately: the real one builds the dependency graph, a
 * notification channel and a WorkManager schedule in `onCreate`, and none of that is what this test
 * is about — it would run before the first assertion either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MigrationUpgradeTest {
    private lateinit var context: Application

    @Before
    fun seedVersionOneDatabase() {
        context = RuntimeEnvironment.getApplication()
        databaseFile().delete()

        val seed = SQLiteDatabase.openOrCreateDatabase(databaseFile(), null)
        try {
            seed.execSQL(
                """
                CREATE TABLE `app_settings` (
                    `id` INTEGER NOT NULL,
                    `themeMode` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            seed.execSQL("INSERT INTO `app_settings` (`id`, `themeMode`) VALUES (1, 'dark')")
            seed.version = 1
        } finally {
            seed.close()
        }
    }

    @After
    fun deleteTheDatabaseFile() {
        databaseFile().delete()
    }

    @Test
    fun upgradingFromVersionOneKeepsTheUsersChoicesAndAppliesEveryLaterDefault() = runBlocking {
        val database = Room.databaseBuilder(context, LumoVaultDatabase::class.java, DATABASE_NAME)
            .addMigrations(*LumoVaultDatabase.ALL_MIGRATIONS)
            .build()

        try {
            // This query is what opens the database: Room runs the ten migrations, then validates
            // every entity's table against what they produced. A hand-written column that differs
            // from the compiled one fails right here, with the table and both spellings in the log.
            val settings = checkNotNull(database.appSettingsDao().current()) {
                "the seeded row survived the chain"
            }

            // What the user had at v1...
            assertEquals("dark", settings.themeMode)

            // ...what they chose afterwards, added by 1→2 and 8→9...
            assertFalse(settings.onboardingCompleted)
            assertFalse(settings.telegramLinked)
            assertFalse(settings.backupEnabled)
            assertEquals("not_asked", settings.notificationPreference)
            assertEquals("not_asked", settings.backgroundBackupPreference)
            assertNull(settings.sourceSelection)
            assertEquals("", settings.selectedFolders)
            assertTrue(settings.wifiOnly)
            assertFalse(settings.chargingOnly)
            assertEquals(0L, settings.lastScanSeconds)

            // ...and a table only a later migration created, read through Room rather than merely
            // left open: an empty answer is the right one for a database with no albums, but the
            // query itself would fail if 6→7 had produced something the entity does not expect.
            assertTrue(database.albumDao().albumIds().isEmpty())
        } finally {
            database.close()
        }
    }

    private fun databaseFile(): File =
        context.getDatabasePath(DATABASE_NAME).also { file -> file.parentFile?.mkdirs() }

    private companion object {
        const val DATABASE_NAME = "migration_upgrade_test.db"
    }
}
