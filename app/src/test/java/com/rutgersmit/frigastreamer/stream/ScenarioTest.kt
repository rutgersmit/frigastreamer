package com.rutgersmit.frigastreamer.stream

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import com.rutgersmit.frigastreamer.domain.ErrorCategory
import com.rutgersmit.frigastreamer.domain.PlayerRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real-world scenarios from the assignment (section 13), simulated with fakes and virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioTest {

    @Test
    fun `1 - Frigate unreachable for 60 seconds and then back`() = runTest {
        val h = ManagerHarness(this, 4, 1)
        h.manager.start()
        advance(3_000)
        assertEquals(StreamState.Playing, h.state(0))
        assertEquals(StreamState.Playing, h.state(1))

        h.server.up = false
        val sawStates = mutableSetOf<String>()
        repeat(60) { second ->
            advance(1_000)
            val st = h.state(0)
            sawStates += st::class.simpleName!!
            h.assertInvariants()
            if (second >= 2) {
                // After detection (fake: ~1.1 s) there is always an attempt in progress or a scheduled reconnect.
                assertFalse(st is StreamState.Playing)
                val connectingNow = st is StreamState.Connecting || (st is StreamState.Offline && st.connectingNow)
                assertTrue("reconnect logic stays active ($st)", h.ctrl(0).hasPendingReconnect || connectingNow)
            }
        }
        assertTrue("short: Reconnecting", "Reconnecting" in sawStates)
        assertTrue("after 20 s: Offline", "Offline" in sawStates)
        assertTrue(h.state(0) is StreamState.Offline)
        // Backoff within 60 s: ~1+2+4+8+16+30 -> at most 7 attempts for the active camera.
        val activeStarts = h.factory.created.filter { it.cameraId == "auto:cam0" }.sumOf { it.startCount }
        assertTrue("number of attempts bounded by backoff: $activeStarts", activeStarts in 5..9)

        h.server.up = true
        advance(31_000) // longest backoff is 30 s
        assertEquals(StreamState.Playing, h.state(0))
        advance(5_000)
        assertEquals(StreamState.Playing, h.state(1))
        h.assertInvariants()
        assertEquals(2, h.factory.live.size)
    }

    @Test
    fun `2 - one camera drops out and comes back, the others keep working`() = runTest {
        val h = ManagerHarness(this, 3, 2)
        h.manager.start()
        advance(3_000)
        h.server.down += "cam1_sub"
        h.server.down += "cam1"
        repeat(40) {
            advance(1_000)
            assertEquals(StreamState.Playing, h.state(0))
            assertEquals(StreamState.Playing, h.state(2))
        }
        assertTrue(h.state(1) is StreamState.Offline)
        h.server.down.clear()
        advance(31_000)
        assertEquals(StreamState.Playing, h.state(1))
        h.assertInvariants()
    }

    @Test
    fun `3 - network down and back, Frigate only reachable a few seconds later`() = runTest {
        val h = ManagerHarness(this, 3, 1)
        h.manager.start()
        advance(3_000)
        // Wi-Fi drops (so Frigate is also unreachable from the tablet).
        h.server.up = false
        h.manager.onNetworkChanged(false)
        val startsBefore = h.factory.created.sumOf { it.startCount }
        advance(30_000)
        assertEquals("no attempts while there is no network", startsBefore, h.factory.created.sumOf { it.startCount })
        assertTrue(h.ctrl(0).status.value.waitingForNetwork)
        assertTrue(h.state(0) is StreamState.Offline)

        // Network back, but Frigate not for another 5 s.
        h.manager.onNetworkChanged(true)
        assertTrue("retry immediately", h.factory.created.sumOf { it.startCount } > startsBefore)
        advance(5_000)
        assertFalse(h.state(0) is StreamState.Playing)
        h.server.up = true
        advance(10_000)
        assertEquals(StreamState.Playing, h.state(0))
        assertEquals(StreamState.Playing, h.state(1))
        h.assertInvariants()
    }

    @Test
    fun `4 - RTSP connection stays open while frames stop`() = runTest {
        val h = ManagerHarness(this, 3, 1)
        h.manager.start()
        advance(3_000)
        h.server.frozen += "cam0"
        advance(6_000)
        assertEquals(1, h.log.count("watchdog_triggered", "cam0"))
        assertEquals(ErrorCategory.STALLED, h.ctrl(0).status.value.lastError)
        assertEquals("neighbour is not affected", StreamState.Playing, h.state(1))
        h.server.frozen.clear()
        advance(5_000)
        assertEquals(StreamState.Playing, h.state(0))
        h.assertInvariants()
    }

    @Test
    fun `5 - fast swiping with 12 cameras`() = runTest {
        val h = ManagerHarness(this, 12, 2)
        h.manager.start()
        advance(2_000)
        var index = 0
        // 15 fast swipes forward, then 12 back, with short and very short pauses.
        val moves = List(15) { 1 } + List(12) { -1 }
        moves.forEachIndexed { i, step ->
            index += step
            h.manager.setActiveIndex(index)
            h.assertInvariants()
            advance(if (i % 3 == 0) 20L else 150L)
            h.assertInvariants()
        }
        advance(5_000)
        h.assertInvariants()
        assertEquals(3, h.factory.live.size)
        assertEquals(h.windowIds(), h.factory.live.map { it.cameraId }.toSet())
        assertTrue(h.factory.maxLiveObserved <= 3)
        assertTrue(h.manager.maxObservedPlayers <= 3)
        val active = h.manager.activeCamera()!!
        assertEquals(StreamState.Playing, h.manager.controller(active.id)!!.status.value.state)
        // No controller outside the window still has timers or players.
        for (c in h.manager.controllerMap.value.values) {
            if (c.camera.id !in h.windowIds()) {
                assertFalse(c.hasPlayer)
                assertFalse(c.hasPendingReconnect)
                assertEquals(StreamState.Idle, c.status.value.state)
            }
        }
    }

    @Test
    fun `6 - stream with unsupported codec does not cause an endless reconnect loop`() = runTest {
        val h = ManagerHarness(this, 3, 1)
        h.server.failures["cam0"] = ErrorCategory.UNSUPPORTED_VIDEO_CODEC
        h.manager.start()
        advance(30 * 60_000)
        val st = h.state(0)
        assertTrue(st is StreamState.Error && st.category == ErrorCategory.UNSUPPORTED_VIDEO_CODEC)
        assertEquals(1, h.factory.created.filter { it.cameraId == "auto:cam0" }.sumOf { it.startCount })
        assertFalse(h.ctrl(0).hasPlayer)
        assertEquals("neighbour just plays", StreamState.Playing, h.state(1))
        // Other cameras remain viewable.
        h.manager.setActiveIndex(1)
        advance(2_000)
        assertEquals(StreamState.Playing, h.state(1))
        // Swiping back to the failed camera does not start new attempts (the error stays).
        h.manager.setActiveIndex(0)
        advance(60_000)
        assertEquals(1, h.factory.created.filter { it.cameraId == "auto:cam0" }.sumOf { it.startCount })
        h.assertInvariants()
    }

    @Test
    fun `7 - repeatedly to the background and back`() = runTest {
        val h = ManagerHarness(this, 6, 2)
        h.manager.start()
        advance(3_000)
        repeat(25) { i ->
            h.manager.stop()
            h.manager.stop() // calling twice is harmless
            assertEquals("everything released in the background", 0, h.factory.live.size)
            assertTrue(h.manager.controllerMap.value.values.none { it.hasPendingReconnect })
            advance(if (i % 2 == 0) 50L else 2_000L)
            h.manager.start()
            h.manager.start()
            h.assertInvariants()
            advance(if (i % 3 == 0) 10L else 1_500L)
            h.assertInvariants()
        }
        advance(5_000)
        assertEquals(3, h.factory.live.size)
        assertEquals(StreamState.Playing, h.state(0))
        assertEquals(PlayerRole.ACTIVE, h.ctrl(0).role)
        h.assertInvariants()
        // Exactly one player per active camera, and no extra reconnects from switching.
        assertEquals(0, h.log.count("reconnect_scheduled"))
        h.manager.stop()
        assertEquals(0, h.factory.live.size)
        assertEquals(h.factory.created.size, h.factory.created.count { it.released })
    }

    @Test
    fun `codec error gets another chance when the app returns to the foreground`() = runTest {
        val h = ManagerHarness(this, 2, 0)
        h.server.failures["cam0"] = ErrorCategory.UNSUPPORTED_VIDEO_CODEC
        h.manager.start()
        advance(2_000)
        assertTrue(h.ctrl(0).isError)
        h.server.failures.clear() // e.g. go2rtc now transcodes to H.264
        h.manager.stop()
        h.manager.start()
        advance(2_000)
        assertEquals(StreamState.Playing, h.state(0))
    }
}
