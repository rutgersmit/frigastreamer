package nl.frigastreamer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraListBuilderTest {
    private val ep = RtspEndpoint("192.168.1.10", 8554)
    private val auto = listOf(AutoCamera("voordeur", "voordeur", "voordeur_sub"), AutoCamera("tuin", "tuin", null))

    @Test
    fun `automatische camera's krijgen RTSP-URL's zonder credentials`() {
        val list = CameraListBuilder.build(auto, CameraConfig(), ep)
        val v = list.first().camera
        assertEquals("auto:voordeur", v.id)
        assertEquals("rtsp://192.168.1.10:8554/voordeur", v.mainUrl)
        assertEquals("rtsp://192.168.1.10:8554/voordeur_sub", v.subUrl)
        assertTrue(v.useGlobalRtspAuth)
        assertEquals(StreamKind.MAIN, v.preferredKind())
        assertEquals(StreamKind.SUB, v.preloadKind())
        assertEquals(StreamKind.MAIN, list[1].camera.preloadKind())
    }

    @Test
    fun `handmatige camera's blijven behouden bij een nieuwe automatische lijst`() {
        val cfg = CameraConfig(manual = listOf(ManualCamera("manual:1", "Schuur", "rtsp://u:p@10.0.0.5/live")))
        val before = CameraListBuilder.build(auto, cfg, ep)
        val after = CameraListBuilder.build(listOf(AutoCamera("nieuw", "nieuw", null)), cfg, ep)
        assertTrue(before.any { it.camera.id == "manual:1" })
        assertTrue(after.any { it.camera.id == "manual:1" })
        assertFalse("verwijderde camera verdwijnt", after.any { it.camera.id == "auto:voordeur" })
        assertTrue("nieuwe camera verschijnt", after.any { it.camera.id == "auto:nieuw" })
        // Ook zonder Frigate-host blijven handmatige camera's beschikbaar.
        assertEquals(listOf("manual:1"), CameraListBuilder.build(auto, cfg, null).map { it.camera.id })
    }

    @Test
    fun `volgorde, verbergen en voorkeur substream`() {
        val cfg = CameraConfig(
            manual = listOf(ManualCamera("manual:1", "Schuur", "rtsp://10.0.0.5/live")),
            overrides = mapOf(
                "auto:tuin" to CameraOverride(hidden = true),
                "auto:voordeur" to CameraOverride(preferSub = true),
            ),
            order = listOf("manual:1", "onbekend", "auto:tuin"),
        )
        val list = CameraListBuilder.build(auto, cfg, ep)
        assertEquals(listOf("manual:1", "auto:tuin", "auto:voordeur"), list.map { it.camera.id })
        assertEquals(listOf("manual:1", "auto:voordeur"), CameraListBuilder.visible(list).map { it.id })
        assertEquals(StreamKind.SUB, list.first { it.camera.id == "auto:voordeur" }.camera.preferredKind())
    }

    @Test
    fun `URL van automatische camera overschrijven`() {
        val cfg = CameraConfig(overrides = mapOf("auto:tuin" to CameraOverride(mainUrl = "rtsp://x:y@10.0.0.9/tuin", name = "Achtertuin")))
        val c = CameraListBuilder.build(auto, cfg, ep).first { it.camera.id == "auto:tuin" }.camera
        assertEquals("rtsp://x:y@10.0.0.9/tuin", c.mainUrl)
        assertEquals("Achtertuin", c.name)
        assertFalse(c.useGlobalRtspAuth)
    }

    @Test
    fun `streamnamen worden URL-veilig`() {
        assertEquals("rtsp://192.168.1.10:8554/voor%20deur", ep.urlFor("voor deur"))
        assertEquals("rtsp://[fd00::1]:8554/a", RtspEndpoint("fd00::1", 8554).urlFor("a"))
    }
}
