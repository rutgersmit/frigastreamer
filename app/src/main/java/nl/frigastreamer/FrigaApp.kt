package nl.frigastreamer

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.frigastreamer.data.CameraRepository
import nl.frigastreamer.data.FrigateClient
import nl.frigastreamer.network.AndroidNetworkMonitor
import nl.frigastreamer.network.FrigateHealthMonitor
import nl.frigastreamer.network.NetworkMonitor
import nl.frigastreamer.settings.AppSettings
import nl.frigastreamer.settings.SettingsRepository
import nl.frigastreamer.util.AndroidLogger
import nl.frigastreamer.util.Clock
import nl.frigastreamer.util.Logger
import nl.frigastreamer.util.SystemClockImpl

/** Eenvoudige handmatige dependency-container (geen DI-framework nodig). */
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
        // Frigate is weer bereikbaar: cameralijst bijwerken (nieuwe/verwijderde camera's).
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
