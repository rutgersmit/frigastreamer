package io.github.rutgersmit.frigastreamer.domain

import kotlinx.serialization.Serializable

/** Handmatig toegevoegde camera. URL's mogen credentials bevatten (`rtsp://user:pass@...`). */
@Serializable
data class ManualCamera(
    val id: String,
    val name: String,
    val mainUrl: String,
    val subUrl: String? = null,
)

/** Gebruikersaanpassingen per camera-id (zowel automatisch als handmatig). */
@Serializable
data class CameraOverride(
    val hidden: Boolean = false,
    val preferSub: Boolean = false,
    /** Alleen voor automatische camera's: overschrijf de afgeleide URL's. */
    val mainUrl: String? = null,
    val subUrl: String? = null,
    val name: String? = null,
)

@Serializable
data class CameraConfig(
    val manual: List<ManualCamera> = emptyList(),
    val overrides: Map<String, CameraOverride> = emptyMap(),
    /** Gewenste volgorde (camera-id's). Onbekende id's worden genegeerd, nieuwe achteraan gezet. */
    val order: List<String> = emptyList(),
)

/** Camera plus de instellingen die het instellingenscherm nodig heeft. */
data class CameraEntry(val camera: Camera, val hidden: Boolean)

data class RtspEndpoint(val host: String, val port: Int) {
    fun urlFor(streamName: String): String {
        val h = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "rtsp://$h:$port/" + encodePath(streamName)
    }

    private fun encodePath(s: String): String = buildString {
        for (ch in s) {
            if (ch.isLetterOrDigit() || ch in "-_.~") append(ch)
            else ch.toString().toByteArray(Charsets.UTF_8).forEach { append("%%%02X".format(it)) }
        }
    }
}

object CameraListBuilder {
    fun autoId(name: String) = "auto:$name"

    /** Alle camera's (ook verborgen), in de gewenste volgorde. */
    fun build(auto: List<AutoCamera>, config: CameraConfig, endpoint: RtspEndpoint?): List<CameraEntry> {
        val entries = LinkedHashMap<String, CameraEntry>()
        if (endpoint != null) {
            for (a in auto) {
                val id = autoId(a.name)
                val o = config.overrides[id] ?: CameraOverride()
                val main = o.mainUrl?.takeIf { it.isNotBlank() } ?: endpoint.urlFor(a.mainStream)
                val sub = o.subUrl?.takeIf { it.isNotBlank() } ?: a.subStream?.let { endpoint.urlFor(it) }
                entries[id] = CameraEntry(
                    Camera(
                        id = id,
                        name = o.name?.takeIf { it.isNotBlank() } ?: a.name,
                        mainUrl = main,
                        subUrl = sub,
                        source = CameraSource.AUTO,
                        preferSub = o.preferSub,
                        // Een handmatig overschreven URL kan eigen credentials bevatten.
                        useGlobalRtspAuth = o.mainUrl.isNullOrBlank(),
                    ),
                    hidden = o.hidden,
                )
            }
        }
        for (m in config.manual) {
            val o = config.overrides[m.id] ?: CameraOverride()
            if (m.mainUrl.isBlank()) continue
            entries[m.id] = CameraEntry(
                Camera(
                    id = m.id,
                    name = m.name.ifBlank { "Camera" },
                    mainUrl = m.mainUrl.trim(),
                    subUrl = m.subUrl?.trim()?.takeIf { it.isNotEmpty() },
                    source = CameraSource.MANUAL,
                    preferSub = o.preferSub,
                    useGlobalRtspAuth = false,
                ),
                hidden = o.hidden,
            )
        }
        val ordered = ArrayList<CameraEntry>(entries.size)
        for (id in config.order) entries.remove(id)?.let { ordered += it }
        ordered += entries.values
        return ordered
    }

    fun visible(entries: List<CameraEntry>): List<Camera> = entries.filterNot { it.hidden }.map { it.camera }
}
