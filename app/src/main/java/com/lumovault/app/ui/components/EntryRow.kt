package com.lumovault.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.lumovault.app.ui.theme.LumoVaultType
import com.lumovault.app.ui.theme.MinTouchTarget
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SpaceXs

/**
 * A titled door with a quiet note under it: the app's one entry row.
 *
 * It lives here rather than in either screen that uses it because there are two of them — the settings
 * hub and the backup hub — and two copies of a chevron's reasoning is how they start drawing different
 * chevrons. The chevron is the whole contract: it says there is somewhere to go, which is why the rows
 * that only *do* something (a sign-out that confirms, a theme pick) deliberately are not this row.
 */
@Composable
internal fun EntryRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = SpaceXs, vertical = SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = LumoVaultType.itemTitle, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = subtitle,
                style = LumoVaultType.sectionDetail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}