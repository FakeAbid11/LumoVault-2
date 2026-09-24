package com.lumovault.app

import android.content.Context
import androidx.room.Room
import com.lumovault.app.data.local.AppSettingsStore
import com.lumovault.app.data.local.LumoVaultDatabase
import com.lumovault.app.data.local.mediastore.MediaStoreDataSource
import com.lumovault.app.data.local.cloud.CloudChannelDao
import com.lumovault.app.data.local.cloud.CloudMediaDao
import com.lumovault.app.data.local.mediastore.MediaStoreDataSource
import com.lumovault.app.data.remote.telegram.JniTdLibNative
import com.lumovault.app.data.remote.telegram.TdLibCloudRepository
import com.lumovault.app.data.remote.telegram.TdLibJsonClient
import com.lumovault.app.data.remote.telegram.TelegramAuthRepositoryImpl
import com.lumovault.app.data.remote.telegram.TelegramClient
import com.lumovault.app.data.remote.telegram.TelegramClientInfo
import com.lumovault.app.data.remote.telegram.TelegramCredentials
import com.lumovault.app.data.remote.telegram.TelegramStorage
import com.lumovault.app.data.remote.telegram.DeviceInfo
import com.lumovault.app.data.repository.CloudIndexRepositoryImpl
import com.lumovault.app.data.repository.CountryRepositoryImpl
import com.lumovault.app.data.repository.LocalPresenceLookup
import com.lumovault.app.data.repository.MediaRepositoryImpl
import com.lumovault.app.data.repository.OnboardingRepositoryImpl
import com.lumovault.app.data.repository.SettingsRepositoryImpl
import com.lumovault.app.data.repository.SystemPermissionsRepositoryImpl
import com.lumovault.app.domain.repository.CloudIndexRepository
import com.lumovault.app.domain.repository.CountryRepository
import com.lumovault.app.domain.repository.MediaRepository
import com.lumovault.app.domain.repository.OnboardingRepository
import com.lumovault.app.domain.repository.PermissionRepository
import com.lumovault.app.domain.repository.SettingsRepository
import com.lumovault.app.domain.telegram.TelegramAuthRepository
import com.lumovault.app.domain.telegram.TelegramAuthState
import com.lumovault.app.domain.telegram.TelegramCloudRepository
import com.lumovault.app.domain.usecase.SynchronizeCloudUseCase
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
 * workers need constructor injection across processes.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: LumoVaultDatabase by lazy {
        Room.databaseBuilder(appContext, LumoVaultDatabase::class.java, "lumovault.db")
            .addMigrations(*LumoVaultDatabase.ALL_MIGRATIONS)
            .build()
    }

    private val settingsStore: AppSettingsStore by lazy { AppSettingsStore(database) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepositoryImpl(settingsStore) }

    val onboardingRepository: OnboardingRepository by lazy { OnboardingRepositoryImpl(settingsStore) }

    val permissionRepository: PermissionRepository by lazy { SystemPermissionsRepositoryImpl(appContext) }

    val countryRepository: CountryRepository by lazy { CountryRepositoryImpl() }

    val mediaRepository: MediaRepository by lazy {
        MediaRepositoryImpl(
            database = database,
            dao = database.mediaDao(),
            source = MediaStoreDataSource(appContext.contentResolver),
        )
    }

    private val telegramCredentials: TelegramCredentials by lazy { TelegramCredentials.fromBuildConfig() }

    /**
     * One TDLib client for the whole process, shared by authentication and the cloud scanner.
     *
     * Not an implementation convenience: TDLib's `td_receive` takes no client id and "must not be
     * called simultaneously from two different threads", so a second client would need a second
     * receive loop reading the same stream — which is a corrupted session waiting to happen.
     */
    private val telegramClient: TelegramClient by lazy {
        TdLibJsonClient(
            // Loads libtdjson + the JNI forwarder when the APK carries them; reports unavailable
            // when it does not. See .github/workflows/build-tdlib.yml.
            native = JniTdLibNative(),
            credentials = telegramCredentials,
            scope = applicationScope,
        )
    }

    val telegramAuthRepository: TelegramAuthRepository by lazy {
        TelegramAuthRepositoryImpl(
            client = telegramClient,
            credentials = telegramCredentials,
            info = TelegramClientInfo.of(DeviceInfo.fromDevice(), BuildConfig.VERSION_NAME),
            storage = telegramStorage(),
            scope = applicationScope,
        )
    }

    private val cloudIndexDao: CloudMediaDao by lazy { database.cloudMediaDao() }

    private val cloudChannelDao: CloudChannelDao by lazy { database.cloudChannelDao() }

    val cloudIndexRepository: CloudIndexRepository by lazy {
        CloudIndexRepositoryImpl(
            database = database,
            media = cloudIndexDao,
            channel = cloudChannelDao,
        )
    }

    val localPresenceLookup: LocalPresenceLookup by lazy { LocalPresenceLookup(database.mediaDao()) }

    private val telegramCloudRepository: TelegramCloudRepository by lazy {
        TdLibCloudRepository(client = telegramClient)
    }

    /** The cloud start-up state machine; the Cloud screen renders its [CloudInitState] directly. */
    val cloudSync: SynchronizeCloudUseCase by lazy {
        SynchronizeCloudUseCase(
            telegram = telegramCloudRepository,
            index = cloudIndexRepository,
            isAuthenticated = {
                telegramAuthRepository.state.value is TelegramAuthState.Authenticated
            },
            nowSeconds = { System.currentTimeMillis() / 1000 },
        )
    }

    private fun telegramStorage(): TelegramStorage =
        TelegramStorage(
            databaseDirectory = appContext.getDir("tdlib_database", Context.MODE_PRIVATE),
            filesDirectory = appContext.getDir("tdlib_files", Context.MODE_PRIVATE),
        ).ensureCreated()
}
