package com.lumovault.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lumovault.app.R
import com.lumovault.app.ui.theme.LumoVaultType
import com.lumovault.app.ui.theme.SpaceLg
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceXl
import com.lumovault.app.ui.theme.SpaceXs

/**
 * The shared frame for all six onboarding screens: a header, scrollable body, and one pinned
 * primary action. Content scrolls so the button stays reachable on a small phone with the keyboard
 * open, and the column is width-capped so tablets don't get a stretched form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScaffold(
    step: Int,
    totalSteps: Int,
    title: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    onBack: (() -> Unit)? = null,
    primaryEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = { Text(" ") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = SpaceXl),
        ) {
            StepProgress(step = step, totalSteps = totalSteps)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = FormMaxWidth)
                    .align(Alignment.CenterHorizontally)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.Start,
                // Top-aligned on purpose: a vertically-centred arrangement inside a scroll column
                // pushes the first line out of reach on short screens.
                verticalArrangement = Arrangement.spacedBy(SpaceLg),
            ) {
                Column(
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(SpaceXs),
                ) {
                    Text(
                        // `headlineMedium` is 28 sp, and at that size the heading was the largest thing on a
                        // screen whose actual subject is the form or the picture underneath it. Small enough to
                        // be a heading, big enough to be the first thing read.
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Start,
                    )
                    if (description != null) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                content()
            }

            Spacer(Modifier.height(SpaceXl))

            Button(
                onClick = onPrimary,
                enabled = primaryEnabled,
                // The inner `padding(vertical = 8.dp)` on the label is gone: a Material button is already 40 dp
                // tall, and padding its text made every step's primary action a 56 dp block sitting in the last
                // of the space the body had been given.
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = FormMaxWidth)
                    .align(Alignment.CenterHorizontally),
            ) {
                Text(text = primaryLabel)
            }

            Spacer(Modifier.height(SpaceLg))
        }
    }
}

/**
 * One bar with the count beside it, rather than a bar and then a line of its own.
 *
 * Six steps of a flow should not cost six screen-heights of chrome: the fraction and the bar answer the same
 * question, and a row that answers it once leaves the answer and the room for the content both.
 */
@Composable
private fun StepProgress(step: Int, totalSteps: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SpaceLg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
    ) {
        LinearProgressIndicator(
            progress = { step.toFloat() / totalSteps.toFloat() },
            modifier = Modifier
                .weight(1f)
                .height(ProgressHeight),
        )
        Text(
            text = "$step / $totalSteps",
            style = LumoVaultType.sectionDetail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val FormMaxWidth = 560.dp
private val ProgressHeight = 6.dp
