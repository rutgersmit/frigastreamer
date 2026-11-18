package nl.frigastreamer.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nl.frigastreamer.domain.Camera
import nl.frigastreamer.domain.PlayerRole
import nl.frigastreamer.domain.PlayerWindow
import nl.frigastreamer.domain.RecoveryConfig
import nl.frigastreamer.domain.RtspCredentials
import nl.frigastreamer.player.StreamPlayerFactory
import nl.frigastreamer.util.Clock
import nl.frigastreamer.util.Logger

/**
 * Beheert de [StreamController]s van alle camera's en het sliding window van actieve spelers.
 *
 * - Het venster is gebaseerd op camera-index, nooit op pager-pagina.
 * - Spelers buiten het venster worden EERST vrijgegeven, pas daarna worden nieuwe aangemaakt
 *   (belangrijk bij een beperkt aantal hardwaredecoders).
 * - De actieve camera heeft voorrang: buren worden pas voorgeladen als de actieve camera beeld
 *   heeft (of na [PRELOAD_GATE_MS]).
 * - Bij een decoder-initialisatiefout wordt het preload-venster voor de rest van de sessie verkleind.
 *
 * Alle methodes op de main thread aanroepen.
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

    /** Hoogste aantal gelijktijdige spelers ooit gezien (voor tests en debug-overlay). */
    var maxObservedPlayers = 0
        private set

    val effectivePreload: Int get() = minOf(configuredPreload, preloadCap)
    val playerCount: Int get() = controllers.values.count { it.hasPlayer }

    fun controller(id: String): StreamController? = controllers[id]

    fun activeCamera(): Camera? = cameras.getOrNull(activeIndex)

    // ------------------------------------------------------------------------------------

    /**
     * Nieuwe cameralijst (na refresh of wijziging in de instellingen). De actieve camera blijft
     * behouden als die nog bestaat; anders wordt [preferActiveId] gebruikt (laatst bekeken camera).
     */
    fun setCameras(list: List<Camera>, preferActiveId: String? = null) {
        if (disposed) return
        val keepId = activeId?.takeIf { id -> list.any { it.id == id } } ?: preferActiveId
        val newIds = list.map { it.id }.toSet()
        // Eerst verwijderde camera's opruimen (geeft spelers vrij).
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
        // LinkedHashMap-volgorde gelijk trekken met de lijst.
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

    /** App naar de voorgrond. Idempotent: herhaald aanroepen start geen extra spelers. */
    fun start() {
        if (started || disposed) return
        started = true
        log.i(TAG, "event=start cameras=${cameras.size}")
        // Bij terugkeer naar de voorgrond krijgen camera's met een permanente fout een nieuwe kans.
        controllers.values.filter { it.isError }.forEach { it.retry("app_foreground") }
        gateOpen = false
        applyWindow()
    }

    /** App naar de achtergrond / scherm uit. Alle spelers en RTSP-verbindingen worden vrijgegeven. */
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
        // Bij herstel eerst de actieve camera, daarna de buren.
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
    // Venster
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

        // 1. Eerst vrijgeven wat buiten het venster valt.
        for (c in controllers.values) {
            if (c.camera.id !in wanted && (c.role != null || c.hasPlayer)) c.detach("out_of_window")
        }

        // 2. Actieve camera.
        val activeCam = cameras[activeIndex]
        if (activeCam.id != activeId) gateOpen = false
        activeId = activeCam.id
        val active = controllers.getValue(activeCam.id)
        active.attach(PlayerRole.ACTIVE)

        // 3. Buren die al een speler hebben krijgen alleen een nieuwe rol (geen streamwissel).
        for (slot in slots) {
            if (slot.role != PlayerRole.PRELOAD) continue
            val c = controllers.getValue(cameras[slot.cameraIndex].id)
            if (c.role != null) c.attach(PlayerRole.PRELOAD)
        }
        applyAudio()
        enforceLimit()

        // 4. Nieuwe buren pas als de actieve camera beeld heeft (voorrang voor de actieve decoder).
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

    /** Vangnet: mag door de vensterlogica nooit nodig zijn. */
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
            log.w(TAG, "event=preload_window_reduced new_size=$newCap reason=$reason (geldt voor de rest van de sessie)")
        }
    }

    // ------------------------------------------------------------------------------------
    // StreamController.Host
    // ------------------------------------------------------------------------------------

    override fun rtspCredentials(): RtspCredentials? = credentials

    override fun onDecoderInitFailure(controller: StreamController): StreamController.DecoderDecision {
        if (controller.role != PlayerRole.ACTIVE) {
            // Een voorgeladen speler mag de actieve camera nooit raken: deze geven we gewoon op.
            reduceWindow("decoder_init_failed_on_preload camera=${controller.camera.name}")
            controller.detach("decoder_init_failed_preload")
            publish()
            return StreamController.DecoderDecision.DETACHED
        }
        val others = controllers.values.filter { it !== controller && it.hasPlayer }
        if (others.isEmpty()) {
            log.e(TAG, "event=decoder_init_failed_alone camera=${controller.camera.name} -> codecfout")
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
