package com.lumovault.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lumovault.app.R
import com.lumovault.app.ui.theme.LumoVaultType
import com.lumovault.app.ui.theme.MarkInline
import com.lumovault.app.ui.theme.RingStroke
import com.lumovault.app.ui.theme.SpaceLg
import com.lumovault.app.ui.theme.SpaceXl
import com.lumovault.app.ui.theme.SpaceXs

/**
 * The shared frame for all six onboarding screens: a header, scrollable body, and one pinned
 * primary action. Content scrolls so the button stays reachable on a small phone with the keyboard
 * open, and the column is width-capped so tablets don't get a stretched form.
 *
 * The whole frame sits on [onboardingBackdrop] — a brand tint fading into the background — which is why
 * the scaffold and its top bar draw no colour of their own: a bar painted `surface` over the gradient
 * would cut a flat stripe across every screen that has a back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScaffold(
    /** Null outside the setup flow: a "step 3 of 6" bar above a lone reconnect screen is a lie about
     * where the user is. */
    step: Int?,
    totalSteps: Int,
    title: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    onBack: (() -> Unit)? = null,
    primaryEnabled: Boolean = true,
    /**
     * Replaces the label with a spinner while the step's own work is in flight. The button is expected
     * to be disabled at the same time; the spinner is the visible half of "your tap was received".
     */
    primaryBusy: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(onboardingBackdrop())
            // Edge-to-edge hands the keyboard to the app as an inset instead of resizing the window
            // (API 30+; adjustResize alone stopped doing it), so without this the pinned primary
            // button — the step's whole point — drew behind the keyboard that every sign-in field
            // opens on arrival. The IME inset overlaps the navigation bar, which the scaffold's own
            // innerPadding contributes below, so the overlap is excluded rather than summed and the
            // button lands exactly on the keyboard's edge.
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars())),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                if (onBack != null) {
                    TopAppBar(
                        // Empty rather than a space-string: a title of " " is read aloud as a blank label.
                        title = {},
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                        ),
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
                if (step != null) {
                    SegmentedProgress(
                        step = step,
                        totalSteps = totalSteps,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = SpaceLg),
                    )
                }
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
                            // The flow's own 26 sp role rather than a Material default: it has to rank
                            // above the form under it without out-shouting the picture on the steps
                            // that have one. See LumoVaultType.onboardingTitle.
                            text = title,
                            style = LumoVaultType.onboardingTitle,
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
                    if (primaryBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(MarkInline),
                            strokeWidth = RingStroke,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(text = primaryLabel)
                    }
                }

                Spacer(Modifier.height(SpaceLg))
            }
        }
    }
}

private val FormMaxWidth = 560.dp
