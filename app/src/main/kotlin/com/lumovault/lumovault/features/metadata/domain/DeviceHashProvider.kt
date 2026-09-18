package com.lumovault.lumovault.features.metadata.domain

/**
 * Provides the sha256 of the device id written into the manifest's
 * `device_hash`. Kept as its own type so the sync services stay testable
 * without a Telegram client — the manifest needs a stable per-device value, and
 * in production it comes from the backup layer.
 */
fun interface DeviceHashProvider {
    suspend fun deviceHash(): String
}
