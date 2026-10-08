package com.lumovault.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.lumovault.app.ui.theme.SpaceSm

/**
 * One line that answers "how does this stand?": the label, and the answer as words, a [StatusPill] or a
 * figure.
 *
 * This is the settings lists' row and the diagnostics panel's row, and it is one function because the
 * two were the same body written twice — which is exactly how "Connected" ended up one shade on the
 * account screen and another in diagnostics. The rule it encodes: a value that *is* a state (connected,
 * allowed, restricted) earns the chip, so the words that say how things stand are the same words in the
 * same colours everywhere; figures, timestamps and constraint summaries stay plain text, because they
 * are data about a state rather than the state itself, and a chip around a number only makes the row
 * harder to scan.
 */
@Composable
internal fun DetailRow(
    @StringRes label: Int,
    value: String,
    tone: PillTone? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = SpaceSm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tone == null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        } else {
            StatusPill(text = value, tone = tone)
        }
    }
}