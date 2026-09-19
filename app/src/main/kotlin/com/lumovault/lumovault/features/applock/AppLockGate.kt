package com.lumovault.lumovault.features.applock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Wraps the app and covers it with [AppLockScreen] whenever it is locked.
 *
 * Ported from app_lock_gate.dart. Also re-locks on backgrounding when
 * "Require on App Open" is set, so a task-switcher preview can't be used to
 * read the vault. The biometric prompt is auto-triggered once on entry when
 * biometric lock is enabled — mirroring the Dart screen's post-frame
 * `_promptBiometrics`.
 *
 * The prompt needs a [FragmentActivity]; `MainActivity` currently extends
 * `ComponentActivity`, so a safe cast decides whether auto-triggering is even
 * possible (the lock screen surfaces an "unavailable" hint otherwise).
 */
@Composable
fun AppLockGate(
    modifier: Modifier = Modifier,
    viewModel: AppLockViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val enabled by viewModel.isLockEnabled.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    // Re-lock on backgrounding, mirroring the Dart WidgetsBindingObserver.
    DisposableEffect(lifecycleOwner, enabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && enabled) {
                viewModel.lock()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // If the user turns the lock off from settings while the lock screen is
    // up, don't strand them behind a challenge they can't answer.
    LaunchedEffect(state.locked, enabled) {
        if (state.locked && !enabled) {
            viewModel.unlockWithoutChallenge()
        }
    }

    Box(modifier = modifier) {
        content()
        if (state.locked && enabled) {
            // Auto-trigger biometrics once on entry, as the Dart screen did.
            val promptedOnce = remember { booleanArrayOf(false) }
            LaunchedEffect(settings.biometricLockEnabled) {
                if (!promptedOnce[0] && settings.biometricLockEnabled) {
                    promptedOnce[0] = true
                    (context as? FragmentActivity)?.let { viewModel.unlockWithBiometric(it) }
                }
            }
            AppLockScreen(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
