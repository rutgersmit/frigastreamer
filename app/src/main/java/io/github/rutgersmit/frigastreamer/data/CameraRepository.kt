package io.github.rutgersmit.frigastreamer.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.rutgersmit.frigastreamer.domain.AutoCamera
import io.github.rutgersmit.frigastreamer.domain.CameraEntry
import io.github.rutgersmit.frigastreamer.domain.CameraListBuilder
import io.github.rutgersmit.frigastreamer.domain.RtspEndpoint
import io.github.rutgersmit.frigastreamer.domain.StreamMapper
import io.github.rutgersmit.frigastreamer.domain.SubstreamPattern
import io.github.rutgersmit.frigastreamer.domain.UnmatchedCamera
import io.github.rutgersmit.frigastreamer.settings.SettingsRepository
import io.github.rutgersmit.frigastreamer.util.Clock
import io.github.rutgersmit.frigastreamer.util.Logger

data class RefreshStatus(
    val inProgress: Boolean = false,
    /** null = not fetched yet, true = last refresh succeeded, false = last refresh failed. */
    val ok: Boolean? = null,
    /** True when no Frigate host has been configured yet. */
    val notConfigured: Boolean = false,
    /** Technical error detail of the last failed refresh (English, not localized). */
    val errorDetail: String? = null,
    /** True when `/api/config` could not be read and only go2rtc stream names were used. */
    val configUnavailable: Boolean = false,
    val streamCount: Int = 0,
    val cameraCount: Int = 0,
    val unmatched: List<UnmatchedCamera> = emptyList(),
    val lastAttemptWallMs: Long? = null,
    val lastSuccessWallMs: Long? = null,
    /** True when the list shown comes from the cache because Frigate was not reachable (yet). */
    val usingCache: Boolean = true,
)

/**
 * Source of the camera list: automatic (go2rtc + config), supplemented with manual cameras
 * and user overrides. The last known automatic list is persisted in DataStore and used
 * while Frigate is unreachable.
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

    /** All cameras, including hidden ones, in the configured order. */
    val entries: Flow<List<CameraEntry>> = combine(settings.settings, settings.cameraConfig, autoCameras) { s, cfg, auto ->
        val endpoint = if (s.host.isNotBlank()) RtspEndpoint(s.host.trim(), s.rtspPort) else null
        CameraListBuilder.build(auto, cfg, endpoint)
    }.distinctUntilChanged()

    suspend fun refresh(reason: String): RefreshStatus = mutex.withLock {
        val s = settings.current()
        if (s.host.isBlank()) {
            return@withLock RefreshStatus(ok = false, notConfigured = true).also { _status.value = it }
        }
        _status.value = _status.value.copy(inProgress = true)
        val now = clock.wallMs()
        log.i(TAG, "event=camera_list_refresh reason=$reason")
        val streams = try {
            client.fetchStreamNames().also {
                // An empty list happens in practice while go2rtc is (re)starting.
                // Treat it as a failure, so the last known list is kept.
                if (it.isEmpty()) throw FrigateApiException("go2rtc reports no streams (yet)")
            }
        } catch (e: Exception) {
            log.w(TAG, "event=camera_list_refresh_failed using_cache=true error=${e.javaClass.simpleName}")
            return@withLock _status.value.copy(
                inProgress = false, ok = false, errorDetail = e.message ?: e.javaClass.simpleName, lastAttemptWallMs = now, usingCache = true,
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
        log.i(TAG, "event=camera_list_refreshed streams=${streams.size} cameras=${result.cameras.size} unmatched=${result.unmatched.size}")
        RefreshStatus(
            inProgress = false, ok = true, configUnavailable = configs == null, streamCount = streams.size,
            cameraCount = result.cameras.size, unmatched = result.unmatched,
            lastAttemptWallMs = now, lastSuccessWallMs = now, usingCache = false,
        ).also { _status.value = it }
    }

    companion object {
        private const val TAG = "FS.Api"
    }
}
