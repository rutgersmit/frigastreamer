package com.rutgersmit.frigastreamer.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.rutgersmit.frigastreamer.domain.Backoff
import com.rutgersmit.frigastreamer.domain.Camera
import com.rutgersmit.frigastreamer.domain.ErrorCategory
import com.rutgersmit.frigastreamer.domain.PlayerRole
import com.rutgersmit.frigastreamer.domain.RecoveryConfig
import com.rutgersmit.frigastreamer.domain.RtspCredentials
import com.rutgersmit.frigastreamer.domain.StreamKind
import com.rutgersmit.frigastreamer.player.PlayerFailure
import com.rutgersmit.frigastreamer.player.StreamPlayer
import com.rutgersmit.frigastreamer.player.StreamPlayerFactory
import com.rutgersmit.frigastreamer.player.StreamSource
import com.rutgersmit.frigastreamer.player.TrackInfo
import com.rutgersmit.frigastreamer.util.Clock
import com.rutgersmit.frigastreamer.util.Logger
import com.rutgersmit.frigastreamer.util.Redact

/**
 * Lightweight controller per camera: status, reconnect state machine, frame watchdog,
 * offline threshold and error classification. Lives as long as the camera is in the list;
 * the (heavy) [StreamPlayer] only exists while the camera is inside the player window
 * (between [attach] and [detach]).
 *
 * Threading: all methods must be called on the dispatcher of [parentScope] (main thread);
 * player callbacks also arrive on that thread. So no locks are needed and only one
 * reconnect process can run per camera at a time: every new (re)connect first cancels the
 * previous timer, and callbacks from an old session are ignored via a session number.
 */
