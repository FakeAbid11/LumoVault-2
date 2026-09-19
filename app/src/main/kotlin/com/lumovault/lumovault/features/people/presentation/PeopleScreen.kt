package com.lumovault.lumovault.features.people.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lumovault.lumovault.R
import androidx.compose.runtime.produceState
import com.lumovault.lumovault.core.database.dao.PersonWithCount
import com.lumovault.lumovault.features.gallery.presentation.EmptyCollection
import java.io.File

/** One card in the People grid: circular face crop, name, photo count.
 *  Ported from person_tile.dart. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonTile(
    personWithCount: PersonWithCount,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val person = personWithCount.person
    Column(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            PersonThumbnail(faceId = person.thumbnailFaceId, name = person.name)
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            person.name ?: stringResource(R.string.people_default_person_name),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            if (personWithCount.photoCount == 1) {
                stringResource(R.string.people_photo_count_one, personWithCount.photoCount)
            } else {
                stringResource(R.string.people_photo_count, personWithCount.photoCount)
            },
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            ),
        )
    }
}

/** The face crop for [faceId], resolved through [PeopleViewModel.faceThumbnail];
 *  falls back to a person icon while no crop exists. */
@Composable
fun PersonThumbnail(faceId: Long?, name: String?, viewModel: PeopleViewModel = hiltViewModel()) {
    if (faceId == null) {
        Icon(
            Icons.Default.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        return
    }
    val path by produceState<String?>(initialValue = null, faceId) {
        value = viewModel.faceThumbnail(faceId)
    }
    val context = LocalContext.current
    val resolvedPath = path
    if (resolvedPath != null) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(File(resolvedPath))
                .crossfade(false)
                .build(),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Icon(
            Icons.Default.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
    }
}


/**
 * The People (faces) tab.
 *
 * Ported from people_screen.dart. Long-press selects so merge/delete can be
 * aimed at more than one person; back exits selection first, mirroring the
 * PopScope guard.
 *
 * The scan engine is a later phase, so there is deliberately no scan button,
 * progress banner, or rescan action here — the Flutter screen's "Scan for
 * Faces" CTA would do nothing. Instead the empty state explains, distinguishing
 * "face scan is off in Settings" (AppSettings.faceScanEnabled, default false)
 * from "scan enabled but the library has never been scanned".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    onOpenPerson: (Long) -> Unit,
    viewModel: PeopleViewModel = hiltViewModel(),
) {
    val people by viewModel.people.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showMergePicker by remember { mutableStateOf(false) }
    var pendingMergeTarget by remember { mutableStateOf<Long?>(null) }

    // The Flutter PopScope: back exits selection instead of leaving the tab.
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selected.isEmpty()) {
                            stringResource(R.string.people_title)
                        } else {
                            stringResource(R.string.collection_n_selected, selected.size)
                        },
                    )
                },
                navigationIcon = {
                    if (selected.isNotEmpty()) {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                    }
                },
                actions = {
                    if (selected.isNotEmpty()) {
                        IconButton(
                            onClick = { showMergePicker = true },
                            enabled = selected.size >= 2,
                        ) {
                            Icon(Icons.Default.CallMerge, contentDescription = stringResource(R.string.people_merge_selected))
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.people_delete_selected))
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            people.isEmpty() && !settings.faceScanEnabled -> EmptyCollection(
                title = stringResource(R.string.people_scan_disabled_title),
                explanation = stringResource(R.string.people_scan_disabled_explanation),
                modifier = Modifier.padding(padding),
            )
            people.isEmpty() -> EmptyCollection(
                title = stringResource(R.string.people_empty_title),
                explanation = stringResource(R.string.people_empty_explanation),
                modifier = Modifier.padding(padding),
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(people, key = { it.person.id }) { personWithCount ->
                    PersonTile(
                        personWithCount = personWithCount,
                        selected = personWithCount.person.id in selected,
                        onClick = {
                            val id = personWithCount.person.id
                            if (selected.isNotEmpty()) {
                                selected = if (id in selected) selected - id else selected + id
                            } else {
                                onOpenPerson(id)
                            }
                        },
                        onLongClick = {
                            val id = personWithCount.person.id
                            selected = if (id in selected) selected - id else selected + id
                        },
                    )
                }
            }
        }
    }

    if (showMergePicker) {
        val candidates = people.filter { it.person.id in selected }
        AlertDialog(
            onDismissRequest = { showMergePicker = false },
            title = { Text(stringResource(R.string.people_merge_into_which)) },
            text = {
                Column {
                    candidates.forEach { candidate ->
                        TextButton(onClick = {
                            showMergePicker = false
                            pendingMergeTarget = candidate.person.id
                        }) {
                            Text(
                                (candidate.person.name ?: stringResource(R.string.people_default_person_name)) +
                                    " (" + candidate.photoCount + " " +
                                    (if (candidate.photoCount == 1) "photo" else "photos") + ")",
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
        val target = people.firstOrNull { it.person.id == targetId }
        val targetName = target?.person?.name ?: stringResource(R.string.people_default_person_name)
        val sourceCount = selected.size - 1
        AlertDialog(
            onDismissRequest = { pendingMergeTarget = null },
            title = { Text(stringResource(R.string.people_merge_title)) },
            text = { Text(stringResource(R.string.people_merge_message, sourceCount, targetName)) },
            confirmButton = {
                TextButton(onClick = {
                    selected.filter { it != targetId }.forEach { viewModel.mergePersons(it, targetId) }
                    selected = emptySet()
                    pendingMergeTarget = null
                }) { Text(stringResource(R.string.people_merge_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingMergeTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.people_delete_title)) },
            text = { Text(stringResource(R.string.people_delete_message, selected.size)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePersons(selected)
                    selected = emptySet()
                    showDeleteConfirm = false
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}


