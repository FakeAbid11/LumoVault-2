package com.lumovault.app.data.local

import androidx.room.withTransaction
import com.lumovault.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * The only writer of the single `app_settings` row.
 *
 * Every change is a read-modify-write inside a transaction, which is what keeps two repositories
 * sharing one row (theme here, onboarding there) from overwriting each other's columns.
 */
class AppSettingsStore(private val database: LumoVaultDatabase) {
    private val dao: AppSettingsDao = database.appSettingsDao()

    val changes: Flow<AppSettingsEntity?> = dao.observe()

    suspend fun update(transform: (AppSettingsEntity) -> AppSettingsEntity) {
        database.withTransaction {
            val current = dao.current()
                ?: AppSettingsEntity(themeMode = ThemeMode.Default.storageKey)
            dao.upsert(transform(current))
        }
    }
}
