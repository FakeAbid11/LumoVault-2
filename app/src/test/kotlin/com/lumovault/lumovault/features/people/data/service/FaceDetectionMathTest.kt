package com.lumovault.lumovault.features.people.data.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests for the pure-algorithm face pipeline.
 *
 * Inference itself loads `libonnxruntime.so` and cannot run on the JVM, but
 * every constant and branch in [FaceDetectionMath] can — and a wrong anchor
 * stride or a mis-signed landmark offset is exactly the failure that returns
 * plausible-looking garbage instead of crashing.
 */
class FaceDetectionMathTest {

    @Test
    fun `arcFaceTemplate112 has the five canonical landmark positions`() {
        val template = arcFaceTemplate112
        assertEquals(5, template.size)
        // InsightFace arcface_dst — a drift here misaligns every face.
        assertEquals(38.2946, template[0].first, 1e-4)
        assertEquals(51.6963, template[0].second, 1e-4)
        assertEquals(73.5318, template[1].first, 1e-4)
        assertEquals(51.5014, template[1].second, 1e-4)
        assertEquals(56.0252, template[2].first, 1e-4)
        assertEquals(71.7366, template[2].second, 1e-4)
        assertEquals(41.5493, template[3].first, 1e-4)
        assertEquals(92.3655, template[3].second, 1e-4)
        assertEquals(70.7299, template[4].first, 1e-4)
        assertEquals(92.2041, template[4].second, 1e-4)
    }

    @Test
    fun `arcFaceTemplate scales the canonical template proportionally`() {
        val scaled = arcFaceTemplate(224)
        assertEquals(5, scaled.size)
        val factor = 224.0 / 112.0
        arcFaceTemplate112.forEachIndexed { i, (x, y) ->
            assertEquals(x * factor, scaled[i].first, 1e-4)
            assertEquals(y * factor, scaled[i].second, 1e-4)
        }
    }

    @Test
    fun `estimateSimilarityTransform maps the source points onto the destination`() {
        // A pure translation by (10, 20).
        val src = listOf(0.0 to 0.0, 5.0 to 5.0, 10.0 to 2.0)
        val dst = src.map { (x, y) -> (x + 10.0) to (y + 20.0) }

        val transform = estimateSimilarityTransform(src, dst)
        assertNotNull(transform)

        transform!!
        assertEquals(1.0, transform.scale, 1e-9)
        for (i in src.indices) {
            val (mx, my) = transform.apply(src[i].first, src[i].second)
            assertEquals(dst[i].first, mx, 1e-9)
            assertEquals(dst[i].second, my, 1e-9)
        }
    }

    @Test
    fun `estimateSimilarityTransform recovers a uniform scale and rotation`() {
        // Scale by 2 and rotate 90 degrees: (x, y) -> (-2y, 2x).
        val src = listOf(1.0 to 0.0, 0.0 to 1.0, 3.0 to 4.0)
        val dst = src.map { (x, y) -> (-2.0 * y) to (2.0 * x) }

        val transform = estimateSimilarityTransform(src, dst)
        assertNotNull(transform)
        transform!!

        assertEquals(2.0, transform.scale, 1e-9)
        for (i in src.indices) {
            val (mx, my) = transform.apply(src[i].first, src[i].second)
            assertEquals(dst[i].first, mx, 1e-9)
            assertEquals(dst[i].second, my, 1e-9)
        }
    }

    @Test
    fun `estimateSimilarityTransform inverse round-trips through apply`() {
        val src = listOf(1.0 to 2.0, 3.0 to 5.0, 6.0 to 1.0)
        val dst = listOf(4.0 to 7.0, 10.0 to 3.0, 2.0 to 9.0)

        val transform = estimateSimilarityTransform(src, dst)
        assertNotNull(transform)
        transform!!

        for (i in src.indices) {
            val (mx, my) = transform.apply(src[i].first, src[i].second)
            val (bx, by) = transform.inverse(mx, my)
            assertEquals(src[i].first, bx, 1e-9)
            assertEquals(src[i].second, by, 1e-9)
        }
    }

    @Test
    fun `estimateSimilarityTransform rejects degenerate point sets`() {
        assertNull(estimateSimilarityTransform(listOf(0.0 to 0.0), listOf(1.0 to 1.0)))
        // All coincident: denominator is zero.
        val coincident = listOf(1.0 to 1.0, 1.0 to 1.0, 1.0 to 1.0)
        assertNull(estimateSimilarityTransform(coincident, coincident))
    }

