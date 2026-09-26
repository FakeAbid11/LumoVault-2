package com.lumovault.app.domain.telegram

/**
 * What LumoVault writes into the storage channel about one backed-up file.
 *
 * The channel already holds the bytes; what it did not hold was any way to say which local file they
 * came from. Telegram's own message metadata reports a name for a video and nothing for a photo, and it
 * reports no identity for the original at all — so a reinstall that lost the local database could list
 * 1,000 messages and still not know that any of them is the user's `IMG_0001.jpg`. PRD section 14 makes
 * that recognition a core requirement, and section 11 makes the content hash the key that answers it.
 *
 * So the manifest travels as the media message's own caption. That placement is deliberate:
 * - The hash and the file it describes live in one message, so the association cannot drift the way a
 *   sidecar record could — there is no second message to lose, and no ordering between two.
 * - The existing history walk already reads captions ([LumoVaultStorageProtocol] validates channels by
 *   one), so recognition needs no extra request and downloads no original bytes. That is PRD section 25
 *   and phase-6 section 8 satisfied by construction rather than by discipline.
 * - One backup stays one message in the user's own channel.
 *
 * The fields are keyed and order-fixed rather than JSON because a caption has to survive being read
 * back after an unknown number of Telegram revisions, and because a format nothing in this file has to
 * parse generically is a format that cannot be tricked by a file named `"}"`.
 */
data class BackupManifest(
    /** SHA-256 of the local original's bytes, lowercase hex. The authoritative exact-content key. */
    val contentHash: String,
    /** Bytes as counted while hashing, kept as a cross-check against a caption that outlived its file. */
    val sizeBytes: Long,
    /** MediaStore's modification time for the hashed file, in whole seconds. 0 when unknown. */
    val modifiedSeconds: Long,
    /** Display name, for a Cloud screen that would otherwise show a bare timestamp. */
    val fileName: String,
)

/**
 * The text form of [BackupManifest].
 *
 * `decode` never throws and returns null for anything it cannot vouch for, which is the property that
 * matters most here: a malformed caption must read as "this message tells me nothing", not as a hash
 * that happens to look like a prefix of some real file. Guessing on this path puts a ✓ on a photo that
 * was never backed up.
 */
object BackupManifestFormat {
    /** Distinct from [LumoVaultStorageProtocol.MARKER_KEY] on purpose, so a manifest is never a marker. */
    const val PREFIX = "LUMOVAULT_META"

    /** Bump when the field set changes; a build that cannot speak a version must ignore it. */
    const val VERSION = 1

    /**
     * Telegram's own caption ceiling is a `message_caption_length_max` option, and hard-coding the
     * server's number would make a send fail on a field this app does not need. Half of it leaves the
     * manifest room for every field while staying far inside any plausible limit.
     */
    const val CAPTION_BUDGET_CHARS = 512

    private const val KEY_HASH = "h"
    private const val KEY_SIZE = "s"
    private const val KEY_MODIFIED = "m"
    private const val KEY_NAME = "n"
    private const val VERSION_PREFIX = "v"
    private const val SHA_256_HEX_LENGTH = 64

    /**
     * The deterministic text form: prefix, version, hash, then the optional fields in a fixed order.
     *
     * Optional fields are dropped from the end rather than truncated, so a caption that fits 512
     * characters of one file and 300 of another still parses to the same hash and the same size for
     * both. A half-written file name would be a wrong fact; a missing one is just less decoration.
     */
    fun encode(manifest: BackupManifest, budgetChars: Int = CAPTION_BUDGET_CHARS): String {
        val builder = StringBuilder()
            .append(PREFIX)
            .append(' ')
            .append(VERSION_PREFIX)
            .append(VERSION)
            .append(' ')
            .append(KEY_HASH)
            .append('=')
            .append(manifest.contentHash)

        fun fits(candidate: String): Boolean = builder.length + 1 + candidate.length <= budgetChars

        if (fits("$KEY_SIZE=${manifest.sizeBytes}")) {
            builder.append(' ').append(KEY_SIZE).append('=').append(manifest.sizeBytes)
        }
        if (fits("$KEY_MODIFIED=${manifest.modifiedSeconds}")) {
            builder.append(' ').append(KEY_MODIFIED).append('=').append(manifest.modifiedSeconds)
        }

        val name = manifest.fileName.trim()
        if (name.isNotEmpty()) {
            val escaped = escape(name)
            if (fits("$KEY_NAME=$escaped")) builder.append(' ').append(KEY_NAME).append('=').append(escaped)
        }
        return builder.toString()
    }

