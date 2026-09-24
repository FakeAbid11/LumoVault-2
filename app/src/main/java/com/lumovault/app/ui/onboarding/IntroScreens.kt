package com.lumovault.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lumovault.app.R

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
        Box(
            modifier = Modifier
                .size(104.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Text(
            text = stringResource(R.string.welcome_tagline),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Screen 2: phone → LumoVault → Telegram, in the order the user will experience it, with four
 * plain sentences. Deliberately not a Telegram tutorial (PRD section 34).
 */
@Composable
fun HowItWorksScreen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingScaffold(
        step = 2,
        totalSteps = ONBOARDING_STEPS,
        title = stringResource(R.string.how_title),
        primaryLabel = stringResource(R.string.how_action),
        onPrimary = onContinue,
        modifier = modifier,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 8.dp)) {
            StepNode(label = stringResource(R.string.how_your_phone))
            Connector()
            StepNode(label = stringResource(R.string.app_name), emphasised = true)
            Connector()
            StepNode(label = stringResource(R.string.how_your_cloud))
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
            Text(text = stringResource(R.string.how_point_local), style = MaterialTheme.typography.bodyLarge)
            Text(text = stringResource(R.string.how_point_backup), style = MaterialTheme.typography.bodyLarge)
            Text(text = stringResource(R.string.how_point_cloud), style = MaterialTheme.typography.bodyLarge)
            Text(text = stringResource(R.string.how_point_originals), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun StepNode(label: String, emphasised: Boolean = false) {
    val container = if (emphasised) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (emphasised) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .background(container, CircleShape)
            .padding(horizontal = 28.dp, vertical = 16.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium, color = content)
    }
}

@Composable
private fun Connector() {
    Icon(
        imageVector = Icons.Filled.ArrowDownward,
        contentDescription = null,
        modifier = Modifier.padding(vertical = 4.dp),
        tint = MaterialTheme.colorScheme.outline,
    )
}
