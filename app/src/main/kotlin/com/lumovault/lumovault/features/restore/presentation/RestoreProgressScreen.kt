package com.lumovault.lumovault.features.restore.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import com.lumovault.lumovault.features.restore.data.service.RestorePhase as RestoreRunPhase

/**
 * Coarse restore phases, mirroring the original's `RestorePhase` collapsed to
 * the three user-visible steps: scan the channel, download media, rebuild the
 * local library. No engine exists to produce these yet; the screen renders
 * the idle state unless a caller passes [RestoreUiState].
 */
enum class RestorePhase { scan, download, rebuild }

/**
 * Optional progress state. Null (the default) means idle — no restore running.
 */
data class RestoreUiState(
    val phase: RestorePhase = RestorePhase.scan,
    val itemsDone: Int = 0,
    val itemsTotal: Int = 0,
)

/**
 * Restore progress — live layout for the three phases.
 *
 * Ported from restore_progress_screen.dart (PRD 10.3). State comes from the
 * restore controller via [RestoreViewModel]: a null [state] still means idle
 * (nothing ever started), while a running or finished run renders its phases
 * and counters. The original's speed/ETA and pause/cancel remain unported.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreProgressScreen(
    onBack: () -> Unit,
    viewModel: RestoreViewModel = hiltViewModel(),
) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val state = if (progress.running || progress.itemsDone > 0) {
        // Map the controller's phase onto this screen's presentation enum.
        val phase = when (progress.phase) {
            RestoreRunPhase.scan -> RestorePhase.scan
            RestoreRunPhase.download -> RestorePhase.download
            RestoreRunPhase.rebuild -> RestorePhase.rebuild
        }
        RestoreUiState(
            phase = phase,
            itemsDone = progress.itemsDone,
            itemsTotal = progress.itemsTotal,
        )
    } else {
        null
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.restore_progress_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
        ) {
            PhaseRow(
                label = stringResource(R.string.restore_phase_scan),
                phase = RestorePhase.scan,
                state = state,
            )
            Spacer(modifier = Modifier.height(16.dp))
            PhaseRow(
                label = stringResource(R.string.restore_phase_download),
                phase = RestorePhase.download,
                state = state,
            )
            Spacer(modifier = Modifier.height(16.dp))
            PhaseRow(
                label = stringResource(R.string.restore_phase_rebuild),
                phase = RestorePhase.rebuild,
                state = state,
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (state == null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.restore_progress_idle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else if (state.itemsTotal > 0) {
                Text(
                    stringResource(
                        R.string.restore_progress_items,
                        state.itemsDone,
                        state.itemsTotal,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (progress.failed > 0) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    stringResource(R.string.restore_progress_failed, progress.failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            progress.error?.let { message ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * One phase row: a check when the phase is behind the current one, an
 * indeterminate indicator when it is the current one, plain text otherwise.
 */
@Composable
private fun PhaseRow(label: String, phase: RestorePhase, state: RestoreUiState?) {
    val done = state != null && phase.ordinal < state.phase.ordinal
    val active = state != null && phase == state.phase
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (done) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        } else if (active) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            Spacer(modifier = Modifier.padding(horizontal = 12.dp))
        }
        Spacer(modifier = Modifier.padding(horizontal = 8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (state == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
    if (active) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
    }
}
