package com.lumovault.lumovault.core.security

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of the PIN throttle, with monotonic-clock anchors.
 *
 * [lockedUntilElapsedMs] is a `SystemClock.elapsedRealtime()` deadline — the
 * monotonic clock cannot be moved by the user, fixing the Flutter defect where
 * the wall-clock `DateTime` expiry was bypassed by changing the system clock.
 */
data class PinLockoutState(
    /** Consecutive failed attempts since the last success. */
    val failedAttempts: Int = 0,
    /** elapsedRealtime deadline for the current lockout, or null. */
    val lockedUntilElapsedMs: Long? = null,
) {
    /** Whether attempts are currently refused. */
    val isLockedOut: Boolean
        get() = remainingMs() > 0L

    /** Remaining lockout in milliseconds, or 0 when not locked out. */
    fun remainingMs(nowElapsedMs: Long = android.os.SystemClock.elapsedRealtime()): Long {
        val until = lockedUntilElapsedMs ?: return 0L
        return (until - nowElapsedMs).coerceAtLeast(0L)
    }
}

/**
 * Rate limits PIN entry so a 6-digit secret can't be brute-forced.
 *
 * Ported from lib/core/security/pin_attempt_throttle.dart, with one deliberate
 * divergence: the original anchored lockout expiry to wall-clock `DateTime`,
 * which a user could defeat by setting the device clock forward. This port
 * anchors the live countdown to `SystemClock.elapsedRealtime()` (monotonic,
 * not user-settable). Only the *remaining duration* is persisted as a fallback
 * for the cold-start case where the process was killed during a lockout — the
 * persisted remainder is re-anchored to the monotonic clock on load, so
 * tampering with the wall clock cannot shorten it.
 *
 * After [attemptsBeforeLockout] consecutive failures the lock enters a
 * cooldown that doubles with each further failed attempt, capped at
 * [maxLockoutMs]. State is persisted (encrypted) so force-quitting the app
 * doesn't reset the counter.
 */
@Singleton
class PinAttemptThrottle @Inject constructor(
    @ApplicationContext context: Context,
) {

    /** Failures tolerated before the first lockout kicks in. */
    val attemptsBeforeLockout: Int = 5

    /** Cooldown applied at the first lockout; doubles on each later failure. */
    val baseLockoutMs: Long = 30_000L

    /** Upper bound on the cooldown. */
    val maxLockoutMs: Long = 30L * 60_000L

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }


    @Volatile private var cache: PinLockoutState? = null

    /** Read the current lockout state. */
    @Synchronized
    fun getState(): PinLockoutState {
        cache?.let { return it }

        val loaded = try {
            val raw = prefs.getString(STORAGE_KEY, null)
            if (raw != null) {
                val json = JSONObject(raw)
                val attempts = json.optInt(KEY_FAILED_ATTEMPTS, 0)
                // Persisted value is a remaining duration, re-anchored to the
                // monotonic clock now — never a wall-clock timestamp.
                val remaining = json.optLong(KEY_REMAINING_MS, 0L)
                PinLockoutState(
                    failedAttempts = attempts,
                    lockedUntilElapsedMs = if (remaining > 0L) {
                        android.os.SystemClock.elapsedRealtime() + remaining
                    } else {
                        null
                    },
                )
            } else {
                null
            }
        } catch (e: Throwable) {
            // Corrupt or unreadable state — fall through to a clean slate
            // rather than locking the user out of their own vault permanently.
            Log.w(TAG, "Failed to read throttle state", e)
            null
        }

        val state = loaded ?: PinLockoutState()
        cache = state
        return state
    }

    /** Record a failed attempt and return the resulting state. */
    @Synchronized
    fun recordFailure(): PinLockoutState {
        val current = getState()
        val attempts = current.failedAttempts + 1

        var lockedUntil: Long? = null
        if (attempts >= attemptsBeforeLockout) {
            val overshoot = (attempts - attemptsBeforeLockout).coerceIn(0, 16)
            var cooldown = baseLockoutMs shl overshoot
            if (cooldown > maxLockoutMs) cooldown = maxLockoutMs
            lockedUntil = android.os.SystemClock.elapsedRealtime() + cooldown
        }

        return write(PinLockoutState(failedAttempts = attempts, lockedUntilElapsedMs = lockedUntil))
    }

    /** Clear all failure history after a successful unlock. */
    @Synchronized
    fun recordSuccess() {
        cache = PinLockoutState()
        try {
            prefs.edit().remove(STORAGE_KEY).apply()
        } catch (e: Throwable) {
            // In-memory state is already reset; a stale blob only over-throttles.
            Log.w(TAG, "Failed to delete throttle state", e)
        }
    }

    private fun write(state: PinLockoutState): PinLockoutState {
        cache = state
        try {
            val json = JSONObject()
                .put(KEY_FAILED_ATTEMPTS, state.failedAttempts)
                .put(KEY_REMAINING_MS, state.remainingMs())
            prefs.edit().putString(STORAGE_KEY, json.toString()).apply()
        } catch (e: Throwable) {
            // Persistence failed — the cache still throttles this session.
            Log.w(TAG, "Failed to persist throttle state", e)
        }
        return state
    }

    private companion object {
        const val TAG = "PinAttemptThrottle"
        const val PREF_NAME = "lumovault_pin_throttle_prefs"
        const val STORAGE_KEY = "lumovault_pin_throttle"
        const val KEY_FAILED_ATTEMPTS = "failedAttempts"
        const val KEY_REMAINING_MS = "remainingLockoutMs"
    }
}
