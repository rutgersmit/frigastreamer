package com.rutgersmit.frigastreamer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rutgersmit.frigastreamer.BuildConfig
import com.rutgersmit.frigastreamer.stream.StreamState

/**
 * Debug overlay: the main tool when testing on the real tablet.
 * Developer diagnostics only, so deliberately English and not localized.
 */
@Composable
fun DebugOverlay(vm: MainViewModel, modifier: Modifier = Modifier) {
    val now by rememberTicker(500)
    val state by vm.manager.state.collectAsStateWithLifecycle()
    val network by vm.network.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val controllers by vm.manager.controllerMap.collectAsStateWithLifecycle()

    val lines = buildList {
        add("FrigaStreamer ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        add(
            "players: ${state.playerCount}/3 (max seen ${vm.manager.maxObservedPlayers})  " +
                "preload: ${state.effectivePreload} (configured ${state.configuredPreload}${if (state.autoReduced) ", AUTO-REDUCED" else ""})",
        )
        add("network: ${if (network.available) "ok" else "DOWN"} [${network.description}]  frigate api=${health.api} rtsp=${health.rtsp}")
        add("active: ${state.activeIndex + 1}/${state.cameras.size}  started=${state.started}  mute=${state.muted}  t=${now / 1000}")
        controllers.values.map { it.snapshot() }.filter { it.role != null || it.hasPlayer || it.state is StreamState.Error }.forEach { s ->
            val since = s.lastFrameElapsedMs?.let { "${(now - it).coerceAtLeast(0)}ms" } ?: "-"
            val st = when (val x = s.state) {
                is StreamState.Connecting -> "Connecting#${x.attempt}"
                is StreamState.Reconnecting -> "Reconnecting#${x.attempt} ${x.reason}"
                is StreamState.Offline -> "Offline#${x.attempt} ${x.reason ?: ""}"
                is StreamState.Error -> "Error ${x.category}"
                else -> x.toString()
            }
            val next = s.currentBackoffMs?.let { b ->
                val left = when (val x = s.state) {
                    is StreamState.Reconnecting -> x.nextAttemptAtMs
                    is StreamState.Offline -> x.nextAttemptAtMs
                    else -> null
                }?.let { ((it - now).coerceAtLeast(0) / 100) / 10.0 }
                " backoff=${b / 1000}s next=${left ?: "-"}s"
            } ?: ""
            val track = s.trackInfo?.let { " ${it.videoMime?.removePrefix("video/") ?: "?"} ${it.width}x${it.height}${if (!it.audioSupported) " audio✗" else ""}" } ?: ""
            add("• ${s.cameraName} [${s.role ?: "-"}] ${s.kind ?: "-"} p#${s.playerInstanceId ?: "-"} $st frame=$since$next$track${if (s.buffering) " buf" else ""} n=${s.totalConnects}")
        }
    }
    Column(
        modifier
            .widthIn(max = 720.dp)
            .background(Color(0xB0000000), RoundedCornerShape(6.dp))
            .padding(8.dp),
    ) {
        lines.forEach { Text(it, color = Color(0xFF7CFC00), fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 13.sp) }
    }
}
