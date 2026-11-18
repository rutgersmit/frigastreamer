package io.github.rutgersmit.frigastreamer.domain

/**
 * Error categories. [permanent] errors do NOT trigger automatic reconnects
 * (no endless loop); the user can retry manually.
 *
 * [description] is an English, non-localized description for logs and the debug overlay.
 * The UI shows a localized label via `ui/ErrorLabels.kt`.
 */
enum class ErrorCategory(val permanent: Boolean, val description: String) {
    NETWORK(false, "Network error"),
    NETWORK_UNAVAILABLE(false, "No network connection"),
    CONNECT_TIMEOUT(false, "Connecting takes too long"),
    RTSP_PROTOCOL(false, "RTSP protocol error"),
    RTSP_NOT_FOUND(false, "Stream not found on the RTSP server"),
    RTSP_AUTH(true, "RTSP authentication failed"),
    STREAM_ENDED(false, "Stream ended by the server"),
    STALLED(false, "Connection open, but no new frames"),
    UNSUPPORTED_VIDEO_CODEC(true, "Video codec not supported by this device"),
    UNSUPPORTED_AUDIO_CODEC(false, "Audio codec not supported"),
    DECODER_INIT(false, "Video decoder could not start"),
    DECODING(false, "Decoder error"),
    CODEC_PERMANENT(true, "Stream cannot be decoded"),
    UNKNOWN(false, "Unknown stream error"),
    ;

    val isCodecRelated: Boolean
        get() = this == UNSUPPORTED_VIDEO_CODEC || this == UNSUPPORTED_AUDIO_CODEC ||
            this == DECODER_INIT || this == DECODING || this == CODEC_PERMANENT
}

enum class TrackKind { VIDEO, AUDIO, OTHER }

/**
 * Neutral description of a player failure, independent of Media3, so classification
 * can be tested without Android/ExoPlayer.
 */
data class FailureInfo(
    /** Media3 PlaybackException.errorCode. */
    val errorCode: Int,
    /** Track/renderer where the failure occurred, if known. */
    val trackKind: TrackKind? = null,
    /** Messages and class names from the exception chain (already redacted). */
    val messages: List<String> = emptyList(),
)

/** Mirrors the relevant Media3 `PlaybackException.ERROR_CODE_*` constants (verified in a unit test). */
object PlaybackErrorCodes {
    const val UNSPECIFIED = 1000
    const val REMOTE_ERROR = 1001
    const val BEHIND_LIVE_WINDOW = 1002
    const val TIMEOUT = 1003
    const val FAILED_RUNTIME_CHECK = 1004
    const val IO_UNSPECIFIED = 2000
    const val IO_NETWORK_CONNECTION_FAILED = 2001
    const val IO_NETWORK_CONNECTION_TIMEOUT = 2002
    const val IO_INVALID_HTTP_CONTENT_TYPE = 2003
    const val IO_BAD_HTTP_STATUS = 2004
    const val IO_FILE_NOT_FOUND = 2005
    const val IO_NO_PERMISSION = 2006
    const val IO_CLEARTEXT_NOT_PERMITTED = 2007
    const val IO_READ_POSITION_OUT_OF_RANGE = 2008
    const val PARSING_CONTAINER_MALFORMED = 3001
    const val PARSING_MANIFEST_MALFORMED = 3002
    const val PARSING_CONTAINER_UNSUPPORTED = 3003
    const val PARSING_MANIFEST_UNSUPPORTED = 3004
    const val DECODER_INIT_FAILED = 4001
    const val DECODER_QUERY_FAILED = 4002
    const val DECODING_FAILED = 4003
    const val DECODING_FORMAT_EXCEEDS_CAPABILITIES = 4004
    const val DECODING_FORMAT_UNSUPPORTED = 4005
    const val DECODING_RESOURCES_RECLAIMED = 4006
    const val AUDIO_TRACK_INIT_FAILED = 5001
    const val AUDIO_TRACK_WRITE_FAILED = 5002
    const val AUDIO_TRACK_OFFLOAD_WRITE_FAILED = 5003
    const val AUDIO_TRACK_OFFLOAD_INIT_FAILED = 5004
}
