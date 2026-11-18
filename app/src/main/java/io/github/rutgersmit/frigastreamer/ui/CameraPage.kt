package io.github.rutgersmit.frigastreamer.ui

import android.os.SystemClock
import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import io.github.rutgersmit.frigastreamer.R
import io.github.rutgersmit.frigastreamer.domain.Camera
import io.github.rutgersmit.frigastreamer.domain.CameraSource
import io.github.rutgersmit.frigastreamer.domain.ErrorCategory
import io.github.rutgersmit.frigastreamer.network.FrigateHealth
import io.github.rutgersmit.frigastreamer.network.Reach
import io.github.rutgersmit.frigastreamer.player.ExoStreamPlayer
import io.github.rutgersmit.frigastreamer.player.StreamPlayer
import io.github.rutgersmit.frigastreamer.stream.StreamController
import io.github.rutgersmit.frigastreamer.stream.StreamState
import io.github.rutgersmit.frigastreamer.stream.StreamStatus
import java.text.DateFormat
import java.util.Date

@Composable
fun CameraPage(
    controller: StreamController?,
    camera: Camera,
    bound: Boolean,
    health: FrigateHealth,
    muted: Boolean,
    onRetry: () -> Unit,
) {
    val fallbackStatus = remember(camera.id) { mutableStateOf(StreamStatus(camera.id, camera.name)) }
    val fallbackPlayer = remember { mutableStateOf<StreamPlayer?>(null) }
    val status by (controller?.status?.collectAsState() ?: fallbackStatus as State<StreamStatus>)
    val player by (controller?.player?.collectAsState() ?: fallbackPlayer as State<StreamPlayer?>)
    val exo = (player as? ExoStreamPlayer)?.exoPlayer

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> LayoutInflater.from(ctx).inflate(R.layout.player_view, null, false) as PlayerView },
            update = { view ->
                val target = if (bound) exo else null
                if (view.player !== target) view.player = target
            },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        val neverHadFrame = status.lastFrameWallMs == null
        when (val st = status.state) {
            is StreamState.Connecting, is StreamState.Reconnecting -> {
                if (neverHadFrame) CenterName(camera.name, showSpinner = true)
                ConnectingPill(Modifier.align(Alignment.BottomStart).padding(16.dp))
            }
            is StreamState.Offline -> OfflineScreen(camera, status, st, health)
            is StreamState.Error -> ErrorScreen(camera, st, onRetry)
            StreamState.Idle -> if (neverHadFrame) CenterName(camera.name, showSpinner = false)
            StreamState.Playing -> Unit
        }
        if (status.audioUnsupported && !muted && status.state is StreamState.Playing) {
            Text(
                stringResource(R.string.status_audio_unsupported),
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp)
                    .background(Color(0x99000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun CenterName(name: String, showSpinner: Boolean) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, color = Color(0xFFDDDDDD), fontSize = 22.sp)
        if (showSpinner) {
            Spacer(Modifier.height(16.dp))
            CircularProgressIndicator(color = Color(0xFF888888), strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun ConnectingPill(modifier: Modifier) {
    Row(
        modifier
            .background(Color(0x99000000), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        Spacer(Modifier.size(8.dp))
        Text(stringResource(R.string.status_connecting), color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun OfflineScreen(camera: Camera, status: StreamStatus, st: StreamState.Offline, health: FrigateHealth) {
    val now by rememberTicker()
    Box(Modifier.fillMaxSize().background(Color(0xD0101010)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 560.dp).padding(24.dp)) {
            Text(camera.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.status_camera_offline), color = Color(0xFFFF8A80), fontSize = 22.sp)
            Spacer(Modifier.height(16.dp))
            Text(diagnose(camera, status, health), color = Color(0xFFDDDDDD), fontSize = 16.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            val last = status.lastFrameWallMs
            InfoLine(
                stringResource(R.string.info_last_frame),
                if (last != null) formatTime(last) else stringResource(R.string.info_no_frame_yet),
            )
            InfoLine(
                stringResource(R.string.info_next_attempt),
                when {
                    status.waitingForNetwork -> stringResource(R.string.info_when_network_returns)
                    st.connectingNow -> stringResource(R.string.info_connecting_now)
                    st.nextAttemptAtMs != null -> stringResource(
                        R.string.info_next_attempt_in,
                        (((st.nextAttemptAtMs - now).coerceAtLeast(0) + 999) / 1000).toInt(),
                        st.attempt + 1,
                    )
                    else -> "-"
                },
            )
        }
    }
}

@Composable
private fun ErrorScreen(camera: Camera, st: StreamState.Error, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xE0101010)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 600.dp).padding(24.dp)) {
            Text(camera.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.error_cannot_play), color = Color(0xFFFFB74D), fontSize = 20.sp)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(st.category.labelRes()), color = Color(0xFFDDDDDD), fontSize = 16.sp, textAlign = TextAlign.Center)
            val hint = when {
                st.category.isCodecRelated -> stringResource(R.string.error_hint_codec, camera.name)
                st.category == ErrorCategory.RTSP_AUTH -> stringResource(R.string.error_hint_auth)
                else -> null
            }
            if (hint != null) {
                Spacer(Modifier.height(8.dp))
                Text(hint, color = Color(0xFFAAAAAA), fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.error_auto_retry_disabled), color = Color(0xFF888888), fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", color = Color(0xFF999999), fontSize = 15.sp)
        Text(value, color = Color(0xFFDDDDDD), fontSize = 15.sp)
    }
}

/** Understandable cause, distinguishing Frigate / RTSP server / camera / frames / codec. */
@Composable
fun diagnose(camera: Camera, status: StreamStatus, health: FrigateHealth): String {
    val err = status.lastError
    val frigateCamera = camera.source == CameraSource.AUTO && camera.useGlobalRtspAuth
    return when {
        status.waitingForNetwork || err == ErrorCategory.NETWORK_UNAVAILABLE -> stringResource(R.string.diag_no_network)
        frigateCamera && health.api == Reach.DOWN && health.rtsp == Reach.DOWN -> stringResource(R.string.diag_frigate_unreachable)
        frigateCamera && health.rtsp == Reach.DOWN -> stringResource(R.string.diag_rtsp_unreachable)
        err == ErrorCategory.STALLED -> stringResource(R.string.diag_stalled)
        err != null && err.isCodecRelated -> stringResource(R.string.diag_codec)
        frigateCamera && health.rtsp == Reach.OK -> stringResource(
            R.string.diag_camera_unreachable,
            stringResource(err?.labelRes() ?: R.string.diag_no_image).lowercase(),
        )
        else -> stringResource(err?.labelRes() ?: R.string.diag_no_image)
    }
}

@Composable
fun rememberTicker(periodMs: Long = 500): State<Long> = produceState(SystemClock.elapsedRealtime()) {
    while (true) {
        value = SystemClock.elapsedRealtime()
        delay(periodMs)
    }
}

fun formatTime(wallMs: Long): String {
    val today = System.currentTimeMillis() - wallMs < 12 * 3_600_000L
    val fmt = if (today) DateFormat.getTimeInstance(DateFormat.MEDIUM) else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
    return fmt.format(Date(wallMs))
}
