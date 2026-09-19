package com.lumovault.lumovault.features.people.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.lumovault.core.database.dao.FaceDao
import com.lumovault.lumovault.core.database.dao.PersonWithCount
import com.lumovault.lumovault.core.database.entity.FaceEntity
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.core.database.entity.PersonEntity
import com.lumovault.lumovault.features.settings.domain.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * People (faces) tab state.
 *
 * Ported from people_providers.dart. The people list is the Room flow
 * [FaceDao.allPeopleWithCounts], which replaces the Flutter in-memory
 * accumulation over a LEFT JOIN — a rename, merge, or delete re-emits without
 * a manual `invalidate`, so every mutation below is fire-and-forget.
 *
 * The face-scan engine/controller is a later phase: this ViewModel exposes the
 * `faceScanEnabled` setting (via the injected [AppSettings] flow, same as
 * AppThemeViewModel) and [hasScanned], and the screen renders the disabled /
 * never-scanned empty states from those two. It never starts a scan.
 */
@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val faceDao: FaceDao,
    val settings: StateFlow<AppSettings>,
) : ViewModel() {

    val people: StateFlow<List<PersonWithCount>> = faceDao.allPeopleWithCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Total faces stored. Zero while the library has never been scanned. */
    val faceCount: StateFlow<Int> = people
        .map { list -> list.sumOf { it.faceCount } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _hasScanned = MutableStateFlow(false)

    /** False until at least one photo has a face_scans row. `face_scans` has
     *  no Flow query, so this is refreshed when the screen starts and after
     *  every mutation. */
    val hasScanned: StateFlow<Boolean> = _hasScanned.asStateFlow()

    init {
        refreshScanState()
    }

    fun refreshScanState() {
        viewModelScope.launch {
            _hasScanned.value = faceDao.scannedMediaItemCount() > 0
        }
    }

    /** Empty submit is "no change", not "erase the name" — passing null to
     *  [FaceDao.updatePersonName] used to overwrite the stored name with NULL. */
    fun renamePerson(personId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            faceDao.updatePersonName(personId, trimmed, System.currentTimeMillis())
        }
    }

    fun mergePersons(sourceId: Long, targetId: Long) {
        viewModelScope.launch {
            faceDao.mergePersons(sourceId, targetId)
        }
    }

    fun deletePerson(personId: Long) {
        viewModelScope.launch {
            faceDao.deletePerson(personId)
            refreshScanState()
        }
    }

    fun deletePersons(personIds: Set<Long>) {
        viewModelScope.launch {
            personIds.forEach { faceDao.deletePerson(it) }
            refreshScanState()
        }
    }

    /** Crop path for a face id, or null when the face/its crop does not
     *  exist — the People grid's per-person thumbnail lookup
     *  (personThumbnailProvider). */
    suspend fun faceThumbnail(faceId: Long): String? =
        faceDao.allFaces().firstOrNull { it.id == faceId }?.thumbnailPath
}
