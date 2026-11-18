package com.rutgersmit.frigastreamer.domain

import com.rutgersmit.frigastreamer.domain.PlaybackErrorCodes as C

/** Translates a [FailureInfo] into an [ErrorCategory]. Pure function, fully unit tested. */
object ErrorClassifier {

    /** Media3 reports RTSP status errors as e.g. "DESCRIBE 404" or "SETUP 401 Unauthorized". */
    private val rtspStatus = Regex("""\b(OPTIONS|DESCRIBE|SETUP|PLAY|PAUSE|TEARDOWN|GET_PARAMETER)\s+(\d{3})\b""")

    fun classify(info: FailureInfo): ErrorCategory {
        rtspStatusCode(info.messages)?.let { status ->
            return when (status) {
                401, 403 -> ErrorCategory.RTSP_AUTH
                404 -> ErrorCategory.RTSP_NOT_FOUND
                else -> ErrorCategory.RTSP_PROTOCOL
            }
        }
        val audio = info.trackKind == TrackKind.AUDIO
        return when (info.errorCode) {
            C.IO_NETWORK_CONNECTION_FAILED -> ErrorCategory.NETWORK
            C.IO_NETWORK_CONNECTION_TIMEOUT, C.TIMEOUT -> ErrorCategory.CONNECT_TIMEOUT
            C.IO_CLEARTEXT_NOT_PERMITTED, C.IO_NO_PERMISSION -> ErrorCategory.RTSP_PROTOCOL
            in 2000..2999 -> if (looksLikeTimeout(info.messages)) ErrorCategory.CONNECT_TIMEOUT else ErrorCategory.NETWORK
            C.BEHIND_LIVE_WINDOW -> ErrorCategory.RTSP_PROTOCOL
            in 3000..3999 -> ErrorCategory.RTSP_PROTOCOL
            C.DECODER_INIT_FAILED, C.DECODER_QUERY_FAILED, C.DECODING_RESOURCES_RECLAIMED ->
                if (audio) ErrorCategory.UNSUPPORTED_AUDIO_CODEC else ErrorCategory.DECODER_INIT
            C.DECODING_FORMAT_EXCEEDS_CAPABILITIES, C.DECODING_FORMAT_UNSUPPORTED ->
                if (audio) ErrorCategory.UNSUPPORTED_AUDIO_CODEC else ErrorCategory.UNSUPPORTED_VIDEO_CODEC
            C.DECODING_FAILED -> if (audio) ErrorCategory.UNSUPPORTED_AUDIO_CODEC else ErrorCategory.DECODING
            in 5000..5999 -> ErrorCategory.UNSUPPORTED_AUDIO_CODEC
            else -> when {
                looksLikeTimeout(info.messages) -> ErrorCategory.CONNECT_TIMEOUT
                looksLikeNetwork(info.messages) -> ErrorCategory.NETWORK
                else -> ErrorCategory.UNKNOWN
            }
        }
    }

    fun rtspStatusCode(messages: List<String>): Int? {
        for (m in messages) {
            val match = rtspStatus.find(m) ?: continue
            val code = match.groupValues[2].toInt()
            if (code >= 300) return code
        }
        return null
    }

    private fun looksLikeTimeout(messages: List<String>) =
        messages.any { it.contains("SocketTimeoutException") || it.contains("timed out", ignoreCase = true) }

    private fun looksLikeNetwork(messages: List<String>) = messages.any {
        it.contains("ConnectException") || it.contains("SocketException") ||
            it.contains("UnknownHostException") || it.contains("NoRouteToHostException") ||
            it.contains("EOFException") || it.contains("Connection reset", ignoreCase = true)
    }
}
