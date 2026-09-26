package com.lumovault.app.domain.telegram

/**
 * The storage protocol that marks a Telegram channel as LumoVault's own.
 *
 * A channel called "LumoVault Backup" is not evidence of anything: anyone can name a channel that,
 * and adopting it would mean reading a stranger's messages into this user's library and writing
 * backups into someone else's chat (PRD section 6). So the name only produces *candidates*, and
 * [isMarker] decides whether one is ours.
 *
 * The marker is written twice at creation — into the channel description and as the first message —
 * because the two fail differently: a user who edits their channel bio removes the description
 * copy, while a channel restored from an old session may be missing history. Either copy is enough
 * to adopt, and [markerVersion] rejects a protocol this build cannot speak.
 */
object LumoVaultStorageProtocol {
    /** Exact channel title, per PRD section 5.1. */
    const val CHANNEL_TITLE = "LumoVault Backup"

    /** Bump when the on-channel format changes; older builds must not adopt a newer channel. */
    const val VERSION = 1

    /** First line of the marker. Kept free of spaces so a line-level test is unambiguous. */
    const val MARKER_KEY = "LUMOVAULT_BACKUP"

    private const val VERSION_PREFIX = "version:"

    fun markerText(version: Int = VERSION): String = "$MARKER_KEY\n$VERSION_PREFIX $version"

    /**
     * Protocol version declared by [text], or null when it is not a marker at all.
     *
     * Deliberately unbounded: whether a version is *supported* is a decision for the caller, because
     * "not a marker" and "a marker from a newer LumoVault" need different answers — one is ignored,
     * the other is reported.
     */
    fun markerVersion(text: String?): Int? {
        // Newlines are flattened because the same marker has to parse from a message *and* from a
        // channel description, and Telegram is free to collapse a line break in the latter.
        val normalized = text?.replace('\n', ' ')?.replace('\r', ' ')?.trim() ?: return null
        if (!normalized.startsWith(MARKER_KEY)) return null

        val versionIndex = normalized.indexOf(VERSION_PREFIX)
        if (versionIndex < 0) return null

        val digits = normalized.substring(versionIndex + VERSION_PREFIX.length)
            .takeWhile { it.isDigit() || it.isWhitespace() }
            .trim()
        return digits.toIntOrNull()?.takeIf { it > 0 }
    }

    /** True only for a marker this build can speak; a newer protocol is rejected, not adopted. */
    fun isMarker(text: String?): Boolean {
        val version = markerVersion(text) ?: return false
        return version in MIN_SUPPORTED_VERSION..VERSION
    }

    /** A channel cannot be downgraded, so a newer protocol is a reason to keep away from it. */
    private const val MIN_SUPPORTED_VERSION = 1
}
