package com.lumovault.lumovault.features.settings.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import com.lumovault.lumovault.features.settings.domain.model.withClearedPin

/**
 * Privacy settings — app lock and encryption.
 *
 * Ported from privacy_settings_screen.dart. These toggles write the flags
 * only: PIN entry/hashing and the biometric prompt are the app-lock batch's
 * work, not this screen's.
 *
 * Security note vs the original: DISABLING biometric or PIN lock should
 * require verifying the current PIN/biometric first, otherwise anyone holding
 * the unlocked phone can strip the lock. The original did not gate this.
 * The verification gate lands with the app-lock batch — the lock screen and
 * prompt do not exist yet, so the toggle writes the flag directly for now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacySettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val anyLockEnabled = settings.biometricLockEnabled || settings.pinLockEnabled

    Scaffold(
        topBar = { SettingsTopBar(R.string.privacy_title, onBack) },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item { SettingsSectionHeader(R.string.privacy_section_app_lock) }
            item {
                SettingsSwitchItem(
                    title = R.string.privacy_biometric_lock,
                    subtitle = R.string.privacy_biometric_lock_subtitle,
                    icon = Icons.Default.Fingerprint,
                    checked = settings.biometricLockEnabled,
                    onCheckedChange = { v ->
                        viewModel.update { it.copy(biometricLockEnabled = v) }
                    },
                )
            }
            item {
                SettingsSwitchItem(
                    title = R.string.privacy_pin_lock,
                    subtitle = R.string.privacy_pin_lock_subtitle,
                    icon = Icons.Default.Password,
                    checked = settings.pinLockEnabled,
                    onCheckedChange = { v ->
                        // Enabling without a PIN entry flow is a placeholder
                        // until the app-lock batch lands. Disabling clears the
                        // stored hash, mirroring the original's clearPinHash.
                        if (v) {
                            viewModel.update { it.copy(pinLockEnabled = true) }
                        } else {
                            viewModel.update { it.copy(pinLockEnabled = false).withClearedPin() }
                        }
                    },
                )
            }
            item {
                SettingsSwitchItem(
                    title = R.string.privacy_require_on_open,
                    subtitle = R.string.privacy_require_on_open_subtitle,
                    icon = Icons.Default.LockClock,
                    checked = settings.requireAuthOnAppOpen,
                    enabled = anyLockEnabled,
                    onCheckedChange = { v ->
                        viewModel.update { it.copy(requireAuthOnAppOpen = v) }
                    },
                )
            }

            item { HorizontalDivider() }
            item { SettingsSectionHeader(R.string.privacy_section_encryption) }
            item {
                SettingsNavItem(
                    title = R.string.privacy_e2e,
                    subtitle = R.string.privacy_e2e_subtitle,
                    icon = Icons.Default.Lock,
                    onClick = {},
                )
            }
        }
    }
}
