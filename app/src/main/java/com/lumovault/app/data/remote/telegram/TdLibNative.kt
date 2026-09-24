package com.lumovault.app.data.remote.telegram

/**
 * The native surface LumoVault needs from TDLib's JSON interface — `td_create_client_id`,
 * `td_send` and `td_receive` at the commit pinned in `.github/workflows/build-tdlib.yml`.
 *
 * There is no destroy call on purpose: TDLib's integer client API has none, and a session ends when
 * Kotlin sends `close` or `logOut`. [receive] takes no client id because TDLib's `td_receive` does
 * not — one process-wide loop reads responses for every client, which is also why a second
 * [TdLibNative] instance must never be started.
 */
interface TdLibNative {
    /** False when this build carries no TDLib binary; callers must then report "not configured". */
    val isAvailable: Boolean

    fun createClientId(): Int

    fun send(clientId: Int, request: String)

    /** Blocks up to [timeoutSeconds]; returns null when nothing arrived in time. */
    fun receive(timeoutSeconds: Double): String?
}

/** Stand-in until the native library is packaged; never throws, so nothing crashes on launch. */
object MissingTdLibNative : TdLibNative {
    override val isAvailable: Boolean = false

    override fun createClientId(): Int = throw IllegalStateException("TDLib is not packaged in this build")

    override fun send(clientId: Int, request: String): Unit =
        throw IllegalStateException("TDLib is not packaged in this build")

    override fun receive(timeoutSeconds: Double): String? = null
}

/**
 * The real seam: `libtdjson.so` (TDLib itself) plus `liblumo_tdlib.so` (the four-line JNI forwarder
 * in `tdlib/lumo_tdlib_jni.c`). Both are produced by the manual `Build TDLib` workflow from pinned
 * upstream source, and dropped into `app/src/main/jniLibs/<abi>/` before a build.
 *
 * Loading is attempted once and the outcome remembered, because availability is a property of the
 * APK rather than of the moment: an APK without the binaries reports "not configured" everywhere or
 * nowhere, never per screen.
 */
class JniTdLibNative : TdLibNative {
    override val isAvailable: Boolean
        get() = LOADED

    private external fun nativeCreateClientId(): Int

    private external fun nativeSend(clientId: Int, request: String)

    private external fun nativeReceive(timeoutSeconds: Double): String?

    override fun createClientId(): Int = nativeCreateClientId()

    override fun send(clientId: Int, request: String): Unit = nativeSend(clientId, request)

    override fun receive(timeoutSeconds: Double): String? = nativeReceive(timeoutSeconds)

    companion object {
        /**
         * Loaded once per process, on the first instance created: `System.loadLibrary` is idempotent
         * but repeating it per seam object would put file I/O on whatever thread builds a repository.
         */
        private val LOADED: Boolean by lazy {
            runCatching {
                // TDLib first: the forwarder has an unresolved dependency on it, so link order matters.
                System.loadLibrary("tdjson")
                System.loadLibrary("lumo_tdlib")
            }.isSuccess
        }
    }
}
