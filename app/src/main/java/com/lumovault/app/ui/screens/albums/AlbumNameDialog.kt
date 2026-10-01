package com.lumovault.app.ui.screens.albums

import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.lumovault.app.R
import com.lumovault.app.domain.organization.AlbumRepository

/**
 * The name prompt, shared by create and rename.
 *
 * Confirm stays disabled while the field is blank rather than explaining afterwards why nothing happened:
 * the repository will refuse a blank name anyway, so a dialog that let the user press it would be
 * showing a button that is known not to work. [pending] disables it for the same reason while the write
 * is in flight — the prompt now stays open until the write answers, and a second tap during that window
 * would create or rename twice. [error] is the refusal or the failed write, shown under the field in the
 * error colour: the typed name is still here to correct, so the news goes where the correction happens
 * instead of in a line at the bottom of the screen that the dialog is covering anyway.
 */
@Composable
fun AlbumNameDialog(
    @StringRes title: Int,
    @StringRes confirm: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initial: String = "",
    maxLength: Int = AlbumRepository.MAX_NAME_LENGTH,
    pending: Boolean = false,
    @StringRes error: Int? = null,
) {
    // Saveable: the activity recreates on rotation, and the typed name — with the prompt still open —
    // is work the user is mid-way through, not a detail to lose with the pixels.
    var value by rememberSaveable { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = value,
                // Capped where the library will keep it: the repository truncates a longer name rather
                // than refusing it, and stopping the field at the same limit means what was typed is
                // what gets stored.
                onValueChange = { input -> value = input.take(maxLength) },
                label = { Text(stringResource(R.string.album_name_label)) },
                singleLine = true,
                isError = value.isBlank() || error != null,
                // Only drawn when there is something to say; M3 colours it from isError, so no
                // colour is chosen here that could disagree with the field above it.
                supportingText = if (error != null) {
                    { Text(stringResource(error)) }
                } else {
                    null
                },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank() && !pending) {
                Text(stringResource(confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.album_cancel)) }
        },
    )
}
