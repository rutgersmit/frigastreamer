package nl.frigastreamer.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.frigastreamer.domain.AutoCamera
import nl.frigastreamer.domain.CameraEntry
import nl.frigastreamer.domain.CameraListBuilder
import nl.frigastreamer.domain.RtspEndpoint
import nl.frigastreamer.domain.StreamMapper
import nl.frigastreamer.domain.SubstreamPattern
import nl.frigastreamer.domain.UnmatchedCamera
import nl.frigastreamer.settings.SettingsRepository
import nl.frigastreamer.util.Clock
import nl.frigastreamer.util.Logger

data class RefreshStatus(
    val inProgress: Boolean = false,
    val ok: Boolean? = null,
    val message: String = "Nog niet opgehaald",
    val streamCount: Int = 0,
    val cameraCount: Int = 0,
    val unmatched: List<UnmatchedCamera> = emptyList(),
    val lastAttemptWallMs: Long? = null,
    val lastSuccessWallMs: Long? = null,
    /** True als de getoonde lijst uit de cache komt omdat Frigate (nog) niet bereikbaar was. */
    val usingCache: Boolean = true,
)

/**
 * Bron van de cameralijst: automatisch (go2rtc + config) aangevuld met handmatige camera's
 * en gebruikersaanpassingen. De laatst bekende automatische lijst wordt in DataStore bewaard
 * en gebruikt zolang Frigate onbereikbaar is.
 */
class CameraRepository(
    private val settings: SettingsRepository,
    private val client: FrigateClient,
    private val clock: Clock,
    private val log: Logger,
) {
    private val mutex = Mutex()
    private val liveAuto = MutableStateFlow<List<AutoCamera>?>(null)
    private val _status = MutableStateFlow(RefreshStatus())
    val status: StateFlow<RefreshStatus> = _status.asStateFlow()

    private val autoCameras: Flow<List<AutoCamera>> =
        combine(liveAuto, settings.cachedAutoCameras) { live, cache -> live ?: cache }

    /** Alle camera's, inclusief verborgen, in de ingestelde volgorde. */
    val entries: Flow<List<CameraEntry>> = combine(settings.settings, settings.cameraConfig, autoCameras) { s, cfg, auto ->
        val endpoint = if (s.host.isNotBlank()) RtspEndpoint(s.host.trim(), s.rtspPort) else null
        CameraListBuilder.build(auto, cfg, endpoint)
    }.distinctUntilChanged()

    suspend fun refresh(reason: String): RefreshStatus = mutex.withLock {
        val s = settings.current()
        if (s.host.isBlank()) {
            return@withLock RefreshStatus(ok = false, message = "Stel eerst het IP-adres van Frigate in").also { _status.value = it }
        }
        _status.value = _status.value.copy(inProgress = true)
        val now = clock.wallMs()
        log.i(TAG, "event=camera_list_refresh reason=$reason")
        val streams = try {
            client.fetchStreamNames().also {
                // Een lege lijst komt in de praktijk voor tijdens een (her)start van go2rtc.
                // Behandel dat als storing, zodat de laatst bekende lijst behouden blijft.
                if (it.isEmpty()) throw FrigateApiException("go2rtc meldt (nog) geen streams")
            }
        } catch (e: Exception) {
            val msg = "Frigate-API onbereikbaar: ${e.message ?: e.javaClass.simpleName}"
            log.w(TAG, "event=camera_list_refresh_failed using_cache=true error=${e.javaClass.simpleName}")
            return@withLock _status.value.copy(
                inProgress = false, ok = false, message = msg, lastAttemptWallMs = now, usingCache = true,
            ).also { _status.value = it }
        }
        val configs = try {
            FrigateJson.parseCameras(client.fetchConfigBody())
        } catch (e: Exception) {
            log.w(TAG, "event=config_fetch_failed action=streams_only error=${e.javaClass.simpleName}")
            null
        }
        val result = StreamMapper.map(streams, configs.orEmpty(), SubstreamPattern(s.substreamPattern))
        for (u in result.unmatched) log.w(TAG, "event=camera_without_stream camera=${u.name} reason=\"${u.reason}\"")
        for (c in result.cameras) log.d(TAG, "event=camera_mapped camera=${c.name} main=${c.mainStream} sub=${c.subStream ?: "-"}")
        liveAuto.value = result.cameras
        settings.saveAutoCameras(result.cameras)
        val msg = buildString {
            append("${streams.size} go2rtc-streams, ${result.cameras.size} camera's")
            if (configs == null) append(" (config niet beschikbaar: alleen streamnamen gebruikt)")
            if (result.unmatched.isNotEmpty()) append(", ${result.unmatched.size} zonder stream")
        }
        log.i(TAG, "event=camera_list_refreshed streams=${streams.size} cameras=${result.cameras.size} unmatched=${result.unmatched.size}")
        RefreshStatus(
            inProgress = false, ok = true, message = msg, streamCount = streams.size,
            cameraCount = result.cameras.size, unmatched = result.unmatched,
            lastAttemptWallMs = now, lastSuccessWallMs = now, usingCache = false,
        ).also { _status.value = it }
    }

    companion object {
        private const val TAG = "FS.Api"
    }
}
