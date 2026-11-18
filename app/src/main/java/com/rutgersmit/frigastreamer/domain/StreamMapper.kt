package com.rutgersmit.frigastreamer.domain

/** A go2rtc stream that belongs to a camera input in the Frigate config. */
data class InputRef(val streamName: String, val roles: List<String>)

/** The relevant part of a camera from `/api/config`. */
data class FrigateCameraConfig(
    val name: String,
    val enabled: Boolean = true,
    val inputs: List<InputRef> = emptyList(),
    /** Stream names from `live.streams` (0.16+) or `live.stream_name` (older versions), in order. */
    val liveStreams: List<String> = emptyList(),
)

/** An automatically discovered camera: names of go2rtc streams (not URLs). */
data class AutoCamera(val name: String, val mainStream: String, val subStream: String?)

/** Why a Frigate camera could not be mapped to a go2rtc stream. */
enum class UnmatchReason {
    /** The camera is disabled in Frigate (`enabled: false`). */
    DISABLED,

    /** No go2rtc stream with the camera's name, and the camera does not reference go2rtc. */
    NO_GO2RTC_STREAM,

    /** The camera references go2rtc streams, but none of them exist in go2rtc. */
    REFERENCED_STREAMS_MISSING,
}

data class UnmatchedCamera(val name: String, val reason: UnmatchReason)

data class MappingResult(
    val cameras: List<AutoCamera>,
    val unmatched: List<UnmatchedCamera>,
)

/**
 * Maps go2rtc streams (the source of truth for what is reachable over RTSP) to
 * Frigate cameras from `/api/config`.
 *
 * Rules:
 * 1. Main stream of camera X: the stream named exactly X; otherwise the first `live` stream;
 *    otherwise the input with role `record`; otherwise another input that points to go2rtc.
 * 2. Substream: the stream matching the substream pattern (default `X_sub`); otherwise an input
 *    with role `detect` that is not the main stream; otherwise another `live` stream.
 * 3. Streams not attached to any camera become standalone cameras (with their own
 *    `_sub` variant as substream, if present).
 * 4. Cameras without a matching go2rtc stream are not shown but reported in [MappingResult.unmatched].
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
                unmatched += UnmatchedCamera(cam.name, UnmatchReason.DISABLED)
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
                    UnmatchReason.NO_GO2RTC_STREAM
                } else {
                    UnmatchReason.REFERENCED_STREAMS_MISSING
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

        // Standalone streams: not attached to any camera.
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
