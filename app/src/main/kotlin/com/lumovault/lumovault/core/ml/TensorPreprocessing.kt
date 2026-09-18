package com.lumovault.lumovault.core.ml

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.InputStream

/**
 * Image decode and tensor-preprocessing helpers shared by the ML services.
 *
 * Ported from the preprocessing routines scattered across the Dart services.
 * **These constants are the whole ballgame** — each model expects a different
 * pixel scaling, and mixing them is the classic silent failure that returns
 * plausible-looking garbage instead of crashing:
 *
 * | Model            | Scaling                        |
 * |------------------|--------------------------------|
 * | SCRFD detector   | `(px − 127.5) / 128`           |
 * | ArcFace embedder | `px / 127.5 − 1`               |
 * | MobileOne class. | `(px/255 − mean) / std`        |
 * | MobileCLIP2      | `px / 255`, no mean/std        |
 *
 * All four models are **NCHW and RGB**; the channel loop is outer so the
 * flattened tensor comes out channel-major.
 */
object TensorPreprocessing {

    /** ImageNet mean/std, canonical torchvision order. */
    val imagenetMean = floatArrayOf(0.485f, 0.456f, 0.406f)
    val imagenetStd = floatArrayOf(0.229f, 0.224f, 0.225f)

    /**
     * Decodes a JPEG/PNG stream into a [Bitmap].
     *
     * [maxSide] bounds the decode: the face pipeline deliberately avoids a
     * full-res decode (detection only needs 640px of input) and letting a
     * 4032×3024 photo materialize costs both memory and time. `inSampleSize`
     * is a power-of-two downscale computed from the source dimensions, which
     * is the cheapest way to hit that bound.
     *
     * The stream is buffered to a byte array first because bounds-pass and
     * decode-pass both read it — a stream only decodes once.
     */
    fun decodeDownsampled(stream: InputStream, maxSide: Int): Bitmap? {
        val bytes = stream.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /**
     * Largest power of two that keeps the longer side at or under [maxSide].
     *
     * The face detector squares and stretches its input to 640 anyway, so
     * overshooting the bound wastes memory for no accuracy gain.
     */
    fun calculateSampleSize(width: Int, height: Int, maxSide: Int): Int {
        if (width <= 0 || height <= 0 || maxSide <= 0) return 1
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / sample > maxSide) {
            sample *= 2
        }
        return sample
    }

    /**
     * Stretches [bitmap] to [size]×[size] and builds an NCHW RGB tensor using
     * [normalize] to map each 0-255 channel value into model space.
     *
     * The detector and the ArcFace embedder both want a square input, so the
     * aspect ratio is deliberately distorted rather than letterboxed.
     */
    fun toNchwTensor(
        bitmap: Bitmap,
        size: Int,
        normalize: (channel: Int, pixel255: Float) -> Float,
    ): FloatArray {
        val scaled = if (bitmap.width == size && bitmap.height == size) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, size, size, true)
        }
        val pixels = IntArray(size * size)
        scaled.getPixels(pixels, 0, size, 0, 0, size, size)

        val tensor = FloatArray(3 * size * size)
        val plane = size * size
        for (i in pixels.indices) {
            tensor[i] = normalize(0, Color.red(pixels[i]).toFloat())
            tensor[plane + i] = normalize(1, Color.green(pixels[i]).toFloat())
            tensor[2 * plane + i] = normalize(2, Color.blue(pixels[i]).toFloat())
        }
        if (scaled !== bitmap) scaled.recycle()
        return tensor
    }

    /**
     * SCRFD detector input: 640² NCHW RGB, `(px − 127.5) / 128`.
     */
    fun detectorTensor(bitmap: Bitmap, size: Int = 640): FloatArray =
        toNchwTensor(bitmap, size) { _, px -> (px - 127.5f) / 128f }

    /**
     * ArcFace embedder input: 112² NCHW RGB, `px / 127.5 − 1`.
     */
    fun embedderTensor(bitmap: Bitmap, size: Int = 112): FloatArray =
        toNchwTensor(bitmap, size) { _, px -> px / 127.5f - 1f }

    /**
     * MobileCLIP2 vision input: 256² NCHW RGB, pixels scaled to [0, 1] with
     * **no** mean/std shift.
     *
     * The comment in the original is load-bearing: "MobileCLIP2-S0 expects
     * pixels scaled to [0, 1] … Feeding raw 0-255 produced garbage vectors."
     * Applying the standard CLIP mean/std here is the exact mistake that makes
     * semantic search return nothing but noise.
     */
    fun clipVisionTensor(bitmap: Bitmap, size: Int = 256): FloatArray =
        toNchwTensor(bitmap, size) { _, px -> px / 255f }

    /**
     * MobileOne classifier input: 224² NCHW RGB, `(px/255 − mean) / std`.
     */
    fun classifierTensor(bitmap: Bitmap, size: Int = 224): FloatArray =
        toNchwTensor(bitmap, size) { channel, px ->
            (px / 255f - imagenetMean[channel]) / imagenetStd[channel]
        }
}
