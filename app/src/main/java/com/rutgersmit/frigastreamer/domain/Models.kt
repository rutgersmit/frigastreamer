package com.rutgersmit.frigastreamer.domain

enum class StreamKind { MAIN, SUB }

enum class CameraSource { AUTO, MANUAL }

enum class PlayerRole { ACTIVE, PRELOAD }

data class RtspCredentials(val username: String, val password: String) {
    override fun toString(): String = "RtspCredentials(username=***, password=***)"
}

/**
 * A camera to display, with fully resolved stream URLs (without credentials for
 * automatically discovered cameras; those are only added in the player layer).
 */
data class Camera(
    val id: String,
    val name: String,
    val mainUrl: String,
    val subUrl: String?,
    val source: CameraSource,
    val preferSub: Boolean = false,
    /** true: use the global RTSP credentials (go2rtc) from the settings. */
    val useGlobalRtspAuth: Boolean = source == CameraSource.AUTO,
) {
    fun urlFor(kind: StreamKind): String =
        if (kind == StreamKind.SUB && subUrl != null) subUrl else mainUrl

    /** Stream for the active view. */
    fun preferredKind(): StreamKind =
        if (preferSub && subUrl != null) StreamKind.SUB else StreamKind.MAIN

    /** Stream for a preloaded neighbour: preferably the substream. */
    fun preloadKind(): StreamKind = if (subUrl != null) StreamKind.SUB else StreamKind.MAIN

    fun kindFor(role: PlayerRole): StreamKind =
        if (role == PlayerRole.ACTIVE) preferredKind() else preloadKind()
}

/** Settings of the recovery logic. All times in milliseconds. */
data class RecoveryConfig(
    val offlineThresholdMs: Long = 20_000,
    val watchdogEnabled: Boolean = true,
    val watchdogTimeoutMs: Long = 5_000,
    val backoffMinMs: Long = 1_000,
    val backoffMaxMs: Long = 30_000,
    /** Maximum time until the first frame. Generous enough for cameras with a 10 s keyframe interval. */
    val connectTimeoutMs: Long = 20_000,
    /** After this many ms of uninterrupted video the connection counts as stable and the backoff is reset. */
    val stableAfterMs: Long = 10_000,
    /** Interval at which the watchdog checks the frame counter. */
    val watchdogTickMs: Long = 500,
    /** After this many consecutive decoder errors without any frame, it becomes a permanent codec error. */
    val maxCodecFailures: Int = 3,
)
