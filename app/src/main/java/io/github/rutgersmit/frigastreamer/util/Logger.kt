package io.github.rutgersmit.frigastreamer.util

/**
 * Structured logging. Every line has the form `event=<name> key=value ...`.
 * Tags: `FS.Cam.<camera>`, `FS.Manager`, `FS.Net`, `FS.Api`, `FS.Health`.
 *
 * NEVER log passwords, tokens or URLs with credentials: use [Redact.url].
 */
interface Logger {
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun e(tag: String, msg: String, t: Throwable? = null)

    companion object {
        fun cameraTag(cameraName: String): String =
            "FS.Cam." + cameraName.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(60)
    }
}

/** Implementation based on android.util.Log (usable via `adb logcat`). */
object AndroidLogger : Logger {
    override fun d(tag: String, msg: String) { android.util.Log.d(tag, msg) }
    override fun i(tag: String, msg: String) { android.util.Log.i(tag, msg) }
    override fun w(tag: String, msg: String, t: Throwable?) {
        android.util.Log.w(tag, msg + (t?.let { " error=" + Redact.text(it.toString()) } ?: ""))
    }
    override fun e(tag: String, msg: String, t: Throwable?) {
        android.util.Log.e(tag, msg + (t?.let { " error=" + Redact.text(it.toString()) } ?: ""))
    }
}
