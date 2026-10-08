package com.lumovault.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens this app's page in the system settings.
 *
 * The one exit when a permission can no longer be asked for — Android stops showing its dialog after
 * "Don't ask again", and a button that re-launches a dialog that never appears is a dead end with a
 * label on it. [runCatching] because no device promises the page exists: a failure here is a tap that
 * visibly did nothing, which is the same as today's dead button, not a crash.
 */
fun Context.openAppDetailsSettings() = runCatching {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ),
    )
}

/**
 * Opens the system page that governs battery restrictions.
 *
 * Not a permission and not the same page: this is the one exit for the background row on both the setup
 * card and the Settings permissions screen, which is why it lives here rather than being written twice.
 * Like [openAppDetailsSettings] it is wrapped, and for the same reason — a device that does not offer the
 * page makes this a tap that visibly did nothing, which is the same as a dead button and not a crash.
 *
 * Nothing is recorded from the act of opening it. The battery status is read live when the screen comes
 * back (PRD section 38.3), so LumoVault can never claim a restriction was lifted because a page was shown.
 */
fun Context.openBatterySettings() = runCatching {
    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}
