package io.github.rutgersmit.frigastreamer.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rutgersmit.frigastreamer.BuildConfig
import io.github.rutgersmit.frigastreamer.domain.CameraConfig
import io.github.rutgersmit.frigastreamer.domain.CameraEntry
import io.github.rutgersmit.frigastreamer.domain.CameraOverride
import io.github.rutgersmit.frigastreamer.domain.CameraSource
import io.github.rutgersmit.frigastreamer.domain.ManualCamera
import io.github.rutgersmit.frigastreamer.domain.SubstreamPattern
import io.github.rutgersmit.frigastreamer.network.Reach
import io.github.rutgersmit.frigastreamer.service.HomeAppHelper
import io.github.rutgersmit.frigastreamer.settings.ApiMode
import io.github.rutgersmit.frigastreamer.settings.AppSettings
import io.github.rutgersmit.frigastreamer.settings.OrientationSetting
import io.github.rutgersmit.frigastreamer.ui.MainViewModel
import io.github.rutgersmit.frigastreamer.ui.formatTime
import io.github.rutgersmit.frigastreamer.util.Redact
import java.util.UUID

@Composable
fun SettingsScreen(vm: MainViewModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val settings by vm.settings.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val refresh by vm.refreshStatus.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<CameraEntry?>(null) }
    var adding by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Instellingen", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Button(onClick = onClose) { Text("Terug naar camera's") }
            }
            Text("Versie ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall)
        }

        item { FrigateSection(settings, onSave = { vm.updateSettings { _ -> it } }) }

        item {
            Section("Cameralijst") {
                val api = when (health.api) {
                    Reach.OK -> "bereikbaar${health.version?.let { " (Frigate $it)" } ?: ""}"
                    Reach.DOWN -> "onbereikbaar"
                    Reach.AUTH_FAILED -> "inloggen mislukt"
                    Reach.UNKNOWN -> "onbekend"
                }
                Text("Frigate-API: $api · RTSP-poort: ${health.rtsp.name.lowercase()}")
                Text(
                    refresh.message + (refresh.lastSuccessWallMs?.let { " · laatst gelukt ${formatTime(it)}" } ?: "") +
                        if (refresh.usingCache && refresh.ok == false) " · laatst bekende lijst wordt gebruikt" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (refresh.unmatched.isNotEmpty()) {
                    Text("Camera's zonder go2rtc-stream (niet getoond):", style = MaterialTheme.typography.bodySmall)
                    refresh.unmatched.forEach { Text(" • ${it.name}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::refreshCameras, enabled = !refresh.inProgress) {
                        Text(if (refresh.inProgress) "Bezig..." else "Cameralijst opnieuw ophalen")
                    }
                    OutlinedButton(onClick = { adding = true }) { Text("Camera handmatig toevoegen") }
                }
            }
        }

        itemsIndexed(entries, key = { _, e -> e.camera.id }) { index, entry ->
            CameraRow(
                entry = entry,
                canUp = index > 0,
                canDown = index < entries.size - 1,
                onMove = { delta -> vm.updateCameraConfig { cfg -> move(cfg, entries, index, delta) } },
                onToggleHidden = { hidden -> vm.updateCameraConfig { it.withOverride(entry.camera.id) { o -> o.copy(hidden = hidden) } } },
                onToggleSub = { sub -> vm.updateCameraConfig { it.withOverride(entry.camera.id) { o -> o.copy(preferSub = sub) } } },
                onEdit = { editing = entry },
                onDelete = {
                    vm.updateCameraConfig { cfg ->
                        cfg.copy(
                            manual = cfg.manual.filterNot { it.id == entry.camera.id },
                            overrides = cfg.overrides - entry.camera.id,
                            order = cfg.order - entry.camera.id,
                        )
                    }
                },
            )
        }

        item { RecoverySection(settings) { t -> vm.updateSettings(t) } }

        item {
            Section("Overig") {
                SwitchRow("Altijd gemute starten", settings.startMuted) { v -> vm.updateSettings { it.copy(startMuted = v) } }
                Text("Schermoriëntatie")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrientationSetting.entries.forEach { o ->
                        FilterChip(
                            selected = settings.orientation == o,
                            onClick = { vm.updateSettings { it.copy(orientation = o) } },
                            label = {
                                Text(
                                    when (o) {
                                        OrientationSetting.LANDSCAPE -> "Liggend"
                                        OrientationSetting.PORTRAIT -> "Staand"
                                        OrientationSetting.AUTO -> "Automatisch"
                                    },
                                )
                            },
                        )
                    }
                }
                SwitchRow("Debug-overlay tonen", settings.debugOverlay) { v -> vm.updateSettings { it.copy(debugOverlay = v) } }
                HorizontalDivider()
                Text(
                    if (HomeAppHelper.isDefaultHome(context)) "Deze app is nu de standaard home-app (start automatisch na reboot)."
                    else "Kies deze app als standaard home-app om hem na een reboot automatisch te laten starten.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { HomeAppHelper.openHomeSettings(context) }) { Text("Standaard home-app instellen / terugzetten") }
            }
        }
        item { Spacer(Modifier.height(48.dp)) }
    }

    editing?.let { e ->
        CameraEditDialog(
            initial = e,
            onDismiss = { editing = null },
            onSave = { name, main, sub ->
                editing = null
                vm.updateCameraConfig { cfg ->
                    if (e.camera.source == CameraSource.MANUAL) {
                        cfg.copy(manual = cfg.manual.map { if (it.id == e.camera.id) it.copy(name = name, mainUrl = main, subUrl = sub) else it })
                    } else {
                        cfg.withOverride(e.camera.id) { o ->
                            o.copy(
                                name = name,
                                // Alleen overschrijven als de gebruiker de URL echt wijzigde.
                                mainUrl = if (main == e.camera.mainUrl) o.mainUrl else main,
                                subUrl = if (sub == e.camera.subUrl) o.subUrl else sub,
                            )
                        }
                    }
                }
            },
        )
    }
    if (adding) {
        CameraEditDialog(
            initial = null,
            onDismiss = { adding = false },
            onSave = { name, main, sub ->
                adding = false
                vm.updateCameraConfig { cfg ->
                    cfg.copy(manual = cfg.manual + ManualCamera("manual:" + UUID.randomUUID(), name, main, sub))
                }
            },
        )
    }
}

