package com.lumovault.app.ui.screens.albums

import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.lumovault.app.R

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
) {
    var value by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
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
