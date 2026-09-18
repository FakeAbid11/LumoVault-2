package com.lumovault.lumovault.features.gallery.data.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import com.lumovault.lumovault.core.ml.OnnxModelHost
import com.lumovault.lumovault.core.ml.TensorPreprocessing
import com.lumovault.lumovault.features.people.data.service.l2Normalize
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CLIP image and text embeddings for semantic search.
 *
 * Ported from lib/features/gallery/data/services/clip_embedding_service.dart.
 * The image tower embeds a photo into 512-dim; the text tower embeds a query
 * into the same space, which is what makes text-to-image ranking work.
 *
 * The one thing that must not be ported loosely is the preprocessing:
 * MobileCLIP2-S0 wants pixels scaled to [0, 1] with **no mean/std shift**. The
 * original's comment is load-bearing — "Feeding raw 0-255 produced garbage
 * vectors" — and applying the standard CLIP mean/std instead is the exact
 * mistake that makes semantic search silently return noise.
 *
 * Coroutine-based: the Dart version pushed decode+preprocess to `compute()`
 * and ran inference on the calling isolate; here every entry point is
 * `suspend` and the caller's dispatcher plays the isolate's role, with
 * per-session mutexes because an OrtSession is not thread-safe.
 */
@Singleton
class ClipEmbeddingService @Inject constructor(
    private val context: Context,
    private val modelHost: OnnxModelHost,
) {

    @Volatile private var visionSession: OrtSession? = null
    @Volatile private var textSession: OrtSession? = null
    @Volatile private var tokenizer: ClipTokenizer? = null

    private val visionMutex = Mutex()
    private val textMutex = Mutex()

    private suspend fun ensureVision(): OrtSession = visionSession ?: modelHost
        .loadSession(VISION_ASSET).also { visionSession = it }

    private suspend fun ensureText(): Pair<OrtSession, ClipTokenizer> {
        val session = textSession ?: modelHost.loadSession(TEXT_ASSET).also { textSession = it }
        val tokenizer = tokenizer ?: ClipTokenizer.fromAssets(context).also { this.tokenizer = it }
        return session to tokenizer
    }

    /**
     * Embeds an image into the 512-dim CLIP space, or null if the bytes cannot
     * be decoded or the tower is unavailable.
     */
    suspend fun embedImage(bytes: ByteArray): FloatArray? = visionMutex.withLock {
        val session = ensureVision()
        val bitmap = java.io.ByteArrayInputStream(bytes).use { stream ->
            TensorPreprocessing.decodeDownsampled(stream, maxDecodeSide = VISION_INPUT_SIZE * 2)
        } ?: return@withLock null

        try {
            val input = TensorPreprocessing.clipVisionTensor(bitmap, VISION_INPUT_SIZE)
            val shape = longArrayOf(1, 3, VISION_INPUT_SIZE.toLong(), VISION_INPUT_SIZE.toLong())
            val inputName = session.inputNames.first()
            val outputName = session.outputNames.first()

            OnnxTensor.createTensor(modelHost.environment, input, shape).use { tensor ->
                val outputs = session.run(mapOf(inputName to tensor))
                try {
                    val out = outputs[outputName] ?: return@withLock null
                    val buffer = out.floatBuffer
                    if (buffer.remaining() < EMBEDDING_DIM) return@withLock null
                    val slice = FloatArray(EMBEDDING_DIM)
                    buffer.get(slice)
                    l2Normalize(slice)
                } finally {
                    outputs.close()
                }
            }
        } catch (_: Throwable) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Embeds a search query into the same 512-dim space as the image
     * embeddings. The query is tokenized with CLIP's BPE (padded to the model's
     * context length) and run through the text tower.
     *
     * Returns null when the text tower is unavailable, so search can degrade to
     * keyword matching rather than crashing.
     */
    suspend fun embedText(query: String): FloatArray? = textMutex.withLock {
        val (session, tokenizer) = try {
            ensureText()
        } catch (_: Throwable) {
            return@withLock null
        }

        try {
            val tokens = tokenizer.tokenize(query)
            val shape = longArrayOf(1, tokens.size.toLong())
            val inputName = session.inputNames.first()
            val outputName = session.outputNames.first()

            OnnxTensor.createTensor(modelHost.environment, tokens, shape).use { tensor ->
                val outputs = session.run(mapOf(inputName to tensor))
                try {
                    val out = outputs[outputName] ?: return@withLock null
                    val buffer = out.floatBuffer
                    if (buffer.remaining() < EMBEDDING_DIM) return@withLock null
                    val slice = FloatArray(EMBEDDING_DIM)
                    buffer.get(slice)
                    // The export already L2-normalizes; normalize again
                    // defensively so a quantization wobble can't skew cosine
                    // ranking.
                    l2Normalize(slice)
                } finally {
                    outputs.close()
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        const val VISION_ASSET = "models/mobileclip2_s0_vision.onnx"
        const val TEXT_ASSET = "models/mobileclip2_s0_text_int8.onnx"

        const val VISION_INPUT_SIZE = 256
        const val EMBEDDING_DIM = 512
    }
}
