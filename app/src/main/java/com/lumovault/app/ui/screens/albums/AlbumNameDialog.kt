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
 * showing a button that is known not to work.
 */
@Composable
fun AlbumNameDialog(
    @StringRes title: Int,
    @StringRes confirm: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initial: String = "",
    maxLength: Int = AlbumRepository.MAX_NAME_LENGTH,
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
                isError = value.isBlank(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text(stringResource(confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.album_cancel)) }
        },
    )
}