private fun CameraConfig.withOverride(id: String, f: (CameraOverride) -> CameraOverride): CameraConfig =
    copy(overrides = overrides + (id to f(overrides[id] ?: CameraOverride())))

private fun move(cfg: CameraConfig, entries: List<CameraEntry>, index: Int, delta: Int): CameraConfig {
    val ids = entries.map { it.camera.id }.toMutableList()
    val target = index + delta
    if (target !in ids.indices) return cfg
    val id = ids.removeAt(index)
    ids.add(target, id)
    return cfg.copy(order = ids)
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun FrigateSection(settings: AppSettings, onSave: (AppSettings) -> Unit) {
    var host by rememberSaveable(settings.host) { mutableStateOf(settings.host) }
    var mode by rememberSaveable(settings.apiMode) { mutableStateOf(settings.apiMode) }
    var apiPort by rememberSaveable(settings.apiPort) { mutableStateOf(settings.apiPort.toString()) }
    var https by rememberSaveable(settings.apiHttps) { mutableStateOf(settings.apiHttps) }
    var trust by rememberSaveable(settings.apiTrustSelfSigned) { mutableStateOf(settings.apiTrustSelfSigned) }
    var apiUser by rememberSaveable(settings.apiUsername) { mutableStateOf(settings.apiUsername) }
    var apiPass by rememberSaveable(settings.apiPassword) { mutableStateOf(settings.apiPassword) }
    var rtspPort by rememberSaveable(settings.rtspPort) { mutableStateOf(settings.rtspPort.toString()) }
    var rtspUser by rememberSaveable(settings.rtspUsername) { mutableStateOf(settings.rtspUsername) }
    var rtspPass by rememberSaveable(settings.rtspPassword) { mutableStateOf(settings.rtspPassword) }
    var pattern by rememberSaveable(settings.substreamPattern) { mutableStateOf(settings.substreamPattern) }

    Section("Frigate") {
        OutlinedTextField(host, { host = it }, label = { Text("IP-adres of hostnaam (bv. 192.168.1.10)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("API-modus")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mode == ApiMode.INTERNAL, onClick = {
                mode = ApiMode.INTERNAL
                if (apiPort == AppSettings.DEFAULT_AUTH_PORT.toString()) apiPort = AppSettings.DEFAULT_INTERNAL_PORT.toString()
            }, label = { Text("Intern (poort 5000, geen login)") })
            FilterChip(mode == ApiMode.AUTHENTICATED, onClick = {
                mode = ApiMode.AUTHENTICATED
                if (apiPort == AppSettings.DEFAULT_INTERNAL_PORT.toString()) apiPort = AppSettings.DEFAULT_AUTH_PORT.toString()
            }, label = { Text("Geauthenticeerd (poort 8971, login)") })
        }
        NumberField("API-poort", apiPort) { apiPort = it }
        if (mode == ApiMode.AUTHENTICATED) {
            SwitchRow("HTTPS gebruiken (Frigate-standaard op 8971)", https) { https = it }
            if (https) SwitchRow("Self-signed certificaat van Frigate accepteren", trust) { trust = it }
            OutlinedTextField(apiUser, { apiUser = it }, label = { Text("API-gebruikersnaam") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                apiPass, { apiPass = it }, label = { Text("API-wachtwoord") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
            )
        }
        NumberField("RTSP-poort (go2rtc)", rtspPort) { rtspPort = it }
        OutlinedTextField(rtspUser, { rtspUser = it }, label = { Text("RTSP-gebruikersnaam (optioneel, go2rtc)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            rtspPass, { rtspPass = it }, label = { Text("RTSP-wachtwoord") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            pattern, { pattern = it }, label = { Text("Substream-naampatroon (standaard ${SubstreamPattern.DEFAULT})") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            onSave(
                settings.copy(
                    host = host.trim(),
                    apiMode = mode,
                    apiPort = apiPort.toIntOrNull()?.coerceIn(1, 65535) ?: settings.apiPort,
                    apiHttps = https,
                    apiTrustSelfSigned = trust,
                    apiUsername = apiUser.trim(),
                    apiPassword = apiPass,
                    rtspPort = rtspPort.toIntOrNull()?.coerceIn(1, 65535) ?: settings.rtspPort,
                    rtspUsername = rtspUser.trim(),
                    rtspPassword = rtspPass,
                    substreamPattern = SubstreamPattern.normalize(pattern),
                ),
            )
        }) { Text("Opslaan en cameralijst ophalen") }
    }
}

@Composable
private fun RecoverySection(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    Section("Herstel") {
        Text("Preload-venster (voorgeladen buurcamera's)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..2).forEach { n ->
                FilterChip(settings.preloadWindow == n, onClick = { update { it.copy(preloadWindow = n) } }, label = {
                    Text(
                        when (n) {
                            0 -> "0 (alleen actief)"
                            1 -> "1 (volgende)"
                            else -> "2 (volgende + vorige)"
                        },
                    )
                })
            }
        }
        IntSetting("Offline-drempel (seconden)", settings.offlineThresholdSec, 3..600) { v -> update { it.copy(offlineThresholdSec = v) } }
        SwitchRow("Frame-watchdog", settings.watchdogEnabled) { v -> update { it.copy(watchdogEnabled = v) } }
        IntSetting("Watchdog-time-out (seconden zonder nieuw frame)", settings.watchdogTimeoutSec, 2..120) { v -> update { it.copy(watchdogTimeoutSec = v) } }
        IntSetting("Reconnect-backoff minimum (seconden)", settings.backoffMinSec, 1..60) { v -> update { it.copy(backoffMinSec = v) } }
        IntSetting("Reconnect-backoff maximum (seconden)", settings.backoffMaxSec, 1..600) { v -> update { it.copy(backoffMaxSec = v) } }
    }
}

@Composable
private fun IntSetting(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    LaunchedEffect(text) {
        val v = text.toIntOrNull()
        if (v != null && v in range && v != value) {
            kotlinx.coroutines.delay(600)
            onChange(v)
        }
    }
    NumberField("$label [${range.first}-${range.last}]", text) { text = it }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { s -> onChange(s.filter { it.isDigit() }.take(5)) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.widthIn(min = 200.dp, max = 480.dp),
    )
}

@Composable
private fun CameraRow(
    entry: CameraEntry,
    canUp: Boolean,
    canDown: Boolean,
    onMove: (Int) -> Unit,
    onToggleHidden: (Boolean) -> Unit,
    onToggleSub: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val cam = entry.camera
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cam.name + if (entry.hidden) " (verborgen)" else "", style = MaterialTheme.typography.titleMedium)
                    Text(
                        (if (cam.source == CameraSource.AUTO) "automatisch" else "handmatig") + " · main: " + Redact.url(cam.mainUrl) +
                            (cam.subUrl?.let { " · sub: " + Redact.url(it) } ?: " · geen substream"),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
                TextButton(onClick = { onMove(-1) }, enabled = canUp) { Text("▲") }
                TextButton(onClick = { onMove(1) }, enabled = canDown) { Text("▼") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Zichtbaar")
                Switch(checked = !entry.hidden, onCheckedChange = { onToggleHidden(!it) })
                Spacer(Modifier.width(8.dp))
                Text("Substream als actief")
                Switch(checked = cam.preferSub, onCheckedChange = onToggleSub, enabled = cam.subUrl != null)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEdit) { Text("Bewerken") }
                if (cam.source == CameraSource.MANUAL) TextButton(onClick = onDelete) { Text("Verwijderen") }
            }
        }
    }
}

@Composable
private fun CameraEditDialog(initial: CameraEntry?, onDismiss: () -> Unit, onSave: (String, String, String?) -> Unit) {
    val cam = initial?.camera
    var name by remember { mutableStateOf(cam?.name ?: "") }
    var main by remember { mutableStateOf(cam?.mainUrl ?: "rtsp://192.168.1.10:8554/") }
    var sub by remember { mutableStateOf(cam?.subUrl ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Camera toevoegen" else "Camera bewerken") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Naam") }, singleLine = true)
                OutlinedTextField(main, { main = it }, label = { Text("RTSP-URL main stream") }, singleLine = true)
                OutlinedTextField(sub, { sub = it }, label = { Text("RTSP-URL substream (optioneel)") }, singleLine = true)
                if (cam?.source == CameraSource.AUTO) {
                    Text("Laat leeg/ongewijzigd om de automatisch bepaalde URL te houden.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && main.trim().startsWith("rtsp", ignoreCase = true),
                onClick = { onSave(name.trim(), main.trim(), sub.trim().ifEmpty { null }) },
            ) { Text("Opslaan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuleren") } },
    )
}
