package com.lumovault.lumovault.features.people.data.service

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure-algorithm pieces of the face pipeline: SCRFD anchor decoding, ArcFace
 * alignment geometry, NMS and vector normalization.
 *
 * Kept ONNX-free deliberately. Inference loads a native library and cannot run
 * in a JVM unit test, but every constant and every branch in here *can* — and
 * a wrong preprocessing constant is exactly the failure that silently returns
 * garbage instead of crashing, so this is where the test coverage belongs.
 *
 * Ported from lib/features/people/data/services/face_detection_service.dart.
 */

// ---------------------------------------------------------------------------
// Alignment
// ---------------------------------------------------------------------------

/**
 * InsightFace's `arcface_dst` template: where the five landmarks must land in
 * a 112×112 crop, ordered left eye, right eye, nose, left mouth, right mouth.
 *
 * ArcFace was trained on faces warped onto this template, so warping to it
 * removes the in-plane rotation and scale variation that would otherwise
 * spread one person's embeddings apart and split them across clusters. It is
 * the single biggest quality lever in the pipeline.
 */
val arcFaceTemplate112 = listOf(
    38.2946 to 51.6963,
    73.5318 to 51.5014,
    56.0252 to 71.7366,
    41.5493 to 92.3655,
    70.7299 to 92.2041,
)

/** The template scaled to a [size]×[size] crop. */
fun arcFaceTemplate(size: Int): List<Pair<Double, Double>> {
    if (size == 112) return arcFaceTemplate112
    val s = size / 112.0
    return arcFaceTemplate112.map { (x, y) -> x * s to y * s }
}

/**
 * A 2-D similarity transform — uniform scale, rotation and translation, no
 * shear: `(x, y) → (a·x − b·y + tx, b·x + a·y + ty)`.
 */
data class SimilarityTransform(
    val a: Double,
    val b: Double,
    val tx: Double,
    val ty: Double,
) {
    /** Uniform scale factor applied by the transform. */
    val scale: Double get() = sqrt(a * a + b * b)

    /** Maps a point forward, from source into destination space. */
    fun apply(x: Double, y: Double): Pair<Double, Double> =
        (a * x - b * y + tx) to (b * x + a * y + ty)

    /**
     * Maps a destination point back into source space.
     *
     * This is the direction a warp actually needs: for each output pixel, find
     * where to sample the input.
     */
    fun inverse(x: Double, y: Double): Pair<Double, Double> {
        val det = a * a + b * b
        val px = x - tx
        val py = y - ty
        return ((a * px + b * py) / det) to ((-b * px + a * py) / det)
    }
}

/**
 * Least-squares similarity transform mapping [src] onto [dst].
 *
 * The closed form of Umeyama's algorithm for the 2-D scale-plus-rotation case —
 * the same fit `skimage.transform.SimilarityTransform` (and therefore
 * InsightFace's `norm_crop`) performs, without an SVD: the model is linear in
 * `(a, b, tx, ty)`, so the normal equations solve directly. Returns null if the
 * points are degenerate (fewer than two, or all coincident), which no real
 * 5-point face landmark set is.
 */
fun estimateSimilarityTransform(
    src: List<Pair<Double, Double>>,
    dst: List<Pair<Double, Double>>,
): SimilarityTransform? {
    val n = min(src.size, dst.size)
    if (n < 2) return null

    var meanSx = 0.0
    var meanSy = 0.0
    var meanDx = 0.0
    var meanDy = 0.0
    for (i in 0 until n) {
        meanSx += src[i].first
        meanSy += src[i].second
        meanDx += dst[i].first
        meanDy += dst[i].second
    }
    meanSx /= n
    meanSy /= n
    meanDx /= n
    meanDy /= n

    var numA = 0.0
    var numB = 0.0
    var den = 0.0
    for (i in 0 until n) {
        val sx = src[i].first - meanSx
        val sy = src[i].second - meanSy
        val dx = dst[i].first - meanDx
        val dy = dst[i].second - meanDy
        numA += sx * dx + sy * dy
        numB += sx * dy - sy * dx
        den += sx * sx + sy * sy
    }
    if (den <= 0) return null

    val a = numA / den
    val b = numB / den
    if (a == 0.0 && b == 0.0) return null

    return SimilarityTransform(
        a = a,
        b = b,
        tx = meanDx - (a * meanSx - b * meanSy),
        ty = meanDy - (b * meanSx + a * meanSy),
    )
}

/**
 * A detected face box plus its landmarks and score, in source-image coordinates.
 */
data class ScrfdDetection(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val score: Double,
    /** Five landmark pairs, or empty when the export carries no keypoint head. */
    val landmarks: List<Pair<Double, Double>> = emptyList(),
) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
}

/**
 * Which FPN level and anchor count produced a tensor with [rows] anchor rows.
 */
data class ScrfdLayout(val stride: Int, val numAnchors: Int)

/** FPN strides of the bundled `det_500m.onnx` export. */
val scrfdStrides = intArrayOf(8, 16, 32)

/**
 * Resolves the FPN level for an output tensor with [rows] anchor rows.
 *
 * For a 640×640 input the row counts are unambiguous: 12800/3200/800 for two
 * anchors per cell at strides 8/16/32, and 6400/1600/400 for one.
 */
