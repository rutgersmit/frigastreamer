package io.github.rutgersmit.frigastreamer.settings

import io.github.rutgersmit.frigastreamer.domain.RecoveryConfig
import io.github.rutgersmit.frigastreamer.domain.RtspCredentials
import io.github.rutgersmit.frigastreamer.domain.SubstreamPattern

enum class ApiMode { INTERNAL, AUTHENTICATED }

enum class OrientationSetting { LANDSCAPE, PORTRAIT, AUTO }

data class AppSettings(
    val host: String = "",
    val apiMode: ApiMode = ApiMode.INTERNAL,
    val apiPort: Int = DEFAULT_INTERNAL_PORT,
    /** Authenticated mode only: Frigate uses HTTPS on 8971 by default. */
    val apiHttps: Boolean = true,
    /** Accept Frigate's self-signed certificate (for this host only). */
    val apiTrustSelfSigned: Boolean = true,
    val apiUsername: String = "",
    val apiPassword: String = "",
    val rtspPort: Int = 8554,
    val rtspUsername: String = "",
    val rtspPassword: String = "",
    val substreamPattern: String = SubstreamPattern.DEFAULT,
    val preloadWindow: Int = 1,
    val offlineThresholdSec: Int = 20,
    val watchdogEnabled: Boolean = true,
    val watchdogTimeoutSec: Int = 5,
    val backoffMinSec: Int = 1,
    val backoffMaxSec: Int = 30,
    val startMuted: Boolean = true,
    val muted: Boolean = true,
    val orientation: OrientationSetting = OrientationSetting.LANDSCAPE,
    val debugOverlay: Boolean = false,
    val lastCameraId: String? = null,
) {
    val isConfigured: Boolean get() = host.isNotBlank()

    fun recoveryConfig(): RecoveryConfig {
        val min = backoffMinSec.coerceIn(1, 60) * 1000L
        return RecoveryConfig(
            offlineThresholdMs = offlineThresholdSec.coerceIn(3, 600) * 1000L,
            watchdogEnabled = watchdogEnabled,
            watchdogTimeoutMs = watchdogTimeoutSec.coerceIn(2, 120) * 1000L,
            backoffMinMs = min,
            backoffMaxMs = maxOf(min, backoffMaxSec.coerceIn(1, 600) * 1000L),
        )
    }

    fun rtspCredentials(): RtspCredentials? =
        if (rtspUsername.isNotBlank()) RtspCredentials(rtspUsername, rtspPassword) else null

    override fun toString(): String =
        "AppSettings(host=$host, apiMode=$apiMode, apiPort=$apiPort, rtspPort=$rtspPort, apiAuth=${apiUsername.isNotBlank()}, " +
            "rtspAuth=${rtspUsername.isNotBlank()}, pattern=$substreamPattern, preload=$preloadWindow)"

    companion object {
        const val DEFAULT_INTERNAL_PORT = 5000
        const val DEFAULT_AUTH_PORT = 8971
    }
}