    @Test
    fun `resolveScrfdLayout maps 640-input row counts to strides and anchor counts`() {
        // Two anchors per cell at strides 8/16/32 on a 640 square.
        assertEquals(ScrfdLayout(8, 2), resolveScrfdLayout(80 * 80 * 2, 640))
        assertEquals(ScrfdLayout(16, 2), resolveScrfdLayout(40 * 40 * 2, 640))
        assertEquals(ScrfdLayout(32, 2), resolveScrfdLayout(20 * 20 * 2, 640))
        // One anchor per cell.
        assertEquals(ScrfdLayout(8, 1), resolveScrfdLayout(80 * 80, 640))
        assertEquals(ScrfdLayout(16, 1), resolveScrfdLayout(40 * 40, 640))
        assertEquals(ScrfdLayout(32, 1), resolveScrfdLayout(20 * 20, 640))
    }

    @Test
    fun `resolveScrfdLayout rejects row counts that match no level`() {
        assertNull(resolveScrfdLayout(12345, 640))
        assertNull(resolveScrfdLayout(0, 640))
    }

    @Test
    fun `decodeScrfdOutputs converts stride-unit distances into source boxes`() {
        // A 640-input detector where one cell at stride 8, anchor 0, reports
        // 1.0 distance on every side. The box is the 8x8 cell centred on the
        // anchor, in detector space (scale 1.0).
        val stride = 8
        val grid = 640 / stride
        val rows = grid * grid * 2

        val scores = FloatArray(rows)
        val boxes = FloatArray(rows * 4)
        val cell = 5 * grid + 3 // row 5, column 3
        val anchorIndex = cell * 2
        scores[anchorIndex] = 0.9f
        boxes[anchorIndex * 4 + 0] = 1.0f
        boxes[anchorIndex * 4 + 1] = 1.0f
        boxes[anchorIndex * 4 + 2] = 1.0f
        boxes[anchorIndex * 4 + 3] = 1.0f

        val detections = decodeScrfdOutputs(
            scoresByStride = mapOf(stride to scores),
            bboxesByStride = mapOf(stride to boxes),
            anchorsByStride = mapOf(stride to 2),
            inputSize = 640,
            scoreThreshold = 0.5,
            scaleX = 1.0,
            scaleY = 1.0,
        )

        assertEquals(1, detections.size)
        val d = detections.first()
        assertEquals(0.9, d.score, 1e-6)
        val anchorX = 3 * stride.toDouble()
        val anchorY = 5 * stride.toDouble()
        assertEquals(anchorX - stride, d.left, 1e-6)
        assertEquals(anchorY - stride, d.top, 1e-6)
        assertEquals(anchorX + stride, d.right, 1e-6)
        assertEquals(anchorY + stride, d.bottom, 1e-6)
    }

    @Test
    fun `decodeScrfdOutputs scales boxes into source-image coordinates`() {
        val stride = 32
        val grid = 640 / stride
        val rows = grid * grid

        val scores = FloatArray(rows).also { it[0] = 0.8f }
        val boxes = FloatArray(rows * 4).also {
            it[0] = 0.5f; it[1] = 0.5f; it[2] = 0.5f; it[3] = 0.5f
        }

        // Source image is 1280x960; the detector space is 640x640.
        val detections = decodeScrfdOutputs(
            scoresByStride = mapOf(stride to scores),
            bboxesByStride = mapOf(stride to boxes),
            anchorsByStride = mapOf(stride to 1),
            inputSize = 640,
            scoreThreshold = 0.5,
            scaleX = 1280.0 / 640.0,
            scaleY = 960.0 / 640.0,
        )

        assertEquals(1, detections.size)
        val d = detections.first()
        // Anchor at cell (0,0) = (0,0) in detector space; box +-0.5*32 = 16.
        assertEquals(16.0 * (1280.0 / 640.0), d.left, 1e-6)
        assertEquals(16.0 * (960.0 / 640.0), d.top, 1e-6)
    }

    @Test
    fun `decodeScrfdOutputs drops sub-threshold detections`() {
        val scores = floatArrayOf(0.9f, 0.3f, 0.6f)
        val boxes = FloatArray(12)

        val detections = decodeScrfdOutputs(
            scoresByStride = mapOf(8 to scores),
            bboxesByStride = mapOf(8 to boxes),
            anchorsByStride = mapOf(8 to 1),
            inputSize = 640,
            scoreThreshold = 0.5,
            scaleX = 1.0,
            scaleY = 1.0,
        )

        // 0.3 is below the floor; the other two survive.
        assertEquals(2, detections.size)
        assertTrue(detections.all { it.score >= 0.5 })
    }

