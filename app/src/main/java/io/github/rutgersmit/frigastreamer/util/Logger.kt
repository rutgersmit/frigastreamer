package io.github.rutgersmit.frigastreamer.util

/**
 * Gestructureerde logging. Elke regel heeft de vorm `event=<naam> key=value ...`.
 * Tags: `FS.Cam.<camera>`, `FS.Manager`, `FS.Net`, `FS.Api`, `FS.Health`.
 *
 * Log NOOIT wachtwoorden, tokens of URL's met credentials: gebruik [Redact.url].
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

/** Implementatie op basis van android.util.Log (bruikbaar via `adb logcat`). */
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
