package com.rutgersmit.frigastreamer.domain

/**
 * Name pattern for substreams, e.g. `<camera>_sub` (default) or `{camera}-low`.
 * Both `<camera>` and `{camera}` are recognized as placeholder.
 */
class SubstreamPattern(raw: String) {
    val pattern: String = normalize(raw)
    private val prefix: String
    private val suffix: String

    init {
        val idx = pattern.indexOf(TOKEN)
        prefix = pattern.substring(0, idx)
        suffix = pattern.substring(idx + TOKEN.length)
    }

    fun subNameFor(camera: String): String = prefix + camera + suffix

    /** Returns the camera name if [streamName] matches the pattern, otherwise null. */
    fun mainNameFor(streamName: String): String? {
        if (prefix.isEmpty() && suffix.isEmpty()) return null
        if (!streamName.startsWith(prefix) || !streamName.endsWith(suffix)) return null
        if (streamName.length <= prefix.length + suffix.length) return null
        return streamName.substring(prefix.length, streamName.length - suffix.length)
    }

    companion object {
        const val TOKEN = "<camera>"
        const val DEFAULT = "<camera>_sub"

        fun normalize(raw: String): String {
            val r = raw.trim().replace("{camera}", TOKEN)
            return if (r.contains(TOKEN) && r != TOKEN) r else DEFAULT
        }
    }
}
