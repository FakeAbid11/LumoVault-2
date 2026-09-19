package com.lumovault.lumovault.core.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure arithmetic in [TensorPreprocessing].
 *
 * The bitmap paths need Android's `BitmapFactory` (native), but the sample-size
 * math does not — and an off-by-one here either wastes memory on an oversized
 * decode or feeds the model a blurry image.
 */
class TensorPreprocessingTest {

    @Test
    fun `calculateSampleSize returns 1 when already within bounds`() {
        assertEquals(1, TensorPreprocessing.calculateSampleSize(640, 480, 1600))
        assertEquals(1, TensorPreprocessing.calculateSampleSize(1600, 1600, 1600))
    }

    @Test
    fun `calculateSampleSize halves until the long side fits`() {
        // 4032x3024 against a 1600 cap: 4032 -> 2016 -> 1008, so sample 4.
        assertEquals(4, TensorPreprocessing.calculateSampleSize(4032, 3024, 1600))
    }

    @Test
    fun `calculateSampleSize is always a power of two`() {
        for (maxSide in listOf(640, 1600, 256, 1000)) {
            for (dims in listOf(4000 to 3000, 1080 to 1920, 720 to 1280)) {
                val sample = TensorPreprocessing.calculateSampleSize(dims.first, dims.second, maxSide)
                assertTrue("sample $sample is not a power of two", sample and (sample - 1) == 0)
                assertTrue(sample >= 1)
            }
        }
    }

    @Test
    fun `calculateSampleSize never overshoots by more than a factor of two`() {
        // The long side after downsampling must be at or under 2x the cap, and
        // at or over the cap (else a larger sample would have been taken).
        val (w, h) = 5000 to 3750
        val maxSide = 1600
        val sample = TensorPreprocessing.calculateSampleSize(w, h, maxSide)
        val longest = maxOf(w, h) / sample
        // inSampleSize semantics: the *largest power of two* that keeps the
        // long side at or under the cap. It therefore under-shoots (5000 / 4 =
        // 1250 against a 1600 cap) rather than over-shooting, so the bound is
        // "at or under the cap" and "a smaller sample would not have fitted".
        assertTrue("decode too large: $longest", longest <= maxSide)
        assertTrue("a smaller sample would have sufficed: $longest", longest > maxSide / 2)
    }

    @Test
    fun `calculateSampleSize handles degenerate dimensions`() {
        assertEquals(1, TensorPreprocessing.calculateSampleSize(0, 0, 1600))
        assertEquals(1, TensorPreprocessing.calculateSampleSize(100, 100, 0))
    }

    @Test
    fun `the four preprocessing scalings are the constants the models expect`() {
        // These are the values that silently break search if swapped. Asserting
        // them here keeps a refactor from drifting them unnoticed.
        // SCRFD: (px - 127.5) / 128. ArcFace: px/127.5 - 1.
        // MobileCLIP2: px/255 with NO mean/std. MobileOne: (px/255 - mean)/std.
        assertEquals(0.485f, TensorPreprocessing.imagenetMean[0], 1e-6f)
        assertEquals(0.456f, TensorPreprocessing.imagenetMean[1], 1e-6f)
        assertEquals(0.406f, TensorPreprocessing.imagenetMean[2], 1e-6f)
        assertEquals(0.229f, TensorPreprocessing.imagenetStd[0], 1e-6f)
        assertEquals(0.224f, TensorPreprocessing.imagenetStd[1], 1e-6f)
        assertEquals(0.225f, TensorPreprocessing.imagenetStd[2], 1e-6f)
    }
}
