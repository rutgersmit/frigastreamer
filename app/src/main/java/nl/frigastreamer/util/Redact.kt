package nl.frigastreamer.util

/** Verwijdert credentials uit URL's en willekeurige tekst voordat die gelogd of getoond wordt. */
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
