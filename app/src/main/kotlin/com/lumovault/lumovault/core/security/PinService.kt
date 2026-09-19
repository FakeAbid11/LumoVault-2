package com.lumovault.lumovault.core.security

import android.util.Base64
import com.lumovault.lumovault.features.settings.data.SettingsRepository
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/** Minimum number of digits accepted for an app-lock PIN. */
const val MIN_PIN_LENGTH = 6

/** Maximum number of digits accepted for an app-lock PIN. */
const val MAX_PIN_LENGTH = 12

/**
 * Derives and verifies salted PIN hashes for the app lock.
 *
 * Ported from lib/core/security/pin_service.dart. PINs are short and
 * low-entropy, so a plain digest is trivially reversible with a rainbow table.
 * Each PIN is stretched with PBKDF2-HMAC-SHA256 over a per-user random salt,
 * and the parameters are stored alongside the digest so [iterations] can be
 * raised later without invalidating old PINs.
 *
 * The encoded form is a single string, which lets it live in the existing
 * `AppSettings.pinHash` field:
 *
 *     pbkdf2-sha256$<iterations>$<base64 salt>$<base64 digest>
 *
 * PINs are digits only, hence ASCII, so the JCA `PBKDF2WithHmacSHA256`
 * password encoding (low 8 bits of each char) is byte-identical to the Dart
 * version's UTF-8 HMAC key — hashes are interchangeable across the two apps.
 */
@Singleton
class PinService @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {

    /** PBKDF2 iteration count. */
    val iterations: Int = DEFAULT_ITERATIONS

    private val random = SecureRandom()

    /** Whether [pin] is an acceptable new PIN (digits only, long enough). */
    fun isValidPin(pin: String): Boolean =
        pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it.isDigit() }

    /**
     * Derive a storable, salted hash for [pin].
     *
     * Throws [IllegalArgumentException] if [pin] fails [isValidPin].
     */
    fun hashPin(pin: String): String {
        require(isValidPin(pin)) { "PIN must be $MIN_PIN_LENGTH-$MAX_PIN_LENGTH digits" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val digest = pbkdf2(pin, salt, iterations, KEY_BYTES)
        return listOf(
            ALGORITHM,
            iterations.toString(),
            base64Encode(salt),
            base64Encode(digest),
        ).joinToString("\$")
    }

    /**
     * Verify [pin] against a hash previously produced by [hashPin].
     *
     * Returns false for malformed or empty [encoded] values rather than
     * throwing, so a corrupted settings blob locks the user out of the PIN
     * path instead of crashing the unlock screen.
     */
    fun verifyPin(pin: String, encoded: String?): Boolean {
        if (encoded.isNullOrEmpty()) return false

        val parts = encoded.split('$')
        if (parts.size != 4 || parts[0] != ALGORITHM) return false

        val storedIterations = parts[1].toIntOrNull() ?: return false
        if (storedIterations <= 0) return false

        val salt: ByteArray
        val expected: ByteArray
        try {
            salt = base64Decode(parts[2])
            expected = base64Decode(parts[3])
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (expected.isEmpty()) return false

        val actual = pbkdf2(pin, salt, storedIterations, expected.size)
        return constantTimeEquals(actual, expected)
    }

    /**
     * Whether [encoded] uses weaker parameters than this service would
     * produce, meaning the PIN should be re-hashed the next time it is
     * entered.
     */
    fun needsRehash(encoded: String?): Boolean {
        if (encoded.isNullOrEmpty()) return true
        val parts = encoded.split('$')
        if (parts.size != 4 || parts[0] != ALGORITHM) return true
        val storedIterations = parts[1].toIntOrNull() ?: 0
        return storedIterations < iterations
    }

    /** Persist a new hash for [pin] into `AppSettings.pinHash`. */
    fun storePin(pin: String) {
        val hash = hashPin(pin)
        settingsRepository.update { it.copy(pinHash = hash) }
    }

    /** Verify [pin] against the hash currently stored in settings. */
    fun verifyStoredPin(pin: String): Boolean =
        verifyPin(pin, settingsRepository.load().pinHash)

    /**
     * Transparently upgrade hashes created with weaker parameters, mirroring
     * the Dart controller's post-unlock rehash step.
     */
    fun rehashIfNeeded(pin: String) {
        val current = settingsRepository.load().pinHash
        if (needsRehash(current)) {
            settingsRepository.update { it.copy(pinHash = hashPin(pin)) }
        }
    }

    /** PBKDF2-HMAC-SHA256 (RFC 8018), single-block output. */
    private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, keyBytes * 8)
        return try {
            SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Compare without leaking length-prefix timing information. */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }

    private fun base64Encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun base64Decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val DEFAULT_ITERATIONS = 120_000
        const val ALGORITHM = "pbkdf2-sha256"
        const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val SALT_BYTES = 16
        const val KEY_BYTES = 32
    }
}
