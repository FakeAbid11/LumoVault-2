package com.lumovault.lumovault.core.logging

import android.util.Log

/**
 * Gated verbose app logger.
 *
 * Ported from Flutter `lib/core/logging/app_logger.dart`.
 *
 * Wraps [Log] so that tagged logs (starting with `[`) are suppressed when
 * [verboseEnabled] is false (the default). Untagged framework output is
 * always forwarded.
 *
 * Toggle [verboseEnabled] in sync with `AppSettings.debugMode` from the
 * settings layer.
 */
object AppLogger {

    private var originalPrintln: ((Int, String, String) -> Unit)? = null
    private var installed = false

    /** When false, tagged logs are suppressed. */
    @Volatile
    var verboseEnabled: Boolean = false

    /**
     * Install the logger gate. Idempotent -- safe to call from both main()
     * and background entry points.
     */
    fun install() {
        if (installed) return
        installed = true
        // Android Log is already the backend; we just gate via verboseEnabled.
    }

    /** Log a debug message. Tagged logs are suppressed when !verboseEnabled. */
    fun d(tag: String, message: String) {
        if (!verboseEnabled && tag.startsWith("[")) return
        Log.d(tag, message)
    }

    /** Log an info message. */
    fun i(tag: String, message: String) {
        if (!verboseEnabled && tag.startsWith("[")) return
        Log.i(tag, message)
    }

    /** Log a warning. */
    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    /** Log an error. */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }

    /** For testing: reset the gate and state. */
    fun resetForTesting() {
        verboseEnabled = false
        installed = false
    }
}
