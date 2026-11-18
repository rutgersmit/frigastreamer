package io.github.rutgersmit.frigastreamer.domain

/**
 * Exponential backoff: 1 s, 2 s, 4 s, 8 s, ... tot maximaal [maxMs].
 * [attempt] is 1-gebaseerd (eerste herpoging = 1).
 */
class Backoff(private val minMs: Long, private val maxMs: Long) {
    init {
        require(minMs > 0) { "minMs moet > 0 zijn" }
        require(maxMs >= minMs) { "maxMs moet >= minMs zijn" }
    }

    fun delayFor(attempt: Int): Long {
        if (attempt <= 1) return minMs
        val shift = (attempt - 1).coerceAtMost(30)
        val d = minMs.toDouble() * (1L shl shift).toDouble()
        return if (d >= maxMs) maxMs else d.toLong()
    }
}
