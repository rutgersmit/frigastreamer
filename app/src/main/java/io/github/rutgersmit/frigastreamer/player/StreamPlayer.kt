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
 * Abstractie van een videospeler voor één camera. De echte implementatie is [ExoStreamPlayer];
 * tests gebruiken een fake. Alle methodes en callbacks lopen op de main thread.
 */
interface StreamPlayer {
    /** Uniek per instantie (voor logging en debug-overlay). */
    val instanceId: Int

    fun setListener(listener: Listener?)

    /** Start (of herstart) de stream. Een eerdere stream wordt eerst gestopt. */
    fun start(source: StreamSource)

    /** Stopt de stream en sluit de RTSP-verbinding; het laatste beeld blijft staan. */
    fun stop()

    fun setMuted(muted: Boolean)

    /**
     * Monotone teller die oploopt zolang er videoframes gedecodeerd/gerenderd worden.
     * De frame-watchdog vergelijkt opeenvolgende waarden.
     */
    fun frameCounter(): Long

    /** Geeft alle resources (decoder, sockets, threads) definitief vrij. */
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
