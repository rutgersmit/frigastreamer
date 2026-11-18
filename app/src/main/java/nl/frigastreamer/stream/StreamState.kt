package nl.frigastreamer.stream

import nl.frigastreamer.domain.ErrorCategory
import nl.frigastreamer.domain.PlayerRole
import nl.frigastreamer.domain.StreamKind
import nl.frigastreamer.player.TrackInfo

/**
 * Zichtbare toestand van een camera. Overgangen (zie [StreamController]):
 *
 * ```
 * Idle ──attach──▶ Connecting ──eerste frame──▶ Playing
 *                    │  ▲                          │ fout / watchdog / netwerkverlies
 *                    │  └──────backoff-timer───────┤
 *                    ▼                             ▼
 *                Reconnecting ◀────────────────────┘
 *    (Connecting/Reconnecting langer dan offline-drempel) ⇒ Offline  (reconnect loopt door)
 *    permanente fout (codec/auth) ⇒ Error  (geen automatische reconnects)
 *    detach (buiten venster / app naar achtergrond) ⇒ Idle
 * ```
 */
sealed interface StreamState {
    data object Idle : StreamState

    data class Connecting(val attempt: Int) : StreamState

    data object Playing : StreamState

    /** [nextAttemptAtMs] = null betekent: wachten tot het netwerk terug is. */
    data class Reconnecting(val attempt: Int, val nextAttemptAtMs: Long?, val reason: ErrorCategory) : StreamState

    /** Langer dan de offline-drempel geen beeld. De reconnect-logica blijft actief. */
    data class Offline(
        val attempt: Int,
        val nextAttemptAtMs: Long?,
        val connectingNow: Boolean,
        val reason: ErrorCategory?,
    ) : StreamState

    data class Error(val category: ErrorCategory, val detail: String) : StreamState
}

data class StreamStatus(
    val cameraId: String,
    val cameraName: String,
    val state: StreamState = StreamState.Idle,
    val role: PlayerRole? = null,
    val kind: StreamKind? = null,
    val hasPlayer: Boolean = false,
    val playerInstanceId: Int? = null,
    val lastFrameElapsedMs: Long? = null,
    val lastFrameWallMs: Long? = null,
    val outageSinceElapsedMs: Long? = null,
    val currentBackoffMs: Long? = null,
    val lastError: ErrorCategory? = null,
    val audioUnsupported: Boolean = false,
    val waitingForNetwork: Boolean = false,
    val buffering: Boolean = false,
    val trackInfo: TrackInfo? = null,
    val totalConnects: Int = 0,
)
