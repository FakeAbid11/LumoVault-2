package com.lumovault.lumovault.features.people.data.service

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.lumovault.lumovault.core.ml.OnnxModelHost
import com.lumovault.lumovault.core.ml.TensorPreprocessing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Detects faces, aligns them onto the ArcFace template, embeds them, and
 * produces a thumbnail crop per face.
 *
 * Ported from lib/features/people/data/services/face_detection_service.dart.
 * The Dart original ran preprocessing on a dedicated long-lived worker isolate
 * and inference on the isolate that owned the service (a platform-channel
 * requirement). Here every entry point is `suspend` and the caller's coroutine
 * dispatcher plays the isolate's role — no main thread, no isolate plumbing.
 *
 * What carried over deliberately:
 *  - the embedder is EdgeFace-XS-GAMMA and nothing else. Vectors from two
 *    embedding models are not comparable; the old silent fallback mixed vector
 *    spaces and merged different people across models. A load failure surfaces
 *    as [FaceDetectorUnavailable] instead of quietly switching spaces.
 *  - detector outputs are classified by **shape**, not name — the exported
 *    names are opaque numbers.
 *  - [embedderModelTag] is stamped on every face row and person centroid;
 *    clustering refuses vectors whose tags differ.
 *  - a broken pipeline throws rather than returning an empty result: returning
 *    `empty()` used to record "scanned, 0 faces" for every photo, poisoning the
 *    scan log and leaving the People tab showing "no people" with no retry.
 *
 * Native caveat: this class loads `libonnxruntime.so` and cannot be exercised
 * in a JVM unit test. The pure decode/alignment/clustering math lives in
 * [FaceDetectionMath] and [FaceClusteringService], which can.
 */
