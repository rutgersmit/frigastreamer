package io.github.rutgersmit.frigastreamer

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.rutgersmit.frigastreamer.data.CameraRepository
import io.github.rutgersmit.frigastreamer.data.FrigateClient
import io.github.rutgersmit.frigastreamer.network.AndroidNetworkMonitor
import io.github.rutgersmit.frigastreamer.network.FrigateHealthMonitor
import io.github.rutgersmit.frigastreamer.network.NetworkMonitor
import io.github.rutgersmit.frigastreamer.settings.AppSettings
import io.github.rutgersmit.frigastreamer.settings.SettingsRepository
import io.github.rutgersmit.frigastreamer.util.AndroidLogger
import io.github.rutgersmit.frigastreamer.util.Clock
import io.github.rutgersmit.frigastreamer.util.Logger
import io.github.rutgersmit.frigastreamer.util.SystemClockImpl

/** Simple manual dependency container (no DI framework needed). */
class AppGraph(app: Application) {
    val log: Logger = AndroidLogger
    val clock: Clock = SystemClockImpl
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settingsRepository = SettingsRepository(app)
    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(appScope, SharingStarted.Eagerly, AppSettings())
    val frigateClient = FrigateClient({ settings.value }, log)
    val cameraRepository = CameraRepository(settingsRepository, frigateClient, clock, log)
    val networkMonitor: NetworkMonitor = AndroidNetworkMonitor(app, log)
    val healthMonitor = FrigateHealthMonitor(frigateClient, appScope, clock, log) {
        // Frigate is reachable again: refresh the camera list (new/removed cameras).
        appScope.launch { cameraRepository.refresh("api_recovered") }
    }
}

class FrigaApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.log.i("FS.App", "event=app_start version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE}")
    }
}
