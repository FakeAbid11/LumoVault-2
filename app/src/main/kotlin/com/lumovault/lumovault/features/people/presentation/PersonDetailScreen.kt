package com.lumovault.lumovault.features.people.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lumovault.lumovault.R
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.features.gallery.presentation.EmptyCollection
import com.lumovault.lumovault.features.gallery.presentation.MediaGrid
import java.io.File
import kotlinx.coroutines.launch

/** Header for the person detail: thumbnail avatar, name (or add-name), count. */
@Composable
private fun PersonHeader(
    name: String?,
    photoCount: Int,
    thumbnailPath: String?,
    onAddName: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(64.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnailPath != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(File(thumbnailPath)).crossfade(false).build(),
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Default.Person, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                name ?: stringResource(R.string.person_detail_unnamed),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W600),
            )
            Text(
                stringResource(R.string.person_detail_photo_count, photoCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (name == null) {
                TextButton(onClick = onAddName) {
                    Text(stringResource(R.string.person_detail_add_name))
                }
            }
        }
    }
}

/**
 * One person: face avatar header, their photos, rename, merge, delete, and
 * correction mode ("this is not the person" / move to another person).
 *
 * Ported from person_detail_screen.dart. Long-press a photo to enter
 * correction mode; taps then mark photos instead of opening the viewer, and
 * back exits the mode first (the PopScope guard).
 *
 * Correction mode operates on faces, not photos — the DAO has no
 * "faces for this person on this media item" query, so the selected photos'
 * face ids are derived from the already-loaded [PersonDetailViewModel.faces]
 * list rather than a new query.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailScreen(
    personId: Long,
    onBack: () -> Unit,
    onOpenItem: (Int, List<MediaItemEntity>) -> Unit,
    viewModel: PersonDetailViewModel = hiltViewModel(),
) {
    val person by viewModel.person.collectAsStateWithLifecycle()
    val faces by viewModel.faces.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val thumbnailPath by viewModel.thumbnailPath.collectAsStateWithLifecycle()
    val otherPeople by viewModel.otherPeople.collectAsStateWithLifecycle()

    LaunchedEffect(personId) { viewModel.openPerson(personId) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedMediaIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showMergePicker by remember { mutableStateOf(false) }
    var pendingMergeTarget by remember { mutableStateOf<Long?>(null) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var showMovePicker by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun exitSelection() {
        selectionMode = false
        selectedMediaIds = emptySet()
    }

    BackHandler(enabled = selectionMode) { exitSelection() }

    /** Face ids on the selected photos that belong to this person. */
    fun selectedFaceIds(): List<Long> =
        faces.filter { it.mediaItemId in selectedMediaIds }.map { it.id }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            selectionMode -> stringResource(R.string.collection_n_selected, selectedMediaIds.size)
                            else -> person?.name ?: stringResource(R.string.person_detail_fallback_title)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (selectionMode) exitSelection() else onBack() }) {
                        Icon(
                            if (selectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },

                actions = {
                    if (selectionMode) {
                        IconButton(
                            onClick = { showRemoveConfirm = true },
                            enabled = selectedMediaIds.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.PersonOff, contentDescription = stringResource(R.string.person_detail_not_this_person))
                        }
                        IconButton(
                            onClick = { showMovePicker = true },
                            enabled = selectedMediaIds.isNotEmpty(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.person_detail_move_to_another))
                        }
                    } else {
                        IconButton(onClick = { showRename = true }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.person_detail_edit_name))
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.person_detail_merge_with)) },
                                leadingIcon = { Icon(Icons.Default.CallMerge, contentDescription = null) },
                                onClick = { menuOpen = false; showMergePicker = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.person_detail_delete)) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; showDelete = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val currentPerson = person
        if (currentPerson == null) {
            EmptyCollection(
                title = stringResource(R.string.person_detail_not_found_title),
                explanation = stringResource(R.string.person_detail_not_found_explanation),
                modifier = Modifier.padding(padding),
            )
        } else {
            Column(modifier = Modifier.padding(padding)) {
                PersonHeader(
                    name = currentPerson.name,
                    photoCount = photos.size,
                    thumbnailPath = thumbnailPath,
                    onAddName = { showRename = true },
                )
                if (photos.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.person_detail_empty_photos),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    MediaGrid(
                        items = photos,
                        onItemClick = { index ->
                            val item = photos.getOrNull(index) ?: return@MediaGrid
                            if (selectionMode) {
                                selectedMediaIds = if (item.localId in selectedMediaIds) {
                                    selectedMediaIds - item.localId
                                } else {
                                    selectedMediaIds + item.localId
                                }
                            } else {
                                onOpenItem(index, photos)
                            }
                        },
                        selection = selectedMediaIds,
                        onItemLongClick = { localId ->
                            if (!selectionMode) selectionMode = true
                            selectedMediaIds = if (localId in selectedMediaIds) {
                                selectedMediaIds - localId
                            } else {
                                selectedMediaIds + localId
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }


    // -- Rename --
    if (showRename) {
        var name by remember { mutableStateOf(person?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.person_detail_rename_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.person_detail_name_label)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.rename(name); showRename = false },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.person_detail_save_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // -- Delete person (groupings go, photos stay) --
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.person_detail_delete_confirm_title)) },
            text = { Text(stringResource(R.string.person_detail_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete()
                    showDelete = false
                    onBack()
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // -- Merge this person into another --
    if (showMergePicker) {
        AlertDialog(
            onDismissRequest = { showMergePicker = false },
            title = { Text(stringResource(R.string.people_merge_into_which)) },
            text = {
                Column {
                    if (otherPeople.isEmpty()) {
                        Text(stringResource(R.string.person_detail_empty_photos))
                    }
                    otherPeople.forEach { candidate ->
                        TextButton(onClick = {
                            showMergePicker = false
                            pendingMergeTarget = candidate.person.id
                        }) {
                            Text(
                                (candidate.person.name ?: stringResource(R.string.people_default_person_name)) +
                                    " (" + candidate.photoCount + ")",
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showMergePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    pendingMergeTarget?.let { targetId ->
        val targetName = otherPeople.firstOrNull { it.person.id == targetId }?.person?.name
            ?: stringResource(R.string.people_default_person_name)
        AlertDialog(
            onDismissRequest = { pendingMergeTarget = null },
            title = { Text(stringResource(R.string.person_detail_merge_confirm_title)) },
            text = { Text(stringResource(R.string.person_detail_merge_confirm_message, targetName)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.mergeInto(targetId)
                    pendingMergeTarget = null
                    onBack()
                }) { Text(stringResource(R.string.people_merge_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingMergeTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // -- "This is not the person" --
    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title = { Text(stringResource(R.string.person_detail_remove_faces_title)) },
            text = { Text(stringResource(R.string.person_detail_remove_faces_message, selectedMediaIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selectedFaceIds()
                    viewModel.removeFaces(ids)
                    exitSelection()
                    showRemoveConfirm = false
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            context.resources.getString(R.string.person_detail_removed, ids.size),
                        )
                    }
                }) { Text(stringResource(R.string.person_detail_remove_faces_action), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
