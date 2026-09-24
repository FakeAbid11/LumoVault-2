package com.lumovault.app

import android.content.Context
import androidx.room.Room
import com.lumovault.app.data.local.LumoVaultDatabase
import com.lumovault.app.data.repository.SettingsRepositoryImpl
import com.lumovault.app.domain.repository.SettingsRepository

/**
 * Application-scoped dependency graph. Everything is lazy so nothing is constructed — and no
 * disk is touched — before a screen actually asks for it.
 *
 * This is intentionally a small hand-written container rather than a framework: three nodes do
 * not justify a DI graph, and Phase 4 (Telegram session) plus Phase 5 (WorkManager workers) will
 * show whether one is actually needed.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: LumoVaultDatabase by lazy {
        Room.databaseBuilder(appContext, LumoVaultDatabase::class.java, "lumovault.db").build()
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(database.appSettingsDao())
    }
}