class StreamController(
    camera: Camera,
    parentScope: CoroutineScope,
    private val playerFactory: StreamPlayerFactory,
    private val clock: Clock,
    config: RecoveryConfig,
    private val host: Host,
    private val log: Logger,
) {
    interface Host {
        fun rtspCredentials(): RtspCredentials?

        /** Called on a decoder initialization failure; the host decides what happens next. */
        fun onDecoderInitFailure(controller: StreamController): DecoderDecision

        fun onStatusChanged(controller: StreamController) {}
    }

    enum class DecoderDecision {
        /** Other players have been released; retry the active camera immediately. */
        RETRY_NOW,

        /** There were no other players: it is a real codec/decoder error. */
        GIVE_UP,

        /** The host has already detached this (preloaded) controller itself. */
        DETACHED,
    }

    private enum class Phase { IDLE, CONNECTING, PLAYING, WAITING, ERROR }

    var camera: Camera = camera
        private set
    private var config: RecoveryConfig = config
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val tag: String get() = Logger.cameraTag(camera.name)

    private val _status = MutableStateFlow(StreamStatus(camera.id, camera.name))

    /** Updated on every state transition (not per frame; use [snapshot] for live values). */
    val status: StateFlow<StreamStatus> = _status.asStateFlow()

    private val _player = MutableStateFlow<StreamPlayer?>(null)

    /** Current player instance (only inside the window), for attaching to the video view. */
    val player: StateFlow<StreamPlayer?> = _player.asStateFlow()

    private var phase = Phase.IDLE
    var role: PlayerRole? = null
        private set
    private var kind: StreamKind? = null
    private var playerInstance: StreamPlayer? = null
    private var session = 0
    private var attempt = 0
    private var outageSince: Long? = null
    private var lastFrameElapsed: Long? = null
    private var lastFrameWall: Long? = null
    private var frameBaseline = 0L
    private var nextAttemptAt: Long? = null
    private var currentBackoff: Long? = null
    private var lastError: ErrorCategory? = null
    private var errorDetail: String = ""
    private var audioDisabled = false
    private var audioUnsupported = false
    private var codecFailures = 0
    private var decoderRetries = 0
    private var buffering = false
    private var networkAvailable = true
    private var muted = true
    private var trackInfo: TrackInfo? = null
    private var totalConnects = 0
    private var disposed = false

    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var offlineJob: Job? = null
    private var stableJob: Job? = null

    val hasPlayer: Boolean get() = playerInstance != null
    val isError: Boolean get() = phase == Phase.ERROR
    val isPlaying: Boolean get() = phase == Phase.PLAYING

    /** True when a reconnect timer is currently running (for tests: never more than one). */
    val hasPendingReconnect: Boolean get() = reconnectJob?.isActive == true

    // ------------------------------------------------------------------------------------
    // Public API (called by StreamManager)
    // ------------------------------------------------------------------------------------

    /** Attach to the player window with the given role, or only change the role if already attached. */
    fun attach(newRole: PlayerRole) {
        check(!disposed) { "controller has been disposed" }
        val previous = role
        role = newRole
        if (phase == Phase.ERROR) {
            // Permanent error: do not use a player (or a decoder) until an explicit retry.
            applyAudio()
            publish()
            return
        }
        if (previous != null && phase != Phase.IDLE) {
            if (previous != newRole) log.i(tag, "event=role_changed from=$previous to=$newRole")
            applyAudio()
            if (newRole == PlayerRole.ACTIVE && kind != camera.kindFor(newRole)) {
                switchStream("promoted_to_active")
            }
            publish()
            return
        }
        log.i(tag, "event=attach role=$newRole")
        attempt = 0
        outageSince = clock.elapsedMs()
        connect("attach")
    }

    /** Switch to the stream that belongs to the current role (main or sub), if not already. */
    fun ensurePreferredStream() {
        val r = role ?: return
        if (phase == Phase.ERROR || phase == Phase.IDLE) return
        if (kind != camera.kindFor(r)) switchStream("role_stream_preference")
    }

    /** Detach from the window: fully release the player, stop all timers. */
    fun detach(reason: String) {
        if (role == null && playerInstance == null && phase != Phase.ERROR) return
        if (role != null || playerInstance != null) log.i(tag, "event=detach reason=$reason")
        cancelJobs()
        watchdogJob?.cancel(); watchdogJob = null
        releasePlayer(reason)
        role = null
        if (phase != Phase.ERROR) phase = Phase.IDLE
        attempt = 0
        nextAttemptAt = null
        currentBackoff = null
        outageSince = null
        buffering = false
        publish()
    }

    fun setMuted(value: Boolean) {
        muted = value
        applyAudio()
    }

    fun onNetworkChanged(available: Boolean) {
        if (available == networkAvailable) return
        networkAvailable = available
        if (role == null || phase == Phase.ERROR || phase == Phase.IDLE) {
            publish()
            return
        }
        if (!available) {
            log.w(tag, "event=network_lost action=pause_reconnects")
            lastError = ErrorCategory.NETWORK_UNAVAILABLE
            enterWaitingForNetwork()
        } else {
            log.i(tag, "event=network_restored action=reconnect_now")
            attempt = 0
            connect("network_restored")
        }
    }

    /** Manual (or lifecycle) retry after a permanent error. */
    fun retry(reason: String = "manual_retry") {
        if (disposed) return
        val wasError = phase == Phase.ERROR
        codecFailures = 0
        decoderRetries = 0
        audioDisabled = false
        audioUnsupported = false
        if (wasError) {
            log.i(tag, "event=retry reason=$reason")
            phase = Phase.IDLE
            lastError = null
            errorDetail = ""
        }
        if (role != null && (wasError || phase != Phase.PLAYING)) {
            attempt = 0
            outageSince = outageSince ?: clock.elapsedMs()
            connect(reason)
        } else {
            publish()
        }
    }

    fun updateCamera(newCamera: Camera) {
        val old = camera
        camera = newCamera
        val changed = old.mainUrl != newCamera.mainUrl || old.subUrl != newCamera.subUrl ||
            old.preferSub != newCamera.preferSub || old.useGlobalRtspAuth != newCamera.useGlobalRtspAuth
        if (changed) {
            log.i(tag, "event=camera_config_changed")
            if (phase == Phase.ERROR) {
                retry("camera_config_changed")
                return
            }
            if (role != null && phase != Phase.IDLE) {
                if (phase == Phase.WAITING) attempt = 0
                connect("camera_config_changed")
                return
            }
        }
        publish()
    }

    /** Reconnect because e.g. the RTSP credentials changed. */
    fun reconnectNow(reason: String) {
        if (role == null || phase == Phase.ERROR || phase == Phase.IDLE) return
        attempt = 0
        connect(reason)
    }

    fun updateConfig(newConfig: RecoveryConfig) {
        config = newConfig
        if (outageSince != null && phase != Phase.PLAYING) armOfflineTimer()
        publish()
    }

    fun dispose() {
        if (disposed) return
        detach("dispose")
        disposed = true
        scope.cancel()
    }

    /** Current status, including live values such as the time of the last frame. */
    fun snapshot(): StreamStatus {
        val now = clock.elapsedMs()
        val offline = outageSince?.let { now - it >= config.offlineThresholdMs } ?: false
        val state: StreamState = when (phase) {
            Phase.IDLE -> StreamState.Idle
            Phase.ERROR -> StreamState.Error(lastError ?: ErrorCategory.UNKNOWN, errorDetail)
            Phase.PLAYING -> StreamState.Playing
            Phase.CONNECTING ->
                if (offline) StreamState.Offline(attempt, null, true, lastError) else StreamState.Connecting(attempt)
            Phase.WAITING ->
                if (offline) {
                    StreamState.Offline(attempt, nextAttemptAt, false, lastError)
                } else {
                    StreamState.Reconnecting(attempt, nextAttemptAt, lastError ?: ErrorCategory.UNKNOWN)
                }
        }
        return StreamStatus(
            cameraId = camera.id,
            cameraName = camera.name,
            state = state,
            role = role,
            kind = kind.takeIf { playerInstance != null },
            hasPlayer = playerInstance != null,
            playerInstanceId = playerInstance?.instanceId,
            lastFrameElapsedMs = lastFrameElapsed,
            lastFrameWallMs = lastFrameWall,
            outageSinceElapsedMs = outageSince,
            currentBackoffMs = currentBackoff,
            lastError = lastError,
            audioUnsupported = audioUnsupported,
            waitingForNetwork = phase == Phase.WAITING && !networkAvailable,
            buffering = buffering,
            trackInfo = trackInfo,
            totalConnects = totalConnects,
        )
    }

    // ------------------------------------------------------------------------------------
    // State-machine
    // ------------------------------------------------------------------------------------

    private fun connect(reason: String) {
        if (disposed) return
        val r = role ?: return
        if (phase == Phase.ERROR) return
        reconnectJob?.cancel(); reconnectJob = null
        connectTimeoutJob?.cancel(); connectTimeoutJob = null
        stableJob?.cancel(); stableJob = null
        nextAttemptAt = null
        currentBackoff = null
        if (!networkAvailable) {
            enterWaitingForNetwork()
            return
        }
        val p = playerInstance ?: playerFactory.create(camera.id, tag).also {
            playerInstance = it
            _player.value = it
            log.i(tag, "event=player_created instance=${it.instanceId}")
        }
        session++
        val s = session
        phase = Phase.CONNECTING
        buffering = false
        val k = camera.kindFor(r)
        kind = k
        frameBaseline = p.frameCounter()
        p.setListener(SessionListener(s))
        totalConnects++
        val url = camera.urlFor(k)
        log.i(tag, "event=connect_start reason=$reason attempt=$attempt stream=$k role=$r url=${Redact.url(url)}")
        p.start(
            StreamSource(
                url = url,
                kind = k,
                credentials = if (camera.useGlobalRtspAuth) host.rtspCredentials() else null,
                audioDisabled = audioDisabled,
            ),
        )
        applyAudio()
        val timeout = config.connectTimeoutMs
        connectTimeoutJob = scope.launch {
            delay(timeout)
            if (session == s && phase == Phase.CONNECTING) {
                onFailure(PlayerFailure(ErrorCategory.CONNECT_TIMEOUT, "no video within ${timeout / 1000} s"), s)
            }
        }
        ensureWatchdog()
        armOfflineTimer()
        publish()
    }

    private fun switchStream(reason: String) {
        if (phase == Phase.CONNECTING || phase == Phase.PLAYING) {
            log.i(tag, "event=stream_switch reason=$reason from=$kind to=${role?.let { camera.kindFor(it) }}")
            connect(reason)
        }
        // In WAITING the next attempt automatically picks the right stream.
    }

    private fun onFrameProgress() {
        lastFrameElapsed = clock.elapsedMs()
        lastFrameWall = clock.wallMs()
        if (phase == Phase.CONNECTING) onConnected()
    }

    private fun onConnected() {
        phase = Phase.PLAYING
        connectTimeoutJob?.cancel(); connectTimeoutJob = null
        offlineJob?.cancel(); offlineJob = null
        val wasOutage = outageSince != null
        outageSince = null
        lastError = null
        errorDetail = ""
        codecFailures = 0
        decoderRetries = 0
        nextAttemptAt = null
        currentBackoff = null
        log.i(tag, "event=connect_success attempt=$attempt stream=$kind recovered=$wasOutage")
        val s = session
        stableJob?.cancel()
        stableJob = scope.launch {
            delay(config.stableAfterMs)
            if (session == s && phase == Phase.PLAYING) {
                if (attempt > 0) log.i(tag, "event=backoff_reset after_stable_ms=${config.stableAfterMs}")
                attempt = 0
                publish()
            }
        }
        publish()
    }

    private fun onFailure(failure: PlayerFailure, s: Int) {
        if (s != session || disposed) return
        if (phase == Phase.IDLE || phase == Phase.ERROR || phase == Phase.WAITING) return
        val cat = failure.category
        connectTimeoutJob?.cancel(); connectTimeoutJob = null
        stableJob?.cancel(); stableJob = null
        lastError = cat
        errorDetail = failure.detail
        val wasPlaying = phase == Phase.PLAYING
        if (cat.isCodecRelated) {
            log.e(tag, "event=codec_error category=$cat detail=${Redact.text(failure.detail)}")
        } else {
            log.w(tag, "event=${if (wasPlaying) "connection_lost" else "connect_failed"} category=$cat detail=${Redact.text(failure.detail)}")
        }
        when {
            cat == ErrorCategory.UNSUPPORTED_AUDIO_CODEC && !audioDisabled -> {
                // Video must keep working: reconnect without the audio track.
                audioDisabled = true
                audioUnsupported = true
                log.w(tag, "event=audio_disabled reason=unsupported_audio_codec")
                connect("audio_disabled")
                return
            }
            cat == ErrorCategory.DECODER_INIT -> {
                when (host.onDecoderInitFailure(this)) {
                    DecoderDecision.DETACHED -> return
                    DecoderDecision.RETRY_NOW -> {
                        decoderRetries++
                        if (decoderRetries > MAX_DECODER_RETRIES) {
                            enterError(cat, failure.detail)
                        } else {
                            log.w(tag, "event=decoder_retry retry=$decoderRetries action=released_other_players")
                            releasePlayer("decoder_retry")
                            connect("decoder_retry")
                        }
                        return
                    }
                    DecoderDecision.GIVE_UP -> {
                        enterError(cat, failure.detail.ifBlank { "decoder does not start, even without other players" })
                        return
                    }
                }
            }
            cat.permanent -> {
                enterError(cat, failure.detail)
                return
            }
            cat.isCodecRelated -> {
                codecFailures++
                if (codecFailures >= config.maxCodecFailures) {
                    enterError(ErrorCategory.CODEC_PERMANENT, "$codecFailures times ${cat.description.lowercase()} without video")
                    return
                }
            }
        }
        scheduleReconnect(cat)
    }

    private fun scheduleReconnect(reason: ErrorCategory) {
        playerInstance?.stop()
        buffering = false
        if (outageSince == null) outageSince = clock.elapsedMs()
        phase = Phase.WAITING
        if (!networkAvailable) {
            enterWaitingForNetwork()
            return
        }
        attempt++
        val d = Backoff(config.backoffMinMs, config.backoffMaxMs).delayFor(attempt)
        currentBackoff = d
        nextAttemptAt = clock.elapsedMs() + d
        log.i(tag, "event=reconnect_scheduled attempt=$attempt delay_ms=$d reason=$reason")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(d)
            reconnectJob = null
            log.i(tag, "event=reconnect_execute attempt=$attempt")
            connect("backoff")
        }
        armOfflineTimer()
        publish()
    }

    private fun enterWaitingForNetwork() {
        reconnectJob?.cancel(); reconnectJob = null
        connectTimeoutJob?.cancel(); connectTimeoutJob = null
        stableJob?.cancel(); stableJob = null
        playerInstance?.stop()
        buffering = false
        if (phase == Phase.PLAYING || phase == Phase.CONNECTING) session++ // ignore late callbacks
        phase = Phase.WAITING
        nextAttemptAt = null
        currentBackoff = null
        if (outageSince == null) outageSince = clock.elapsedMs()
        if (lastError == null) lastError = ErrorCategory.NETWORK_UNAVAILABLE
        log.i(tag, "event=waiting_for_network")
        armOfflineTimer()
        publish()
    }

    private fun enterError(category: ErrorCategory, detail: String) {
        cancelJobs()
        watchdogJob?.cancel(); watchdogJob = null
        releasePlayer("permanent_error")
        phase = Phase.ERROR
        lastError = category
        errorDetail = detail
        nextAttemptAt = null
        currentBackoff = null
        log.e(tag, "event=stream_error category=$category permanent=true auto_reconnect=false detail=${Redact.text(detail)}")
        publish()
    }

    // ------------------------------------------------------------------------------------
    // Watchdog and timers
    // ------------------------------------------------------------------------------------

    private fun ensureWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(config.watchdogTickMs)
                watchdogTick()
            }
        }
    }

    private fun watchdogTick() {
        val p = playerInstance ?: return
        if (phase != Phase.CONNECTING && phase != Phase.PLAYING) return
        val c = p.frameCounter()
        if (c != frameBaseline) {
            frameBaseline = c
            onFrameProgress()
        }
        if (phase == Phase.PLAYING && config.watchdogEnabled) {
            val now = clock.elapsedMs()
            val since = now - (lastFrameElapsed ?: now)
            // Brief buffering is normal; allow a bit more time then, but never indefinitely.
            val limit = if (buffering) config.watchdogTimeoutMs * 2 else config.watchdogTimeoutMs
            if (since >= limit) {
                log.w(tag, "event=watchdog_triggered no_frame_ms=$since limit_ms=$limit buffering=$buffering")
                onFailure(PlayerFailure(ErrorCategory.STALLED, "no new frame for ${since / 1000} s"), session)
            }
        }
    }

    private fun armOfflineTimer() {
        offlineJob?.cancel(); offlineJob = null
        val since = outageSince ?: return
        val remaining = since + config.offlineThresholdMs - clock.elapsedMs()
        if (remaining <= 0) return
        offlineJob = scope.launch {
            delay(remaining)
            if (phase == Phase.CONNECTING || phase == Phase.WAITING) {
                log.w(tag, "event=offline threshold_ms=${config.offlineThresholdMs} reason=${lastError ?: "unknown"}")
            }
            publish()
        }
    }

    private fun cancelJobs() {
        reconnectJob?.cancel(); reconnectJob = null
        connectTimeoutJob?.cancel(); connectTimeoutJob = null
        offlineJob?.cancel(); offlineJob = null
        stableJob?.cancel(); stableJob = null
    }

    private fun releasePlayer(reason: String) {
        val p = playerInstance ?: return
        session++
        p.setListener(null)
        p.release()
        playerInstance = null
        _player.value = null
        kind = null
        trackInfo = null
        log.i(tag, "event=player_released instance=${p.instanceId} reason=$reason")
    }

    private fun applyAudio() {
        playerInstance?.setMuted(muted || role != PlayerRole.ACTIVE || audioDisabled)
    }

    private fun publish() {
        _status.value = snapshot()
        host.onStatusChanged(this)
    }

    private inner class SessionListener(private val s: Int) : StreamPlayer.Listener {
        override fun onFirstFrame() {
            if (s != session) return
            playerInstance?.let { frameBaseline = it.frameCounter() }
            onFrameProgress()
        }

        override fun onBuffering(buffering: Boolean) {
            if (s != session) return
            if (this@StreamController.buffering != buffering) {
                this@StreamController.buffering = buffering
                if (phase == Phase.PLAYING) log.d(tag, "event=buffering value=$buffering")
            }
        }

        override fun onTracks(info: TrackInfo) {
            if (s != session) return
            trackInfo = info
            if (!info.audioSupported && !audioUnsupported) {
                audioUnsupported = true
                log.w(tag, "event=audio_unsupported mime=${info.audioMime} action=video_only")
            }
            publish()
        }

        override fun onFailure(failure: PlayerFailure) {
            this@StreamController.onFailure(failure, s)
        }
    }

    companion object {
        const val MAX_DECODER_RETRIES = 2
    }
}
