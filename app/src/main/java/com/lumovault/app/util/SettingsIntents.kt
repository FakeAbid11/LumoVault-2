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
