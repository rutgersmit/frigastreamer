package nl.frigastreamer.util

/** Injecteerbare klok, zodat backoff, watchdog en offline-drempel in tests virtuele tijd gebruiken. */
interface Clock {
    /** Monotone tijd in milliseconden (verstrijkt ook in standby), voor timers en intervallen. */
    fun elapsedMs(): Long

    /** Wandklok in milliseconden sinds epoch, alleen voor weergave (bv. tijdstip laatste frame). */
    fun wallMs(): Long
}

object SystemClockImpl : Clock {
    override fun elapsedMs(): Long = android.os.SystemClock.elapsedRealtime()
    override fun wallMs(): Long = System.currentTimeMillis()
}
