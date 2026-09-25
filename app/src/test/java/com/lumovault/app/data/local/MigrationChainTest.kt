package com.lumovault.app.data.local

import androidx.room.migration.Migration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The migration chain, checked without a database.
 *
 * `fallbackToDestructiveMigration` is used nowhere in this app, which means a schema the compiled entities
 * describe and no migration produces is not a warning — it is a crash on the first launch after an
 * upgrade, on a user's own photo library, with nothing in the app that can repair it. Nothing else in the
 * build can see that: `app/schemas` is exported into the runner's workspace and compared to nothing, and
 * the entities and the migrations compile happily side by side while disagreeing.
 *
 * So what is checkable is the bookkeeping. The declared `@Database` version is not — Room retains that
 * annotation as BINARY, so it is absent at runtime — which leaves the shape of the chain: one migration
 * per step, no step skipped, no step doubled, and every one moving forward by exactly one version.
 */
class MigrationChainTest {
    private val migrations: List<Migration> = LumoVaultDatabase.ALL_MIGRATIONS.toList()

    @Test
    fun everyStepFromTheFirstSchemaToTheLatestHasExactlyOneMigration() {
        val lastVersion = migrations.maxOf { it.endVersion }

        assertEquals(
            "a gap or a duplicate in the chain fails validation on an existing install, not at build time",
            (1 until lastVersion).toList(),
            migrations.sortedBy { it.startVersion }.map { it.startVersion },
        )
    }

    @Test
    fun noMigrationSkipsOrRepeatsAVersion() {
        migrations.forEach {
            assertEquals(
                "MIGRATION_${it.startVersion}_${it.endVersion} must move forward by one, which is what " +
                    "`addMigrations` chains and Room's `validateMigration` both require",
                it.startVersion + 1,
                it.endVersion,
            )
        }
    }

    @Test
    fun theOldestSchemaIsVersionOne() {
        // An install can only upgrade from a version this app shipped, and 1 is the first one it ever was.
        assertTrue(
            "the chain must begin at 1",
            migrations.minOf { it.startVersion } == 1,
        )
    }
}
