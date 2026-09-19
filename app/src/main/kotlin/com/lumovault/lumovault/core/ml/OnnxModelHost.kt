package com.lumovault.lumovault.core.ml

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the single ONNX [OrtEnvironment] and loads model sessions from assets.
 *
 * The five bundled models (~151 MB) load the same way, so the per-service
 * boilerplate collapses here. Every service then only knows its asset path and
 * its tensor shapes.
 *
 * Native library caveat: `OnnxRuntime.init()` loads `libonnxruntime.so`, so
 * *nothing* in this class can be exercised by a JVM unit test — it needs an
 * instrumented test or a real device. That is why every pure-algorithm piece
 * of the pipelines lives in ONNX-free files that ARE unit-testable, and why a
 * golden-vector instrumented test per model is the only real guard against a
 * silently wrong preprocessing constant.
 */
@Singleton
class OnnxModelHost @Inject constructor(
    private val context: Context,
) {

    /**
     * The shared environment. Lazy so `libonnxruntime.so` only loads when a
     * model is actually needed — the gallery works without ML, and a
     * low-end device that never opens People should not pay the load.
     */
    val environment: OrtEnvironment by lazy {
        OrtEnvironment.getEnvironment()
    }

    /**
     * Loads [assetPath] as an ONNX session.
     *
     * Thread options mirror the Flutter port's `OrtSessionOptions(
     * intraOpNumThreads: 2, interOpNumThreads: 2)`: two threads per model keeps
     * four concurrent pipelines (detector, embedder, CLIP vision, classifier)
     * from oversubscribing a big-little CPU.
     */
    fun loadSession(assetPath: String, intraOpThreads: Int = 2, interOpThreads: Int = 2): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(intraOpThreads)
            setInterOpNumThreads(interOpThreads)
        }
        return context.assets.open(assetPath).use { input ->
            environment.createSession(input.readBytes(), options)
        }
    }
}
