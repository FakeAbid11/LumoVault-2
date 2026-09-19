package com.lumovault.lumovault.features.onboarding.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lumovault.lumovault.R

/**
 * Permissions screen — the required media-access gate.
 *
 * Ported from permissions_screen.dart, reduced to what the Kotlin
 * PermissionService actually owns: the visual permissions (READ_MEDIA_IMAGES /
 * READ_MEDIA_VIDEO on 33+, READ_EXTERNAL_STORAGE below) plus
 * ACCESS_MEDIA_LOCATION, requested together via
 * [ActivityResultContracts.RequestMultiplePermissions]. Notification and
 * battery cards moved to the background-permissions screen, matching where
 * the flow shows them.
 *
 * Fix carried over deliberately: the original's Skip button navigated past
 * this screen even with media access denied, silently landing the user on a
 * gallery and backup that could not work. Skip here is gated behind a dialog
 * that says exactly that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    onNext: () -> Unit,
    onBack: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val permissionService = viewModel.permissionService
    var granted by remember { mutableStateOf(permissionService.hasVisualPermission()) }
    var showSkipDialog by rememberSaveable { mutableStateOf(false) }

    // ACCESS_MEDIA_LOCATION rides along so EXIF coordinates survive on 29+.
    val mediaPermissions = (permissionService.visualPermissions +
        android.Manifest.permission.ACCESS_MEDIA_LOCATION).distinct().toTypedArray()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // Partial ("limited") access still lists the photos the user picked,
        // which is enough to continue — the original's granted||limited gate.
        granted = permissionService.hasVisualPermission() || results.values.any { it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onboarding_permissions_title)) },
                actions = {
                    TextButton(onClick = {
                        if (granted) onNext() else showSkipDialog = true
                    }) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_permissions_heading),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.onboarding_permissions_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            MediaAccessCard(granted = granted, onGrant = { launcher.launch(mediaPermissions) })
            Spacer(Modifier.height(12.dp))
            RationaleCard()
            Spacer(Modifier.height(24.dp))
            Row {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.onboarding_back))
                }
                Spacer(Modifier.width(16.dp))
                Button(
                    // Part of the defect fix: Continue stays disabled until
                    // access is granted, instead of Skip quietly bypassing it.
                    onClick = onNext,
                    enabled = granted,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.onboarding_continue))
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }

    if (showSkipDialog) {
        AlertDialog(
            onDismissRequest = { showSkipDialog = false },
            title = { Text(stringResource(R.string.onboarding_media_skip_dialog_title)) },
            text = { Text(stringResource(R.string.onboarding_media_skip_dialog_message)) },
            confirmButton = {
                TextButton(onClick = { showSkipDialog = false; onNext() }) {
                    Text(stringResource(R.string.onboarding_media_skip_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSkipDialog = false
                    launcher.launch(mediaPermissions)
                }) {
                    Text(stringResource(R.string.onboarding_media_skip_dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun MediaAccessCard(granted: Boolean, onGrant: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.onboarding_media_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(R.string.onboarding_media_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(
                        if (granted) R.string.onboarding_media_granted
                        else R.string.onboarding_media_required_badge,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            if (!granted) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_media_grant))
                }
            }
        }
    }
}

@Composable
private fun RationaleCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.onboarding_media_rationale_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.onboarding_media_rationale_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
