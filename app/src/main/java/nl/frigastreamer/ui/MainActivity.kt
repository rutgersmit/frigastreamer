package nl.frigastreamer.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import nl.frigastreamer.settings.OrientationSetting
import nl.frigastreamer.ui.settings.SettingsScreen
import nl.frigastreamer.util.AndroidLogger

/**
 * Enige Activity. Lifecycle:
 * - ON_START  → streams starten (laatst geselecteerde camera + preload-venster).
 * - ON_STOP   → alle spelers en RTSP-verbindingen vrijgeven (scherm uit, andere app, standby).
 * ON_PAUSE/ON_RESUME worden bewust niet gebruikt om te stoppen: in multi-window/split-screen is de
 * app gepauzeerd maar zichtbaar en moet de stream doorlopen. start()/stop() zijn idempotent, dus
 * herhaald wisselen tussen voor- en achtergrond levert nooit dubbele spelers of reconnects op.
 */
class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        AndroidLogger.i(TAG, "event=lifecycle state=ON_START")
                        vm.onForeground()
                    }
                    Lifecycle.Event.ON_STOP -> {
                        AndroidLogger.i(TAG, "event=lifecycle state=ON_STOP")
                        vm.onBackground()
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        AndroidLogger.d(TAG, "event=lifecycle state=ON_RESUME")
                        hideSystemBars()
                    }
                    Lifecycle.Event.ON_PAUSE -> AndroidLogger.d(TAG, "event=lifecycle state=ON_PAUSE")
                    else -> Unit
                }
            },
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.settings.map { it.orientation }.distinctUntilChanged().collect { o ->
                    requestedOrientation = when (o) {
                        OrientationSetting.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        OrientationSetting.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                        OrientationSetting.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                    }
                }
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var showSettings by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(showSettings) {
                    vm.setSettingsOpen(showSettings)
                    if (!showSettings) hideSystemBars()
                }
                if (showSettings) {
                    SettingsScreen(vm, onClose = { showSettings = false })
                } else {
                    LiveScreen(vm, onOpenSettings = { showSettings = true })
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        private const val TAG = "FS.App"
    }
}
