package com.lumovault.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lumovault.app.R
import com.lumovault.app.domain.model.Country
import com.lumovault.app.domain.model.search

/**
 * Full country list, searchable, with flags rendered from the ISO code. The list is filtered in
 * memory on each keystroke, so a few hundred rows never touch a disk or a network.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountryPicker(
    countries: List<Country>,
    selected: Country?,
    onSelected: (Country) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(countries, query) { countries.search(query) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.country_picker_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.country_search_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.country_search_clear),
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
            )

            Text(
                text = stringResource(R.string.country_result_count, matches.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            if (matches.isEmpty()) {
                Text(
                    text = stringResource(R.string.country_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(items = matches, key = { it.iso2 }) { country ->
                        CountryRow(
                            country = country,
                            isSelected = country.iso2 == selected?.iso2,
                            onClick = { onSelected(country) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CountryRow(country: Country, isSelected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(country.name) },
        leadingContent = {
            // The flag is text, not an image, so TalkBack still announces the country name itself.
            Text(text = country.flag, style = MaterialTheme.typography.titleMedium)
        },
        trailingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 24.dp)) {
                    Text(text = country.dialPrefix, style = MaterialTheme.typography.bodyMedium)
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
        },
        // ListItem has no onClick of its own; the row is made clickable through the modifier.
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}
