package com.lumovault.lumovault.features.settings.presentation

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R

/**
 * Developer options — debug info and the debug-mode toggle.
 *
 * Ported from developer_settings_screen.dart, minus the database-info and
 * sync-status dialogs (their providers do not exist in this rewrite yet).
 * Version comes from PackageManager, guarded by try/catch like the original's
 * `packageInfo.when(error: …)`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember { appVersion(context) }

    Scaffold(
        topBar = { SettingsTopBar(R.string.developer_title, onBack) },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item { SettingsSectionHeader(R.string.developer_section_debug) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.developer_app_version)) },
                    supportingContent = { Text(version) },
                )
            }

            item { HorizontalDivider() }
            item { SettingsSectionHeader(R.string.developer_section_experimental) }
            item {
                SettingsSwitchItem(
                    title = R.string.developer_debug_mode,
                    subtitle = R.string.developer_debug_mode_subtitle,
                    icon = Icons.Default.Science,
                    checked = settings.debugMode,
                    onCheckedChange = { v -> viewModel.update { it.copy(debugMode = v) } },
                )
            }
        }
    }
}

private fun appVersion(context: Context): String = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "${info.versionName} (${info.versionCode})"
} catch (_: Exception) {
    // NameNotFoundException in practice; the original showed 'unavailable'.
    "unavailable"
}
