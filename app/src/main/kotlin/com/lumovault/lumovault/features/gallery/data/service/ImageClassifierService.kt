package com.lumovault.lumovault.features.gallery.data.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import com.lumovault.lumovault.core.ml.OnnxModelHost
import com.lumovault.lumovault.core.ml.TensorPreprocessing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * ImageNet-1k classification of a photo's thumbnail, producing the `ai_*`
 * label tags that keyword search matches against.
 *
 * Ported from lib/features/gallery/data/services/image_classifier_service.dart.
 * Preprocessing is MobileOne-S2's: 224² NCHW RGB with ImageNet mean/std —
 * note this is the *only* model in the app that uses mean/std, and mixing it
 * with CLIP's identity normalization is a silent failure.
 */
@Singleton
class ImageClassifierService @Inject constructor(
    private val context: Context,
    private val modelHost: OnnxModelHost,
) {

    @Volatile private var session: OrtSession? = null
    private val mutex = Mutex()

    private suspend fun ensureSession(): OrtSession =
        session ?: modelHost.loadSession(MODEL_ASSET).also { session = it }

    /**
     * Classifies [bytes], returning the `ai_*` tag list, or null if the image
     * cannot be decoded or the model is unavailable.
     */
    suspend fun classify(bytes: ByteArray): List<String>? = mutex.withLock {
        val session = ensureSession()
        val bitmap = java.io.ByteArrayInputStream(bytes).use { stream ->
            TensorPreprocessing.decodeDownsampled(stream, maxSide = INPUT_SIZE * 2)
        } ?: return@withLock null

        try {
            val input = TensorPreprocessing.classifierTensor(bitmap, INPUT_SIZE)
            val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            val inputName = session.inputNames.first()
            val outputName = session.outputNames.first()

            OnnxTensor.createTensor(modelHost.environment, java.nio.FloatBuffer.wrap(input), shape).use { tensor ->
                val outputs = session.run(mapOf(inputName to tensor))
                try {
                    val out = outputs[outputName] ?: return@withLock null
                    val buffer = (out as OnnxTensor).floatBuffer
                    val logits = FloatArray(buffer.remaining())
                    buffer.get(logits)
                    decodeTopLabels(logits)
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

    companion object {
        const val MODEL_ASSET = "models/mobileone_s2.onnx"
        const val INPUT_SIZE = 224

        // 0.10 made maxLabels dead — nothing but the top class cleared it.
        const val THRESHOLD = 0.08
        const val MAX_LABELS = 4

        /**
         * Applies softmax and returns the top-N `ai_*` tags for [logits],
         * followed by the generic categories they expand to.
         *
         * Public and ONNX-free so the label decode path — thresholds, the
         * canonical-index mapping and the hypernym expansion — is unit-testable
         * without a model: this is pure math over the logits.
         */
        fun decodeTopLabels(
            logits: FloatArray,
            threshold: Double = THRESHOLD.toDouble(),
            maxLabels: Int = MAX_LABELS,
        ): List<String> {
            if (logits.isEmpty()) return emptyList()

            // Softmax with numerical stability.
            val maxLogit = logits.max()
            val exps = FloatArray(logits.size) { exp((logits[it] - maxLogit).toDouble()).toFloat() }
            var sumExp = 0.0
            for (e in exps) sumExp += e

            // Collect candidates above the confidence floor.
            val indexed = ArrayList<Pair<Int, Float>>(maxLabels)
            for (i in logits.indices) {
                if (exps[i] / sumExp >= threshold) {
                    indexed.add(i to exps[i])
                }
            }
            indexed.sortByDescending { it.second }

            val labels = mutableListOf<String>()
            for ((index, _) in indexed.take(maxLabels)) {
                val label = imageNetLabels[index] ?: continue
                labels.add("ai_${label.lowercase().replace(' ', '_')}")
                for (hypernym in hypernymsFor(label.lowercase())) {
                    val tag = "ai_$hypernym"
                    if (tag !in labels) labels.add(tag)
                }
            }
            return labels
        }

        /**
         * Generic categories a class name belongs to (word-boundary matched, so
         * 'tabby' → 'cat' but 'catamaran' does not).
         */
        fun hypernymsFor(lowerCasedName: String): List<String> =
            imageNetHypernyms.entries
                .filter { (_, words) ->
                    words.any { word ->
                        Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(lowerCasedName)
                    }
                }
                .map { it.key }
    }
}