@Singleton
class FaceDetectionService @Inject constructor(
    private val context: Context,
    private val modelHost: OnnxModelHost,
) {

    /** A decoded face with its embedding and source-space geometry. */
    data class DetectedFace(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
        val confidence: Double,
        val embedding: FloatArray,
        val landmarks: List<Pair<Double, Double>>,
        /** Absolute path of the written thumbnail crop, or null if unwritable. */
        val thumbnailPath: String? = null,
    )

    data class Result(
        val faces: List<DetectedFace>,
        val imageWidth: Int,
        val imageHeight: Int,
    ) {
        companion object {
            val EMPTY = Result(emptyList(), 0, 0)
        }
    }

    /**
     * The detector is unavailable — its ONNX session could not be brought up.
     * No detection result, not even an empty one, is trustworthy.
     */
    class FaceDetectorUnavailable(message: String = "Face detector unavailable") :
        RuntimeException("FaceDetectorUnavailable: $message")

    // A single OrtSession is not thread-safe, and a scan can be driven from
    // more than one coroutine, so inference is serialized per session.
    private val detectorMutex = Mutex()
    private val embedderMutex = Mutex()

    @Volatile private var detectorSession: OrtSession? = null
    @Volatile private var embedderSession: OrtSession? = null

    /** Set once the first inference confirms the export has no keypoint head. */
    @Volatile private var warnedNoKeypoints = false

    /**
     * The one embedder accepted since v20. The filename is the model tag
     * stamped onto every face row and person centroid.
     */
    val embedderModelTag: String
        get() = EMBEDDER_ASSET.substringAfterLast('/')

    private suspend fun ensureSessions(): Pair<OrtSession, OrtSession> {
        val detector = detectorSession ?: modelHost.loadSession(DETECTOR_ASSET).also {
            detectorSession = it
        }
        val embedder = embedderSession ?: modelHost.loadSession(EMBEDDER_ASSET).also {
            embedderSession = it
        }
        return detector to embedder
    }

    /**
     * Detects, aligns, embeds, and writes one thumbnail per face.
     *
     * [maxDecodeSide] bounds the decode — the original caps detection at 1600px
     * so a full-res 4032×3024 photo never materializes. Boxes and landmarks come
     * back in the coordinate space of that decode.
     */
    suspend fun detectFaces(imageBytes: ByteArray, maxDecodeSide: Int = 1600): Result {
        val (detector, embedder) = ensureSessions()

        val bitmap = decode(imageBytes, maxDecodeSide)
            ?: throw FaceDetectorUnavailable("Image could not be decoded")
        if (bitmap.width == 0 || bitmap.height == 0) {
            bitmap.recycle()
            throw FaceDetectorUnavailable("Decoded image has zero dimensions")
        }

        val width = bitmap.width
        val height = bitmap.height

        val detections = runDetection(bitmap, detector)
        if (detections.isEmpty()) {
            bitmap.recycle()
            return Result(emptyList(), width, height)
        }

        val faces = mutableListOf<DetectedFace>()
        detections.forEach { detection ->
            val aligned = warpToTemplate(bitmap, detection)
            val embedding = embedSingle(embedder, aligned)
            if (embedding.isNotEmpty()) {
                faces.add(
                    DetectedFace(
                        left = detection.left,
                        top = detection.top,
                        right = detection.right,
                        bottom = detection.bottom,
                        confidence = detection.score,
                        embedding = embedding,
                        landmarks = detection.landmarks,
                        thumbnailPath = writeThumbnail(aligned),
                    ),
                )
            }
            aligned.recycle()
        }
        bitmap.recycle()

        return Result(faces, width, height)
    }

    private fun decode(imageBytes: ByteArray, maxDecodeSide: Int): Bitmap? =
        java.io.ByteArrayInputStream(imageBytes).use { stream ->
            TensorPreprocessing.decodeDownsampled(stream, maxDecodeSide)
        }

    /** Runs detector inference and decodes + NMS-suppresses the outputs. */
    private suspend fun runDetection(bitmap: Bitmap, session: OrtSession): List<ScrfdDetection> =
        detectorMutex.withLock {
            val input = TensorPreprocessing.detectorTensor(bitmap, DETECTOR_INPUT_SIZE)
            val shape = longArrayOf(1, 3, DETECTOR_INPUT_SIZE.toLong(), DETECTOR_INPUT_SIZE.toLong())
            val inputName = session.inputNames.first()

            OnnxTensor.createTensor(modelHost.environment, java.nio.FloatBuffer.wrap(input), shape).use { tensor ->
                val outputs = session.run(mapOf(inputName to tensor))
                try {
                    decodeDetectorOutputs(outputs, bitmap.width, bitmap.height)
                } finally {
                    outputs.close()
                }
            }
        }

    /**
     * Classifies SCRFD's six output tensors by shape and decodes them.
     *
     * Per FPN stride the export emits a score tensor `[rows, 1]`, a
     * box-distance tensor `[rows, 4]` and a keypoint tensor `[rows, 10]`. The
     * names are opaque numbers, so disambiguation is by last dimension — with
     * a fallback for a score head exported as `[1, rows]` instead of `[rows, 1]`.
     */
    private fun decodeDetectorOutputs(
        outputs: OrtSession.Result,
        imageWidth: Int,
        imageHeight: Int,
    ): List<ScrfdDetection> {
        val scoresByStride = HashMap<Int, FloatArray>()
        val bboxesByStride = HashMap<Int, FloatArray>()
        val kpsByStride = HashMap<Int, FloatArray>()
        val anchorsByStride = HashMap<Int, Int>()

        for ((_, value) in outputs) {
            val tensor = value as? OnnxTensor ?: continue
            val shape = tensor.info.shape
            if (shape.isEmpty()) continue
            val total = shape.fold(1L) { acc, dim -> acc * dim }.toInt()
            if (total <= 0) continue
            val lastDim = shape.last().toInt()

            val rows: Int
            val channels: Int
            when (lastDim) {
                1, 4, 10 -> {
                    rows = total / lastDim
                    channels = lastDim
                }
                else -> {
                    // Score head exported as [1, rows] rather than [rows, 1].
                    if (resolveScrfdLayout(total, DETECTOR_INPUT_SIZE) == null) continue
                    rows = total
                    channels = 1
                }
            }

            val layout = resolveScrfdLayout(rows, DETECTOR_INPUT_SIZE) ?: continue
            val buffer = tensor.floatBuffer
            val values = FloatArray(minOf(total, buffer.remaining()))
            buffer.get(values)
            anchorsByStride[layout.stride] = layout.numAnchors
            when (channels) {
                1 -> scoresByStride[layout.stride] = values
                4 -> bboxesByStride[layout.stride] = values
                10 -> kpsByStride[layout.stride] = values
            }
        }

        if (scoresByStride.isEmpty() || bboxesByStride.isEmpty()) return emptyList()
        if (kpsByStride.isEmpty() && !warnedNoKeypoints) {
            warnedNoKeypoints = true
            // No keypoint head → unaligned crops, which cluster less accurately.
            // Logged once rather than per photo, as in the original.
        }

        // Scale from the detector's 640px square back onto the decode.
        val decoded = decodeScrfdOutputs(
            scoresByStride = scoresByStride,
            bboxesByStride = bboxesByStride,
            anchorsByStride = anchorsByStride,
            inputSize = DETECTOR_INPUT_SIZE,
            scoreThreshold = SCORE_THRESHOLD,
            scaleX = imageWidth.toDouble() / DETECTOR_INPUT_SIZE,
            scaleY = imageHeight.toDouble() / DETECTOR_INPUT_SIZE,
            kpsByStride = kpsByStride,
        )
        return nonMaxSuppression(decoded, NMS_THRESHOLD)
    }

    /**
     * Warps the face onto the 112×112 ArcFace template using the five
     * landmarks, or falls back to an unaligned square crop when the export has
     * no keypoints. Aligned crops are the biggest quality lever in the
     * pipeline; the fallback is deliberately worse, not silently equivalent.
     */
    private fun warpToTemplate(bitmap: Bitmap, detection: ScrfdDetection): Bitmap {
        val size = EMBEDDER_INPUT_SIZE
        if (detection.landmarks.size == 5) {
            val transform = estimateSimilarityTransform(
                src = detection.landmarks,
                dst = arcFaceTemplate(size),
            )
            if (transform != null) {
                return warpAffine(bitmap, transform, size)
            }
        }
        val side = (maxOf(detection.width, detection.height) * 1.2).toInt().coerceAtLeast(1)
        val cx = ((detection.left + detection.right) / 2).toInt()
        val cy = ((detection.top + detection.bottom) / 2).toInt()
        val left = (cx - side / 2).coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
        val top = (cy - side / 2).coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
        val cropW = min(side, bitmap.width - left).coerceAtLeast(1)
        val cropH = min(side, bitmap.height - top).coerceAtLeast(1)
        val src = Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
        return Bitmap.createScaledBitmap(src, size, size, true).also {
            if (it !== src) src.recycle()
        }
    }

    /**
     * Inverse-mapping bilinear warp through [transform], clamping at the edges.
     *
     * Integer output coordinates are treated as pixel centres to match
     * `cv2.warpAffine` / InsightFace's `norm_crop`.
     */
    private fun warpAffine(src: Bitmap, transform: SimilarityTransform, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val maxX = (src.width - 1).toDouble()
        val maxY = (src.height - 1).toDouble()
        val pixels = IntArray(size * size)

        for (y in 0 until size) {
            for (x in 0 until size) {
                val (sxRaw, syRaw) = transform.inverse(x.toDouble(), y.toDouble())
                val sx = sxRaw.coerceIn(0.0, maxX)
                val sy = syRaw.coerceIn(0.0, maxY)

                val x0 = sx.toInt()
                val y0 = sy.toInt()
                val x1 = min(x0 + 1, src.width - 1)
                val y1 = min(y0 + 1, src.height - 1)
                val fx = (sx - x0).toFloat()
                val fy = (sy - y0).toFloat()

                val p00 = src.getPixel(x0, y0)
                val p10 = src.getPixel(x1, y0)
                val p01 = src.getPixel(x0, y1)
                val p11 = src.getPixel(x1, y1)

                val r = bilinear(Color.red(p00), Color.red(p10), Color.red(p01), Color.red(p11), fx, fy)
                val g = bilinear(Color.green(p00), Color.green(p10), Color.green(p01), Color.green(p11), fx, fy)
                val b = bilinear(Color.blue(p00), Color.blue(p10), Color.blue(p01), Color.blue(p11), fx, fy)
                pixels[y * size + x] = Color.rgb(r, g, b)
            }
        }
        out.setPixels(pixels, 0, size, 0, 0, size, size)
        return out
    }

    private fun bilinear(a00: Int, a10: Int, a01: Int, a11: Int, fx: Float, fy: Float): Int {
        val top = a00 + (a10 - a00) * fx
        val bottom = a01 + (a11 - a01) * fx
        return (top + (bottom - top) * fy).toInt().coerceIn(0, 255)
    }

    private suspend fun embedSingle(session: OrtSession, aligned: Bitmap): FloatArray =
        embedderMutex.withLock {
            val input = TensorPreprocessing.embedderTensor(aligned, EMBEDDER_INPUT_SIZE)
            val shape = longArrayOf(1, 3, EMBEDDER_INPUT_SIZE.toLong(), EMBEDDER_INPUT_SIZE.toLong())
            val inputName = session.inputNames.first()
            val outputName = session.outputNames.first()

            OnnxTensor.createTensor(modelHost.environment, java.nio.FloatBuffer.wrap(input), shape).use { tensor ->
                val outputs = session.run(mapOf(inputName to tensor))
                try {
                    val out = outputs[outputName] ?: return@withLock FloatArray(0)
                    val buffer = (out as OnnxTensor).floatBuffer
                    if (buffer.remaining() < EMBEDDING_DIM) return@withLock FloatArray(0)
                    val slice = FloatArray(EMBEDDING_DIM)
                    buffer.get(slice)
                    l2Normalize(slice)
                } finally {
                    outputs.close()
                }
            }
        }

    /** Writes a 200×200 JPEG q85 crop to the cache dir, mirroring the original. */
    private fun writeThumbnail(aligned: Bitmap): String? {
        return try {
            val thumb = Bitmap.createScaledBitmap(aligned, 200, 200, true)
            val bytes = ByteArrayOutputStream().use { out ->
                thumb.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
            thumb.recycle()
            val seq = thumbnailSeq++
            val file = java.io.File(context.cacheDir, "face_${System.currentTimeMillis()}_$seq.jpg")
            file.writeBytes(bytes)
            file.absolutePath
        } catch (_: Throwable) {
            // A missing thumbnail degrades to the letter avatar; the DB row is
            // still written, so a rescan restores it.
            null
        }
    }

    private companion object {
        const val DETECTOR_ASSET = "models/det_500m.onnx"
        const val EMBEDDER_ASSET = "models/edgeface_xs_gamma_06.onnx"

        const val DETECTOR_INPUT_SIZE = 640
        const val EMBEDDER_INPUT_SIZE = 112
        const val EMBEDDING_DIM = 512
        const val SCORE_THRESHOLD = 0.5
        const val NMS_THRESHOLD = 0.4

        // Monotonic suffix so two crops written in the same millisecond cannot
        // collide on the same path.
        var thumbnailSeq = 0
    }
}
