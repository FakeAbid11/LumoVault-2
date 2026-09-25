package com.lumovault.app.data.media

import com.lumovault.app.domain.model.MediaType
import java.io.InputStream

/**
 * What a file actually is, read from the few bytes that decide it.
 *
 * A restored file's name and MIME type arrive from the cloud index, which recorded what *this* app told
 * Telegram — and Telegram's own containers are not obliged to keep that shape. An animation sent as a GIF
 * can come back as an MP4; a photo can be recompressed into a different JPEG. Writing the index's MIME
 * type over bytes that are not that type produces a gallery entry that opens in no app, and the only
 * witness is the file itself.
 *
 * So this reads a header. Sixteen bytes, from the front of a file that may be four gigabytes — which is
 * also the reason it is not `MediaMetadataRetriever`, `BitmapFactory`, or any API that has to parse the
 * container to answer a question the first eight bytes already settled.
 */
internal object FileKindSniffing {

    /** Bytes read to decide: enough for a box signature, which starts with a length word. */
    private const val HEADER_BYTES = 16

    /**
     * The container the bytes announce, or null when nothing here matched.
     *
     * Null is a real answer and callers must handle it: a truncated download, a file Telegram stored as an
     * opaque document, or a format this list has not been given a reason to know. In every one of those
     * cases the index's own MIME type is the better guess than a coin flip.
     */
    fun sniff(source: InputStream): FileKind? {
        val header = readHeader(source)
        if (header.isEmpty()) return null

        // JPEG: SOI marker.
        if (header.hasPrefix(0xFF, 0xD8, 0xFF)) return FileKind.Jpeg
        // PNG: the start of the eight-byte signature.
        if (header.hasPrefix(0x89, 'P', 'N', 'G')) return FileKind.Png
        // GIF: "GIF8".
        if (header.hasPrefix('G', 'I', 'F', '8')) return FileKind.Gif
        // WEBP: "RIFF" at the front and "WEBP" four fields in.
        if (header.hasPrefix('R', 'I', 'F', 'F') && header.hasPrefixAt(8, 'W', 'E', 'B', 'P')) {
            return FileKind.WebP
        }
        // The MP4 family: a box whose type is "ftyp", which sits at byte four.
        if (header.hasPrefixAt(4, 'f', 't', 'y', 'p')) return FileKind.Mp4
        return null
    }

    /**
     * Up to [HEADER_BYTES] from the front, in as many reads as the stream wants to give.
     *
     * Written out because the one-call version, `InputStream.readNBytes`, is API 34 — and this app supports
     * 29, where a short read is ordinary and a loop is the only correct way to fill a fixed buffer. The
     * `read` call may return fewer bytes than asked for even on a healthy file, so stopping on a short read
     * would mis-sniff real photos on some devices and not others.
     */
    private fun readHeader(source: InputStream): ByteArray {
        val buffer = ByteArray(HEADER_BYTES)
        var filled = 0
        while (filled < HEADER_BYTES) {
            val read = source.read(buffer, filled, HEADER_BYTES - filled)
            if (read < 0) break
            filled += read
        }
        return buffer.copyOf(filled)
    }

    /**
     * These bytes, at the start or at [offset].
     *
     * Its own helpers rather than standard-library calls because `ByteArray` has no `startsWith`: the
     * overloads that exist are for `Array<T>` and `List<T>`, and comparing a signed byte against an `Int`
     * literal is exactly the conversion a header check should not repeat at every call site. Two names, not
     * one defaulted parameter, so `hasPrefix(0xFF, …)` cannot be read as an offset of 255.
     */
    private fun ByteArray.hasPrefix(vararg bytes: Int): Boolean = hasPrefixAt(0, *bytes)

    /** The character spelling, so `"GIF8"` reads as the four bytes it names rather than as four numbers. */
    private fun ByteArray.hasPrefix(vararg chars: Char): Boolean =
        hasPrefixAt(0, *chars.map { it.code }.toIntArray())

    private fun ByteArray.hasPrefixAt(offset: Int, vararg bytes: Int): Boolean {
        if (offset < 0 || size < offset + bytes.size) return false
        return bytes.indices.all { index ->
            this[offset + index].toInt() and 0xFF == bytes[index] and 0xFF
        }
    }

    private fun ByteArray.hasPrefixAt(offset: Int, vararg chars: Char): Boolean =
        hasPrefixAt(offset, *chars.map { it.code }.toIntArray())
}

/**
 * A container a header announced.
 *
 * Each entry carries what MediaStore needs to file the row correctly: which collection, and the MIME type
 * and extension that match the bytes rather than the filename.
 */
internal enum class FileKind(
    val mediaType: MediaType,
    val mimeType: String,
    val extension: String,
) {
    Jpeg(MediaType.Photo, "image/jpeg", "jpg"),
    Png(MediaType.Photo, "image/png", "png"),
    WebP(MediaType.Photo, "image/webp", "webp"),
    Gif(MediaType.Gif, "image/gif", "gif"),
    Mp4(MediaType.Video, "video/mp4", "mp4"),
    ;

    /** Where this kind of file belongs, and the folder it is filed under. */
    val relativePath: String
        get() = when (mediaType) {
            MediaType.Photo -> "Pictures/$OWNED_ALBUM/"
            MediaType.Video, MediaType.Gif -> "Movies/$OWNED_ALBUM/"
        }

    private companion object {
        /**
         * The folder restored files live in.
         *
         * One owned directory rather than the source's original path, because the path a file came from is
         * not knowable after a device change and a row written into somebody else's folder cannot be
         * updated by this app without a system consent prompt every time.
         */
        const val OWNED_ALBUM = "LumoVault"
    }
}