    @Test
    fun `decodeScrfdOutputs decodes landmarks as offsets ADDED to the anchor`() {
        val stride = 16
        val grid = 640 / stride
        val rows = grid * grid

        val scores = FloatArray(rows).also { it[0] = 0.9f }
        val boxes = FloatArray(rows * 4)
        // Five landmark pairs, each (+1.0, +2.0) in stride units.
        val kps = FloatArray(rows * 10).also {
            for (n in 0 until 5) {
                it[n * 2] = 1.0f
                it[n * 2 + 1] = 2.0f
            }
        }

        val detections = decodeScrfdOutputs(
            scoresByStride = mapOf(stride to scores),
            bboxesByStride = mapOf(stride to boxes),
            anchorsByStride = mapOf(stride to 1),
            inputSize = 640,
            scoreThreshold = 0.5,
            scaleX = 2.0,
            scaleY = 3.0,
            kpsByStride = mapOf(stride to kps),
        )

        assertEquals(1, detections.size)
        val landmarks = detections.first().landmarks
        assertEquals(5, landmarks.size)
        // Anchor centre is (0,0) in detector space; landmarks land at
        // (1*16, 2*16) scaled by (2, 3).
        landmarks.forEach { (x, y) ->
            assertEquals(1.0 * stride * 2.0, x, 1e-6)
            assertEquals(2.0 * stride * 3.0, y, 1e-6)
        }
    }

    @Test
    fun `decodeScrfdOutputs yields empty landmarks when the keypoint head is absent`() {
        val scores = floatArrayOf(0.9f)
        val boxes = FloatArray(4)

        val detections = decodeScrfdOutputs(
            scoresByStride = mapOf(8 to scores),
            bboxesByStride = mapOf(8 to boxes),
            anchorsByStride = mapOf(8 to 1),
            inputSize = 640,
            scoreThreshold = 0.5,
            scaleX = 1.0,
            scaleY = 1.0,
        )

        assertEquals(1, detections.size)
        assertTrue(detections.first().landmarks.isEmpty())
    }

    @Test
    fun `decodeScrfdOutputs skips degenerate boxes`() {
        // Zero-size box: left == right after decoding.
        val scores = floatArrayOf(0.9f)
        val boxes = floatArrayOf(0f, 0f, -1f, 0f)

        val detections = decodeScrfdOutputs(
            scoresByStride = mapOf(8 to scores),
            bboxesByStride = mapOf(8 to boxes),
            anchorsByStride = mapOf(8 to 1),
            inputSize = 640,
            scoreThreshold = 0.5,
            scaleX = 1.0,
            scaleY = 1.0,
        )

        assertTrue(detections.isEmpty())
    }

    @Test
    fun `nonMaxSuppression keeps non-overlapping boxes and drops the weaker duplicate`() {
        val detections = listOf(
            ScrfdDetection(0.0, 0.0, 10.0, 10.0, score = 0.9),
            ScrfdDetection(1.0, 1.0, 11.0, 11.0, score = 0.6), // overlaps heavily
            ScrfdDetection(100.0, 100.0, 110.0, 110.0, score = 0.5),
        )

        val kept = nonMaxSuppression(detections, threshold = 0.3)

        assertEquals(2, kept.size)
        assertEquals(0.9, kept[0].score, 1e-9)
        // The far box survives; the near-duplicate is suppressed.
        assertTrue(kept.any { it.left == 100.0 })
        assertFalse(kept.any { it.score == 0.6 })
    }

    @Test
    fun `nonMaxSuppression handles empty input`() {
        assertTrue(nonMaxSuppression(emptyList(), 0.4).isEmpty())
    }

    @Test
    fun `l2Normalize produces a unit vector`() {
        val vector = floatArrayOf(3f, 4f)
        val normalized = l2Normalize(vector)

        assertEquals(2, normalized.size)
        assertEquals(0.6f, normalized[0], 1e-6f)
        assertEquals(0.8f, normalized[1], 1e-6f)
        var sum = 0.0
        for (v in normalized) sum += v * v
        assertEquals(1.0, sum, 1e-9)
    }

    @Test
    fun `l2Normalize leaves a zero vector untouched rather than producing NaN`() {
        val zero = floatArrayOf(0f, 0f, 0f)
        val normalized = l2Normalize(zero)

        for (v in normalized) {
            assertFalse(v.isNaN())
            assertEquals(0.0, abs(v.toDouble()), 1e-9)
        }
    }

    @Test
    fun `cosineSimilarity of identical unit vectors is one`() {
        val a = floatArrayOf(1f, 0f, 0f)
        assertEquals(1.0, cosineSimilarity(a, a), 1e-9)
    }

    @Test
    fun `cosineSimilarity of orthogonal vectors is zero`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        assertEquals(0.0, cosineSimilarity(a, b), 1e-9)
    }

    @Test
    fun `cosineSimilarity refuses mismatched dimensions`() {
        // A mismatch is INCOMPARABLE, not dissimilar — returning 0 rather than
        // scoring a shared prefix is what stops two vector spaces merging people.
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(1f, 0f)
        assertEquals(0.0, cosineSimilarity(a, b), 1e-9)
    }
}
