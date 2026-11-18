package nl.frigastreamer.domain

enum class StreamKind { MAIN, SUB }

enum class CameraSource { AUTO, MANUAL }

enum class PlayerRole { ACTIVE, PRELOAD }

data class RtspCredentials(val username: String, val password: String) {
    override fun toString(): String = "RtspCredentials(username=***, password=***)"
}

/**
 * Een te tonen camera met volledig opgeloste stream-URL's (zonder credentials voor
 * automatisch gevonden camera's; die worden pas in de spelerlaag toegevoegd).
 */
data class Camera(
    val id: String,
    val name: String,
    val mainUrl: String,
    val subUrl: String?,
    val source: CameraSource,
    val preferSub: Boolean = false,
    /** true: gebruik de globale RTSP-credentials (go2rtc) uit de instellingen. */
    val useGlobalRtspAuth: Boolean = source == CameraSource.AUTO,
) {
    fun urlFor(kind: StreamKind): String =
        if (kind == StreamKind.SUB && subUrl != null) subUrl else mainUrl

    /** Stream voor de actieve weergave. */
    fun preferredKind(): StreamKind =
        if (preferSub && subUrl != null) StreamKind.SUB else StreamKind.MAIN

    /** Stream voor een voorgeladen buur: bij voorkeur de substream. */
    fun preloadKind(): StreamKind = if (subUrl != null) StreamKind.SUB else StreamKind.MAIN

    fun kindFor(role: PlayerRole): StreamKind =
        if (role == PlayerRole.ACTIVE) preferredKind() else preloadKind()
}

/** Instellingen van de herstel-logica. Alle tijden in milliseconden. */
data class RecoveryConfig(
    val offlineThresholdMs: Long = 20_000,
    val watchdogEnabled: Boolean = true,
    val watchdogTimeoutMs: Long = 5_000,
    val backoffMinMs: Long = 1_000,
    val backoffMaxMs: Long = 30_000,
    /** Maximale tijd tot het eerste frame. Ruim genoeg voor camera's met een keyframe-interval van 10 s. */
    val connectTimeoutMs: Long = 20_000,
    /** Na zoveel ms ononderbroken beeld geldt de verbinding als stabiel en wordt de backoff gereset. */
    val stableAfterMs: Long = 10_000,
    /** Interval waarmee de watchdog de framecounter controleert. */
    val watchdogTickMs: Long = 500,
    /** Na zoveel opeenvolgende decoderfouten zonder enig frame wordt het een permanente codecfout. */
    val maxCodecFailures: Int = 3,
)
