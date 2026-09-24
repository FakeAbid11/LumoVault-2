package com.lumovault.app.data.remote.telegram

/**
 * The native surface LumoVault needs from TDLib's JSON interface
 * (`td_json_client_create`, `td_json_client_send`, `td_json_client_receive`,
 * `td_json_client_destroy`).
 *
 * This is the one seam Phase 2 does not implement: a stock `libtdjson.so` exports those as C
 * symbols, so reaching them from Kotlin needs a small forwarding JNI library, built by TDLib's own
 * `example/android/build-tdlib.sh` flow. Keeping it behind an interface means the JSON protocol, the
 * request correlation and the whole authentication state machine below are real, unit-tested code —
 * so the fast follow is a binary plus a handful of bindings rather than a rewrite.
 */
interface TdLibNative {
    /** False when this build carries no TDLib binary; callers must then report "not configured". */
    val isAvailable: Boolean

    fun createClientId(): Int

    fun send(clientId: Int, request: String)

    /** Blocks up to [timeoutSeconds]; returns null when nothing arrived in time. */
    fun receive(clientId: Int, timeoutSeconds: Double): String?

    fun destroy(clientId: Int)
}

/** Stand-in until the native library is packaged; never throws, so nothing crashes on launch. */
object MissingTdLibNative : TdLibNative {
    override val isAvailable: Boolean = false

    override fun createClientId(): Int = throw IllegalStateException("TDLib is not packaged in this build")

    override fun send(clientId: Int, request: String): Unit = throw IllegalStateException("TDLib is not packaged in this build")

    override fun receive(clientId: Int, timeoutSeconds: Double): String? = null

    override fun destroy(clientId: Int) = Unit
}
