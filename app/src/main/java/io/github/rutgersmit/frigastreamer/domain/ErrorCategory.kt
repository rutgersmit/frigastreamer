package io.github.rutgersmit.frigastreamer.domain

/**
 * Foutcategorieën. [permanent] fouten leiden NIET tot automatische reconnects
 * (geen eindeloze loop); de gebruiker kan handmatig opnieuw proberen.
 */
enum class ErrorCategory(val permanent: Boolean, val label: String) {
    NETWORK(false, "Netwerkfout"),
    NETWORK_UNAVAILABLE(false, "Geen netwerkverbinding"),
    CONNECT_TIMEOUT(false, "Verbinding maken duurt te lang"),
    RTSP_PROTOCOL(false, "RTSP-protocolfout"),
    RTSP_NOT_FOUND(false, "Stream niet gevonden op de RTSP-server"),
    RTSP_AUTH(true, "RTSP-authenticatie mislukt"),
    STREAM_ENDED(false, "Stream beëindigd door de server"),
    STALLED(false, "Verbinding open, maar geen nieuwe beelden"),
    UNSUPPORTED_VIDEO_CODEC(true, "Videocodec niet ondersteund door dit apparaat"),
    UNSUPPORTED_AUDIO_CODEC(false, "Audiocodec niet ondersteund"),
    DECODER_INIT(false, "Videodecoder kon niet starten"),
    DECODING(false, "Decoderfout"),
    CODEC_PERMANENT(true, "Stream kan niet gedecodeerd worden"),
    UNKNOWN(false, "Onbekende streamfout"),
    ;

    val isCodecRelated: Boolean
        get() = this == UNSUPPORTED_VIDEO_CODEC || this == UNSUPPORTED_AUDIO_CODEC ||
            this == DECODER_INIT || this == DECODING || this == CODEC_PERMANENT
}

enum class TrackKind { VIDEO, AUDIO, OTHER }

/**
 * Neutrale beschrijving van een spelerfout, los van Media3, zodat classificatie
 * zonder Android/ExoPlayer te testen is.
 */
data class FailureInfo(
    /** Media3 PlaybackException.errorCode. */
    val errorCode: Int,
    /** Track/renderer waar de fout optrad, als bekend. */
    val trackKind: TrackKind? = null,
    /** Berichten en klassennamen uit de exception-keten (al geredacteerd). */
    val messages: List<String> = emptyList(),
)

/** Spiegelt de relevante Media3 `PlaybackException.ERROR_CODE_*`-constanten (geverifieerd in een unit test). */
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
