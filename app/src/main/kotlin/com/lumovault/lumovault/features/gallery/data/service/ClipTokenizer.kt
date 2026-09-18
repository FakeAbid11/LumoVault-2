package com.lumovault.lumovault.features.gallery.data.service

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * CLIP byte-pair tokenizer — a faithful Kotlin port of OpenCLIP's
 * `SimpleTokenizer`, which is exactly what MobileCLIP uses to encode text into
 * the same token space its text tower was trained on.
 *
 * Ported from lib/features/gallery/data/services/clip_tokenizer.dart.
 *
 * The resulting id sequence from [tokenize] feeds the exported text-tower ONNX;
 * its output embedding lives in the same 512-dim space as the stored image
 * embeddings, which is what makes text-to-image ranking work.
 *
 * **The byte-encoding step is not optional.** Without it, a token beyond
 * latin-1 (Cyrillic, CJK, emoji) is looked up raw, is absent from the vocab,
 * and is silently dropped by the encoder miss — semantic search then returns
 * zero results for any non-latin query even though the vocab holds
 * byte-encoded ids for every byte.
 */
class ClipTokenizer private constructor(
    private val encoder: Map<String, Int>,
    private val bpeRanks: Map<Pair<String, String>, Int>,
    private val byteEncoder: Map<Int, String>,
    val contextLength: Int,
) {

    companion object {
        private const val SOT_ID = 49406
        private const val EOT_ID = 49407

        // The vocab asset is gzipped; the config is plain JSON.
        private const val VOCAB_ASSET = "tokenizer/bpe_simple_vocab_16e6.txt.gz"
        private const val CONFIG_ASSET = "tokenizer/config.json"

        // open_clip: merges = lines[1 : 49152 - 256 - 2 + 1]. The end is clamped
        // so a truncated vocab does not range-error. The constant yields exactly
        // 48,894 merges -> 256 + 256 + 48,894 + 2 specials = the canonical
        // 49,408-entry CLIP vocabulary, matching the fixed SOT/EOT ids and the
        // model's embedding table. (An earlier 49415-based slice pulled 263
        // extra merges whose derived ids collided with SOT/EOT and ran past the
        // embedding table.)
        private const val MERGE_END_INDEX = 49152 - 256 - 2 + 1

        private val TOKEN_PATTERN = Regex(
            """<\|startoftext\|>|<\|endoftext\|>|'s|'t|'re|'ve|'m|'ll|'d|[\p{L}]+|[\p{N}]|[^\s\p{L}\p{N}]+""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.UNICODE),
        )

        private val WHITESPACE_PATTERN = Regex("""\s+""")

        /**
         * Builds from the vocab + config in the app's assets.
         *
         * The files ship with the app, so a failure here means a broken build
         * rather than a runtime condition — it is allowed to throw.
         */
        fun fromAssets(context: Context): ClipTokenizer {
            val contextLength = readContextLength(context)
            val lines = context.assets.open(VOCAB_ASSET).use { stream ->
                GZIPInputStream(stream).use { gz ->
                    BufferedReader(InputStreamReader(gz, Charsets.UTF_8)).readLines()
                }
            }
            return fromLines(lines, contextLength = contextLength)
        }

        private fun readContextLength(context: Context): Int {
            val config = context.assets.open(CONFIG_ASSET).use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
            // Kept deliberately dependency-free: the config is one flat object,
            // so a substring beat pulling a JSON parser into this class.
            val regex = Regex(""""contextLength"\s*:\s*(\d+)""")
            return regex.find(config)?.groupValues?.get(1)?.toIntOrNull()
                ?: throw IllegalStateException("Tokenizer config has no contextLength")
        }

        /**
         * Builds from raw vocab lines. Mirrors OpenCLIP's
         * `SimpleTokenizer.__init__` so ids match the canonical tokenizer.
         */
        fun fromLines(lines: List<String>, contextLength: Int): ClipTokenizer {
            val byteEncoder = buildByteEncoder()
            val byteValues = byteEncoder.values.toList()

            val end = MERGE_END_INDEX.coerceIn(1, lines.size)
            val merges = lines.subList(1, end).map { line ->
                val parts = line.split(" ")
                if (parts.size < 2) null else parts[0] to parts[1]
            }
            // A malformed line has no merge pair; dropping it shifts later
            // ranks, but a vocab file that ships in the APK does not have any.
            .filterNotNull()

            val vocab = buildList {
                addAll(byteValues)
                addAll(byteValues.map { "$it</w>" })
                addAll(merges.map { (a, b) -> a + b })
                add("<|startoftext|>")
                add("")
            }
            val encoder = vocab.mapIndexed { index, token -> token to index }.toMap()
            val bpeRanks = merges.mapIndexed { index, pair -> pair to index }.toMap()

            return ClipTokenizer(encoder, bpeRanks, byteEncoder, contextLength)
        }

        /**
         * CLIP's reversible byte-to-unicode table: printable ASCII/latin-1 map
         * to themselves, the other 68 bytes map to code points 256+, so
         * arbitrary bytes stay visible tokens.
         */
        private fun buildByteEncoder(): Map<Int, String> {
            val bs = mutableListOf<Int>()
            for (b in 0x21..0x7E) bs.add(b)
            for (b in 0xA1..0xAC) bs.add(b)
            for (b in 0xAE..0xFF) bs.add(b)

            val cs = bs.toMutableList()
            var n = 0
            for (b in 0..0xFF) {
                if (b !in bs) {
                    bs.add(b)
                    cs.add(0x100 + n)
                    n++
                }
            }
            return bs.mapIndexed { i, byte -> byte to cs[i].toChar().toString() }.toMap()
        }
    }

    /**
     * Encodes [text] into exactly [contextLength] token ids:
     * `[SOT, word ids..., EOT, 0-padding...]`. Queries longer than the context
     * are truncated (CLIP-standard behavior).
     */
    fun tokenize(text: String): LongArray {
        val cleaned = whitespaceClean(text.lowercase())
        val ids = mutableListOf<Long>()

        for (match in TOKEN_PATTERN.findAll(cleaned)) {
            val token = match.value
            // Byte-encode before BPE, as OpenCLIP does — see the class doc.
            val byteToken = token.toByteArray(Charsets.UTF_8)
                .joinToString("") { byteEncoder[it.toInt()].orEmpty() }

            for (piece in bpe(byteToken).split(' ')) {
                encoder[piece]?.let { ids.add(it.toLong()) }
            }
        }

        val kept = ids.take((contextLength - 2).coerceAtLeast(0))
        val tokens = LongArray(contextLength)
        tokens[0] = SOT_ID.toLong()
        kept.forEachIndexed { i, id -> tokens[i + 1] = id }
        tokens[kept.size + 1] = EOT_ID.toLong()
        return tokens
    }

    /**
     * Byte-pair merge on one word's characters, with CLIP's `</w>` suffix on
     * the trailing character marking the end of the word. Repeatedly merges the
     * lowest-ranked adjacent pair until no ranked pair remains.
     */
    private fun bpe(word: String): String {
        if (word.length <= 1) return "$word</w>"

        val parts = word.toCharArray().map { it.toString() }.toMutableList()
        parts[parts.size - 1] = "${parts.last()}</w>"

        while (parts.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestIndex = -1
            for (i in 0 until parts.size - 1) {
                val rank = bpeRanks[parts[i] to parts[i + 1]] ?: continue
                if (rank < bestRank) {
                    bestRank = rank
                    bestIndex = i
                }
            }
            if (bestIndex == -1) break
            parts[bestIndex] = parts[bestIndex] + parts[bestIndex + 1]
            parts.removeAt(bestIndex + 1)
        }

        return parts.joinToString(" ")
    }

    private fun whitespaceClean(text: String): String =
        text.split('\n').joinToString(" ") { it.trim() }
            .replace(WHITESPACE_PATTERN, " ")
            .trim()
}
