package com.lumovault.lumovault.core.security

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Why an authentication attempt did not succeed.
 *
 * Ported from biometric_service.dart's [BiometricFailure]; kept for
 * parity with the Dart error taxonomy even though the simple Kotlin
 * surface only reports success/failure.
 */
enum class BiometricFailure {
    /** The user was shown the prompt and dismissed or failed it. */
    rejected,

    /** No biometrics, PIN, pattern or passcode is configured on the device. */
    noCredentialsSet,

    /** The device has no usable biometric hardware. */
    noHardware,

    /** Too many failed attempts; retry later. */
    temporaryLockout,

    /** Biometrics are locked until the device credential is entered. */
    biometricLockout,

    /** Biometric auth is not available at all on this platform/build. */
    unsupported,

    /** Anything else, including device-level errors. */
    error,
}

/**
 * Provides biometric authentication for the app lock, wrapping
 * [BiometricPrompt] (androidx.biometric).
 *
 * Ported from lib/core/security/biometric_service.dart. Every entry point
 * degrades gracefully: availability failures and prompt errors are reported
 * as `false`/unavailable rather than propagating to the caller, mirroring the
 * Dart version's catch-everything posture.
 *
 * [authenticate] requires a [FragmentActivity] because [BiometricPrompt] is
 * fragment-based. `MainActivity` currently extends `ComponentActivity`, so
 * callers must obtain the activity via `LocalContext.current as?
 * FragmentActivity` and fall back to a "biometric unavailable" path when the
 * cast fails — do not pass a non-FragmentActivity.
 */
@Singleton
class BiometricService @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val biometricManager: BiometricManager
        get() = BiometricManager.from(context)

    /**
     * True only when biometric hardware exists, credentials are enrolled, and
     * a prompt can actually be shown.
     */
    fun canAuthenticate(): Boolean =
        biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Classify the current [BiometricManager] status, for callers that need
     * to say *why* biometrics are unavailable.
     */
    fun availabilityFailure(): BiometricFailure? =
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            -> BiometricFailure.noHardware
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricFailure.noCredentialsSet
            else -> BiometricFailure.unsupported
        }

    /**
     * Prompt the user for biometric authentication. Suspends until the prompt
     * resolves; returns true only on success.
     *
     * Device-credential fallback is allowed (matching the Dart default of
     * `biometricOnly = false`) by using a negative button of "Use PIN" plus
     * the BIOMETRIC_STRONG authenticator set — on API 30+ the system offers
     * the device credential alongside biometrics.
     */
    suspend fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String? = null,
        negativeButtonText: String,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onAuthenticationFailed() {
                    // A single failed scan is not terminal; the prompt stays up.
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Log.d(TAG, "Biometric error $errorCode: $errString")
                    if (continuation.isActive) continuation.resume(false)
                }
            },
        )

        continuation.invokeOnCancellation {
            runCatching { prompt.cancelAuthentication() }
        }

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .apply { if (subtitle != null) setSubtitle(subtitle) }
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        try {
            prompt.authenticate(info)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to show biometric prompt", e)
            if (continuation.isActive) continuation.resume(false)
        }
    }

    private companion object {
        const val TAG = "BiometricService"
    }
}
