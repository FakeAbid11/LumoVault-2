package com.lumovault.app

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.work.WorkerParameters
import com.lumovault.app.data.backup.BackupNotifications
import com.lumovault.app.data.backup.BackupScheduler
import com.lumovault.app.data.backup.BackupUploadWorker
import com.lumovault.app.data.local.AppSettingsStore
import com.lumovault.app.data.local.LumoVaultDatabase
import com.lumovault.app.data.local.backup.BackupQueueDao
import com.lumovault.app.data.local.cloud.CloudChannelDao
import com.lumovault.app.data.local.cloud.CloudMediaDao
import com.lumovault.app.data.local.mediastore.MediaStoreDataSource
import com.lumovault.app.data.local.metadata.MediaMetadataDao
import com.lumovault.app.data.local.organization.AlbumDao
import com.lumovault.app.data.local.organization.MediaOrganizationDao
import com.lumovault.app.data.local.organization.SystemAlbumDao
import com.lumovault.app.data.local.restore.MediaRestoreDao
import com.lumovault.app.data.media.ContentResolverMediaHasher
import com.lumovault.app.data.media.MediaFileStager
import com.lumovault.app.data.media.MediaStoreLocalDeleter
import com.lumovault.app.data.media.MediaStoreRestoredWriter
import com.lumovault.app.data.metadata.ExifMediaMetadataReader
import com.lumovault.app.data.remote.telegram.DeviceInfo
import com.lumovault.app.data.remote.telegram.TdLibClient
import com.lumovault.app.data.remote.telegram.TdLibCloudRepository
import com.lumovault.app.data.remote.telegram.TdLibOriginalRepository
import com.lumovault.app.data.remote.telegram.TdLibPreviewRepository
import com.lumovault.app.data.remote.telegram.TdLibUploadRepository
import com.lumovault.app.data.remote.telegram.TelegramAuthRepositoryImpl
import com.lumovault.app.data.remote.telegram.TelegramClient
import com.lumovault.app.data.remote.telegram.TelegramClientInfo
import com.lumovault.app.data.remote.telegram.TelegramCredentials
import com.lumovault.app.data.remote.telegram.TelegramStorage
import com.lumovault.app.data.repository.AlbumRepositoryImpl
import com.lumovault.app.data.repository.BackupQueueRepositoryImpl
import com.lumovault.app.data.repository.CloudIndexRepositoryImpl
import com.lumovault.app.data.repository.CountryRepositoryImpl
import com.lumovault.app.data.repository.FreeUpSpaceRepositoryImpl
import com.lumovault.app.data.repository.LocalPresenceLookup
import com.lumovault.app.data.repository.MediaMetadataRepositoryImpl
import com.lumovault.app.data.repository.MediaOrganizationRepositoryImpl
import com.lumovault.app.data.repository.MediaRepositoryImpl
import com.lumovault.app.data.repository.OnboardingRepositoryImpl
import com.lumovault.app.data.repository.RestoreRepositoryImpl
import com.lumovault.app.data.repository.SettingsRepositoryImpl
import com.lumovault.app.data.repository.SystemPermissionsRepositoryImpl
import com.lumovault.app.domain.backup.BackupQueueRepository
import com.lumovault.app.domain.backup.MediaContentHasher
import com.lumovault.app.domain.backup.MediaSourceStager
import com.lumovault.app.domain.backup.TelegramUploadRepository
import com.lumovault.app.domain.map.MapFocus
import com.lumovault.app.domain.metadata.MediaContentMetadataReader
import com.lumovault.app.domain.organization.AlbumRepository
import com.lumovault.app.domain.organization.MediaOrganizationRepository
import com.lumovault.app.domain.repository.CloudIndexRepository
import com.lumovault.app.domain.repository.CountryRepository
import com.lumovault.app.domain.repository.FreeUpSpaceRepository
import com.lumovault.app.domain.repository.MediaMetadataRepository
import com.lumovault.app.domain.repository.MediaRepository
import com.lumovault.app.domain.repository.OnboardingRepository
import com.lumovault.app.domain.repository.PermissionRepository
import com.lumovault.app.domain.repository.SettingsRepository
import com.lumovault.app.domain.restore.RestoreRepository
import com.lumovault.app.domain.restore.RestoredMediaWriter
import com.lumovault.app.domain.telegram.TelegramAuthRepository
import com.lumovault.app.domain.telegram.TelegramAuthState
import com.lumovault.app.domain.telegram.TelegramCloudRepository
import com.lumovault.app.domain.telegram.TelegramOriginalRepository
import com.lumovault.app.domain.telegram.TelegramPreviewRepository
import com.lumovault.app.domain.usecase.ExtractMediaMetadataUseCase
import com.lumovault.app.domain.usecase.FreeUpSpaceUseCase
import com.lumovault.app.domain.usecase.RecognizeBackupUseCase
import com.lumovault.app.domain.usecase.RestoreCloudMediaUseCase
import com.lumovault.app.domain.usecase.RunBackupQueueUseCase
import com.lumovault.app.domain.usecase.SynchronizeCloudUseCase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
            organization = mediaOrganizationDao,
            albums = albumDao,
            metadata = mediaMetadataDao,
        )
    }

    private val albumDao: AlbumDao by lazy { database.albumDao() }

    private val mediaOrganizationDao: MediaOrganizationDao by lazy { database.mediaOrganizationDao() }

    private val systemAlbumDao: SystemAlbumDao by lazy { database.systemAlbumDao() }

    /**
     * User albums.
     *
     * It gets no reference to the media or backup tables on purpose: an album holds ids, and the only way
     * for "delete this album" to remove a photo or its backup would be for this class to reach past its
     * own DAOs.
     */
    val albumRepository: AlbumRepository by lazy {
        AlbumRepositoryImpl(albums = albumDao, nowSeconds = ::unixNow)
    }

    /** Favourite, archive and Trash — organisation that never enqueues an upload by construction. */
    val mediaOrganizationRepository: MediaOrganizationRepository by lazy {
        MediaOrganizationRepositoryImpl(
            organization = mediaOrganizationDao,
            systemAlbums = systemAlbumDao,
            media = database.mediaDao(),
            albums = albumDao,
            nowSeconds = ::unixNow,
            inTransaction = { block -> database.withTransaction(block) },
        )
    }

    /**
     * The one piece of Trash that touches the platform.
     *
     * Deliberately not behind a domain interface: its whole job is to hand an `IntentSender` to an
     * Activity result launcher, and hiding that inside a domain type would only move the Android
     * dependency somewhere less obvious. The bookkeeping that follows a confirmed deletion is in
     * [mediaOrganizationRepository], which is unit-testable.
     */
    val localMediaDeleter: MediaStoreLocalDeleter by lazy {
        MediaStoreLocalDeleter(appContext.contentResolver)
    }

    private val mediaMetadataDao: MediaMetadataDao by lazy { database.mediaMetadataDao() }

    /** Where and with what a photo was taken — see [MediaMetadataRepository]. */
    val mediaMetadataRepository: MediaMetadataRepository by lazy {
        MediaMetadataRepositoryImpl(mediaMetadata = mediaMetadataDao)
    }

    /**
     * Reads EXIF through the content resolver, asking for the unredacted file only while the media-location
     * grant is actually held.
     *
     * A method reference rather than a stored boolean, because the grant can be revoked from system settings
     * while LumoVault is backgrounded and a remembered answer would start recording "no coordinates" for
     * files whose coordinates were simply being withheld.
     */
    private val mediaMetadataReader: MediaContentMetadataReader by lazy {
        ExifMediaMetadataReader(
            resolver = appContext.contentResolver,
            locationAccessGranted = permissionRepository::mediaLocationGranted,
        )
    }

    /** The bounded EXIF pass behind the map and the details panel. */
    val extractMediaMetadata: ExtractMediaMetadataUseCase by lazy {
        ExtractMediaMetadataUseCase(
            metadata = mediaMetadataRepository,
            reader = mediaMetadataReader,
            nowSeconds = ::unixNow,
        )
    }

    /**
     * Starts one metadata pass on the application's own scope, if none is running.
     *
     * Called when the map appears rather than from a worker. The work is real I/O against the user's files
     * and it is worth doing only while something is going to show the result, so it belongs to a screen
     * being opened and not to a background constraint; the use case already refuses to overlap itself and
     * stops at its own budget. Application scope rather than a view model's, so a pass that has begun is not
     * cancelled by tapping a marker and landing in the viewer.
     */
    fun startMetadataExtraction() {
        applicationScope.launch { extractMediaMetadata.run() }
    }

    private val telegramCredentials: TelegramCredentials by lazy { TelegramCredentials.fromBuildConfig() }

    /**
     * One TDLib client for the whole process, shared by authentication and the cloud scanner.
     *
     * Not an implementation convenience: TDLib's Java Client routes every answer through one receiver
     * thread it starts itself, and a second client would open the same session database a second time.
     */
    private val telegramClient: TelegramClient by lazy {
        // Reports unavailable when the APK carries no libtdjni.so. See build-tdlib.yml.
        TdLibClient(credentials = telegramCredentials)
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

    val localPresenceLookup: LocalPresenceLookup by lazy {
        // Two tables because the question has two halves: the media index for what is on the device, and
        // the backup records for which of those files the channel already holds by content.
        LocalPresenceLookup(database.mediaDao(), database.backupQueueDao())
    }

    private val telegramCloudRepository: TelegramCloudRepository by lazy {
        TdLibCloudRepository(client = telegramClient)
    }

    /**
     * Thumbnails only. Deliberately built on the same [telegramClient] as the rest of Telegram, since
     * TDLib allows one receive loop per process.
     */
    val telegramPreviewRepository: TelegramPreviewRepository by lazy {
        TdLibPreviewRepository(client = telegramClient)
    }

    /**
     * The cloud start-up state machine; the Cloud screen renders its [CloudInitState] directly.
     *
     * `isAuthenticated` reads a live value rather than a remembered one, because a session can be
     * revoked outside the app and a check that consults it is the only honest kind.
     */
    val cloudSync: SynchronizeCloudUseCase by lazy {
        SynchronizeCloudUseCase(
            telegram = telegramCloudRepository,
            index = cloudIndexRepository,
            isAuthenticated = {
                telegramAuthRepository.state.value is TelegramAuthState.Authenticated
            },
            nowSeconds = ::unixNow,
        )
    }

    private val backupQueueDao: BackupQueueDao by lazy { database.backupQueueDao() }

    /**
     * The queue's policy lives with the repository, not with the worker: how many attempts an item
     * gets and what counts as retryable are properties of the queue, and a second caller would
     * otherwise have to agree with the first by coincidence.
     */
    val backupQueueRepository: BackupQueueRepository by lazy {
        BackupQueueRepositoryImpl(dao = backupQueueDao, nowSeconds = ::unixNow)
    }

    /**
     * Staging copies live in the cache directory on purpose: it is excluded from backup and from
     * device transfer by definition, and a staged original is a temporary copy of a file the user
     * still has. If the system evicts one mid-upload, the copy fails, the item is re-queued, and
     * nothing pretends the file was lost.
     */
    private val mediaStager: MediaSourceStager by lazy {
        MediaFileStager(
            resolver = appContext.contentResolver,
            directory = File(appContext.cacheDir, "backup_staging").apply { mkdirs() },
        )
    }

    private val telegramUploadRepository: TelegramUploadRepository by lazy {
        // The same single client as authentication and the cloud scanner — TDLib permits one per
        // process, and an upload is a `sendMessage` like any other request.
        TdLibUploadRepository(client = telegramClient)
    }

    private val mediaRestoreDao: MediaRestoreDao by lazy { database.mediaRestoreDao() }

    /** In-flight and finished restore requests — see [RestoreRepository]. */
    val restoreRepository: RestoreRepository by lazy {
        RestoreRepositoryImpl(dao = mediaRestoreDao, nowSeconds = ::unixNow)
    }

    /**
     * Downloads originals through the same typed client as everything else, on the file type the cloud
     * index recorded. A second TDLib client is not available to this process and `getRemoteFile` is not a
     * transport — it is a lookup into the one client's own file table.
     */
    private val telegramOriginalRepository: TelegramOriginalRepository by lazy {
        TdLibOriginalRepository(client = telegramClient)
    }

    /**
     * Files a downloaded original into MediaStore.
     *
     * `downloadDirectory` is TDLib's own cache because that is where the bytes land, and `mediaVolume` is
     * the app's external files directory used only as a *probe* of the shared volume's free space — asking
     * `Environment.getExternalStorageDirectory()` for its space is deprecated from API 29, and this path is
     * on the same volume MediaStore's primary collection lives on without needing a permission to stat it.
     */
    private val restoredMediaWriter: RestoredMediaWriter by lazy {
        MediaStoreRestoredWriter(
            resolver = appContext.contentResolver,
            downloadDirectory = telegramStorage().filesDirectory,
            mediaVolume = appContext.getExternalFilesDir(null) ?: appContext.filesDir,
        )
    }

    /** PRD section 52's flow, end to end. */
    val restoreCloudMedia: RestoreCloudMediaUseCase by lazy {
        RestoreCloudMediaUseCase(
            restores = restoreRepository,
            downloads = telegramOriginalRepository,
            writer = restoredMediaWriter,
            queue = backupQueueRepository,
            media = mediaRepository,
            hasher = mediaContentHasher,
            scope = applicationScope,
        )
    }

    /**
     * Settles restores a previous process left mid-transfer, before anything can draw a progress bar.
     *
     * Run from the Cloud screen's first composition rather than from Application.onCreate: releasing a file
     * needs TDLib, and TDLib is not started by this class until something asks for Telegram. Nothing is lost
     * by the delay — the row is honest about being live either way, and the file it names is in the app's own
     * cache, which the system may reclaim regardless.
     */
    fun reconcileRestores() {
        applicationScope.launch { restoreCloudMedia.reconcileAfterStart() }
    }

    /** What may be removed from this device because it is verifiably stored elsewhere. */
    val freeUpSpaceRepository: FreeUpSpaceRepository by lazy {
        FreeUpSpaceRepositoryImpl(dao = database.freeUpSpaceDao())
    }

    val freeUpSpace: FreeUpSpaceUseCase by lazy {
        FreeUpSpaceUseCase(
            freeUpSpace = freeUpSpaceRepository,
            organization = mediaOrganizationRepository,
            media = mediaRepository,
        )
    }

    /**
     * Reads files to hash them, and nothing else.
     *
     * Separate from [mediaStager] on purpose: staging exists because TDLib needs a path, while recognition
     * needs only to know what the bytes are, and copying a four-gigabyte video into the cache to hash it
     * would spend disk to learn a fact a stream can give for free.
     */
    private val mediaContentHasher: MediaContentHasher by lazy {
        ContentResolverMediaHasher(resolver = appContext.contentResolver)
    }

    /**
     * Backup recognition: content identity, and whether the channel already holds it.
     *
     * It spans three collaborators — the queue's records, the cloud index, and the file system behind
     * MediaStore — which is what puts it in `domain/usecase` rather than inside either repository.
     */
    val recognizeBackup: RecognizeBackupUseCase by lazy {
        RecognizeBackupUseCase(
            queue = backupQueueRepository,
            cloud = cloudIndexRepository,
            hasher = mediaContentHasher,
        )
    }

    val backupNotifications: BackupNotifications by lazy { BackupNotifications(appContext) }

    /**
     * The backup queue, wired to the channel the cloud flow adopted.
     *
     * The channel is resolved per pass rather than captured once: the association can change between
     * passes — a rediscovery after a deletion, or a different account signing in — and a queue holding
     * on to a stale chat id would either fail or, worse, find a channel that is not the one it was
     * meant to use.
     */
    val runBackupQueue: RunBackupQueueUseCase by lazy {
        RunBackupQueueUseCase(
            queue = backupQueueRepository,
            upload = telegramUploadRepository,
            stager = mediaStager,
            recognition = recognizeBackup,
            resolveChannel = { cloudIndexRepository.association()?.chatId ?: NO_CHANNEL },
        )
    }

    /** The viewer's "view on map" hands the map a centre through this, not through a route argument. */
    val mapFocus = MapFocus()

    val backupScheduler: BackupScheduler by lazy { BackupScheduler(appContext) }

    /** Called by [BackupWorkerFactory]; WorkManager cannot construct a worker with dependencies. */
    fun newBackupUploadWorker(parameters: WorkerParameters): BackupUploadWorker = BackupUploadWorker(
        context = appContext,
        parameters = parameters,
        queue = backupQueueRepository,
        runner = runBackupQueue,
        recognizer = recognizeBackup,
        notifications = backupNotifications,
    )

    /** Removes staging copies left by a previous process, before anything can be queued again. */
    fun prepareStagingForBackup() {
        mediaStager.purgeStale()
    }

    private fun telegramStorage(): TelegramStorage =
        TelegramStorage(
            databaseDirectory = appContext.getDir("tdlib_database", Context.MODE_PRIVATE),
            filesDirectory = appContext.getDir("tdlib_files", Context.MODE_PRIVATE),
        ).ensureCreated()

    private fun unixNow(): Long = System.currentTimeMillis() / 1000

    private companion object {
        /** TDLib reserves 0 for "no identifier", so it also means "no channel adopted yet". */
        const val NO_CHANNEL = 0L
    }
}
