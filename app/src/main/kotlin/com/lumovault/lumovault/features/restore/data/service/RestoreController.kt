package com.lumovault.lumovault.features.restore.data.service

import com.lumovault.lumovault.core.di.ApplicationScope
import com.lumovault.lumovault.core.tdlib.TdLibConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Restore phase for the progress screen. */
enum class RestorePhase { scan, download, rebuild }

/** Live restore state observed by the progress screen. */
data class RestoreProgressState(
    val running: Boolean = false,
    val phase: RestorePhase = RestorePhase.scan,
    val itemsDone: Int = 0,
    val itemsTotal: Int = 0,
    val exported: Int = 0,
    val failed: Int = 0,
    val error: String? = null,
)

/**
 * Owns the restore run: scan → (download → rebuild → export) per item.
 *
 * A singleton because the run outlives any screen — the user can navigate
 * away from the progress screen and back without losing the run (the Flutter
 * original achieved the same with a global provider).
 */
@Singleton
class RestoreController @Inject constructor(
    private val engine: RestoreEngine,
    private val connectionManager: TdLibConnectionManager,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(RestoreProgressState())
    val state: StateFlow<RestoreProgressState> = _state.asStateFlow()

    private var job: Job? = null

    val isRunning: Boolean get() = _state.value.running

    /** Starts a full restore. No-op while one is already running. */
    fun start() {
        if (_state.value.running) return
        job = scope.launch {
            _state.value = RestoreProgressState(running = true, phase = RestorePhase.scan)
            try {
                if (!connectionManager.isConnected) connectionManager.connect()
                val items = engine.scanChannel { scanned ->
                    _state.value = _state.value.copy(phase = RestorePhase.scan, itemsTotal = scanned)
                }

                _state.value = _state.value.copy(phase = RestorePhase.download, itemsTotal = items.size)
                for (item in items) {
                    try {
                        val localFile = engine.download(item)
                        val hash = hashFile(localFile)
                        _state.value = _state.value.copy(phase = RestorePhase.rebuild)
                        engine.rebuildRow(item, localFile, hash)
                        if (engine.exportToGallery(item, localFile) != null) {
                            _state.value = _state.value.copy(exported = _state.value.exported + 1)
                        }
                    } catch (_: Throwable) {
                        // One failed file must not abort the restore; count it
                        // and keep the remaining items moving.
                        _state.value = _state.value.copy(failed = _state.value.failed + 1)
                    }
                    _state.value = _state.value.copy(itemsDone = _state.value.itemsDone + 1)
                }
                _state.value = _state.value.copy(running = false)
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    running = false,
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(running = false)
    }

    private fun hashFile(file: java.io.File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private companion object {
        const val BUFFER_BYTES = 8 * 1024
    }
}