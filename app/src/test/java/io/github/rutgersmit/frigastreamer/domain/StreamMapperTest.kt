package io.github.rutgersmit.frigastreamer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMapperTest {
    private val pattern = SubstreamPattern(SubstreamPattern.DEFAULT)

    @Test
    fun `stream met cameranaam is main, camera_sub is substream`() {
        val r = StreamMapper.map(
            listOf("voordeur", "voordeur_sub", "tuin"),
            listOf(FrigateCameraConfig("voordeur"), FrigateCameraConfig("tuin")),
            pattern,
        )
        assertEquals(
            listOf(AutoCamera("voordeur", "voordeur", "voordeur_sub"), AutoCamera("tuin", "tuin", null)),
            r.cameras,
        )
        assertTrue(r.unmatched.isEmpty())
    }

    @Test
    fun `losse streams worden losse camera's, met eigen sub-variant`() {
        val r = StreamMapper.map(
            listOf("voordeur", "garage", "garage_sub", "extra_sub"),
            listOf(FrigateCameraConfig("voordeur")),
            pattern,
        )
        assertEquals(AutoCamera("garage", "garage", "garage_sub"), r.cameras.first { it.name == "garage" })
        // "extra_sub" zonder "extra" blijft gewoon een losse camera.
        assertEquals(AutoCamera("extra_sub", "extra_sub", null), r.cameras.first { it.name == "extra_sub" })
        assertEquals(3, r.cameras.size)
    }

    @Test
    fun `camera zonder go2rtc-stream wordt niet getoond maar wel gemeld`() {
        val r = StreamMapper.map(
            listOf("voordeur"),
            listOf(
                FrigateCameraConfig("voordeur"),
                FrigateCameraConfig("schuur", inputs = listOf(InputRef("bestaatniet", listOf("record")))),
                FrigateCameraConfig("oud"),
                FrigateCameraConfig("uit", enabled = false),
            ),
            pattern,
        )
        assertEquals(listOf("voordeur"), r.cameras.map { it.name })
        assertEquals(setOf("schuur", "oud", "uit"), r.unmatched.map { it.name }.toSet())
    }

    @Test
    fun `koppeling via config-inputs en rollen als namen afwijken`() {
        val cam = FrigateCameraConfig(
            "deurbel",
            inputs = listOf(
                InputRef("reolink_main", listOf("record")),
                InputRef("reolink_ext", listOf("detect")),
            ),
        )
        val r = StreamMapper.map(listOf("reolink_main", "reolink_ext"), listOf(cam), pattern)
        assertEquals(listOf(AutoCamera("deurbel", "reolink_main", "reolink_ext")), r.cameras)
    }

    @Test
    fun `live streams uit config (Frigate 0_16) bepalen de main stream`() {
        val cam = FrigateCameraConfig("tuin", liveStreams = listOf("tuin_hd", "tuin_low"))
        val r = StreamMapper.map(listOf("tuin_hd", "tuin_low"), listOf(cam), pattern)
        assertEquals(listOf(AutoCamera("tuin", "tuin_hd", "tuin_low")), r.cameras)
    }

    @Test
    fun `instelbaar substream-patroon`() {
        val p = SubstreamPattern("{camera}-low")
        val r = StreamMapper.map(listOf("oprit", "oprit-low", "oprit_sub"), listOf(FrigateCameraConfig("oprit")), p)
        assertEquals(AutoCamera("oprit", "oprit", "oprit-low"), r.cameras.first())
        // oprit_sub past niet in dit patroon en wordt een losse camera.
        assertEquals(listOf("oprit", "oprit_sub"), r.cameras.map { it.name })
    }

    @Test
    fun `zonder config worden streams alleen op naam gekoppeld`() {
        val r = StreamMapper.map(listOf("a", "a_sub", "b"), emptyList(), pattern)
        assertEquals(listOf(AutoCamera("a", "a", "a_sub"), AutoCamera("b", "b", null)), r.cameras)
    }

    @Test
    fun `substream-patroon parsing`() {
        val p = SubstreamPattern("<camera>_sub")
        assertEquals("x_sub", p.subNameFor("x"))
        assertEquals("x", p.mainNameFor("x_sub"))
        assertNull(p.mainNameFor("_sub"))
        assertNull(p.mainNameFor("x"))
        assertEquals(SubstreamPattern.DEFAULT, SubstreamPattern("zonder-token").pattern)
        assertEquals("pre_<camera>", SubstreamPattern("pre_{camera}").pattern)
        assertEquals("y", SubstreamPattern("pre_{camera}").mainNameFor("pre_y"))
    }
}
