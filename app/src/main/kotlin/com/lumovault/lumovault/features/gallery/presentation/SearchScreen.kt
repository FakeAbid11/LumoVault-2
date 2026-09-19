package com.lumovault.lumovault.features.gallery.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import com.lumovault.lumovault.core.database.entity.MediaItemEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenItem: (index: Int, items: List<MediaItemEntity>) -> Unit,
    initialSimilarTo: String? = null,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val similarResults by viewModel.similarResults.collectAsStateWithLifecycle()
    val similarTo by viewModel.similarTo.collectAsStateWithLifecycle()
    val semanticMode by viewModel.semanticMode.collectAsStateWithLifecycle()
    val aiScanState by viewModel.aiScanState.collectAsStateWithLifecycle()

    LaunchedEffect(initialSimilarTo) {
        if (initialSimilarTo != null) {
            viewModel.setSimilarTo(initialSimilarTo)
        }
    }

    val isFindSimilar = similarTo != null
    val displayResults = if (isFindSimilar) similarResults else results

    val resultLabels = remember(displayResults) {
        displayResults.flatMap { it.aiLabels }.distinct().sorted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isFindSimilar) stringResource(R.string.search_find_similar)
                        else stringResource(R.string.search_title)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isFindSimilar) {
                            viewModel.setSimilarTo(null)
                            viewModel.clear()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (!isFindSimilar) {
                        IconButton(onClick = { viewModel.toggleSemanticMode() }) {
                            Icon(
                                if (semanticMode) Icons.Filled.Psychology else Icons.Filled.Search,
                                contentDescription = if (semanticMode) "Semantic search" else "Keyword search",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!isFindSimilar) {
                SearchField(
                    query = query,
                    onQueryChange = viewModel::onQueryChange,
                    onClear = viewModel::clear,
                )
            }

            when {
                isFindSimilar && similarResults.isEmpty() && query.isBlank() -> {
                    if (aiScanState.running) {
                        AiScanProgress(aiScanState, onStop = { viewModel.stopAiScan() })
                    } else {
                        EmptyCollection(
                            title = stringResource(R.string.search_find_similar),
                            explanation = stringResource(R.string.search_find_similar_explanation),
                        )
                    }
                }
                query.isBlank() && !isFindSimilar -> {
                    if (aiScanState.running) {
                        AiScanProgress(aiScanState, onStop = { viewModel.stopAiScan() })
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                stringResource(R.string.search_idle_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                stringResource(R.string.search_idle_explanation),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(24.dp))
                            Button(onClick = { viewModel.startAiScan() }) {
                                Icon(Icons.Filled.Psychology, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.search_start_scan))
                            }
                        }
                    }
                }
                displayResults.isEmpty() && !settings.aiScanEnabled && !isFindSimilar -> EmptyCollection(
                    title = stringResource(R.string.search_empty_title),
                    explanation = stringResource(R.string.search_ai_hint),
                )
                displayResults.isEmpty() -> EmptyCollection(
                    title = stringResource(R.string.search_empty_title),
                    explanation = stringResource(R.string.search_empty_explanation),
                )
                else -> {
                    if (resultLabels.isNotEmpty() && !isFindSimilar) {
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
                        items = displayResults,
                        onItemClick = { index -> onOpenItem(index, displayResults) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun AiScanProgress(state: AiScanState, onStop: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (state.total > 0) {
            Text(
                "Scanning ${state.completed} / ${state.total}",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.size(16.dp))
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "${((state.progress) * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CircularProgressIndicator()
            Spacer(Modifier.size(16.dp))
            Text("Preparing scan...")
        }
        Spacer(Modifier.size(24.dp))
        Button(onClick = onStop, colors = ButtonDefaults.buttonColors()) {
            Text("Stop")
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
