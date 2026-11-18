package nl.frigastreamer.domain

/** Een go2rtc-stream die in de Frigate-config bij een camera-input hoort. */
data class InputRef(val streamName: String, val roles: List<String>)

/** Het relevante deel van een camera uit `/api/config`. */
data class FrigateCameraConfig(
    val name: String,
    val enabled: Boolean = true,
    val inputs: List<InputRef> = emptyList(),
    /** Streamnamen uit `live.streams` (0.16+) of `live.stream_name` (oudere versies), in volgorde. */
    val liveStreams: List<String> = emptyList(),
)

/** Een automatisch gevonden camera: namen van go2rtc-streams (geen URL's). */
data class AutoCamera(val name: String, val mainStream: String, val subStream: String?)

data class UnmatchedCamera(val name: String, val reason: String)

data class MappingResult(
    val cameras: List<AutoCamera>,
    val unmatched: List<UnmatchedCamera>,
)

/**
 * Koppelt go2rtc-streams (bron van waarheid voor wat via RTSP bereikbaar is) aan
 * Frigate-camera's uit `/api/config`.
 *
 * Regels:
 * 1. Main stream van camera X: stream met exact de naam X; anders de eerste `live`-stream;
 *    anders de input met rol `record`; anders een andere input die naar go2rtc verwijst.
 * 2. Substream: stream volgens het substream-patroon (standaard `X_sub`); anders een input
 *    met rol `detect` die niet de main stream is; anders een andere `live`-stream.
 * 3. Streams die aan geen camera hangen worden losse camera's (met eventueel hun eigen
 *    `_sub`-variant als substream).
 * 4. Camera's zonder bijbehorende go2rtc-stream worden niet getoond maar in [MappingResult.unmatched] gemeld.
 */
object StreamMapper {

    fun map(
        go2rtcStreams: List<String>,
        cameras: List<FrigateCameraConfig>,
        pattern: SubstreamPattern,
    ): MappingResult {
        val available = LinkedHashSet(go2rtcStreams)
        val claimed = HashSet<String>()
        val result = ArrayList<AutoCamera>()
        val unmatched = ArrayList<UnmatchedCamera>()

        for (cam in cameras) {
            if (!cam.enabled) {
                unmatched += UnmatchedCamera(cam.name, "camera staat uit in Frigate (enabled: false)")
                continue
            }
            val fromInputs = cam.inputs.filter { it.streamName in available }
            val main = when {
                cam.name in available -> cam.name
                else -> cam.liveStreams.firstOrNull { it in available }
                    ?: fromInputs.firstOrNull { "record" in it.roles }?.streamName
                    ?: fromInputs.firstOrNull()?.streamName
            }
            if (main == null) {
                val reason = if (cam.inputs.isEmpty() && cam.liveStreams.isEmpty()) {
                    "geen go2rtc-stream met naam '${cam.name}' (camera gebruikt go2rtc niet)"
                } else {
                    "geen van de gerefereerde streams bestaat in go2rtc"
                }
                unmatched += UnmatchedCamera(cam.name, reason)
                continue
            }
            val patternSub = pattern.subNameFor(cam.name).takeIf { it in available && it != main }
            val sub = patternSub
                ?: pattern.subNameFor(main).takeIf { it in available && it != main }
                ?: fromInputs.firstOrNull { "detect" in it.roles && it.streamName != main }?.streamName
                ?: cam.liveStreams.firstOrNull { it in available && it != main }
            claimed += main
            sub?.let { claimed += it }
            result += AutoCamera(cam.name, main, sub)
        }

        // Losse streams: niet aan een camera gekoppeld.
        val loose = available.filter { it !in claimed }
        val looseSet = loose.toSet()
        val usedAsSub = HashSet<String>()
        for (name in loose) {
            val base = pattern.mainNameFor(name)
            if (base != null && base in looseSet) usedAsSub += name
        }
        for (name in loose) {
            if (name in usedAsSub) continue
            val sub = pattern.subNameFor(name).takeIf { it in usedAsSub }
            result += AutoCamera(name, name, sub)
        }
        return MappingResult(result, unmatched)
    }
}
