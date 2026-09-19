package com.lumovault.lumovault.features.applock

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.lumovault.R
import com.lumovault.lumovault.core.security.BiometricService
import com.lumovault.lumovault.core.security.PinAttemptThrottle
import com.lumovault.lumovault.core.security.PinLockoutState
import com.lumovault.lumovault.core.security.PinService
import com.lumovault.lumovault.features.settings.domain.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** High-level lock state the UI renders against. */
enum class LockStatus {
    /** Vault contents must stay hidden. */
    locked,

    /** Vault contents may be shown. */
    unlocked,
}

/**
 * State of the app lock screen.
 *
 * Ported from app_lock_provider.dart's [AppLockState].
 */
data class AppLockUiState(
    val status: LockStatus = LockStatus.unlocked,
    /** True while a biometric prompt or PIN verification is running. */
    val checking: Boolean = false,
    /** User-facing error from the last attempt. */
    val error: String? = null,
    /** Throttle snapshot for the lockout countdown display. */
    val lockout: PinLockoutState = PinLockoutState(),
) {
    val locked: Boolean get() = status == LockStatus.locked
}

/**
 * Drives the app lock: biometric prompt, PIN verification, and re-locking.
 *
 * Ported from app_lock_provider.dart's [AppLockController]. Starts locked
 * whenever a lock is configured and "Require on App Open" is set, so the very
 * first frame after launch is the lock screen rather than the timeline.
 */
@HiltViewModel
class AppLockViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val settings: StateFlow<AppSettings>,
    private val pinService: PinService,
    private val throttle: PinAttemptThrottle,
    private val biometricService: BiometricService,
) : ViewModel() {

    private val _state = MutableStateFlow(
        AppLockUiState(
            status = if (computeLockEnabled(settings.value) && settings.value.requireAuthOnAppOpen) {
                LockStatus.locked
            } else {
                LockStatus.unlocked
            },
        ),
    )
    val state: StateFlow<AppLockUiState> = _state.asStateFlow()

    /**
     * Whether the app lock is armed at all — mirrors the Dart
     * `appLockEnabledProvider` combined with `requireAuthOnAppOpen`.
     */
    val isLockEnabled: StateFlow<Boolean> = settings
        .map { computeLockEnabled(it) && it.requireAuthOnAppOpen }
        .stateIn(viewModelScope, SharingStarted.Eagerly, computeLockEnabled(settings.value))

    /** Lock the app — called when it goes to the background. */
    fun lock() {
        if (_state.value.locked) return
        _state.value = AppLockUiState(status = LockStatus.locked, lockout = throttle.getState())
    }

    /**
     * Unlock without a challenge. Only for the case where the lock was
     * disabled while the lock screen was showing.
     */
    fun unlockWithoutChallenge() {
        _state.value = AppLockUiState(status = LockStatus.unlocked)
    }

    /** Prompt for biometrics; the app unlocks on success. */
    fun unlockWithBiometric(activity: FragmentActivity) {
        if (_state.value.checking) return
        if (!biometricService.canAuthenticate()) {
            _state.value = _state.value.copy(
                error = context.getString(R.string.app_lock_biometric_unavailable),
            )
            return
        }
        _state.value = _state.value.copy(checking = true, error = null)
        viewModelScope.launch {
            val ok = biometricService.authenticate(
                activity = activity,
                title = context.getString(R.string.app_lock_biometric_prompt_title),
                subtitle = context.getString(R.string.app_lock_biometric_prompt_subtitle),
                negativeButtonText = context.getString(R.string.app_lock_biometric_prompt_negative),
            )
            _state.value = if (ok) {
                AppLockUiState(status = LockStatus.unlocked)
            } else {
                _state.value.copy(
                    checking = false,
                    error = context.getString(R.string.app_lock_auth_cancelled),
                )
            }
        }
    }

    /** Verify [pin] against the stored hash, honouring the attempt throttle. */
    fun unlock(pin: String, onResult: ((Boolean) -> Unit)? = null) {
        if (_state.value.checking) return

        val lockout = throttle.getState()
        if (lockout.isLockedOut) {
            _state.value = _state.value.copy(
                error = lockoutMessage(lockout.remainingMs()),
                lockout = lockout,
            )
            onResult?.invoke(false)
            return
        }

        _state.value = _state.value.copy(checking = true, error = null)

        viewModelScope.launch {
            val ok = pinService.verifyStoredPin(pin)
            if (!ok) {
                val updated = throttle.recordFailure()
                val remaining = updated.remainingMs()
                _state.value = _state.value.copy(
                    checking = false,
                    error = if (remaining > 0L) {
                        lockoutMessage(remaining)
                    } else {
                        context.getString(R.string.app_lock_incorrect_pin)
                    },
                    lockout = updated,
                )
                onResult?.invoke(false)
                return@launch
            }

            throttle.recordSuccess()

            // Transparently upgrade hashes created with weaker parameters.
            pinService.rehashIfNeeded(pin)

            _state.value = AppLockUiState(status = LockStatus.unlocked)
            onResult?.invoke(true)
        }
    }

    /** Human-readable cooldown, matching the Dart `_lockoutMessage`. */
    fun lockoutMessage(remainingMs: Long): String {
        val seconds = (remainingMs / 1_000L).coerceAtLeast(1L)
        return if (seconds >= 60L) {
            val minutes = (seconds + 59L) / 60L
            context.getString(R.string.app_lock_lockout_minutes, minutes)
        } else {
            context.getString(R.string.app_lock_lockout_seconds, seconds)
        }
    }

    companion object {
        /** Whether the app lock is armed at all, per the Dart provider. */
        fun computeLockEnabled(s: AppSettings): Boolean =
            s.biometricLockEnabled || (s.pinLockEnabled && s.pinHash != null)
    }
}
