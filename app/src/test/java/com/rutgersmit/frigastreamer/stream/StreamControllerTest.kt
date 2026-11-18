package com.rutgersmit.frigastreamer.stream

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import com.rutgersmit.frigastreamer.domain.Camera
import com.rutgersmit.frigastreamer.domain.CameraSource
import com.rutgersmit.frigastreamer.domain.ErrorCategory
import com.rutgersmit.frigastreamer.domain.PlayerRole
import com.rutgersmit.frigastreamer.domain.RecoveryConfig
import com.rutgersmit.frigastreamer.domain.RtspCredentials
import com.rutgersmit.frigastreamer.domain.StreamKind
import com.rutgersmit.frigastreamer.fakes.FakeClock
import com.rutgersmit.frigastreamer.fakes.FakePlayerFactory
import com.rutgersmit.frigastreamer.fakes.FakeServer
import com.rutgersmit.frigastreamer.fakes.FakeStreamPlayer
import com.rutgersmit.frigastreamer.fakes.RecordingLogger
import com.rutgersmit.frigastreamer.player.PlayerFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

fun cam(name: String, sub: Boolean = true) = Camera(
    id = "auto:$name",
    name = name,
    mainUrl = "rtsp://192.168.1.10:8554/$name",
    subUrl = if (sub) "rtsp://192.168.1.10:8554/${name}_sub" else null,
    source = CameraSource.AUTO,
)

@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.advance(ms: Long) {
    advanceTimeBy(ms)
    runCurrent()
}

@OptIn(ExperimentalCoroutinesApi::class)
class StreamControllerTest {

    private class Harness(val scope: TestScope, val config: RecoveryConfig = RecoveryConfig()) {
        val server = FakeServer()
        val factory = FakePlayerFactory(scope.backgroundScope, server)
        val clock = FakeClock(scope.testScheduler)
        val log = RecordingLogger()
        var decision = StreamController.DecoderDecision.GIVE_UP
        var decoderCalls = 0
        var credentials: RtspCredentials? = null
        val host = object : StreamController.Host {
            override fun rtspCredentials() = credentials
            override fun onDecoderInitFailure(controller: StreamController): StreamController.DecoderDecision {
                decoderCalls++
                return decision
            }
        }

        fun controller(c: Camera = cam("cam1")) =
            StreamController(c, scope.backgroundScope, factory, clock, config, host, log)

        fun scheduledDelays(): List<Long> = log.lines.filter { it.contains("event=reconnect_scheduled") }
            .map { Regex("delay_ms=(\\d+)").find(it)!!.groupValues[1].toLong() }

        fun totalStarts() = factory.created.sumOf { it.startCount }
    }

    @Test
    fun `connect and play`() = runTest {
        val h = Harness(this)
        val c = h.controller()
        c.attach(PlayerRole.ACTIVE)
        assertEquals(StreamState.Connecting(0), c.status.value.state)
        assertTrue(c.hasPlayer)
        advance(300)
        assertEquals(StreamState.Playing, c.status.value.state)
        assertEquals(StreamKind.MAIN, c.status.value.kind)
        assertEquals(1, h.log.count("connect_success"))
        assertNotNull(c.status.value.lastFrameWallMs)
    }