fun resolveScrfdLayout(rows: Int, inputSize: Int): ScrfdLayout? {
    if (rows <= 0 || inputSize <= 0) return null
    for (stride in scrfdStrides) {
        val grid = inputSize / stride
        val cells = grid * grid
        if (cells == 0) continue
        for (numAnchors in intArrayOf(2, 1)) {
            if (rows == cells * numAnchors) {
                return ScrfdLayout(stride, numAnchors)
            }
        }
    }
    return null
}

/**
 * Decodes SCRFD score/box-distance/keypoint tensors into detections in
 * *source image* coordinates, keeping anything at or above [scoreThreshold].
 *
 * Anchor rows are ordered (row, column, anchor) and anchor centres sit at
 * `(x * stride, y * stride)` in the detector's [inputSize] square space;
 * [scaleX]/[scaleY] map that space back onto the original image.
 *
 * Keypoints are signed offsets from the anchor centre, so they are *added* in
 * both axes — unlike the box's left/top distances, which subtract. Getting this
 * backwards silently misaligns every face.
 */
fun decodeScrfdOutputs(
    scoresByStride: Map<Int, FloatArray>,
    bboxesByStride: Map<Int, FloatArray>,
    anchorsByStride: Map<Int, Int>,
    inputSize: Int,
    scoreThreshold: Double,
    scaleX: Double,
    scaleY: Double,
    kpsByStride: Map<Int, FloatArray> = emptyMap(),
): List<ScrfdDetection> {
    val results = mutableListOf<ScrfdDetection>()

    for (stride in scrfdStrides) {
        val scores = scoresByStride[stride] ?: continue
        val boxes = bboxesByStride[stride] ?: continue
        val kps = kpsByStride[stride]
        val numAnchors = anchorsByStride[stride] ?: 1
        if (numAnchors <= 0) continue
        val grid = inputSize / stride
        if (grid <= 0) continue

        for (i in scores.indices) {
            if (scores[i] < scoreThreshold) continue
            if (i * 4 + 3 >= boxes.size) break

            val cell = i / numAnchors
            val anchorX = (cell % grid) * stride
            val anchorY = (cell / grid) * stride

            val left = anchorX - boxes[i * 4] * stride
            val top = anchorY - boxes[i * 4 + 1] * stride
            val right = anchorX + boxes[i * 4 + 2] * stride
            val bottom = anchorY + boxes[i * 4 + 3] * stride
            if (right <= left || bottom <= top) continue

            val landmarks = if (kps != null && i * 10 + 9 < kps.size) {
                (0 until 5).map { n ->
                    ((anchorX + kps[i * 10 + n * 2] * stride) * scaleX) to
                        ((anchorY + kps[i * 10 + n * 2 + 1] * stride) * scaleY)
                }
            } else {
                emptyList()
            }

            results.add(
                ScrfdDetection(
                    left = left * scaleX,
                    top = top * scaleY,
                    right = right * scaleX,
                    bottom = bottom * scaleY,
                    score = scores[i].toDouble(),
                    landmarks = landmarks,
                ),
            )
        }
    }

    return results
}

/**
 * Greedy non-maximum suppression: keeps the highest-scoring box and drops
 * everything overlapping it by more than [threshold] (IoU).
 */
fun nonMaxSuppression(
    detections: List<ScrfdDetection>,
    threshold: Double,
): List<ScrfdDetection> {
    if (detections.isEmpty()) return emptyList()

    val sorted = detections.sortedByDescending { it.score }
    val suppressed = BooleanArray(sorted.size)

    val kept = mutableListOf<ScrfdDetection>()
    for (i in sorted.indices) {
        if (suppressed[i]) continue
        kept.add(sorted[i])
        for (j in (i + 1) until sorted.size) {
            if (suppressed[j]) continue
            if (iou(sorted[i], sorted[j]) > threshold) {
                suppressed[j] = true
            }
        }
    }
    return kept
}

private fun iou(a: ScrfdDetection, b: ScrfdDetection): Double {
    val interLeft = max(a.left, b.left)
    val interTop = max(a.top, b.top)
    val interRight = min(a.right, b.right)
    val interBottom = min(a.bottom, b.bottom)

    val interArea = max(0.0, interRight - interLeft) * max(0.0, interBottom - interTop)
    val aArea = a.width * a.height
    val bArea = b.width * b.height
    val unionArea = aArea + bArea - interArea

    return if (unionArea > 0) interArea / unionArea else 0.0
}

/**
 * L2-normalizes a vector in place-safe fashion; a zero vector is returned
 * untouched (dividing by zero would produce NaN embeddings that still compare
 * as "similar" to each other).
 */
fun l2Normalize(vector: FloatArray): FloatArray {
    var sum = 0.0
    for (v in vector) sum += (v * v).toDouble()
    val norm = sqrt(sum)
    if (norm == 0.0) return vector
    return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
}

/** Cosine similarity of two equal-length vectors; 0 for mismatched lengths. */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
    if (a.size != b.size || a.isEmpty()) return 0.0
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
    }
    val denom = sqrt(normA) * sqrt(normB)
    return if (denom > 0) dot / denom else 0.0
}
