package com.rutgersmit.frigastreamer.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.rutgersmit.frigastreamer.data.FrigateApiException
import com.rutgersmit.frigastreamer.data.FrigateClient
import com.rutgersmit.frigastreamer.util.Clock
import com.rutgersmit.frigastreamer.util.Logger

enum class Reach { UNKNOWN, OK, DOWN, AUTH_FAILED }

data class FrigateHealth(
    val api: Reach = Reach.UNKNOWN,
    val rtsp: Reach = Reach.UNKNOWN,
    val version: String? = null,
    val apiError: String? = null,
    val checkedAtWallMs: Long? = null,
)

/**
 * Periodically checks whether the Frigate API and the RTSP port (go2rtc) are reachable, so the UI
 * can distinguish between "Frigate unreachable", "RTSP server unreachable" and "camera
 * unreachable". Does not affect the reconnect logic of the streams.
 */
class FrigateHealthMonitor(
    private val client: FrigateClient,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val log: Logger,
    private val onApiRecovered: () -> Unit,
) {
    private val _health = MutableStateFlow(FrigateHealth())
    val health: StateFlow<FrigateHealth> = _health.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                check()
                delay(if (_health.value.api == Reach.OK && _health.value.rtsp == Reach.OK) 30_000 else 10_000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun checkNow() {
        scope.launch { check() }
    }

    private suspend fun check() {
        val before = _health.value
        var version: String? = null
        var error: String? = null
        val api = try {
            version = client.fetchVersion()
            Reach.OK
        } catch (e: FrigateApiException) {
            error = e.message
            if (e.httpStatus == 401 || e.httpStatus == 403 || e.message?.contains("Inloggen") == true) Reach.AUTH_FAILED else Reach.DOWN
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            Reach.DOWN
        }
        val rtsp = if (client.probeRtsp()) Reach.OK else Reach.DOWN
        val now = FrigateHealth(api, rtsp, version ?: before.version, error, clock.wallMs())
        if (before.api != now.api || before.rtsp != now.rtsp) {
            log.i(TAG, "event=frigate_health api=${now.api} rtsp=${now.rtsp}")
        }
        _health.value = now
        if (before.api != Reach.OK && now.api == Reach.OK) onApiRecovered()
    }

    companion object {
        private const val TAG = "FS.Health"
    }
}
