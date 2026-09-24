package com.lumovault.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lumovault.app.domain.model.ThemeMode
import com.lumovault.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "lumovault_settings")

class SettingsRepositoryImpl(context: Context) : SettingsRepository {
    private val dataStore = context.applicationContext.settingsDataStore

    override val themeMode: Flow<ThemeMode> = dataStore.data
        .map { preferences -> ThemeMode.fromStorageKey(preferences[THEME_MODE_KEY]) }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences -> preferences[THEME_MODE_KEY] = mode.storageKey }
    }

    private companion object {
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
    }
}
