package com.rutgersmit.frigastreamer.ui

import androidx.annotation.StringRes
import com.rutgersmit.frigastreamer.R
import com.rutgersmit.frigastreamer.domain.ErrorCategory
import com.rutgersmit.frigastreamer.domain.UnmatchReason

/** Localized label for an [ErrorCategory]. */
@StringRes
fun ErrorCategory.labelRes(): Int = when (this) {
    ErrorCategory.NETWORK -> R.string.err_network
    ErrorCategory.NETWORK_UNAVAILABLE -> R.string.err_network_unavailable
    ErrorCategory.CONNECT_TIMEOUT -> R.string.err_connect_timeout
    ErrorCategory.RTSP_PROTOCOL -> R.string.err_rtsp_protocol
    ErrorCategory.RTSP_NOT_FOUND -> R.string.err_rtsp_not_found
    ErrorCategory.RTSP_AUTH -> R.string.err_rtsp_auth
    ErrorCategory.STREAM_ENDED -> R.string.err_stream_ended
    ErrorCategory.STALLED -> R.string.err_stalled
    ErrorCategory.UNSUPPORTED_VIDEO_CODEC -> R.string.err_unsupported_video_codec
    ErrorCategory.UNSUPPORTED_AUDIO_CODEC -> R.string.err_unsupported_audio_codec
    ErrorCategory.DECODER_INIT -> R.string.err_decoder_init
    ErrorCategory.DECODING -> R.string.err_decoding
    ErrorCategory.CODEC_PERMANENT -> R.string.err_codec_permanent
    ErrorCategory.UNKNOWN -> R.string.err_unknown
}

/** Localized reason why a Frigate camera has no go2rtc stream. */
@StringRes
fun UnmatchReason.labelRes(): Int = when (this) {
    UnmatchReason.DISABLED -> R.string.unmatched_disabled
    UnmatchReason.NO_GO2RTC_STREAM -> R.string.unmatched_no_stream
    UnmatchReason.REFERENCED_STREAMS_MISSING -> R.string.unmatched_streams_missing
}
