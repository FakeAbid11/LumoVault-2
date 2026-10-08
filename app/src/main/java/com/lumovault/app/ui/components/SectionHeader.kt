package com.lumovault.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lumovault.app.ui.theme.LumoVaultType
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceXs

/**
 * A heading that separates one group of settings from the next.
 *
 * A heading with no rule under it: the gap is the separator. This is the app's one section heading — it
 * was written twice (the backup hub's and the settings hub's, as two private copies) and a third time on
 * the Albums screen, which is how two screens end up disagreeing about how loud a section title is.
 *
 * [modifier] is applied after the padding rather than instead of it, so a caller can widen the heading
 * but cannot drop the gap that makes it a separator — which is the one job this composable has.
 */
@Composable
internal fun SectionHeader(@StringRes label: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(label),
        style = LumoVaultType.sectionHeader,
        // onSurface, like every other section heading in the app: this tint was the one screen that
        // muted it, and a heading that changes weight by room reads as a different kind of heading.
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth().padding(top = SpaceMd, bottom = SpaceXs),
    )
}