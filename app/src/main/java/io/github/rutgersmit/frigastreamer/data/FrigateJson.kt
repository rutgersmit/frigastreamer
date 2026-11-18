package io.github.rutgersmit.frigastreamer.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import io.github.rutgersmit.frigastreamer.domain.FrigateCameraConfig
import io.github.rutgersmit.frigastreamer.domain.InputRef

/** Parsen van Frigate/go2rtc-JSON. Pure Kotlin, dus in JVM-unit-tests te testen. */
object FrigateJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Verwijzing naar de lokale go2rtc-restream, bv. `rtsp://127.0.0.1:8554/voordeur?video=copy`. */
    private val go2rtcPath = Regex(
        """^rtsp://(?:[^@/]*@)?(?:127\.0\.0\.1|localhost|0\.0\.0\.0|\[::1]):8554/([^?#/]+)""",
        RegexOption.IGNORE_CASE,
    )

    /** `/api/go2rtc/streams` geeft een object met de streamnamen als keys. */
    fun parseStreamNames(body: String): List<String> {
        val root = json.parseToJsonElement(body)
        require(root is JsonObject) { "verwacht een JSON-object van /api/go2rtc/streams" }
        return root.keys.toList()
    }

    fun parseCameras(body: String): List<FrigateCameraConfig> {
        val root = json.parseToJsonElement(body)
        require(root is JsonObject) { "verwacht een JSON-object van /api/config" }
        val cameras = root["cameras"] as? JsonObject ?: return emptyList()
        return cameras.map { (name, el) ->
            val cam = el as? JsonObject ?: JsonObject(emptyMap())
            val enabled = (cam["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
            val inputs = ((cam["ffmpeg"] as? JsonObject)?.get("inputs") as? JsonArray).orEmpty().mapNotNull { inEl ->
                val input = inEl as? JsonObject ?: return@mapNotNull null
                val path = (input["path"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val stream = go2rtcStreamName(path) ?: return@mapNotNull null
                val roles = (input["roles"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                InputRef(stream, roles)
            }
            val live = cam["live"] as? JsonObject
            val liveStreams = buildList {
                (live?.get("streams") as? JsonObject)?.values?.forEach { v ->
                    (v as? JsonPrimitive)?.contentOrNull?.let { add(it) }
                }
                (live?.get("stream_name") as? JsonPrimitive)?.contentOrNull?.let { if (it !in this) add(it) }
            }
            FrigateCameraConfig(name, enabled, inputs, liveStreams)
        }
    }

    fun parseVersion(body: String): String = body.trim().trim('"').take(40)

    fun go2rtcStreamName(path: String): String? =
        go2rtcPath.find(path.trim())?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
}
