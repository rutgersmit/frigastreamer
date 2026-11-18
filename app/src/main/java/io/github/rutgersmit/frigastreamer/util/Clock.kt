package io.github.rutgersmit.frigastreamer.util

/** Injectable clock, so backoff, watchdog and offline threshold use virtual time in tests. */
interface Clock {
    /** Monotonic time in milliseconds (also advances during standby), for timers and intervals. */
    fun elapsedMs(): Long

    /** Wall clock in milliseconds since epoch, for display only (e.g. time of the last frame). */
    fun wallMs(): Long
}

object SystemClockImpl : Clock {
    override fun elapsedMs(): Long = android.os.SystemClock.elapsedRealtime()
    override fun wallMs(): Long = System.currentTimeMillis()
}