    @Test
    fun `exponential backoff 1-2-4-8-16-30-30 and keeps going indefinitely`() = runTest {
        val h = Harness(this)
        h.server.up = false
        val c = h.controller()
        c.attach(PlayerRole.ACTIVE)
        advance(10 * 60_000)
        val delays = h.scheduledDelays()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), delays.take(7))
        assertTrue("keeps retrying: ${delays.size}", delays.size > 20)
        assertTrue(delays.drop(5).all { it == 30_000L })
        assertTrue(c.status.value.state is StreamState.Offline)
        assertEquals(1, h.factory.live.size)
    }

    @Test
    fun `backoff resets after a stable connection, not after a brief one`() = runTest {
        val h = Harness(this)
        h.server.up = false
        val c = h.controller()
        c.attach(PlayerRole.ACTIVE)
        advance(20_000) // attempts 1..4 scheduled (1+2+4+8 = 15 s + connect attempts)
        val before = h.scheduledDelays().size
        assertTrue(before >= 4)
        h.server.up = true
        advance(20_000)
        assertEquals(StreamState.Playing, c.status.value.state)
        // Brief recovery: drops again within 10 s -> backoff keeps growing.
        h.server.up = false
        advance(1_500)
        val afterShort = h.scheduledDelays().last()
        assertTrue("backoff kept growing: $afterShort", afterShort > 1_000L)
        // Now stable for a long time: afterwards the backoff starts at 1 s again.
        h.server.up = true
        advance(60_000)
        assertEquals(StreamState.Playing, c.status.value.state)
        assertTrue(h.log.count("backoff_reset") >= 1)
        h.server.up = false
        advance(1_500)
        assertEquals(1_000L, h.scheduledDelays().last())
    }

    @Test
    fun `offline threshold of 20 seconds, then recovery`() = runTest {
        val h = Harness(this)
        val c = h.controller()
        c.attach(PlayerRole.ACTIVE)
        advance(1_000)
        assertEquals(StreamState.Playing, c.status.value.state)
        h.server.up = false
        advance(5_000)
        val s1 = c.status.value.state
        assertTrue("short: Reconnecting/Connecting, was $s1", s1 is StreamState.Reconnecting || s1 is StreamState.Connecting)
        advance(17_000) // > 20 s since the outage
        val s2 = c.status.value.state
        assertTrue("long: Offline, was $s2", s2 is StreamState.Offline)
        assertNotNull(c.status.value.lastFrameWallMs)
        h.server.up = true
        advance(31_000)
        assertEquals(StreamState.Playing, c.status.value.state)
        assertNull(c.status.value.outageSinceElapsedMs)
    }

    @Test
    fun `offline threshold is configurable`() = runTest {
        val h = Harness(this, RecoveryConfig(offlineThresholdMs = 5_000))
        h.server.up = false
        val c = h.controller()
        c.attach(PlayerRole.ACTIVE)
        advance(4_000)
        assertFalse(c.status.value.state is StreamState.Offline)
        advance(1_500)
        assertTrue(c.status.value.state is StreamState.Offline)
    }

    @Test
    fun `watchdog restarts stream when frames stop while connection stays open`() = runTest {
        val h = Harness(this)
        val c = h.controller()
        c.attach(PlayerRole.ACTIVE)
        advance(2_000)
        assertEquals(StreamState.Playing, c.status.value.state)
        h.server.frozen += "cam1"
        advance(4_400)
        assertEquals("still within 5 s", StreamState.Playing, c.status.value.state)
        advance(1_000)
        assertEquals(1, h.log.count("watchdog_triggered"))
        assertEquals(ErrorCategory.STALLED, c.status.value.lastError)
        h.server.frozen.clear()
        advance(3_000)
        assertEquals(StreamState.Playing, c.status.value.state)
        assertEquals(1, h.factory.created.size)
    }

    @Test
    fun `watchdog does not falsely restart during connection setup (keyframe interval 10 s)`() = runTest {
        val h = Harness(this)
        h.server.connectDelayMs = 11_000
        val c = h.controller()
        c.attach(PlayerRole.ACTIVE)
        advance(10_900)
        assertEquals(StreamState.Connecting(0), c.status.value.state)
        advance(500)
        assertEquals(StreamState.Playing, c.status.value.state)
        assertEquals(0, h.log.count("watchdog_triggered"))
        assertEquals(1, h.totalStarts())
    }

    @Test
    fun `watchdog off means no stall detection`() = runTest {
        val h = Harness(this, RecoveryConfig(watchdogEnabled = false))
        val c = h.controller()
        c.attach(PlayerRole.ACTIVE)
        advance(1_000)
        h.server.frozen += "cam1"
        advance(60_000)
        assertEquals(0, h.log.count("watchdog_triggered"))
    }

    @Test
    fun `connect timeout when no frame ever arrives`() = runTest {
        val h = Harness(this)
        h.server.connectDelayMs = 60_000
        val c = h.controller()
        c.attach(PlayerRole.ACTIVE)
        advance(20_100)
        assertEquals(ErrorCategory.CONNECT_TIMEOUT, c.status.value.lastError)
        assertEquals(1, h.scheduledDelays().size)
    }

    @Test
    fun `network loss cancels reconnect timer, recovery connects immediately`() = runTest {
        val h = Harness(this)
        h.server.up = false
        val c = h.controller()
        c.attach(PlayerRole.ACTIVE)
        advance(400)
        assertTrue(c.hasPendingReconnect)
        c.onNetworkChanged(false)
        assertFalse(c.hasPendingReconnect)
        assertTrue(c.status.value.waitingForNetwork)
        val starts = h.totalStarts()
        advance(120_000)
        assertEquals("no attempts without network", starts, h.totalStarts())
        val st = c.status.value.state
        assertTrue(st is StreamState.Offline && st.nextAttemptAtMs == null)
        h.server.up = true
        c.onNetworkChanged(true)
        assertEquals("reconnect immediately", starts + 1, h.totalStarts())
        advance(400)
        assertEquals(StreamState.Playing, c.status.value.state)
    }

    @Test
    fun `never more than one reconnect timer, late callbacks from old session ignored`() = runTest {
        val h = Harness(this)
        val c = h.controller()
        c.attach(PlayerRole.ACTIVE)
        advance(1_000)
        val p = h.factory.created.single() as FakeStreamPlayer
        val oldListener = p.currentListener!!
        h.server.up = false
        advance(1_200) // error detected, reconnect scheduled
        assertTrue(c.hasPendingReconnect)
        val scheduled = h.scheduledDelays().size
        // Duplicate error reports (old session / duplicate callback) must not schedule anything extra.
        oldListener.onFailure(PlayerFailure(ErrorCategory.NETWORK, "dubbel"))
        oldListener.onFailure(PlayerFailure(ErrorCategory.STALLED, "dubbel"))
        assertEquals(scheduled, h.scheduledDelays().size)
        h.server.up = true
        advance(1_000) // reconnect executed -> new session
        oldListener.onFailure(PlayerFailure(ErrorCategory.NETWORK, "oude sessie"))
        assertEquals(scheduled, h.scheduledDelays().size)
        assertEquals(1, h.factory.live.size)
    }

    @Test
    fun `unsupported video codec gives Error and no reconnect loop`() = runTest {
        val h = Harness(this)
        h.server.failures["cam1"] = ErrorCategory.UNSUPPORTED_VIDEO_CODEC
        val c = h.controller()
        c.attach(PlayerRole.ACTIVE)
        advance(30 * 60_000)
        val st = c.status.value.state
        assertTrue(st is StreamState.Error && st.category == ErrorCategory.UNSUPPORTED_VIDEO_CODEC)
        assertEquals(1, h.totalStarts())
        assertEquals("decoder released", 0, h.factory.live.size)
        assertEquals(0, h.scheduledDelays().size)
        // Retrying manually is still possible.
        h.server.failures.clear()
        c.retry()
        advance(500)
        assertEquals(StreamState.Playing, c.status.value.state)
    }

    @Test
    fun `RTSP authentication failure is permanent`() = runTest {
        val h = Harness(this)
        h.server.failures["cam1"] = ErrorCategory.RTSP_AUTH
        val c = h.controller()
        c.attach(PlayerRole.ACTIVE)
        advance(5 * 60_000)
        assertTrue(c.isError)
        assertEquals(1, h.totalStarts())
    }

    @Test
    fun `repeated decoder errors without video become a permanent codec error`() = runTest {
        val h = Harness(this)
        h.server.failures["cam1"] = ErrorCategory.DECODING
        val c = h.controller()
        c.attach(PlayerRole.ACTIVE)
        advance(10 * 60_000)
        val st = c.status.value.state
        assertTrue("$st", st is StreamState.Error && st.category == ErrorCategory.CODEC_PERMANENT)
        assertEquals(3, h.totalStarts())
    }

    @Test
    fun `unsupported audio - video keeps working without audio track`() = runTest {
        val h = Harness(this)
        h.server.failures["cam1"] = ErrorCategory.UNSUPPORTED_AUDIO_CODEC
        val c = h.controller()
        c.attach(PlayerRole.ACTIVE)
        advance(2_000)
        assertEquals(StreamState.Playing, c.status.value.state)
        assertTrue(c.status.value.audioUnsupported)
        val p = h.factory.live.single()
        assertTrue(p.source!!.audioDisabled)
        assertEquals(2, p.startCount)
    }

    @Test
    fun `decoder initialization failure without other players is a codec error`() = runTest {
        val h = Harness(this)
        h.server.maxDecoders = 0
        val c = h.controller()
        c.attach(PlayerRole.ACTIVE)
        advance(60_000)
        val st = c.status.value.state
        assertTrue(st is StreamState.Error && st.category == ErrorCategory.DECODER_INIT)
        assertEquals(1, h.decoderCalls)
        assertEquals(1, h.totalStarts())
    }

    @Test
    fun `detach releases player and stops all timers`() = runTest {
        val h = Harness(this)
        h.server.up = false
        val c = h.controller()
        c.attach(PlayerRole.ACTIVE)
        advance(500)
        assertTrue(c.hasPendingReconnect)
        c.detach("test")
        assertFalse(c.hasPendingReconnect)
        assertEquals(0, h.factory.live.size)
        assertEquals(StreamState.Idle, c.status.value.state)
        val starts = h.totalStarts()
        advance(5 * 60_000)
        assertEquals(starts, h.totalStarts())
        assertEquals(1, h.log.count("player_released"))
    }

    @Test
    fun `preload uses substream and is muted, promotion switches to main`() = runTest {
        val h = Harness(this)
        val c = h.controller()
        c.setMuted(false)
        c.attach(PlayerRole.PRELOAD)
        advance(500)
        val p = h.factory.live.single()
        assertEquals(StreamKind.SUB, p.source!!.kind)
        assertTrue(p.muted)
        c.attach(PlayerRole.ACTIVE)
        assertEquals(StreamKind.MAIN, p.source!!.kind)
        assertFalse(p.muted)
        assertEquals("same player instance", 1, h.factory.created.size)
    }

    @Test
    fun `RTSP credentials go to the player separately, never into logs`() = runTest {
        val h = Harness(this)
        h.credentials = RtspCredentials("admin", "supersecret")
        val c = h.controller()
        c.attach(PlayerRole.ACTIVE)
        advance(500)
        val src = h.factory.live.single().source!!
        assertEquals("admin", src.credentials!!.username)
        assertFalse(src.url.contains("supersecret"))
        assertFalse(src.toString().contains("supersecret"))
        assertTrue(h.log.lines.none { it.contains("supersecret") })
    }
}
