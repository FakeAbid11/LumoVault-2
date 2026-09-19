package com.lumovault.lumovault.features.gallery.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import com.lumovault.lumovault.core.database.entity.MediaItemEntity

/**
 * Keyword search over the scanned library.
 *
 * Ported from lib/features/gallery/presentation/screens/search_screen.dart.
 * The original also had CLIP semantic search, "find similar", and an in-app AI
 * labeling scan; all three are engine work that does not exist in the Kotlin
 * app yet, so this port covers the text search plus display of whatever AI
 * labels the scan has already stored — and says so plainly instead of offering
 * a search mode that silently cannot work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenItem: (index: Int, items: List<MediaItemEntity>) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    // AI labels present on the current results, deduplicated: labels a
    // background scan already wrote to Room. Showing them makes the existing
    // data visible without implying a working label search.
    val resultLabels = remember(results) {
        results.flatMap { it.aiLabels }.distinct().sorted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            SearchField(query = query, onQueryChange = viewModel::onQueryChange, onClear = viewModel::clear)

            when {
                query.isBlank() -> EmptyCollection(
                    title = stringResource(R.string.search_idle_title),
                    explanation = stringResource(R.string.search_idle_explanation),
                )
                results.isEmpty() && !settings.aiScanEnabled -> EmptyCollection(
                    title = stringResource(R.string.search_empty_title),
                    explanation = stringResource(R.string.search_ai_hint),
                )
                results.isEmpty() -> EmptyCollection(
                    title = stringResource(R.string.search_empty_title),
                    explanation = stringResource(R.string.search_empty_explanation),
                )
                else -> {
                    if (resultLabels.isNotEmpty()) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(resultLabels) { label ->
                                AssistChip(onClick = {}, label = { Text(label) })
                            }
                        }
                    }
                    MediaGrid(
                        items = results,
                        onItemClick = { index -> onOpenItem(index, results) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                // Tooltip is an addition: the original clear button had no
                // tooltip and was invisible to screen readers.
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text(stringResource(R.string.search_clear)) } },
                    state = rememberTooltipState(),
                ) {
                    IconButton(onClick = onClear) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.search_clear),
                        )
                    }
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
    )
}
