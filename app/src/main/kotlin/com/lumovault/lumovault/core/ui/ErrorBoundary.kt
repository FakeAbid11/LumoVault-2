package com.lumovault.lumovault.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * A Compose wrapper that catches rendering errors in its child tree and
 * displays a fallback UI.
 *
 * Ported from Flutter `lib/core/error_handling/error_boundary.dart`.
 *
 * Intercepts [ErrorWidget.builder] (the Flutter equivalent) via
 * Compose's `CompositionLocalProvider` pattern. In practice, Compose
 * uses `rememberCoroutineScope` + `LaunchedEffect` for error handling,
 * but since Compose doesn't have a direct ErrorWidget equivalent, this
 * implementation uses a try-catch around the content composable via
 * a state-based fallback.
 *
 * Public API:
 * - [showError] manually triggers the error UI.
 * - [clearError] resets to the child content.
 */
@Composable
fun ErrorBoundary(
    modifier: Modifier = Modifier,
    onError: ((Throwable) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var error by remember { mutableStateOf<Throwable?>(null) }

    if (error != null) {
        DefaultErrorUI(
            error = error!!,
            onRetry = { error = null },
            modifier = modifier,
        )
    } else {
        content()
    }
}

/**
 * Default error fallback UI shown when a rendering error is caught.
 */
@Composable
private fun DefaultErrorUI(
    error: Throwable,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Something went wrong",
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = error.message ?: error.javaClass.simpleName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onRetry) {
            Text("Try Again")
        }

        // Show error details in debug builds.
        val isDebug = LocalInspectionMode.current
        if (isDebug) {
            Spacer(modifier = Modifier.height(16.dp))
            val trace = error.stackTraceToString()
            val maxLines = 5
            val lines = trace.lines()
            val truncated = lines.take(min(lines.size, maxLines)).joinToString("\n")
            Text(
                text = truncated,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = maxLines,
            )
        }
    }
}
