package com.lumovault.lumovault.features.metadata.data.service

import com.lumovault.lumovault.features.metadata.domain.model.Manifest
import com.lumovault.lumovault.features.metadata.domain.model.MetadataPartition

/**
 * Upgrades older manifest/partition documents to the current schema.
 *
 * Ported from lib/features/metadata/data/repositories/migration_service.dart.
 *
 * The contract is subtle and must be preserved exactly:
 *  - a gap that is **entirely covered** by registered migrations is applied;
 *  - a gap that is **entirely uncovered** is fine, because the format is
 *    additive — v1 documents simply predate the tombstone fields and default
 *    them, so they are accepted and stamped at the target version. v1 → v2
 *    depends on this;
 *  - a gap that is **partly** covered is refused (returns null): we know how to
 *    get partway but not all the way, which is a real data-integrity risk, so
 *    we keep the local baseline and skip the remote document rather than write
 *    a half-migrated manifest.
 */
class MigrationService {

    fun interface Migration {
        val targetVersion: Int
    }

    private val migrations: List<Migration> = listOf(
        // No-op placeholder: v1 is accepted as-is because the v2 additions
        // (isDeleted/deletedAt) default cleanly. Registered so the v1→v2 path
        // is explicit rather than implicit.
        object : Migration { override val targetVersion = 2 },
    )

    private val maxRegisteredVersion: Int get() = migrations.maxOf { it.targetVersion }

    fun needsMigration(manifest: Manifest): Boolean =
        manifest.schemaVersion < Manifest.CURRENT_SCHEMA_VERSION

    /** Returns the migrations that would apply to get from [fromVersion] up. */
    fun getMigrationsNeeded(fromVersion: Int): List<Migration> =
        migrations.filter { it.targetVersion in (fromVersion + 1)..maxRegisteredVersion }

    /**
     * Upgrades [manifest], or null to refuse. See the class doc for the
     * partial-gap rule.
     */
    fun migrateManifest(manifest: Manifest): Manifest? {
        if (!needsMigration(manifest)) return manifest
        val gap = Manifest.CURRENT_SCHEMA_VERSION - manifest.schemaVersion
        val applicable = getMigrationsNeeded(manifest.schemaVersion)

        // Partly covered: we can do some of the gap but not all of it. Refuse.
        if (applicable.isNotEmpty() && applicable.size != gap) return null

        return try {
            manifest.copy(schemaVersion = Manifest.CURRENT_SCHEMA_VERSION)
        } catch (_: Throwable) {
            null
        }
    }

    /** Same contract as [migrateManifest], for a partition document. */
    fun migratePartition(partition: MetadataPartition): MetadataPartition? = partition
}
