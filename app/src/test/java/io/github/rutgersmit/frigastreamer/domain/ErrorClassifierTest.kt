package io.github.rutgersmit.frigastreamer.domain

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorClassifierTest {
    private fun c(code: Int, track: TrackKind? = null, vararg msgs: String) =
        ErrorClassifier.classify(FailureInfo(code, track, msgs.toList()))

    @Test
    fun `mirrored error codes match Media3`() {
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackErrorCodes.IO_NETWORK_CONNECTION_FAILED)
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackErrorCodes.IO_NETWORK_CONNECTION_TIMEOUT)
        assertEquals(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackErrorCodes.IO_UNSPECIFIED)
        assertEquals(PlaybackException.ERROR_CODE_TIMEOUT, PlaybackErrorCodes.TIMEOUT)
        assertEquals(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, PlaybackErrorCodes.BEHIND_LIVE_WINDOW)
        assertEquals(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackErrorCodes.PARSING_CONTAINER_MALFORMED)
        assertEquals(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackErrorCodes.DECODER_INIT_FAILED)
        assertEquals(PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackErrorCodes.DECODER_QUERY_FAILED)
        assertEquals(PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackErrorCodes.DECODING_FAILED)
        assertEquals(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, PlaybackErrorCodes.DECODING_FORMAT_EXCEEDS_CAPABILITIES)
        assertEquals(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackErrorCodes.DECODING_FORMAT_UNSUPPORTED)
        assertEquals(PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED, PlaybackErrorCodes.DECODING_RESOURCES_RECLAIMED)
        assertEquals(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackErrorCodes.AUDIO_TRACK_INIT_FAILED)
        assertEquals(PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED, PlaybackErrorCodes.IO_CLEARTEXT_NOT_PERMITTED)
    }

    @Test
    fun `network errors are transient`() {
        assertEquals(ErrorCategory.NETWORK, c(PlaybackErrorCodes.IO_NETWORK_CONNECTION_FAILED))
        assertEquals(ErrorCategory.CONNECT_TIMEOUT, c(PlaybackErrorCodes.IO_NETWORK_CONNECTION_TIMEOUT))
        assertEquals(ErrorCategory.CONNECT_TIMEOUT, c(PlaybackErrorCodes.IO_UNSPECIFIED, null, "SocketTimeoutException: Read timed out"))
        assertEquals(ErrorCategory.NETWORK, c(PlaybackErrorCodes.IO_UNSPECIFIED, null, "IOException: x"))
        assertEquals(ErrorCategory.NETWORK, c(PlaybackErrorCodes.UNSPECIFIED, null, "ConnectException: ECONNREFUSED"))
        for (cat in listOf(ErrorCategory.NETWORK, ErrorCategory.CONNECT_TIMEOUT, ErrorCategory.RTSP_PROTOCOL, ErrorCategory.RTSP_NOT_FOUND, ErrorCategory.STALLED, ErrorCategory.UNKNOWN)) {
            assertFalse("$cat must not be permanent", cat.permanent)
        }
    }

    @Test
    fun `RTSP status codes from Media3 messages`() {
        assertEquals(ErrorCategory.RTSP_NOT_FOUND, c(PlaybackErrorCodes.IO_UNSPECIFIED, null, "RtspPlaybackException: DESCRIBE 404"))
        assertEquals(ErrorCategory.RTSP_AUTH, c(PlaybackErrorCodes.IO_UNSPECIFIED, null, "PlaybackException: x", "RtspPlaybackException: DESCRIBE 401"))
        assertEquals(ErrorCategory.RTSP_PROTOCOL, c(PlaybackErrorCodes.IO_UNSPECIFIED, null, "RtspPlaybackException: SETUP 461"))
        assertEquals(ErrorCategory.RTSP_PROTOCOL, c(PlaybackErrorCodes.IO_UNSPECIFIED, null, "RtspPlaybackException: PLAY 500"))
        assertEquals(ErrorCategory.RTSP_PROTOCOL, c(PlaybackErrorCodes.PARSING_CONTAINER_MALFORMED))
        assertTrue(ErrorCategory.RTSP_AUTH.permanent)
        assertEquals(null, ErrorClassifier.rtspStatusCode(listOf("OPTIONS 200")))
    }

    @Test
    fun `codec errors distinguish video and audio`() {
        assertEquals(ErrorCategory.UNSUPPORTED_VIDEO_CODEC, c(PlaybackErrorCodes.DECODING_FORMAT_UNSUPPORTED, TrackKind.VIDEO))
        assertEquals(ErrorCategory.UNSUPPORTED_VIDEO_CODEC, c(PlaybackErrorCodes.DECODING_FORMAT_EXCEEDS_CAPABILITIES, null))
        assertEquals(ErrorCategory.UNSUPPORTED_AUDIO_CODEC, c(PlaybackErrorCodes.DECODING_FORMAT_UNSUPPORTED, TrackKind.AUDIO))
        assertEquals(ErrorCategory.UNSUPPORTED_AUDIO_CODEC, c(PlaybackErrorCodes.AUDIO_TRACK_INIT_FAILED))
        assertEquals(ErrorCategory.UNSUPPORTED_AUDIO_CODEC, c(PlaybackErrorCodes.DECODER_INIT_FAILED, TrackKind.AUDIO))
        assertEquals(ErrorCategory.DECODER_INIT, c(PlaybackErrorCodes.DECODER_INIT_FAILED, TrackKind.VIDEO))
        assertEquals(ErrorCategory.DECODER_INIT, c(PlaybackErrorCodes.DECODING_RESOURCES_RECLAIMED))
        assertEquals(ErrorCategory.DECODING, c(PlaybackErrorCodes.DECODING_FAILED, TrackKind.VIDEO))
        assertTrue(ErrorCategory.UNSUPPORTED_VIDEO_CODEC.permanent)
        assertFalse("audio is not fatal: video must keep working", ErrorCategory.UNSUPPORTED_AUDIO_CODEC.permanent)
        assertEquals(ErrorCategory.UNKNOWN, c(PlaybackErrorCodes.UNSPECIFIED, null, "IllegalStateException"))
    }
}
