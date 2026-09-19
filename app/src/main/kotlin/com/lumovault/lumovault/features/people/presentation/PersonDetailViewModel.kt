package com.lumovault.lumovault.features.people.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.lumovault.core.database.dao.FaceDao
import com.lumovault.lumovault.core.database.dao.PersonWithCount
import com.lumovault.lumovault.core.database.entity.FaceEntity
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.core.database.entity.PersonEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One person's detail state.
 *
 * Ported from personProvider / personMediaIdsProvider / personThumbnailProvider.
 * `personId` is read from the "personId" nav argument (Screen.PersonDetail);
 * [openPerson] exists so the VM is also usable without a nav entry.
 *
 * Faces and photos have no Room flows in [FaceDao], so they are re-derived
 * whenever the people flow re-emits — which covers every mutation that touches
 * face assignment. [openPerson] with a different id re-runs the derivation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PersonDetailViewModel @Inject constructor(
    private val faceDao: FaceDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val personIdFlow = MutableStateFlow(savedStateHandle.get<Long>("personId") ?: -1L)

    val person: StateFlow<PersonEntity?> = combine(personIdFlow, faceDao.allPeopleFlow()) { id, all ->
        all.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val faces: StateFlow<List<FaceEntity>> = personIdFlow
        .filterNotNull()
        .flatMapLatest { id ->
            // Re-derive whenever the people flow re-emits: any mutation that
            // changes face assignment (move/remove/merge) re-runs the query.
            faceDao.allPeopleWithCounts().map { list ->
                if (list.any { it.person.id == id }) faceDao.facesForPerson(id) else emptyList()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val photos: StateFlow<List<MediaItemEntity>> = faces
        .map { faceList ->
            val ids = faceList.map { it.mediaItemId }.distinct()
            if (ids.isEmpty()) {
                emptyList()
            } else {
                faceDao.mediaItemsForPerson(ids).sortedByDescending { it.createdAt }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Thumbnail crop path of the person's representative face, mirroring
     *  personThumbnailProvider: the pinned thumbnail face when set, else the
     *  most recent face. */
    val thumbnailPath: StateFlow<String?> = combine(person, faces) { p, faceList ->
        if (p == null || faceList.isEmpty()) {
            null
        } else {
            (faceList.firstOrNull { it.id == p.thumbnailFaceId } ?: faceList.first()).thumbnailPath
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Other people, for the merge/move target pickers. */
    val otherPeople: StateFlow<List<PersonWithCount>> = combine(
        personIdFlow,
        faceDao.allPeopleWithCounts(),
    ) { id, all -> all.filter { it.person.id != id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun openPerson(personId: Long) {
        personIdFlow.value = personId
    }

    /** Empty submit is "no change", not "erase the name". */
    fun rename(name: String) {
        val id = personIdFlow.value
        val trimmed = name.trim()
        if (id < 0 || trimmed.isEmpty()) return
        viewModelScope.launch {
            faceDao.updatePersonName(id, trimmed, System.currentTimeMillis())
        }
    }

    fun mergeInto(targetId: Long) {
        val id = personIdFlow.value
        if (id < 0 || id == targetId) return
        viewModelScope.launch {
            faceDao.mergePersons(id, targetId)
        }
    }

    /** "Delete Person" — the groupings go, the photos stay. */
    fun delete() {
        val id = personIdFlow.value
        if (id < 0) return
        viewModelScope.launch {
            faceDao.deletePerson(id)
        }
    }

    /** "This is not the person": unassign AND exclude, so the faces never
     *  rejoin via absorption or seed a new cluster. */
    fun removeFaces(faceIds: List<Long>) {
        if (faceIds.isEmpty()) return
        viewModelScope.launch {
            faceDao.excludeFaces(faceIds)
        }
    }

    /**
     * Move faces to an existing person or a fresh one. Unlike removal the faces
     * stay eligible for future clustering.
     *
     * Ported from person_move_dialog's "New person" branch: a `null` target
     * mints an unnamed person and assigns there. Returns the moved count.
     */
    suspend fun moveFaces(faceIds: List<Long>, targetPersonId: Long?): Int {
        val id = personIdFlow.value
        if (id < 0 || faceIds.isEmpty()) return 0
        val target = targetPersonId ?: run {
            val now = System.currentTimeMillis()
            faceDao.createPerson(PersonEntity(createdAt = now, updatedAt = now))
        }
        faceDao.assignFacesToPerson(faceIds, target)
        return faceIds.size
    }
}
