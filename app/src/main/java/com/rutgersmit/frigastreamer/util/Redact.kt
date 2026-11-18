package com.rutgersmit.frigastreamer.util

/** Removes credentials from URLs and arbitrary text before it is logged or shown. */
object Redact {
    private val userInfo = Regex("""([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@\s]*@""")
    private val tokenParam = Regex("""(?i)((?:token|password|pass|pwd|auth|key)=)[^&\s;]+""")
    private val cookie = Regex("""(?i)(frigate_token=)[^;\s]+""")

    fun url(url: String?): String {
        if (url == null) return "null"
        return text(url)
    }

    fun text(s: String): String =
        s.replace(userInfo, "$1***@")
            .replace(tokenParam, "$1***")
            .replace(cookie, "$1***")
}
