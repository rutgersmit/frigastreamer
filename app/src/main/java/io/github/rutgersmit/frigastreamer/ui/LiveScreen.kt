package io.github.rutgersmit.frigastreamer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import io.github.rutgersmit.frigastreamer.R
import io.github.rutgersmit.frigastreamer.domain.Camera
import io.github.rutgersmit.frigastreamer.domain.PlayerWindow

private const val NAME_VISIBLE_MS = 2_000L
private const val CONTROLS_VISIBLE_MS = 5_000L

@Composable
fun LiveScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val state by vm.manager.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val refresh by vm.refreshStatus.collectAsStateWithLifecycle()
    val cameras = state.cameras

    if (cameras.isEmpty()) {
        EmptyState(
            message = when {
                !settings.isConfigured -> "Nog geen Frigate-server ingesteld."
                refresh.inProgress -> "Cameralijst ophalen..."
                else -> "Geen camera's gevonden.\n${refresh.message}"
            },
            onOpenSettings = onOpenSettings,
            onRefresh = vm::refreshCameras,
        )
        return
    }
    // Bij een gewijzigde cameralijst een nieuwe pager, gestart op de huidige camera.
    key(cameras.map { it.id }) {
        CameraPager(vm, cameras, state.activeIndex, state.muted, settings.debugOverlay, onOpenSettings)
    }
}

@Composable
private fun CameraPager(
    vm: MainViewModel,
    cameras: List<Camera>,
    startIndex: Int,
    muted: Boolean,
    debugOverlay: Boolean,
    onOpenSettings: () -> Unit,
) {
    val n = cameras.size
    val pagerState = rememberPagerState(initialPage = PlayerWindow.initialPage(n, startIndex)) {
        PlayerWindow.virtualPageCount(n)
    }
    val controllers by vm.manager.controllerMap.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()

    LaunchedEffect(pagerState, n) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            vm.onPageSettled(PlayerWindow.cameraIndexForPage(page, n))
        }
    }

    val currentIndex by remember(n) { derivedStateOf { PlayerWindow.cameraIndexForPage(pagerState.currentPage, n) } }
    var nameVisible by remember { mutableStateOf(true) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }

    LaunchedEffect(currentIndex) {
        nameVisible = true
        delay(NAME_VISIBLE_MS)
        nameVisible = false
    }
    LaunchedEffect(controlsVisible, interaction) {
        if (controlsVisible) {
            delay(CONTROLS_VISIBLE_MS)
            controlsVisible = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
                        interaction++
                    },
                    onLongPress = { onOpenSettings() },
                )
            },
    ) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            key = { it },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val index = PlayerWindow.cameraIndexForPage(page, n)
            val camera = cameras[index]
            val bound by remember(page, n) {
                derivedStateOf { PlayerWindow.boundPageFor(index, pagerState.currentPage, n) == page }
            }
            CameraPage(
                controller = controllers[camera.id],
                camera = camera,
                bound = bound,
                health = health,
                muted = muted,
                onRetry = { vm.retry(camera.id) },
            )
        }

        // Cameranaam, ~2 s na het wisselen.
        AnimatedVisibility(
            visible = nameVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
        ) {
            Text(
                cameras[currentIndex].name,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(Color(0x99000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }

        // Pagina-indicator, mute en instellingen: verdwijnen na enkele seconden, terug bij aanraken.
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${currentIndex + 1} / $n",
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .background(Color(0x99000000), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                RoundIconButton(
                    icon = if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
                    description = if (muted) "Geluid aan" else "Geluid uit",
                ) {
                    vm.toggleMute()
                    interaction++
                }
                RoundIconButton(icon = R.drawable.ic_settings, description = "Instellingen") { onOpenSettings() }
            }
        }

        if (debugOverlay) {
            DebugOverlay(vm, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
    }
}

@Composable
private fun RoundIconButton(icon: Int, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).background(Color(0x99000000), CircleShape),
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = Color.White)
    }
}

@Composable
private fun EmptyState(message: String, onOpenSettings: () -> Unit, onRefresh: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTapGestures(onLongPress = { onOpenSettings() })
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("FrigaStreamer", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(message, color = Color(0xFFBBBBBB), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onOpenSettings) { Text("Instellingen") }
                Button(onClick = onRefresh) { Text("Opnieuw ophalen") }
            }
        }
    }
}
