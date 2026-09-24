package com.lumovault.app.data.repository

import com.lumovault.app.data.local.AppSettingsStore
import com.lumovault.app.domain.model.ThemeMode
import com.lumovault.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepositoryImpl(private val store: AppSettingsStore) : SettingsRepository {
    override val themeMode: Flow<ThemeMode> =
        store.changes.map { settings -> ThemeMode.fromStorageKey(settings?.themeMode) }

    override suspend fun setThemeMode(mode: ThemeMode) {
        store.update { it.copy(themeMode = mode.storageKey) }
    }
}
