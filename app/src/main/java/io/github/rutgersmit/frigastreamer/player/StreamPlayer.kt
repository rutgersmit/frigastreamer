package io.github.rutgersmit.frigastreamer.player

import io.github.rutgersmit.frigastreamer.domain.ErrorCategory
import io.github.rutgersmit.frigastreamer.domain.RtspCredentials
import io.github.rutgersmit.frigastreamer.domain.StreamKind

data class StreamSource(
    val url: String,
    val kind: StreamKind,
    val credentials: RtspCredentials?,
    val audioDisabled: Boolean,
) {
    override fun toString(): String =
        "StreamSource(url=${io.github.rutgersmit.frigastreamer.util.Redact.url(url)}, kind=$kind, auth=${credentials != null}, audioDisabled=$audioDisabled)"
}

data class TrackInfo(
    val videoMime: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val audioMime: String? = null,
    val audioSupported: Boolean = true,
)

data class PlayerFailure(val category: ErrorCategory, val detail: String)

/**
 * Abstraction of a video player for one camera. The real implementation is [ExoStreamPlayer];
 * tests use a fake. All methods and callbacks run on the main thread.
 */
interface StreamPlayer {
    /** Unique per instance (for logging and the debug overlay). */
    val instanceId: Int

    fun setListener(listener: Listener?)

    /** Starts (or restarts) the stream. A previous stream is stopped first. */
    fun start(source: StreamSource)

    /** Stops the stream and closes the RTSP connection; the last frame stays visible. */
    fun stop()

    fun setMuted(muted: Boolean)

    /**
     * Monotonic counter that increases while video frames are being decoded/rendered.
     * The frame watchdog compares successive values.
     */
    fun frameCounter(): Long

    /** Permanently releases all resources (decoder, sockets, threads). */
    fun release()

    interface Listener {
        fun onFirstFrame()
        fun onBuffering(buffering: Boolean)
        fun onTracks(info: TrackInfo)
        fun onFailure(failure: PlayerFailure)
    }
}

fun interface StreamPlayerFactory {
    fun create(cameraId: String, logTag: String): StreamPlayer
}
