package com.lumovault.lumovault.features.settings.presentation

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lumovault.lumovault.R

/** Standard back-arrow top bar for a settings sub-screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsTopBar(@StringRes title: Int, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
    )
}

/**
 * Section header. Ported from the private `_SectionHeader` repeated across the
 * Flutter settings screens (primary-colored titleSmall, 16/16/16/8 padding).
 */
@Composable
internal fun SettingsSectionHeader(@StringRes title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** Switch row, the Compose counterpart of the Flutter SwitchListTile. */
@Composable
internal fun SettingsSwitchItem(
    @StringRes title: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    @StringRes subtitle: Int? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = if (subtitle != null) {
            { Text(stringResource(subtitle)) }
        } else {
            null
        },
        leadingContent = if (icon != null) {
            { Icon(icon, contentDescription = null) }
        } else {
            null
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        },
        modifier = Modifier.clickable(enabled = enabled) { onCheckedChange(!checked) },
    )
}

/** Navigation row, the Compose counterpart of a ListTile with a chevron. */
@Composable
internal fun SettingsNavItem(
    @StringRes title: Int,
    onClick: () -> Unit,
    @StringRes subtitle: Int? = null,
    subtitleText: String? = null,
    icon: ImageVector? = null,
) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = if (subtitleText != null) {
            { Text(subtitleText) }
        } else if (subtitle != null) {
            { Text(stringResource(subtitle)) }
        } else {
            null
        },
        leadingContent = if (icon != null) {
            { Icon(icon, contentDescription = null) }
        } else {
            null
        },
        trailingContent = {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * A labelled group of radio rows, used in place of the Flutter SimpleDialog
 * pickers so no dialog plumbing is needed.
 */
@Composable
internal fun <T> SettingsRadioGroup(
    options: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column {
        options.forEach { (value, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(value) }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                RadioButton(selected = value == selected, onClick = { onSelect(value) })
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
