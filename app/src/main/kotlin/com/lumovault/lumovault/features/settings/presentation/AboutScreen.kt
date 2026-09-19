package com.lumovault.lumovault.features.settings.presentation

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lumovault.lumovault.R

/**
 * About screen — app identity and links.
 *
 * Ported from about_screen.dart. Links are plain text: url_launcher has no
 * counterpart here and the license dialog is a Flutter-ism.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember { aboutVersion(context) }

    Scaffold(
        topBar = { SettingsTopBar(R.string.about_title, onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 32.dp)
                        .size(72.dp),
                )
            }
            item {
                Text(
                    stringResource(R.string.about_app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            item {
                Text(
                    stringResource(R.string.about_version_format, version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                Text(
                    stringResource(R.string.about_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 32.dp)) }
            item {
                SettingsNavItem(
                    title = R.string.about_privacy_policy,
                    subtitle = R.string.about_privacy_policy_url,
                    icon = Icons.Default.Policy,
                    onClick = {},
                )
            }
            item {
                SettingsNavItem(
                    title = R.string.about_source_code,
                    subtitle = R.string.about_source_code_url,
                    icon = Icons.Default.Code,
                    onClick = {},
                )
            }
        }
    }
}

private fun aboutVersion(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
} catch (_: Exception) {
    "unavailable"
}
