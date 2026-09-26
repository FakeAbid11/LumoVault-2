package com.lumovault.app.util

/**
 * Bytes to a human line, without pretending a zero is an unknown.
 *
 * Shared because three Phase 9 surfaces quote sizes — the cloud sheet, the restore progress line, and Free
 * Up Space's headline — and a number that reads "1.9 MB" in one place and "3.4 GB" in another is two rules
 * about the same quantity. Binary units, because the sizes come from MediaStore and Telegram, which count
 * bytes, and a user comparing against their device's own storage report should see the same arithmetic.
 */
internal fun Long.toByteText(): String = when {
    this <= 0L -> "0 B"
    this >= 1_000_000_000L -> "%.1f GB".format(this / 1_000_000_000.0)
    this >= 1_000_000L -> "%.1f MB".format(this / 1_000_000.0)
    this >= 1_000L -> "%.0f kB".format(this / 1_000.0)
    else -> "$this B"
}
