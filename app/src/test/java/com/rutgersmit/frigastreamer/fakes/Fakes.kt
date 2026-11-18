package com.rutgersmit.frigastreamer.fakes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import com.rutgersmit.frigastreamer.domain.ErrorCategory
import com.rutgersmit.frigastreamer.player.PlayerFailure
import com.rutgersmit.frigastreamer.player.StreamPlayer
import com.rutgersmit.frigastreamer.player.StreamPlayerFactory
import com.rutgersmit.frigastreamer.player.StreamSource
import com.rutgersmit.frigastreamer.player.TrackInfo
import com.rutgersmit.frigastreamer.util.Clock
import com.rutgersmit.frigastreamer.util.Logger

/** Clock based on the virtual time of kotlinx-coroutines-test. */
class FakeClock(private val scheduler: TestCoroutineScheduler) : Clock {
    override fun elapsedMs(): Long = scheduler.currentTime
    override fun wallMs(): Long = 1_700_000_000_000L + scheduler.currentTime
}

class RecordingLogger(private val echo: Boolean = false) : Logger {
    val lines = mutableListOf<String>()
    private fun add(level: String, tag: String, msg: String) {
        val l = "$level/$tag: $msg"
        lines += l
        if (echo) println(l)
    }
    override fun d(tag: String, msg: String) = add("D", tag, msg)
    override fun i(tag: String, msg: String) = add("I", tag, msg)
    override fun w(tag: String, msg: String, t: Throwable?) = add("W", tag, msg)
    override fun e(tag: String, msg: String, t: Throwable?) = add("E", tag, msg)
    fun count(event: String, tagContains: String? = null) =
        lines.count { it.contains("event=$event") && (tagContains == null || it.contains(tagContains)) }
}

/**
 * Simulated Frigate/go2rtc server. Streams are identified by the last path segment
 * of the URL (e.g. `rtsp://x:8554/cam3_sub` → `cam3_sub`).
 */
class FakeServer {
    /** Frigate/go2rtc reachable as a whole. */
    var up = true
    /** Individual streams that do not respond (camera offline). */
    val down = mutableSetOf<String>()
    /** Streams whose connection stays open but frames stop. */
    val frozen = mutableSetOf<String>()
    /** Streams that always fail with this error (e.g. unsupported codec). */
    val failures = mutableMapOf<String, ErrorCategory>()
    /** Time until the first frame (e.g. high with a 10 s keyframe interval). */
    var connectDelayMs = 300L
    val connectDelayFor = mutableMapOf<String, Long>()
    /** Maximum number of simultaneous decoders on the "device". */
    var maxDecoders = Int.MAX_VALUE
    /** After a server outage the player only notices the disconnect after this many ms (RTSP timeout). */
    var dropDetectMs = 1_000L

    fun name(url: String) = url.substringAfterLast('/')
    fun reachable(name: String) = up && name !in down
}

class FakePlayerFactory(
    private val scope: CoroutineScope,
    val server: FakeServer,
) : StreamPlayerFactory {
    val created = mutableListOf<FakeStreamPlayer>()
    val live: List<FakeStreamPlayer> get() = created.filter { !it.released }
    fun liveFor(cameraId: String) = live.filter { it.cameraId == cameraId }
    fun decodingCount() = live.count { it.decoding }
    var maxLiveObserved = 0
        private set

    override fun create(cameraId: String, logTag: String): StreamPlayer {
        val p = FakeStreamPlayer(created.size + 1, cameraId, scope, server, this)
        created += p
        maxLiveObserved = maxOf(maxLiveObserved, live.size)
        return p
    }
}

class FakeStreamPlayer(
    override val instanceId: Int,
    val cameraId: String,
    private val scope: CoroutineScope,
    private val server: FakeServer,
    private val factory: FakePlayerFactory,
) : StreamPlayer {
    var currentListener: StreamPlayer.Listener? = null
        private set
    private val listener get() = currentListener
    var released = false
        private set
    var startCount = 0
        private set
    var source: StreamSource? = null
        private set
    var muted = true
        private set
    var decoding = false
        private set
    private var frames = 0L
    private var job: Job? = null

    val streamName: String? get() = source?.let { server.name(it.url) }

    override fun setListener(listener: StreamPlayer.Listener?) {
        currentListener = listener
    }

    override fun start(source: StreamSource) {
        check(!released) { "start after release" }
        stop()
        startCount++
        this.source = source
        val name = server.name(source.url)
        job = scope.launch {
            delay(server.connectDelayFor[name] ?: server.connectDelayMs)
            val failure = server.failures[name]
            when {
                !server.reachable(name) -> {
                    listener?.onFailure(PlayerFailure(ErrorCategory.NETWORK, "ConnectException: connection refused"))
                    return@launch
                }
                failure != null -> {
                    if (failure == ErrorCategory.UNSUPPORTED_AUDIO_CODEC && source.audioDisabled) {
                        // without audio the stream does play
                    } else {
                        listener?.onFailure(PlayerFailure(failure, "fake $failure"))
                        return@launch
                    }
                }
                factory.decodingCount() >= server.maxDecoders -> {
                    listener?.onFailure(PlayerFailure(ErrorCategory.DECODER_INIT, "fake: no decoder available"))
                    return@launch
                }
            }
            decoding = true
            listener?.onTracks(TrackInfo("video/avc", 1920, 1080, "audio/mp4a-latm", true))
            listener?.onFirstFrame()
            // Server drops out during playback: after dropDetectMs the player reports a network error.
            while (decoding) {
                delay(100)
                if (!server.reachable(name)) {
                    delay(server.dropDetectMs)
                    decoding = false
                    listener?.onFailure(PlayerFailure(ErrorCategory.NETWORK, "SocketException: connection reset"))
                }
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        decoding = false
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
    }

    /** Increases on every read while "frames are arriving". */
    override fun frameCounter(): Long {
        val name = streamName
        if (decoding && name != null && name !in server.frozen && server.reachable(name)) frames++
        return frames
    }

    override fun release() {
        stop()
        released = true
        currentListener = null
    }
}
