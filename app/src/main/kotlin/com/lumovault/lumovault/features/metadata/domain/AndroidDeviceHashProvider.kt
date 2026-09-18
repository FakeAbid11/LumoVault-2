package com.lumovault.lumovault.features.metadata.domain

import android.os.Build
import java.security.MessageDigest

/**
 * Production device identity for the manifest's `device_hash`.
 *
 * Mirrors `storage_channel_service.dart:419-436`: sha256 of `"lumovault:$id"`
 * where `id` is the Android build ID, with a stable fallback if the lookup
 * fails. The salt keeps the hash from colliding with another app using the
 * same raw identifier.
 */
class AndroidDeviceHashProvider : DeviceHashProvider {

    override suspend fun deviceHash(): String {
        val rawId = try {
            Build.ID
        } catch (_: Throwable) {
            // Degraded but stable within a run, as in the original.
            Build.MANUFACTURER
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("lumovault:$rawId".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
