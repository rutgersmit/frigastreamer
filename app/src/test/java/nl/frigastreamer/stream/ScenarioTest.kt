package nl.frigastreamer.stream

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import nl.frigastreamer.domain.ErrorCategory
import nl.frigastreamer.domain.PlayerRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * De praktijkscenario's uit de opdracht (sectie 13), gesimuleerd met fakes en virtuele tijd.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioTest {

    @Test
    fun `1 - Frigate 60 seconden onbereikbaar en daarna terug`() = runTest {
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
                // Na detectie (fake: ~1,1 s) is er altijd een lopende poging of een ingeplande reconnect.
                assertFalse(st is StreamState.Playing)
                val connectingNow = st is StreamState.Connecting || (st is StreamState.Offline && st.connectingNow)
                assertTrue("reconnect-logica blijft actief ($st)", h.ctrl(0).hasPendingReconnect || connectingNow)
            }
        }
        assertTrue("kort: Reconnecting", "Reconnecting" in sawStates)
        assertTrue("na 20 s: Offline", "Offline" in sawStates)
        assertTrue(h.state(0) is StreamState.Offline)
        // Backoff binnen 60 s: ~1+2+4+8+16+30 -> hooguit 7 pogingen voor de actieve camera.
        val activeStarts = h.factory.created.filter { it.cameraId == "auto:cam0" }.sumOf { it.startCount }
        assertTrue("aantal pogingen begrensd door backoff: $activeStarts", activeStarts in 5..9)

        h.server.up = true
        advance(31_000) // langste backoff is 30 s
        assertEquals(StreamState.Playing, h.state(0))
        advance(5_000)
        assertEquals(StreamState.Playing, h.state(1))
        h.assertInvariants()
        assertEquals(2, h.factory.live.size)
    }

    @Test
    fun `2 - een camera valt weg en komt terug, de andere blijven werken`() = runTest {
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
    fun `3 - netwerk weg en terug, Frigate pas enkele seconden later bereikbaar`() = runTest {
        val h = ManagerHarness(this, 3, 1)
        h.manager.start()
        advance(3_000)
        // Wifi valt weg (ook Frigate is vanaf de tablet dus onbereikbaar).
        h.server.up = false
        h.manager.onNetworkChanged(false)
        val startsBefore = h.factory.created.sumOf { it.startCount }
        advance(30_000)
        assertEquals("geen pogingen zolang er geen netwerk is", startsBefore, h.factory.created.sumOf { it.startCount })
        assertTrue(h.ctrl(0).status.value.waitingForNetwork)
        assertTrue(h.state(0) is StreamState.Offline)

        // Netwerk terug, maar Frigate nog 5 s niet.
        h.manager.onNetworkChanged(true)
        assertTrue("direct opnieuw proberen", h.factory.created.sumOf { it.startCount } > startsBefore)
        advance(5_000)
        assertFalse(h.state(0) is StreamState.Playing)
        h.server.up = true
        advance(10_000)
        assertEquals(StreamState.Playing, h.state(0))
        assertEquals(StreamState.Playing, h.state(1))
        h.assertInvariants()
    }

    @Test
    fun `4 - RTSP-verbinding blijft open terwijl de frames stoppen`() = runTest {
        val h = ManagerHarness(this, 3, 1)
        h.manager.start()
        advance(3_000)
        h.server.frozen += "cam0"
        advance(6_000)
        assertEquals(1, h.log.count("watchdog_triggered", "cam0"))
        assertEquals(ErrorCategory.STALLED, h.ctrl(0).status.value.lastError)
        assertEquals("buur heeft er geen last van", StreamState.Playing, h.state(1))
        h.server.frozen.clear()
        advance(5_000)
        assertEquals(StreamState.Playing, h.state(0))
        h.assertInvariants()
    }

    @Test
    fun `5 - snel swipen met 12 camera's`() = runTest {
        val h = ManagerHarness(this, 12, 2)
        h.manager.start()
        advance(2_000)
        var index = 0
        // 15 snelle swipes vooruit, dan 12 achteruit, met korte en zeer korte pauzes.
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
        // Geen enkele controller buiten het venster heeft nog timers of spelers.
        for (c in h.manager.controllerMap.value.values) {
            if (c.camera.id !in h.windowIds()) {
                assertFalse(c.hasPlayer)
                assertFalse(c.hasPendingReconnect)
                assertEquals(StreamState.Idle, c.status.value.state)
            }
        }
    }

    @Test
    fun `6 - stream met niet-ondersteunde codec geeft geen eindeloze reconnect-loop`() = runTest {
        val h = ManagerHarness(this, 3, 1)
        h.server.failures["cam0"] = ErrorCategory.UNSUPPORTED_VIDEO_CODEC
        h.manager.start()
        advance(30 * 60_000)
        val st = h.state(0)
        assertTrue(st is StreamState.Error && st.category == ErrorCategory.UNSUPPORTED_VIDEO_CODEC)
        assertEquals(1, h.factory.created.filter { it.cameraId == "auto:cam0" }.sumOf { it.startCount })
        assertFalse(h.ctrl(0).hasPlayer)
        assertEquals("buur speelt gewoon", StreamState.Playing, h.state(1))
        // Andere camera's blijven gewoon bekijkbaar.
        h.manager.setActiveIndex(1)
        advance(2_000)
        assertEquals(StreamState.Playing, h.state(1))
        // Terugswipen naar de foute camera start geen nieuwe pogingen (fout blijft staan).
        h.manager.setActiveIndex(0)
        advance(60_000)
        assertEquals(1, h.factory.created.filter { it.cameraId == "auto:cam0" }.sumOf { it.startCount })
        h.assertInvariants()
    }

    @Test
    fun `7 - herhaaldelijk naar de achtergrond en terug`() = runTest {
        val h = ManagerHarness(this, 6, 2)
        h.manager.start()
        advance(3_000)
        repeat(25) { i ->
            h.manager.stop()
            h.manager.stop() // dubbel aanroepen is onschadelijk
            assertEquals("alles vrijgegeven op de achtergrond", 0, h.factory.live.size)
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
        // Per actieve camera precies één speler, en geen extra reconnects door het wisselen.
        assertEquals(0, h.log.count("reconnect_scheduled"))
        h.manager.stop()
        assertEquals(0, h.factory.live.size)
        assertEquals(h.factory.created.size, h.factory.created.count { it.released })
    }

    @Test
    fun `codecfout krijgt een nieuwe kans als de app terug naar de voorgrond komt`() = runTest {
        val h = ManagerHarness(this, 2, 0)
        h.server.failures["cam0"] = ErrorCategory.UNSUPPORTED_VIDEO_CODEC
        h.manager.start()
        advance(2_000)
        assertTrue(h.ctrl(0).isError)
        h.server.failures.clear() // bv. go2rtc transcodeert nu naar H.264
        h.manager.stop()
        h.manager.start()
        advance(2_000)
        assertEquals(StreamState.Playing, h.state(0))
    }
}
