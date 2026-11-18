package io.github.rutgersmit.frigastreamer.stream

import io.github.rutgersmit.frigastreamer.domain.ErrorCategory
import io.github.rutgersmit.frigastreamer.domain.PlayerRole
import io.github.rutgersmit.frigastreamer.domain.StreamKind
import io.github.rutgersmit.frigastreamer.player.TrackInfo

/**
 * Visible state of a camera. Transitions (see [StreamController]):
 *
 * ```
 * Idle ──attach──▶ Connecting ──first frame──▶ Playing
 *                    │  ▲                          │ error / watchdog / network loss
 *                    │  └──────backoff timer───────┤
 *                    ▼                             ▼
 *                Reconnecting ◀────────────────────┘
 *    (Connecting/Reconnecting longer than offline threshold) ⇒ Offline  (reconnect keeps running)
 *    permanent error (codec/auth) ⇒ Error  (no automatic reconnects)
 *    detach (outside window / app to background) ⇒ Idle
 * ```
 */
sealed interface StreamState {
    data object Idle : StreamState

    data class Connecting(val attempt: Int) : StreamState

    data object Playing : StreamState

    /** [nextAttemptAtMs] = null means: waiting for the network to come back. */
    data class Reconnecting(val attempt: Int, val nextAttemptAtMs: Long?, val reason: ErrorCategory) : StreamState

    /** No frames for longer than the offline threshold. The reconnect logic stays active. */
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
