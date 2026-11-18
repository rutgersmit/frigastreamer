package io.github.rutgersmit.frigastreamer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import io.github.rutgersmit.frigastreamer.FrigaApp
import io.github.rutgersmit.frigastreamer.data.RefreshStatus
import io.github.rutgersmit.frigastreamer.domain.CameraConfig
import io.github.rutgersmit.frigastreamer.domain.CameraEntry
import io.github.rutgersmit.frigastreamer.domain.CameraListBuilder
import io.github.rutgersmit.frigastreamer.network.FrigateHealth
import io.github.rutgersmit.frigastreamer.network.NetworkStatus
import io.github.rutgersmit.frigastreamer.player.ExoStreamPlayerFactory
import io.github.rutgersmit.frigastreamer.settings.AppSettings
import io.github.rutgersmit.frigastreamer.stream.StreamManager

/**
 * Connects the data layer (settings, camera list, network) to the [StreamManager].
 * The UI itself does nothing with reconnects or RTSP connections.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as FrigaApp).graph
    private val log = graph.log

    val manager = StreamManager(viewModelScope, ExoStreamPlayerFactory(app, log), graph.clock, log)

    val settings: StateFlow<AppSettings> = graph.settings
    val entries: StateFlow<List<CameraEntry>> =
        graph.cameraRepository.entries.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val refreshStatus: StateFlow<RefreshStatus> = graph.cameraRepository.status
    val health: StateFlow<FrigateHealth> = graph.healthMonitor.health
    val network: StateFlow<NetworkStatus> = graph.networkMonitor.status

    private var foreground = false
    private var settingsOpen = false
    private var periodicRefresh: Job? = null
    private var persistJob: Job? = null
    private var initialCameraId: String? = null

    init {
        viewModelScope.launch {
            val s = graph.settingsRepository.settings.first()
            initialCameraId = s.lastCameraId
            val muted = if (s.startMuted) true else s.muted
            manager.setMuted(muted)
            if (muted != s.muted) graph.settingsRepository.setMuted(muted)

            launch {
                entries.map { CameraListBuilder.visible(it) }.distinctUntilChanged().collect { list ->
                    manager.setCameras(list, preferActiveId = initialCameraId)
                }
            }
            launch {
                settings.collect { st ->
                    manager.setPreload(st.preloadWindow)
                    manager.setConfig(st.recoveryConfig())
                    manager.setCredentials(st.rtspCredentials())
                }
            }
            launch {
                settings.map { Triple(it.host, it.apiPort, it.substreamPattern) }.distinctUntilChanged().collect {
                    if (it.first.isNotBlank()) {
                        graph.cameraRepository.refresh("settings_changed")
                        graph.healthMonitor.checkNow()
                    }
                }
            }
            launch {
                network.map { it.available }.distinctUntilChanged().collect { manager.onNetworkChanged(it) }
            }
        }
    }

    fun onForeground() {
        foreground = true
        updateRunning()
        graph.healthMonitor.start()
        periodicRefresh?.cancel()
        periodicRefresh = viewModelScope.launch {
            while (isActive) {
                graph.cameraRepository.refresh("periodic")
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    fun onBackground() {
        foreground = false
        updateRunning()
        graph.healthMonitor.stop()
        periodicRefresh?.cancel()
        periodicRefresh = null
    }

    fun setSettingsOpen(open: Boolean) {
        if (settingsOpen == open) return
        settingsOpen = open
        updateRunning()
    }

    private fun updateRunning() {
        if (foreground && !settingsOpen) manager.start() else manager.stop()
    }

    fun onPageSettled(cameraIndex: Int) {
        manager.setActiveIndex(cameraIndex)
        val id = manager.activeCamera()?.id ?: return
        initialCameraId = id
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(1_000)
            graph.settingsRepository.setLastCamera(id)
        }
    }

    fun toggleMute() {
        val muted = !manager.state.value.muted
        manager.setMuted(muted)
        viewModelScope.launch { graph.settingsRepository.setMuted(muted) }
    }

    fun retry(cameraId: String) = manager.retry(cameraId)

    fun refreshCameras() {
        viewModelScope.launch {
            graph.cameraRepository.refresh("manual")
            graph.healthMonitor.checkNow()
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            graph.frigateClient.invalidateToken()
            graph.settingsRepository.update(transform)
        }
    }

    fun updateCameraConfig(transform: (CameraConfig) -> CameraConfig) {
        viewModelScope.launch { graph.settingsRepository.updateCameraConfig(transform) }
    }

    override fun onCleared() {
        manager.dispose()
        super.onCleared()
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 5 * 60_000L
    }
}
