package nl.frigastreamer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import nl.frigastreamer.domain.ErrorCategory
import nl.frigastreamer.domain.ErrorClassifier
import nl.frigastreamer.domain.FailureInfo
import nl.frigastreamer.domain.TrackKind
import nl.frigastreamer.util.Logger
import nl.frigastreamer.util.Redact
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Media3/ExoPlayer-implementatie van [StreamPlayer] voor RTSP-livestreams.
 *
 * - RTSP altijd over TCP (`setForceUseRtpTcp(true)`), geen UDP.
 * - Kleine buffers voor lage latency.
 * - Decoder-fallback aan: als de hardwaredecoder niet start, probeert Media3 een andere.
 * - Credentials gaan via de userinfo van de URI naar Media3 (de enige manier die Media3 RTSP
 *   ondersteunt); Media3 haalt die er zelf weer uit voor de RTSP-requests. RTSP-debuglogging
 *   van Media3 staat uit, en zelf loggen we alleen geredacteerde URL's.
 * - Alle callbacks worden via de main-looper doorgestuurd, zodat de controller nooit binnen een
 *   ExoPlayer-callback de speler vrijgeeft.
 */
@OptIn(UnstableApi::class)
class ExoStreamPlayer(
    context: Context,
    private val logTag: String,
    private val log: Logger,
) : StreamPlayer {

    override val instanceId: Int = nextId.incrementAndGet()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val trackSelector = DefaultTrackSelector(context.applicationContext)
    private val metadataFrames = AtomicLong()
    private var decoderProgress = DecoderProgress()
    private var listener: StreamPlayer.Listener? = null
    private var released = false
    private var muted = true

    val exoPlayer: ExoPlayer

    private val frameListener = VideoFrameMetadataListener { _, _, _, _ -> metadataFrames.incrementAndGet() }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> post { it.onBuffering(true) }
                Player.STATE_READY -> post { it.onBuffering(false) }
                Player.STATE_ENDED -> post {
                    it.onFailure(PlayerFailure(ErrorCategory.STREAM_ENDED, "server stuurt geen RTP-data meer"))
                }
                else -> Unit
            }
        }

        override fun onRenderedFirstFrame() {
            post { it.onFirstFrame() }
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (tracks.groups.isEmpty()) return
            var videoMime: String? = null
            var width = 0
            var height = 0
            var hasVideo = false
            var videoSupported = false
            var audioMime: String? = null
            var hasAudio = false
            var audioSupported = false
            for (group in tracks.groups) {
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> {
                        hasVideo = true
                        val f = group.getTrackFormat(0)
                        videoMime = f.sampleMimeType
                        width = f.width
                        height = f.height
                        if (group.isSupported) videoSupported = true
                    }
                    C.TRACK_TYPE_AUDIO -> {
                        hasAudio = true
                        audioMime = group.getTrackFormat(0).sampleMimeType
                        if (group.isSupported) audioSupported = true
                    }
                    else -> Unit
                }
            }
            val info = TrackInfo(videoMime, width, height, audioMime, !hasAudio || audioSupported)
            post { it.onTracks(info) }
            if (!hasVideo) {
                post {
                    it.onFailure(
                        PlayerFailure(
                            ErrorCategory.UNSUPPORTED_VIDEO_CODEC,
                            "geen afspeelbare videotrack in de stream (codec niet ondersteund door Media3 RTSP)",
                        ),
                    )
                }
            } else if (!videoSupported) {
                post {
                    it.onFailure(
                        PlayerFailure(ErrorCategory.UNSUPPORTED_VIDEO_CODEC, "videocodec $videoMime ${width}x$height niet ondersteund"),
                    )
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val info = toFailureInfo(error)
            val category = ErrorClassifier.classify(info)
            val detail = "code=${error.errorCodeName} " + info.messages.take(3).joinToString(" | ")
            post { it.onFailure(PlayerFailure(category, Redact.text(detail).take(300))) }
        }
    }

    init {
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_WARNING)
        val renderers = DefaultRenderersFactory(context.applicationContext)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 500,
                /* maxBufferMs = */ 2_000,
                /* bufferForPlaybackMs = */ 100,
                /* bufferForPlaybackAfterRebufferMs = */ 300,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        exoPlayer = ExoPlayer.Builder(context.applicationContext, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setLooper(Looper.getMainLooper())
            .build()
        exoPlayer.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ false,
        )
        exoPlayer.volume = 0f
        exoPlayer.setVideoFrameMetadataListener(frameListener)
        exoPlayer.addListener(playerListener)
        exoPlayer.playWhenReady = true
    }

    override fun setListener(listener: StreamPlayer.Listener?) {
        this.listener = listener
    }

    override fun start(source: StreamSource) {
        if (released) return
        exoPlayer.stop()
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, source.audioDisabled)
                .build(),
        )
        val uri = withCredentials(source.url, source)
        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setDebugLoggingEnabled(false)
            .setTimeoutMs(RTP_TIMEOUT_MS)
            .createMediaSource(MediaItem.fromUri(uri))
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        applyVolume()
    }

    override fun stop() {
        if (released) return
        exoPlayer.stop()
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        applyVolume()
    }

    override fun frameCounter(): Long {
        if (released) return metadataFrames.get()
        // Gerenderde frames (VideoFrameMetadataListener) plus frames die de decoder wel verwerkte
        // maar niet toonde (bv. een voorgeladen speler die even geen surface heeft).
        return metadataFrames.get() + decoderProgress.update(exoPlayer.videoDecoderCounters)
    }

    override fun release() {
        if (released) return
        released = true
        listener = null
        mainHandler.removeCallbacksAndMessages(null)
        exoPlayer.removeListener(playerListener)
        exoPlayer.clearVideoFrameMetadataListener(frameListener)
        exoPlayer.release()
    }

    private fun applyVolume() {
        if (!released) exoPlayer.volume = if (muted) 0f else 1f
    }

    private inline fun post(crossinline block: (StreamPlayer.Listener) -> Unit) {
        mainHandler.post {
            if (!released) listener?.let(block)
        }
    }

    private fun withCredentials(url: String, source: StreamSource): Uri {
        val parsed = Uri.parse(url)
        val creds = source.credentials ?: return parsed
        if (creds.username.isEmpty() || parsed.userInfo != null) return parsed
        val authority = parsed.encodedAuthority ?: return parsed
        val userInfo = Uri.encode(creds.username) + ":" + Uri.encode(creds.password)
        return parsed.buildUpon().encodedAuthority("$userInfo@$authority").build()
    }

    private fun toFailureInfo(error: PlaybackException): FailureInfo {
        var trackKind: TrackKind? = null
        if (error is ExoPlaybackException && error.type == ExoPlaybackException.TYPE_RENDERER) {
            val mime = error.rendererFormat?.sampleMimeType
            trackKind = when {
                mime == null -> null
                mime.startsWith("video/") -> TrackKind.VIDEO
                mime.startsWith("audio/") -> TrackKind.AUDIO
                else -> TrackKind.OTHER
            }
        }
        val messages = ArrayList<String>()
        var t: Throwable? = error
        var depth = 0
        while (t != null && depth < 8) {
            messages += Redact.text("${t.javaClass.simpleName}: ${t.message ?: ""}")
            t = t.cause
            depth++
        }
        return FailureInfo(error.errorCode, trackKind, messages)
    }

    /** Houdt een monotone som bij van verwerkte (ook overgeslagen/gedropte) videobuffers. */
    private class DecoderProgress {
        private var lastCounters: Any? = null
        private var lastValue = 0L
        private var accumulated = 0L

        fun update(counters: androidx.media3.exoplayer.DecoderCounters?): Long {
            if (counters == null) return accumulated
            counters.ensureUpdated()
            val value = (counters.skippedOutputBufferCount + counters.droppedBufferCount).toLong()
            if (counters !== lastCounters) {
                lastCounters = counters
                lastValue = 0
            }
            if (value > lastValue) {
                accumulated += value - lastValue
                lastValue = value
            }
            return accumulated
        }
    }

    companion object {
        private val nextId = AtomicInteger()

        /** Na zoveel ms zonder RTP-pakketten meldt Media3 einde stream (wordt een reconnect). */
        const val RTP_TIMEOUT_MS = 8_000L
    }
}

class ExoStreamPlayerFactory(private val context: Context, private val log: Logger) : StreamPlayerFactory {
    override fun create(cameraId: String, logTag: String): StreamPlayer = ExoStreamPlayer(context, logTag, log)
}
