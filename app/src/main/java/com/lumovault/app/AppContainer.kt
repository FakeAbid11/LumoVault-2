package com.lumovault.app

import android.content.Context
import androidx.room.Room
import com.lumovault.app.data.local.AppSettingsStore
import com.lumovault.app.data.local.LumoVaultDatabase
import com.lumovault.app.data.remote.telegram.MissingTdLibNative
import com.lumovault.app.data.remote.telegram.TdLibJsonClient
import com.lumovault.app.data.remote.telegram.TelegramAuthRepositoryImpl
import com.lumovault.app.data.remote.telegram.TelegramClientInfo
import com.lumovault.app.data.remote.telegram.TelegramCredentials
import com.lumovault.app.data.remote.telegram.TelegramStorage
import com.lumovault.app.data.remote.telegram.DeviceInfo
import com.lumovault.app.data.repository.CountryRepositoryImpl
import com.lumovault.app.data.repository.OnboardingRepositoryImpl
import com.lumovault.app.data.repository.SettingsRepositoryImpl
import com.lumovault.app.data.repository.SystemPermissionsRepositoryImpl
import com.lumovault.app.domain.repository.CountryRepository
import com.lumovault.app.domain.repository.OnboardingRepository
import com.lumovault.app.domain.repository.PermissionRepository
import com.lumovault.app.domain.repository.SettingsRepository
import com.lumovault.app.domain.telegram.TelegramAuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-scoped dependency graph.
 *
 * Everything is lazy, so no database file, TDLib client or country table is built until something
 * asks. [applicationScope] exists because TDLib's receive loop must outlive the screen that started
 * it — cancelling it with a screen would drop the session mid-handshake.
 *
 * Still hand-written rather than a DI framework: Hilt earns its place when Phase 5's WorkManager
 * workers need constructor injection across processes, not before.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: LumoVaultDatabase by lazy {
        Room.databaseBuilder(appContext, LumoVaultDatabase::class.java, "lumovault.db")
            .addMigrations(LumoVaultDatabase.MIGRATION_1_2)
            .build()
    }

    private val settingsStore: AppSettingsStore by lazy { AppSettingsStore(database) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepositoryImpl(settingsStore) }

    val onboardingRepository: OnboardingRepository by lazy { OnboardingRepositoryImpl(settingsStore) }

    val permissionRepository: PermissionRepository by lazy { SystemPermissionsRepositoryImpl(appContext) }

    val countryRepository: CountryRepository by lazy { CountryRepositoryImpl() }

    private val telegramCredentials: TelegramCredentials by lazy { TelegramCredentials.fromBuildConfig() }

    val telegramAuthRepository: TelegramAuthRepository by lazy {
        TelegramAuthRepositoryImpl(
            client = TdLibJsonClient(
                // The forwarding JNI library is the remaining fast follow; see TdLibNative.
                native = MissingTdLibNative,
                credentials = telegramCredentials,
                scope = applicationScope,
            ),
            credentials = telegramCredentials,
            info = TelegramClientInfo.of(DeviceInfo.fromDevice(), BuildConfig.VERSION_NAME),
            storage = telegramStorage(),
            scope = applicationScope,
        )
    }

    private fun telegramStorage(): TelegramStorage =
        TelegramStorage(
            databaseDirectory = appContext.getDir("tdlib_database", Context.MODE_PRIVATE),
            filesDirectory = appContext.getDir("tdlib_files", Context.MODE_PRIVATE),
        ).ensureCreated()
}
