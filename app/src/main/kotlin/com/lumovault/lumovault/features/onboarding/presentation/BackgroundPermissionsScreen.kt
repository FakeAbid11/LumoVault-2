package com.lumovault.lumovault.features.onboarding.presentation

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R

/**
 * Background-permissions screen — informational, never blocking.
 *
 * Ported from permissions_screen.dart's notification/battery cards and the
 * intent behind background_permissions_screen.dart. The original's
 * per-manufacturer step wizard has no Kotlin counterpart (no BrandSettings
 * service exists yet), so the brand advice survives as a static note.
 * Continue is always enabled — both settings are recommended, not required.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundPermissionsScreen(
    onNext: () -> Unit,
    onBack: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    // Collecting lifecycle state forces a recomposition when the user returns
    // from the battery settings page, so the statuses re-read fresh.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow
        .collectAsStateWithLifecycle()
    val batteryExempt = remember(lifecycleState) {
        val pm = context.getSystemService(PowerManager::class.java)
        pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    val notificationsBuiltin = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
    var notificationsGranted by remember(lifecycleState) {
        mutableStateOf(
            notificationsBuiltin || viewModel.permissionService.isGranted(
                android.Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.onboarding_background_title)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_background_heading),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.onboarding_background_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            BatteryCard(exempt = batteryExempt, onOpen = {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    )
                } catch (e: ActivityNotFoundException) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:${context.packageName}")
                        },
                    )
                }
            })
            Spacer(Modifier.height(12.dp))
            NotificationCard(
                builtin = notificationsBuiltin,
                granted = notificationsGranted,
                onGrant = {
                    notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                },
            )
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.onboarding_background_brand_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Row {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.onboarding_back))
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = onNext, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.onboarding_continue))
                }
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun BatteryCard(exempt: Boolean, onOpen: () -> Unit) {
    InfoCard(
        icon = Icons.Default.BatterySaver,
        title = stringResource(R.string.onboarding_battery_title),
        description = stringResource(R.string.onboarding_battery_description),
        status = stringResource(
            if (exempt) R.string.onboarding_battery_enabled
            else R.string.onboarding_battery_not_set,
        ),
        statusOk = exempt,
    ) {
        if (!exempt) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_battery_open_settings))
            }
        }
    }
}

@Composable
private fun NotificationCard(builtin: Boolean, granted: Boolean, onGrant: () -> Unit) {
    InfoCard(
        icon = Icons.Default.Notifications,
        title = stringResource(R.string.onboarding_notifications_title),
        description = stringResource(R.string.onboarding_notifications_description),
        status = stringResource(
            when {
                builtin -> R.string.onboarding_notifications_builtin
                granted -> R.string.onboarding_notifications_granted
                else -> R.string.onboarding_notifications_not_granted
            },
        ),
        statusOk = builtin || granted,
    ) {
        if (!builtin && !granted) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onGrant, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_notifications_grant))
            }
        }
    }
}

@Composable
private fun InfoCard(
    icon: ImageVector,
    title: String,
    description: String,
    status: String,
    statusOk: Boolean,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (statusOk) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            content()
        }
    }
}
