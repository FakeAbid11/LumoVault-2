package com.lumovault.app.data.repository

import com.lumovault.app.data.local.AppSettingsDao
import com.lumovault.app.data.local.AppSettingsEntity
import com.lumovault.app.domain.model.ThemeMode
import com.lumovault.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepositoryImpl(private val dao: AppSettingsDao) : SettingsRepository {
    override val themeMode: Flow<ThemeMode> =
        dao.observe().map { settings -> ThemeMode.fromStorageKey(settings?.themeMode) }

    override suspend fun setThemeMode(mode: ThemeMode) {
        // Read-modify-write once UserSettings gains more columns; id 1 keeps it to a single row.
        dao.upsert(AppSettingsEntity(themeMode = mode.storageKey))
    }
}