    /** The manifest a caption carries, or null when the caption is not one this build can read. */
    fun decode(caption: String?): BackupManifest? {
        val normalized = caption
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val tokens = normalized.split(' ').filter { it.isNotBlank() }
        if (tokens.firstOrNull() != PREFIX) return null

        val version = tokens.getOrNull(1)?.takeIf { it.startsWith(VERSION_PREFIX) }
            ?.removePrefix(VERSION_PREFIX)?.toIntOrNull() ?: return null
        // A newer manifest is ignored rather than half-read: fields may mean something else in it, and
        // an outdated app must not invent a backup relationship from a format it has never seen.
        if (version !in MIN_SUPPORTED_VERSION..VERSION) return null

        val fields = tokens.drop(2).mapNotNull { token ->
            val separator = token.indexOf('=')
            if (separator <= 0) null else token.substring(0, separator) to token.substring(separator + 1)
        }.toMap()

        val hash = fields[KEY_HASH]?.lowercase()?.takeIf { it.isSha256Hex() } ?: return null

        return BackupManifest(
            contentHash = hash,
            sizeBytes = fields[KEY_SIZE]?.toLongOrNull()?.takeIf { it >= 0L } ?: 0L,
            modifiedSeconds = fields[KEY_MODIFIED]?.toLongOrNull()?.takeIf { it >= 0L } ?: 0L,
            fileName = fields[KEY_NAME]?.let(::unescape).orEmpty(),
        )
    }

    private const val MIN_SUPPORTED_VERSION = 1

    private fun String.isSha256Hex(): Boolean =
        length == SHA_256_HEX_LENGTH && all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * Percent-escapes everything that could otherwise be read as a separator.
     *
     * A space would split the name into two tokens, an `=` would confuse the key boundary, and a `%`
     * has to escape itself or decoding cannot tell an escaped byte from a literal one. Ordinary file
     * names — `IMG_2024-05-01.jpg` — pass through untouched, which keeps the caption readable in the
     * user's own channel.
     */
    private fun escape(value: String): String {
        val builder = StringBuilder(value.length)
        for (byte in value.toByteArray(java.nio.charset.StandardCharsets.UTF_8)) {
            val unsigned = byte.toInt() and 0xFF
            if (isUnreserved(unsigned)) {
                builder.append(unsigned.toChar())
            } else {
                builder.append('%').append(HEX_DIGITS[unsigned shr 4]).append(HEX_DIGITS[unsigned and 0x0F])
            }
        }
        return builder.toString()
    }

    private fun unescape(value: String): String {
        val bytes = java.io.ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            val digitHigh = if (char == '%' && index + 2 < value.length) {
                Character.digit(value[index + 1], 16)
            } else {
                -1
            }
            val digitLow = if (digitHigh >= 0) Character.digit(value[index + 2], 16) else -1

            if (digitHigh >= 0 && digitLow >= 0) {
                bytes.write(digitHigh shl 4 or digitLow)
                index += 3
            } else {
                // Anything else is a literal, including a stray '%' that escaping never produced. The
                // UTF-8 round trip matters here: an escaped non-ASCII byte must not come back as one char.
                bytes.write(char.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                index++
            }
        }
        return String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8)
    }

    private fun isUnreserved(byte: Int): Boolean =
        byte in 'a'.code..'z'.code || byte in 'A'.code..'Z'.code || byte in '0'.code..'9'.code ||
            byte == '.'.code || byte == '-'.code || byte == '_'.code

    private const val HEX_DIGITS = "0123456789ABCDEF"
}
