package com.lumovault.lumovault.features.applock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import com.lumovault.lumovault.core.security.MAX_PIN_LENGTH
import kotlinx.coroutines.delay

/**
 * Full-screen challenge shown while the app is locked.
 *
 * Ported from app_lock_screen.dart: a numeric PIN pad with entry dots, an
 * error line, a lockout countdown while throttled, and a biometric shortcut
 * when enabled. The biometric prompt itself needs a [FragmentActivity];
 * `MainActivity` currently extends `ComponentActivity`, so the activity is
 * resolved with a safe cast and the button degrades to an "unavailable"
 * message when the host isn't a [FragmentActivity].
 */
@Composable
fun AppLockScreen(
    viewModel: AppLockViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    val pinEnabled = settings.pinLockEnabled && settings.pinHash != null
    val biometricEnabled = settings.biometricLockEnabled

    var pin by remember { mutableStateOf("") }

    // Tick once a second while locked out so the countdown advances.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(state.lockout.lockedUntilElapsedMs) {
        while (state.lockout.lockedUntilElapsedMs != null) {
            delay(1_000L)
            tick++
        }
    }
    val lockoutRemainingMs = remember(tick, state.lockout) { state.lockout.remainingMs() }
    val lockedOut = lockoutRemainingMs > 0L

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.app_lock_title),
                style = MaterialTheme.typography.titleLarge,
            )

            if (pinEnabled) {
                // Entry dots: one per typed digit (6 shown before entry).
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val slots = pin.length.coerceAtLeast(6)
                    repeat(slots) { index ->
                        val filled = index < pin.length
                        Surface(
                            modifier = Modifier.size(14.dp),
                            shape = MaterialTheme.shapes.small,
                            color = if (filled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ) {}
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.app_lock_enter_pin),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))

                val padEnabled = !state.checking && !lockedOut
                PinPad(
                    enabled = padEnabled,
                    onDigit = { digit ->
                        if (pin.length < MAX_PIN_LENGTH) pin += digit
                    },
                    onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                )
                Spacer(Modifier.height(12.dp))
                FilledButton(
                    onClick = {
                        viewModel.unlock(pin) { ok -> if (!ok) pin = "" }
                    },
                    enabled = padEnabled && pin.length >= 6,
                ) {
                    Text(stringResource(R.string.app_lock_unlock))
                }
            }

            if (biometricEnabled) {
                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = {
                        if (activity != null) {
                            viewModel.unlockWithBiometric(activity)
                        }
                    },
                    enabled = !state.checking,
                ) {
                    Icon(Icons.Default.Fingerprint, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.app_lock_use_biometrics))
                }
            }

            if (state.checking) {
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator()
            }

            val errorText = when {
                lockedOut -> viewModel.lockoutMessage(lockoutRemainingMs)
                state.error != null -> state.error
                biometricEnabled && activity == null -> {
                    stringResource(R.string.app_lock_biometric_unavailable)
                }
                else -> null
            }
            if (errorText != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    errorText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 3x4 numeric pad: 1-9, empty, 0, backspace. */
@Composable
private fun PinPad(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
) {
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { digit -> PadKey(digit.toString(), enabled) { onDigit(digit) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.size(72.dp))
            PadKey("0", enabled) { onDigit('0') }
            IconButton(
                onClick = onBackspace,
                enabled = enabled,
                modifier = Modifier.size(72.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = null)
            }
        }
    }
}

@Composable
private fun PadKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(72.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}
