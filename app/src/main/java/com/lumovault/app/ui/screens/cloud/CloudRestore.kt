package com.lumovault.app.ui.screens.cloud

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lumovault.app.R
import com.lumovault.app.domain.restore.RestoreFailureKind
import com.lumovault.app.domain.restore.RestoreJob
import com.lumovault.app.domain.restore.RestoreState
import com.lumovault.app.util.toByteText

/**
 * What a restore looks like while it is happening, and afterwards.
 *
 * The states come from the row, never from a guess here: an item with no row is *not requested*, and a row in
 * `pending` is a request whose transfer has not started — collapsing those into one spinner would show
 * "downloading" over a phone that has not asked Telegram for anything yet.
 *
 * Progress is drawn only from `downloaded_bytes`, which is TDLib's own count of readable bytes. When the
 * file's size was never stated the bar is indeterminate, because an invented percentage is the kind of number
 * a user trusts and then discovers was false.
 */
@Composable
internal fun RestoreAction(
    job: RestoreJob?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val live = job?.takeIf { it.state.isLive }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (job?.state) {
            null, RestoreState.Failed, RestoreState.Cancelled -> {
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cloud_download_action))
                }
            }

            RestoreState.Completed -> {
                // Enabled again on purpose: the usual reason to tap a finished restore a second time is that
                // Free Up Space removed the local copy since.
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.restore_again_action))
                }
                Text(
                    text = stringResource(R.string.restore_completed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            RestoreState.Verifying -> StatusLine(stringResource(R.string.restore_verifying), live != null)
            RestoreState.Saving -> StatusLine(stringResource(R.string.restore_saving), live != null)
            RestoreState.Pending -> StatusLine(stringResource(R.string.restore_preparing), live != null)

            RestoreState.Downloading -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    text = job.progressText(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.restore_cancel_action))
                }
            }
        }

        job?.failure?.let { reason ->
            Text(
                text = stringResource(reason.labelRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StatusLine(text: String, indeterminate: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (indeterminate) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Bytes over bytes when both are known, and an honest "getting the file" when they are not. */
@Composable
private fun RestoreJob.progressText(): String = fraction?.let {
    stringResource(
        R.string.restore_downloading,
        downloadedBytes.toByteText(),
        expectedSizeBytes.toByteText(),
    )
} ?: stringResource(R.string.restore_downloading_unknown)

@StringRes
internal fun RestoreFailureKind.labelRes(): Int = when (this) {
    RestoreFailureKind.SourceGone -> R.string.restore_reason_gone
    RestoreFailureKind.NotAuthenticated -> R.string.restore_reason_auth
    RestoreFailureKind.Network -> R.string.restore_reason_network
    RestoreFailureKind.RateLimited -> R.string.restore_reason_rate
    RestoreFailureKind.InsufficientSpace -> R.string.restore_reason_space
    RestoreFailureKind.Incomplete -> R.string.restore_reason_incomplete
    RestoreFailureKind.SaveRejected -> R.string.restore_reason_save
    RestoreFailureKind.Cancelled -> R.string.restore_reason_cancelled
    RestoreFailureKind.Unknown -> R.string.restore_reason_unknown
}

/**
 * Whether a tap can start a transfer, decided in one place.
 *
 * A live row refuses, because the second tap would otherwise look like it had done something while the first
 * transfer carried on underneath — which is the one case in this screen where the honest answer is "nothing
 * happened, because it is already happening".
 */
internal fun canStartRestore(job: RestoreJob?): Boolean = job == null || !job.state.isLive
