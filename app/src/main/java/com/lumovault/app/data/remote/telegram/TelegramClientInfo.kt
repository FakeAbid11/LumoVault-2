package com.lumovault.app.data.remote.telegram

import android.os.Build
import java.io.File
import java.util.Locale

/** Device facts TDLib reports about the app, kept behind a data class so nothing reads statics directly. */
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val release: String,
    val languageCode: String,
) {
    companion object {
        fun fromDevice(): DeviceInfo = DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            release = Build.VERSION.RELEASE,
            languageCode = Locale.getDefault().language,
        )
    }
}

/**
 * The values TDLib asks for when identifying the app. Telegram shows these in its session list, so
 * they describe LumoVault honestly rather than pretending to be an official client.
 */
data class TelegramClientInfo(
    val applicationVersion: String,
    val deviceModel: String,
    val systemVersion: String,
    val systemLanguageCode: String,
) {
    companion object {
        fun of(device: DeviceInfo, applicationVersion: String): TelegramClientInfo = TelegramClientInfo(
            applicationVersion = applicationVersion,
            deviceModel = "${device.manufacturer} ${device.model}".trim(),
            systemVersion = "Android ${device.release}",
            systemLanguageCode = device.languageCode,
        )
    }
}

/**
 * Where TDLib keeps its own session database and downloaded files. This is app-private storage, and
 * it is the only place credentials ever live: LumoVault does not copy session data into its own
 * preferences.
 */
data class TelegramStorage(val databaseDirectory: File, val filesDirectory: File) {
    fun ensureCreated(): TelegramStorage {
        databaseDirectory.mkdirs()
        filesDirectory.mkdirs()
        return this
    }
}
