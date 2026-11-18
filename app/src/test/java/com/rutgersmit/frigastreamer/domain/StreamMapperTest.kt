package com.rutgersmit.frigastreamer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMapperTest {
    private val pattern = SubstreamPattern(SubstreamPattern.DEFAULT)

    @Test
    fun `stream with camera name is main, camera_sub is substream`() {
        val r = StreamMapper.map(
            listOf("front_door", "front_door_sub", "garden"),
            listOf(FrigateCameraConfig("front_door"), FrigateCameraConfig("garden")),
            pattern,
        )
        assertEquals(
            listOf(AutoCamera("front_door", "front_door", "front_door_sub"), AutoCamera("garden", "garden", null)),
            r.cameras,
        )
        assertTrue(r.unmatched.isEmpty())
    }

    @Test
    fun `standalone streams become standalone cameras, with their own sub variant`() {
        val r = StreamMapper.map(
            listOf("front_door", "garage", "garage_sub", "extra_sub"),
            listOf(FrigateCameraConfig("front_door")),
            pattern,
        )
        assertEquals(AutoCamera("garage", "garage", "garage_sub"), r.cameras.first { it.name == "garage" })
        // "extra_sub" without "extra" simply stays a standalone camera.
        assertEquals(AutoCamera("extra_sub", "extra_sub", null), r.cameras.first { it.name == "extra_sub" })
        assertEquals(3, r.cameras.size)
    }

    @Test
    fun `camera without go2rtc stream is not shown but is reported`() {
        val r = StreamMapper.map(
            listOf("front_door"),
            listOf(
                FrigateCameraConfig("front_door"),
                FrigateCameraConfig("shed", inputs = listOf(InputRef("does_not_exist", listOf("record")))),
                FrigateCameraConfig("old"),
                FrigateCameraConfig("off", enabled = false),
            ),
            pattern,
        )
        assertEquals(listOf("front_door"), r.cameras.map { it.name })
        assertEquals(
            mapOf(
                "shed" to UnmatchReason.REFERENCED_STREAMS_MISSING,
                "old" to UnmatchReason.NO_GO2RTC_STREAM,
                "off" to UnmatchReason.DISABLED,
            ),
            r.unmatched.associate { it.name to it.reason },
        )
    }

    @Test
    fun `mapping via config inputs and roles when names differ`() {
        val cam = FrigateCameraConfig(
            "doorbell",
            inputs = listOf(
                InputRef("reolink_main", listOf("record")),
                InputRef("reolink_ext", listOf("detect")),
            ),
        )
        val r = StreamMapper.map(listOf("reolink_main", "reolink_ext"), listOf(cam), pattern)
        assertEquals(listOf(AutoCamera("doorbell", "reolink_main", "reolink_ext")), r.cameras)
    }

    @Test
    fun `live streams from config (Frigate 0_16) determine the main stream`() {
        val cam = FrigateCameraConfig("garden", liveStreams = listOf("garden_hd", "garden_low"))
        val r = StreamMapper.map(listOf("garden_hd", "garden_low"), listOf(cam), pattern)
        assertEquals(listOf(AutoCamera("garden", "garden_hd", "garden_low")), r.cameras)
    }

    @Test
    fun `configurable substream pattern`() {
        val p = SubstreamPattern("{camera}-low")
        val r = StreamMapper.map(listOf("driveway", "driveway-low", "driveway_sub"), listOf(FrigateCameraConfig("driveway")), p)
        assertEquals(AutoCamera("driveway", "driveway", "driveway-low"), r.cameras.first())
        // driveway_sub does not match this pattern and becomes a standalone camera.
        assertEquals(listOf("driveway", "driveway_sub"), r.cameras.map { it.name })
    }

    @Test
    fun `without config streams are mapped by name only`() {
        val r = StreamMapper.map(listOf("a", "a_sub", "b"), emptyList(), pattern)
        assertEquals(listOf(AutoCamera("a", "a", "a_sub"), AutoCamera("b", "b", null)), r.cameras)
    }

    @Test
    fun `substream pattern parsing`() {
        val p = SubstreamPattern("<camera>_sub")
        assertEquals("x_sub", p.subNameFor("x"))
        assertEquals("x", p.mainNameFor("x_sub"))
        assertNull(p.mainNameFor("_sub"))
        assertNull(p.mainNameFor("x"))
        assertEquals(SubstreamPattern.DEFAULT, SubstreamPattern("no-token").pattern)
        assertEquals("pre_<camera>", SubstreamPattern("pre_{camera}").pattern)
        assertEquals("y", SubstreamPattern("pre_{camera}").mainNameFor("pre_y"))
    }
}
