package com.lumovault.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Deliberately entity-free in Phase 1. The media and backup schemas are specified in PRD
 * sections 61 and 80 (Phases 3 and 6); guessing columns now would only create a migration
 * whose job is to delete the guesses.
 *
 * Schema export stays off until the first entity exists, at which point `app/schemas` becomes
 * the versioned contract for migrations.
 */
@Database(entities = [], version = 1, exportSchema = false)
abstract class LumoVaultDatabase : RoomDatabase()
