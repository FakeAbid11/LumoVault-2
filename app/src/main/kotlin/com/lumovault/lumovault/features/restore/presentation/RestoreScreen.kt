package com.lumovault.lumovault.features.restore.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lumovault.lumovault.R

/**
 * Restore entry point.
 *
 * Ported from restore_screen.dart (PRD 10.1). The original auto-detected an
 * existing backup in the storage channel and offered Restore / Start Fresh;
 * that detection ran through the restore engine, which is not part of this
 * build. So instead of a silent auto-check this screen explains what restore
 * will do and renders the "Scan channel" button *disabled* with a caption —
 * the same honesty rule as the backup dashboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.restore_title)) },
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
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text(
                stringResource(R.string.restore_headline),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.restore_explanation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                stringResource(R.string.restore_what_it_does),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    RestoreStep(1, stringResource(R.string.restore_step_scan))
                    Spacer(modifier = Modifier.height(8.dp))
                    RestoreStep(2, stringResource(R.string.restore_step_download))
                    Spacer(modifier = Modifier.height(8.dp))
                    RestoreStep(3, stringResource(R.string.restore_step_rebuild))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Engine pending: disabled with caption, not wired to nothing.
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.restore_scan_channel))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.restore_engine_unavailable_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RestoreStep(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            "$number.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
