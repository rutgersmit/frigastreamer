package com.rutgersmit.frigastreamer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraListBuilderTest {
    private val ep = RtspEndpoint("192.168.1.10", 8554)
    private val auto = listOf(AutoCamera("front_door", "front_door", "front_door_sub"), AutoCamera("garden", "garden", null))

    @Test
    fun `automatic cameras get RTSP URLs without credentials`() {
        val list = CameraListBuilder.build(auto, CameraConfig(), ep)
        val v = list.first().camera
        assertEquals("auto:front_door", v.id)
        assertEquals("rtsp://192.168.1.10:8554/front_door", v.mainUrl)
        assertEquals("rtsp://192.168.1.10:8554/front_door_sub", v.subUrl)
        assertTrue(v.useGlobalRtspAuth)
        assertEquals(StreamKind.MAIN, v.preferredKind())
        assertEquals(StreamKind.SUB, v.preloadKind())
        assertEquals(StreamKind.MAIN, list[1].camera.preloadKind())
    }

    @Test
    fun `manual cameras are kept when a new automatic list arrives`() {
        val cfg = CameraConfig(manual = listOf(ManualCamera("manual:1", "Shed", "rtsp://u:p@10.0.0.5/live")))
        val before = CameraListBuilder.build(auto, cfg, ep)
        val after = CameraListBuilder.build(listOf(AutoCamera("new", "new", null)), cfg, ep)
        assertTrue(before.any { it.camera.id == "manual:1" })
        assertTrue(after.any { it.camera.id == "manual:1" })
        assertFalse("removed camera disappears", after.any { it.camera.id == "auto:front_door" })
        assertTrue("new camera appears", after.any { it.camera.id == "auto:new" })
        // Manual cameras stay available even without a Frigate host.
        assertEquals(listOf("manual:1"), CameraListBuilder.build(auto, cfg, null).map { it.camera.id })
    }

    @Test
    fun `order, hiding and substream preference`() {
        val cfg = CameraConfig(
            manual = listOf(ManualCamera("manual:1", "Shed", "rtsp://10.0.0.5/live")),
            overrides = mapOf(
                "auto:garden" to CameraOverride(hidden = true),
                "auto:front_door" to CameraOverride(preferSub = true),
            ),
            order = listOf("manual:1", "onbekend", "auto:garden"),
        )
        val list = CameraListBuilder.build(auto, cfg, ep)
        assertEquals(listOf("manual:1", "auto:garden", "auto:front_door"), list.map { it.camera.id })
        assertEquals(listOf("manual:1", "auto:front_door"), CameraListBuilder.visible(list).map { it.id })
        assertEquals(StreamKind.SUB, list.first { it.camera.id == "auto:front_door" }.camera.preferredKind())
    }

    @Test
    fun `override the URL of an automatic camera`() {
        val cfg = CameraConfig(overrides = mapOf("auto:garden" to CameraOverride(mainUrl = "rtsp://x:y@10.0.0.9/garden", name = "Backyard")))
        val c = CameraListBuilder.build(auto, cfg, ep).first { it.camera.id == "auto:garden" }.camera
        assertEquals("rtsp://x:y@10.0.0.9/garden", c.mainUrl)
        assertEquals("Backyard", c.name)
        assertFalse(c.useGlobalRtspAuth)
    }

    @Test
    fun `stream names are made URL-safe`() {
        assertEquals("rtsp://192.168.1.10:8554/front%20door", ep.urlFor("front door"))
        assertEquals("rtsp://[fd00::1]:8554/a", RtspEndpoint("fd00::1", 8554).urlFor("a"))
    }
}
