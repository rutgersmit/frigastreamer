package com.rutgersmit.frigastreamer.stream

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import com.rutgersmit.frigastreamer.domain.ErrorCategory
import com.rutgersmit.frigastreamer.domain.PlayerRole
import com.rutgersmit.frigastreamer.domain.PlayerWindow
import com.rutgersmit.frigastreamer.domain.RecoveryConfig
import com.rutgersmit.frigastreamer.domain.StreamKind
import com.rutgersmit.frigastreamer.fakes.FakeClock
import com.rutgersmit.frigastreamer.fakes.FakePlayerFactory
import com.rutgersmit.frigastreamer.fakes.FakeServer
import com.rutgersmit.frigastreamer.fakes.RecordingLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManagerHarness(val scope: TestScope, cameraCount: Int, preload: Int, config: RecoveryConfig = RecoveryConfig()) {
    val server = FakeServer()
    val factory = FakePlayerFactory(scope.backgroundScope, server)
    val log = RecordingLogger()
    val manager = StreamManager(scope.backgroundScope, factory, FakeClock(scope.testScheduler), log, config)
    val cameras = (0 until cameraCount).map { cam("cam$it") }

    init {
        manager.setPreload(preload)
        manager.setCameras(cameras)
    }

    fun ctrl(i: Int) = manager.controller(cameras[i].id)!!
    fun state(i: Int) = ctrl(i).status.value.state

    /** Checks the invariants that must always hold. */
    fun assertInvariants() {
        val live = factory.live
        assertTrue("max 3 players, was ${live.size}", live.size <= PlayerWindow.MAX_PLAYERS)
        val perCamera = live.groupBy { it.cameraId }
        assertTrue("duplicate player for a camera: $perCamera", perCamera.values.all { it.size == 1 })
        assertEquals("no leaks: created - released = alive", factory.created.size - factory.created.count { it.released }, live.size)
        assertEquals(live.size, manager.playerCount)
        // Every controller with a player must be inside the window.
        for (c in manager.controllerMap.value.values) if (c.hasPlayer) assertTrue(c.role != null)
    }

    fun windowIds(): Set<String> {
        val slots = PlayerWindow.compute(cameras.size, manager.state.value.activeIndex, manager.effectivePreload)
        return slots.map { cameras[it.cameraIndex].id }.toSet()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StreamManagerTest {

    @Test
    fun `preload 1 - active camera main, next camera sub and muted`() = runTest {
        val h = ManagerHarness(this, 10, 1)
        h.manager.setMuted(false)
        h.manager.start()
        advance(100)
        assertEquals("neighbours wait until the active camera has video", 1, h.factory.live.size)
        advance(1_000)
        assertEquals(2, h.factory.live.size)
        val active = h.factory.liveFor("auto:cam0").single()
        val next = h.factory.liveFor("auto:cam1").single()
        assertEquals(StreamKind.MAIN, active.source!!.kind)
        assertEquals(StreamKind.SUB, next.source!!.kind)
        assertFalse(active.muted)
        assertTrue(next.muted)
        assertEquals(StreamState.Playing, h.state(1))
        h.assertInvariants()
    }

    @Test
    fun `preload 2 and preload 0`() = runTest {
        val h = ManagerHarness(this, 10, 2)
        h.manager.start()
        advance(2_000)
        assertEquals(setOf("auto:cam0", "auto:cam1", "auto:cam9"), h.factory.live.map { it.cameraId }.toSet())
        h.assertInvariants()
        h.manager.setPreload(0)
        advance(500)
        assertEquals(listOf("auto:cam0"), h.factory.live.map { it.cameraId })
        h.assertInvariants()
    }

    @Test
    fun `preloaded camera becomes active without a new player and switches to main`() = runTest {
        val h = ManagerHarness(this, 5, 1)
        h.manager.start()
        advance(2_000)
        val preloaded = h.factory.liveFor("auto:cam1").single()
        h.manager.setActiveIndex(1)
        assertEquals("cam0 falls outside the window", 0, h.factory.liveFor("auto:cam0").size)
        assertEquals(preloaded, h.factory.liveFor("auto:cam1").single())
        assertEquals(StreamKind.MAIN, preloaded.source!!.kind)
        advance(2_000)
        assertEquals(setOf("auto:cam1", "auto:cam2"), h.factory.live.map { it.cameraId }.toSet())
        h.assertInvariants()
    }

    @Test
    fun `edge cases 1 and 2 cameras`() = runTest {
        val one = ManagerHarness(this, 1, 2)
        one.manager.start()
        advance(5_000)
        assertEquals(1, one.factory.live.size)
        one.assertInvariants()

        val two = ManagerHarness(this, 2, 2)
        two.manager.start()
        advance(5_000)
        assertEquals(2, two.factory.live.size)
        repeat(6) { i ->
            two.manager.setActiveIndex(i)
            advance(700)
            two.assertInvariants()
        }
        advance(5_000)
        assertEquals(2, two.factory.live.size)
        assertEquals("with 2 cameras they only swap roles", 2, two.factory.created.size)
        one.manager.dispose()
        two.manager.dispose()
    }

    @Test
    fun `decoder initialization failure on preload does not affect active camera and shrinks the window`() = runTest {
        val h = ManagerHarness(this, 6, 2)
        h.server.maxDecoders = 2
        h.manager.start()
        advance(5_000)
        assertEquals(StreamState.Playing, h.state(0))
        assertEquals(1, h.manager.effectivePreload)
        assertTrue(h.manager.state.value.autoReduced)
        assertEquals(1, h.log.count("preload_window_reduced"))
        assertEquals(2, h.factory.live.size)
        h.assertInvariants()
        // Further swiping uses the reduced window.
        h.manager.setActiveIndex(3)
        advance(5_000)
        assertEquals(2, h.factory.live.size)
        assertEquals(StreamState.Playing, h.state(3))
        h.assertInvariants()
    }

    @Test
    fun `decoder initialization failure on active camera - release preloads first and retry`() = runTest {
        val h = ManagerHarness(this, 3, 1)
        h.manager.start()
        advance(3_000)
        assertEquals(2, h.factory.live.size)
        // Decoder limit drops (e.g. another app uses a decoder) and the active stream briefly drops out.
        h.server.maxDecoders = 1
        h.server.down += "cam0"
        advance(1_300)
        h.server.down.clear()
        advance(5_000)
        assertEquals(StreamState.Playing, h.state(0))
        assertFalse("preload released", h.ctrl(1).hasPlayer)
        assertEquals(0, h.manager.effectivePreload)
        assertTrue(h.log.count("release_preloads_for_active") >= 1)
        h.assertInvariants()
    }

    @Test
    fun `decoder initialization failure without other players becomes codec error, other cameras keep working`() = runTest {
        val h = ManagerHarness(this, 3, 0)
        h.server.failures["cam0"] = ErrorCategory.DECODER_INIT
        h.manager.start()
        advance(10_000)
        val st = h.state(0)
        assertTrue("$st", st is StreamState.Error && st.category == ErrorCategory.DECODER_INIT)
        h.manager.setActiveIndex(1)
        advance(2_000)
        assertEquals(StreamState.Playing, h.state(1))
        h.assertInvariants()
    }

    @Test
    fun `network recovery - active camera first`() = runTest {
        val h = ManagerHarness(this, 4, 1)
        h.manager.start()
        advance(3_000)
        h.manager.onNetworkChanged(false)
        assertTrue(h.ctrl(0).status.value.waitingForNetwork)
        assertTrue(h.ctrl(1).status.value.waitingForNetwork)
        h.log.lines.clear()
        h.manager.onNetworkChanged(true)
        val starts = h.log.lines.filter { it.contains("event=connect_start") }
        assertEquals(2, starts.size)
        assertTrue(starts[0].contains("FS.Cam.cam0"))
        advance(1_000)
        assertEquals(StreamState.Playing, h.state(0))
        assertEquals(StreamState.Playing, h.state(1))
    }

    @Test
    fun `removed camera is cleaned up, new one added, active one kept`() = runTest {
        val h = ManagerHarness(this, 4, 1)
        h.manager.start()
        h.manager.setActiveIndex(2)
        advance(2_000)
        val newList = listOf(h.cameras[2], h.cameras[3], cam("new"))
        h.manager.setCameras(newList)
        advance(2_000)
        assertEquals("auto:cam2", h.manager.activeCamera()!!.id)
        assertEquals(null, h.manager.controller("auto:cam0"))
        assertTrue(h.factory.live.none { it.cameraId == "auto:cam0" || it.cameraId == "auto:cam1" })
        assertEquals(StreamState.Playing, h.manager.controller("auto:cam2")!!.status.value.state)
    }

    @Test
    fun `last viewed camera is chosen on start`() = runTest {
        val h = ManagerHarness(this, 1, 1)
        val cams = (0 until 5).map { cam("c$it") }
        h.manager.setCameras(cams, preferActiveId = "auto:c3")
        h.manager.start()
        advance(1_000)
        assertEquals("auto:c3", h.manager.activeCamera()!!.id)
        assertEquals(PlayerRole.ACTIVE, h.manager.controller("auto:c3")!!.role)
    }
}
