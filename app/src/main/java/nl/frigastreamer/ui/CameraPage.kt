package nl.frigastreamer.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import nl.frigastreamer.R
import nl.frigastreamer.domain.Camera
import nl.frigastreamer.domain.CameraSource
import nl.frigastreamer.domain.ErrorCategory
import nl.frigastreamer.network.FrigateHealth
import nl.frigastreamer.network.Reach
import nl.frigastreamer.player.ExoStreamPlayer
import nl.frigastreamer.player.StreamPlayer
import nl.frigastreamer.stream.StreamController
import nl.frigastreamer.stream.StreamState
import nl.frigastreamer.stream.StreamStatus
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
                "Geen geluid: audiocodec niet ondersteund",
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
        Text("Verbinden...", color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun OfflineScreen(camera: Camera, status: StreamStatus, st: StreamState.Offline, health: FrigateHealth) {
    val now by rememberTicker()
    Box(Modifier.fillMaxSize().background(Color(0xD0101010)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 560.dp).padding(24.dp)) {
            Text(camera.name, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Camera offline", color = Color(0xFFFF8A80), fontSize = 22.sp)
            Spacer(Modifier.height(16.dp))
            Text(diagnose(camera, status, health), color = Color(0xFFDDDDDD), fontSize = 16.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            val last = status.lastFrameWallMs
            InfoLine("Laatste beeld", if (last != null) formatTime(last) else "nog geen beeld ontvangen")
            InfoLine(
                "Volgende poging",
                when {
                    status.waitingForNetwork -> "zodra het netwerk terug is"
                    st.connectingNow -> "bezig met verbinden..."
                    st.nextAttemptAtMs != null -> "over ${((st.nextAttemptAtMs - now).coerceAtLeast(0) + 999) / 1000} s (poging ${st.attempt + 1})"
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
            Text("Kan deze camera niet afspelen", color = Color(0xFFFFB74D), fontSize = 20.sp)
            Spacer(Modifier.height(12.dp))
            Text(st.category.label, color = Color(0xFFDDDDDD), fontSize = 16.sp, textAlign = TextAlign.Center)
            val hint = when {
                st.category.isCodecRelated ->
                    "Tip: laat go2rtc deze stream naar H.264/AAC transcoderen, bv. ffmpeg:${camera.name}#video=h264#audio=aac (zie README)."
                st.category == ErrorCategory.RTSP_AUTH -> "Controleer de RTSP-gebruikersnaam en het wachtwoord in de instellingen."
                else -> null
            }
            if (hint != null) {
                Spacer(Modifier.height(8.dp))
                Text(hint, color = Color(0xFFAAAAAA), fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(4.dp))
            Text("Automatisch opnieuw proberen staat uit voor dit type fout.", color = Color(0xFF888888), fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("Opnieuw proberen") }
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

/** Begrijpelijke oorzaak, met onderscheid Frigate / RTSP-server / camera / beeld / codec. */
fun diagnose(camera: Camera, status: StreamStatus, health: FrigateHealth): String {
    val err = status.lastError
    val frigateCamera = camera.source == CameraSource.AUTO && camera.useGlobalRtspAuth
    return when {
        status.waitingForNetwork || err == ErrorCategory.NETWORK_UNAVAILABLE -> "Geen netwerkverbinding"
        frigateCamera && health.api == Reach.DOWN && health.rtsp == Reach.DOWN -> "Frigate is niet bereikbaar"
        frigateCamera && health.rtsp == Reach.DOWN -> "Frigate is bereikbaar, maar de RTSP-server (go2rtc) niet"
        err == ErrorCategory.STALLED -> "Verbinding open, maar er komen geen nieuwe beelden binnen"
        err != null && err.isCodecRelated -> "Codec- of decoderprobleem"
        frigateCamera && health.rtsp == Reach.OK -> "Frigate is bereikbaar, maar deze camera niet (${err?.label?.lowercase() ?: "geen beeld"})"
        else -> err?.label ?: "Geen beeld"
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
