package com.lumovault.lumovault.features.gallery.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.features.gallery.data.repository.GalleryRepository
import com.lumovault.lumovault.features.gallery.data.service.ClipEmbeddingService
import com.lumovault.lumovault.features.gallery.data.service.ImageClassifierService
import com.lumovault.lumovault.features.settings.domain.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AiScanState(
    val running: Boolean = false,
    val completed: Int = 0,
    val total: Int = 0,
    val error: String? = null,
) {
    val progress: Float get() = if (total == 0) 0f else completed.toFloat() / total
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: GalleryRepository,
    private val clipService: ClipEmbeddingService,
    private val classifier: ImageClassifierService,
    val settings: StateFlow<AppSettings>,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    val semanticMode = MutableStateFlow(true)

    private val _similarTo = MutableStateFlow<String?>(null)
    val similarTo: StateFlow<String?> = _similarTo

    private val _aiScanState = MutableStateFlow(AiScanState())
    val aiScanState: StateFlow<AiScanState> = _aiScanState
    private var aiScanJob: Job? = null

    val results: StateFlow<List<MediaItemEntity>> = _query
        .debounce(DEBOUNCE_MS)
        .flatMapLatest { q ->
            flow {
                val trimmed = q.trim()
                if (trimmed.isEmpty()) {
                    emit(emptyList())
                    return@flow
                }
                if (semanticMode.value) {
                    val embedding = clipService.embedText(trimmed)
                    if (embedding != null) {
                        emit(repository.semanticSearch(embedding))
                        return@flow
                    }
                }
                emit(repository.search(trimmed))
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val similarResults: StateFlow<List<MediaItemEntity>> = _similarTo
        .debounce(200)
        .flatMapLatest { localId ->
            flow {
                if (localId == null) {
                    emit(emptyList())
                    return@flow
                }
                val item = repository.mediaItem(localId) ?: run {
                    emit(emptyList())
                    return@flow
                }
                var embedding = item.clipEmbedding?.toFloatArray()
                if (embedding == null) {
                    val thumbBytes = readItemBytes(item)
                    if (thumbBytes != null) {
                        embedding = clipService.embedImage(thumbBytes)
                        if (embedding != null) {
                            repository.updateClipEmbedding(localId, embedding)
                        }
                    }
                }
                if (embedding != null) {
                    emit(repository.semanticSearch(embedding).filter { it.localId != localId })
                } else {
                    emit(emptyList())
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun setSimilarTo(localId: String?) {
        _similarTo.value = localId
    }

    fun toggleSemanticMode() {
        semanticMode.value = !semanticMode.value
    }

    fun startAiScan() {
        if (_aiScanState.value.running) return
        aiScanJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                _aiScanState.value = AiScanState(running = true)
                val labeledIds = repository.labeledLocalIds()
                val items = repository.itemsNeedingEmbedding()
                    .filter { it.localId !in labeledIds }
                if (items.isEmpty()) {
                    _aiScanState.value = AiScanState(error = "All photos are already labeled.")
                    return@launch
                }
                _aiScanState.value = AiScanState(running = true, total = items.size)
                for ((i, item) in items.withIndex()) {
                    if (!aiScanJob?.isActive!!) break
                    try {
                        val bytes = readItemBytes(item) ?: continue
                        val labels = classifier.classify(bytes)
                        if (!labels.isNullOrEmpty()) {
                            repository.labelMediaItem(item.localId, labels)
                        }
                        _aiScanState.value = _aiScanState.value.copy(completed = i + 1)
                    } catch (_: Throwable) {
                        _aiScanState.value = _aiScanState.value.copy(completed = i + 1)
                    }
                }
                _aiScanState.value = AiScanState()
            } catch (e: Throwable) {
                _aiScanState.value = AiScanState(error = e.message ?: "Scan failed")
            }
        }
    }

    fun stopAiScan() {
        aiScanJob?.cancel()
        aiScanJob = null
        _aiScanState.value = AiScanState()
    }

    fun clear() {
        _query.value = ""
        _similarTo.value = null
    }

    private fun readItemBytes(item: MediaItemEntity): ByteArray? {
        return try {
            val uri = Uri.parse(item.filePath)
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Throwable) { null }
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
