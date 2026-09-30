package com.lumovault.app.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lumovault.app.R
import com.lumovault.app.ui.theme.IconLeading
import com.lumovault.app.ui.theme.LumoVaultType
import com.lumovault.app.ui.theme.SpaceLg
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SpaceXs

/** How many onboarding steps there are, shown in the progress header. */
const val ONBOARDING_STEPS = 6

/** Screen 1: establish the product idea, not a feature list. */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingScaffold(
        step = 1,
        totalSteps = ONBOARDING_STEPS,
        title = stringResource(R.string.app_name),
        description = stringResource(R.string.welcome_description),
        primaryLabel = stringResource(R.string.welcome_action),
        onPrimary = onGetStarted,
        modifier = modifier,
    ) {
        // Centred as one unit rather than as two children. The scaffold's content column is start-aligned
        // because every other step is a form and a form reads from the margin, but this step is a picture with
        // a line under it — and a tagline that centres itself beside a start-aligned medallion lands to the
        // right of the badge instead of below it. The diagram on the next step already centres itself this way,
        // so the two opening screens now agree.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SpaceLg),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // The app's own mark, not a stock glyph: a framed landscape in a gradient disc, tinted to the
            // scheme's container text so the two blues of the original artwork stay legible on either theme.
            Box(
                modifier = Modifier
                    .size(HeroMedallion)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                            ),
                        ),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(HeroGlyph),
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer),
                )
            }

            Text(
                text = stringResource(R.string.welcome_tagline),
                style = LumoVaultType.onboardingHero,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Screen 2: phone → LumoVault → Telegram, in the order the user will experience it, with four
 * plain sentences. Deliberately not a Telegram tutorial (PRD section 34).
 *
 * The diagram is a stepper — disc, connector, disc — rather than labelled pills with arrows between
 * them: an arrow glyph is a picture of a transition, while a connector line *is* the transition drawn
 * once, and the discs give the middle node the app's own mark to stop at.
 */
@Composable
fun HowItWorksScreen(
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingScaffold(
        step = 2,
        totalSteps = ONBOARDING_STEPS,
        title = stringResource(R.string.how_title),
        primaryLabel = stringResource(R.string.how_action),
        onPrimary = onContinue,
        onBack = onBack,
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = SpaceSm),
        ) {
            DiagramNode(label = stringResource(R.string.how_your_phone)) {
                IconCircle(
                    imageVector = Icons.Filled.Smartphone,
                    size = DiagramDisc,
                )
            }
            Connector()
            DiagramNode(label = stringResource(R.string.app_name)) {
                BrandDisc()
            }
            Connector()
            DiagramNode(label = stringResource(R.string.how_your_cloud)) {
                IconCircle(
                    imageVector = Icons.Filled.Backup,
                    size = DiagramDisc,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(SpaceMd), modifier = Modifier.padding(top = SpaceSm)) {
            PointRow(icon = Icons.Filled.Photo, text = stringResource(R.string.how_point_local))
            PointRow(icon = Icons.Filled.CloudUpload, text = stringResource(R.string.how_point_backup))
            PointRow(icon = Icons.Filled.Cloud, text = stringResource(R.string.how_point_cloud))
            PointRow(icon = Icons.Filled.Download, text = stringResource(R.string.how_point_originals))
        }
    }
}

/** One node of the diagram: a disc with its name under it, centred as a unit. */
@Composable
private fun DiagramNode(label: String, glyph: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpaceXs),
    ) {
        glyph()
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/** The middle node, in the same disc the welcome screen opens with — smaller, so it reads as the same mark. */
@Composable
private fun BrandDisc() {
    Box(
        modifier = Modifier
            .size(DiagramDisc)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                ),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(DiagramMark),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer),
        )
    }
}

/** The line between two nodes: one shape, at the outline's quietest weight. */
@Composable
private fun Connector() {
    Box(
        modifier = Modifier
            .width(ConnectorThickness)
            .height(ConnectorHeight)
            .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(ConnectorThickness)),
    )
}

/** One of the four sentences, with the accent icon that names its idea. */
@Composable
private fun PointRow(icon: ImageVector, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(IconLeading),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The welcome screen's own art: a mark in a gradient disc, big enough to be the screen's subject. */
private val HeroMedallion = 128.dp
private val HeroGlyph = 88.dp

/** The stepper's discs — bigger than a row icon, smaller than the welcome mark. */
private val DiagramDisc = 56.dp
private val DiagramMark = 40.dp

/** The connector spans the width of a rounded 2 dp line, with the corner rounding to match. */
private val ConnectorThickness = 2.dp
private val ConnectorHeight = 20.dp
