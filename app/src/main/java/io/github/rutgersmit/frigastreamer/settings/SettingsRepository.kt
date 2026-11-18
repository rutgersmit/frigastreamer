package io.github.rutgersmit.frigastreamer.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import io.github.rutgersmit.frigastreamer.domain.AutoCamera
import io.github.rutgersmit.frigastreamer.domain.CameraConfig
import kotlinx.serialization.Serializable
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Serializable
private data class CachedAutoCamera(val name: String, val main: String, val sub: String? = null)

/**
 * Alle persistente instellingen via Jetpack DataStore. Credentials staan bewust gewoon in
 * DataStore (LAN-app op een eigen apparaat; geen Keystore/EncryptedSharedPreferences).
 */
class SettingsRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private object K {
        val host = stringPreferencesKey("host")
        val apiMode = stringPreferencesKey("api_mode")
        val apiPort = intPreferencesKey("api_port")
        val apiHttps = booleanPreferencesKey("api_https")
        val apiTrust = booleanPreferencesKey("api_trust_self_signed")
        val apiUser = stringPreferencesKey("api_user")
        val apiPass = stringPreferencesKey("api_pass")
        val rtspPort = intPreferencesKey("rtsp_port")
        val rtspUser = stringPreferencesKey("rtsp_user")
        val rtspPass = stringPreferencesKey("rtsp_pass")
        val pattern = stringPreferencesKey("substream_pattern")
        val preload = intPreferencesKey("preload_window")
        val offline = intPreferencesKey("offline_threshold_sec")
        val watchdogOn = booleanPreferencesKey("watchdog_enabled")
        val watchdog = intPreferencesKey("watchdog_timeout_sec")
        val backoffMin = intPreferencesKey("backoff_min_sec")
        val backoffMax = intPreferencesKey("backoff_max_sec")
        val startMuted = booleanPreferencesKey("start_muted")
        val muted = booleanPreferencesKey("muted")
        val orientation = stringPreferencesKey("orientation")
        val debug = booleanPreferencesKey("debug_overlay")
        val lastCamera = stringPreferencesKey("last_camera_id")
        val cameraConfig = stringPreferencesKey("camera_config_json")
        val autoCache = stringPreferencesKey("auto_cameras_cache_json")
    }

    private val prefs: Flow<Preferences> = context.dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: Flow<AppSettings> = prefs.map { readFrom(it) }.distinctUntilChanged()

    val cameraConfig: Flow<CameraConfig> = prefs.map { p ->
        p[K.cameraConfig]?.let { runCatching { json.decodeFromString<CameraConfig>(it) }.getOrNull() } ?: CameraConfig()
    }.distinctUntilChanged()

    val cachedAutoCameras: Flow<List<AutoCamera>> = prefs.map { p ->
        p[K.autoCache]?.let { s ->
            runCatching { json.decodeFromString<List<CachedAutoCamera>>(s) }.getOrNull()
                ?.map { AutoCamera(it.name, it.main, it.sub) }
        } ?: emptyList()
    }.distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val old = readFrom(p)
            val s = transform(old)
            p[K.host] = s.host.trim()
            p[K.apiMode] = s.apiMode.name
            p[K.apiPort] = s.apiPort
            p[K.apiHttps] = s.apiHttps
            p[K.apiTrust] = s.apiTrustSelfSigned
            p[K.apiUser] = s.apiUsername
            p[K.apiPass] = s.apiPassword
            p[K.rtspPort] = s.rtspPort
            p[K.rtspUser] = s.rtspUsername
            p[K.rtspPass] = s.rtspPassword
            p[K.pattern] = s.substreamPattern
            p[K.preload] = s.preloadWindow.coerceIn(0, 2)
            p[K.offline] = s.offlineThresholdSec
            p[K.watchdogOn] = s.watchdogEnabled
            p[K.watchdog] = s.watchdogTimeoutSec
            p[K.backoffMin] = s.backoffMinSec
            p[K.backoffMax] = s.backoffMaxSec
            p[K.startMuted] = s.startMuted
            p[K.muted] = s.muted
            p[K.orientation] = s.orientation.name
            p[K.debug] = s.debugOverlay
            if (s.lastCameraId != null) p[K.lastCamera] = s.lastCameraId else p.remove(K.lastCamera)
        }
    }

    suspend fun setMuted(muted: Boolean) {
        context.dataStore.edit { it[K.muted] = muted }
    }

    suspend fun setLastCamera(id: String) {
        context.dataStore.edit { it[K.lastCamera] = id }
    }

    suspend fun updateCameraConfig(transform: (CameraConfig) -> CameraConfig) {
        context.dataStore.edit { p ->
            val old = p[K.cameraConfig]?.let { runCatching { json.decodeFromString<CameraConfig>(it) }.getOrNull() } ?: CameraConfig()
            p[K.cameraConfig] = json.encodeToString(CameraConfig.serializer(), transform(old))
        }
    }

    suspend fun saveAutoCameras(list: List<AutoCamera>) {
        context.dataStore.edit { p ->
            p[K.autoCache] = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(CachedAutoCamera.serializer()),
                list.map { CachedAutoCamera(it.name, it.mainStream, it.subStream) },
            )
        }
    }

    private fun readFrom(p: Preferences): AppSettings {
        val d = AppSettings()
        val mode = p[K.apiMode]?.let { runCatching { ApiMode.valueOf(it) }.getOrNull() } ?: d.apiMode
        return AppSettings(
            host = p[K.host] ?: d.host,
            apiMode = mode,
            apiPort = p[K.apiPort] ?: if (mode == ApiMode.AUTHENTICATED) AppSettings.DEFAULT_AUTH_PORT else d.apiPort,
            apiHttps = p[K.apiHttps] ?: d.apiHttps,
            apiTrustSelfSigned = p[K.apiTrust] ?: d.apiTrustSelfSigned,
            apiUsername = p[K.apiUser] ?: d.apiUsername,
            apiPassword = p[K.apiPass] ?: d.apiPassword,
            rtspPort = p[K.rtspPort] ?: d.rtspPort,
            rtspUsername = p[K.rtspUser] ?: d.rtspUsername,
            rtspPassword = p[K.rtspPass] ?: d.rtspPassword,
            substreamPattern = p[K.pattern] ?: d.substreamPattern,
            preloadWindow = p[K.preload] ?: d.preloadWindow,
            offlineThresholdSec = p[K.offline] ?: d.offlineThresholdSec,
            watchdogEnabled = p[K.watchdogOn] ?: d.watchdogEnabled,
            watchdogTimeoutSec = p[K.watchdog] ?: d.watchdogTimeoutSec,
            backoffMinSec = p[K.backoffMin] ?: d.backoffMinSec,
            backoffMaxSec = p[K.backoffMax] ?: d.backoffMaxSec,
            startMuted = p[K.startMuted] ?: d.startMuted,
            muted = p[K.muted] ?: d.muted,
            orientation = p[K.orientation]?.let { runCatching { OrientationSetting.valueOf(it) }.getOrNull() } ?: d.orientation,
            debugOverlay = p[K.debug] ?: d.debugOverlay,
            lastCameraId = p[K.lastCamera],
        )
    }
}
