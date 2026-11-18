package io.github.rutgersmit.frigastreamer.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.github.rutgersmit.frigastreamer.domain.Camera
import io.github.rutgersmit.frigastreamer.domain.PlayerRole
import io.github.rutgersmit.frigastreamer.domain.PlayerWindow
import io.github.rutgersmit.frigastreamer.domain.RecoveryConfig
import io.github.rutgersmit.frigastreamer.domain.RtspCredentials
import io.github.rutgersmit.frigastreamer.player.StreamPlayerFactory
import io.github.rutgersmit.frigastreamer.util.Clock
import io.github.rutgersmit.frigastreamer.util.Logger

/**
 * Manages the [StreamController]s of all cameras and the sliding window of active players.
 *
 * - The window is based on camera index, never on pager page.
 * - Players outside the window are released FIRST; only then are new ones created
 *   (important with a limited number of hardware decoders).
 * - The active camera has priority: neighbours are only preloaded once the active camera has
 *   video (or after [PRELOAD_GATE_MS]).
 * - On a decoder initialization failure the preload window is reduced for the rest of the session.
 *
 * Call all methods on the main thread.
 */
class StreamManager(
    private val scope: CoroutineScope,
    private val playerFactory: StreamPlayerFactory,
    private val clock: Clock,
    private val log: Logger,
    initialConfig: RecoveryConfig = RecoveryConfig(),
) : StreamController.Host {

    data class State(
        val cameras: List<Camera> = emptyList(),
        val activeIndex: Int = 0,
        val configuredPreload: Int = 1,
        val effectivePreload: Int = 1,
        val autoReduced: Boolean = false,
        val playerCount: Int = 0,
        val started: Boolean = false,
        val muted: Boolean = true,
        val networkAvailable: Boolean = true,
    )

    private val controllers = LinkedHashMap<String, StreamController>()
    private val _controllers = MutableStateFlow<Map<String, StreamController>>(emptyMap())
    val controllerMap: StateFlow<Map<String, StreamController>> = _controllers.asStateFlow()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    var cameras: List<Camera> = emptyList()
        private set
    private var activeIndex = 0
    private var activeId: String? = null
    private var started = false
    private var muted = true
    private var configuredPreload = 1
    private var preloadCap = PlayerWindow.MAX_PRELOAD
    private var config = initialConfig
    private var networkAvailable = true
    private var credentials: RtspCredentials? = null
    private var gateOpen = false
    private var gateJob: Job? = null
    private var disposed = false

    /** Highest number of simultaneous players ever seen (for tests and the debug overlay). */
    var maxObservedPlayers = 0
        private set

    val effectivePreload: Int get() = minOf(configuredPreload, preloadCap)
    val playerCount: Int get() = controllers.values.count { it.hasPlayer }

    fun controller(id: String): StreamController? = controllers[id]

    fun activeCamera(): Camera? = cameras.getOrNull(activeIndex)

    // ------------------------------------------------------------------------------------

    /**
     * New camera list (after a refresh or a settings change). The active camera is kept if it
     * still exists; otherwise [preferActiveId] is used (last viewed camera).
     */
    fun setCameras(list: List<Camera>, preferActiveId: String? = null) {
        if (disposed) return
        val keepId = activeId?.takeIf { id -> list.any { it.id == id } } ?: preferActiveId
        val newIds = list.map { it.id }.toSet()
        // Clean up removed cameras first (releases players).
        val removed = controllers.keys.filter { it !in newIds }
        for (id in removed) {
            log.i(TAG, "event=camera_removed id=$id")
            controllers.remove(id)?.dispose()
        }
        for (cam in list) {
            val existing = controllers[cam.id]
            if (existing == null) {
                controllers[cam.id] = StreamController(cam, scope, playerFactory, clock, config, this, log).also {
                    it.onNetworkChanged(networkAvailable)
                    it.setMuted(true)
                }
            } else if (existing.camera != cam) {
                existing.updateCamera(cam)
            }
        }
        // Align the LinkedHashMap order with the list.
        val ordered = list.associate { it.id to controllers.getValue(it.id) }
        controllers.clear()
        controllers.putAll(ordered)
        cameras = list
        _controllers.value = LinkedHashMap(controllers)
        activeIndex = when {
            list.isEmpty() -> 0
            keepId != null && list.any { it.id == keepId } -> list.indexOfFirst { it.id == keepId }
            else -> activeIndex.coerceIn(0, list.size - 1)
        }
        applyWindow()
    }

    fun setActiveIndex(index: Int) {
        if (cameras.isEmpty()) return
        val i = Math.floorMod(index, cameras.size)
        if (i == activeIndex && cameras[i].id == activeId) return
        activeIndex = i
        log.d(TAG, "event=active_changed index=$i camera=${cameras[i].name}")
        applyWindow()
    }

    /** App to the foreground. Idempotent: repeated calls do not start extra players. */
    fun start() {
        if (started || disposed) return
        started = true
        log.i(TAG, "event=start cameras=${cameras.size}")
        // When returning to the foreground, cameras with a permanent error get another chance.
        controllers.values.filter { it.isError }.forEach { it.retry("app_foreground") }
        gateOpen = false
        applyWindow()
    }

    /** App to the background / screen off. All players and RTSP connections are released. */
    fun stop() {
        if (!started) return
        started = false
        gateJob?.cancel(); gateJob = null
        gateOpen = false
        log.i(TAG, "event=stop releasing_players=$playerCount")
        controllers.values.forEach { it.detach("app_background") }
        publish()
    }

    fun setMuted(value: Boolean) {
        muted = value
        applyAudio()
        publish()
    }

    fun setPreload(value: Int) {
        val v = value.coerceIn(0, PlayerWindow.MAX_PRELOAD)
        if (v == configuredPreload) return
        configuredPreload = v
        log.i(TAG, "event=preload_configured value=$v effective=$effectivePreload")
        applyWindow()
    }

    fun setConfig(newConfig: RecoveryConfig) {
        if (newConfig == config) return
        config = newConfig
        controllers.values.forEach { it.updateConfig(newConfig) }
    }

    fun setCredentials(value: RtspCredentials?) {
        if (value == credentials) return
        val hadBefore = credentials != null || value != null
        credentials = value
        if (hadBefore) controllers.values.filter { it.camera.useGlobalRtspAuth }.forEach { it.reconnectNow("rtsp_credentials_changed") }
    }

    fun onNetworkChanged(available: Boolean) {
        if (available == networkAvailable) return
        networkAvailable = available
        log.i(TAG, "event=network available=$available")
        // On recovery, the active camera first, then the neighbours.
        val active = activeCamera()?.let { controllers[it.id] }
        active?.onNetworkChanged(available)
        controllers.values.filter { it !== active }.forEach { it.onNetworkChanged(available) }
        publish()
    }

    fun retry(cameraId: String) {
        controllers[cameraId]?.retry()
    }

    fun dispose() {
        if (disposed) return
        stop()
        controllers.values.forEach { it.dispose() }
        controllers.clear()
        _controllers.value = emptyMap()
        disposed = true
    }

    // ------------------------------------------------------------------------------------
    // Window
    // ------------------------------------------------------------------------------------

    private fun applyWindow() {
        gateJob?.cancel(); gateJob = null
        if (!started || cameras.isEmpty()) {
            controllers.values.forEach { it.detach(if (!started) "not_started" else "no_cameras") }
            publish()
            return
        }
        val slots = PlayerWindow.compute(cameras.size, activeIndex, effectivePreload)
        val wanted = slots.associate { cameras[it.cameraIndex].id to it.role }

        // 1. First release whatever falls outside the window.
        for (c in controllers.values) {
            if (c.camera.id !in wanted && (c.role != null || c.hasPlayer)) c.detach("out_of_window")
        }

        // 2. Active camera.
        val activeCam = cameras[activeIndex]
        if (activeCam.id != activeId) gateOpen = false
        activeId = activeCam.id
        val active = controllers.getValue(activeCam.id)
        active.attach(PlayerRole.ACTIVE)

        // 3. Neighbours that already have a player only get a new role (no stream switch).
        for (slot in slots) {
            if (slot.role != PlayerRole.PRELOAD) continue
            val c = controllers.getValue(cameras[slot.cameraIndex].id)
            if (c.role != null) c.attach(PlayerRole.PRELOAD)
        }
        applyAudio()
        enforceLimit()

        // 4. New neighbours only once the active camera has video (priority for the active decoder).
        if (slots.any { it.role == PlayerRole.PRELOAD }) {
            if (gateOpen) {
                startPreloads()
            } else {
                gateJob = scope.launch {
                    withTimeoutOrNull(PRELOAD_GATE_MS) {
                        active.status.first { it.state is StreamState.Playing || it.state is StreamState.Error }
                    }
                    gateOpen = true
                    gateJob = null
                    startPreloads()
                }
            }
        }
        publish()
    }

    private fun startPreloads() {
        if (!started || cameras.isEmpty()) return
        val slots = PlayerWindow.compute(cameras.size, activeIndex, effectivePreload)
        for (slot in slots) {
            if (slot.role != PlayerRole.PRELOAD) continue
            val c = controllers.getValue(cameras[slot.cameraIndex].id)
            c.attach(PlayerRole.PRELOAD)
            c.ensurePreferredStream()
        }
        applyAudio()
        enforceLimit()
        publish()
    }

    private fun applyAudio() {
        val activeCamId = activeCamera()?.id
        for (c in controllers.values) c.setMuted(muted || c.camera.id != activeCamId)
    }

    /** Safety net: should never be needed given the window logic. */
    private fun enforceLimit() {
        var count = playerCount
        if (count <= PlayerWindow.MAX_PLAYERS) return
        log.e(TAG, "event=player_limit_exceeded count=$count")
        for (c in controllers.values) {
            if (count <= PlayerWindow.MAX_PLAYERS) break
            if (c.hasPlayer && c.role != PlayerRole.ACTIVE) {
                c.detach("player_limit")
                count--
            }
        }
    }

    private fun reduceWindow(reason: String) {
        val newCap = (effectivePreload - 1).coerceAtLeast(0)
        if (newCap < preloadCap) {
            preloadCap = newCap
            log.w(TAG, "event=preload_window_reduced new_size=$newCap reason=$reason scope=session")
        }
    }

    // ------------------------------------------------------------------------------------
    // StreamController.Host
    // ------------------------------------------------------------------------------------

    override fun rtspCredentials(): RtspCredentials? = credentials

    override fun onDecoderInitFailure(controller: StreamController): StreamController.DecoderDecision {
        if (controller.role != PlayerRole.ACTIVE) {
            // A preloaded player must never affect the active camera: we simply give this one up.
            reduceWindow("decoder_init_failed_on_preload camera=${controller.camera.name}")
            controller.detach("decoder_init_failed_preload")
            publish()
            return StreamController.DecoderDecision.DETACHED
        }
        val others = controllers.values.filter { it !== controller && it.hasPlayer }
        if (others.isEmpty()) {
            log.e(TAG, "event=decoder_init_failed_alone camera=${controller.camera.name} -> codec_error")
            return StreamController.DecoderDecision.GIVE_UP
        }
        reduceWindow("decoder_init_failed_on_active camera=${controller.camera.name}")
        log.w(TAG, "event=release_preloads_for_active count=${others.size}")
        others.forEach { it.detach("free_decoder_for_active") }
        gateOpen = false
        gateJob?.cancel()
        gateJob = scope.launch {
            withTimeoutOrNull(PRELOAD_GATE_MS) {
                controller.status.first { it.state is StreamState.Playing || it.state is StreamState.Error }
            }
            gateOpen = true
            gateJob = null
            startPreloads()
        }
        publish()
        return StreamController.DecoderDecision.RETRY_NOW
    }

    override fun onStatusChanged(controller: StreamController) {
        val count = playerCount
        if (count > maxObservedPlayers) maxObservedPlayers = count
        if (_state.value.playerCount != count) publish()
    }

    private fun publish() {
        val count = playerCount
        if (count > maxObservedPlayers) maxObservedPlayers = count
        _state.value = State(
            cameras = cameras,
            activeIndex = activeIndex,
            configuredPreload = configuredPreload,
            effectivePreload = effectivePreload,
            autoReduced = preloadCap < PlayerWindow.MAX_PRELOAD && preloadCap < configuredPreload,
            playerCount = count,
            started = started,
            muted = muted,
            networkAvailable = networkAvailable,
        )
    }

    companion object {
        private const val TAG = "FS.Manager"
        const val PRELOAD_GATE_MS = 3_000L
    }
}
