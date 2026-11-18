package nl.frigastreamer.data

import nl.frigastreamer.domain.InputRef
import nl.frigastreamer.util.Redact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FrigateJsonTest {
    @Test
    fun `go2rtc-streamlijst`() {
        val body = """{"voordeur":{"producers":[{"url":"rtsp://..."}],"consumers":null},"voordeur_sub":{},"tuin":{}}"""
        assertEquals(listOf("voordeur", "voordeur_sub", "tuin"), FrigateJson.parseStreamNames(body))
    }

    @Test
    fun `frigate-config met inputs, rollen en live streams`() {
        val body = """
        {"mqtt":{},"cameras":{
          "voordeur":{"enabled":true,"ffmpeg":{"inputs":[
             {"path":"rtsp://127.0.0.1:8554/voordeur","roles":["record"]},
             {"path":"rtsp://user:pw@localhost:8554/voordeur_sub?video=copy","roles":["detect"]},
             {"path":"rtsp://192.168.1.50:554/direct","roles":["audio"]}
          ]},"live":{"streams":{"Main":"voordeur","Sub":"voordeur_sub"}}},
          "oud":{"ffmpeg":{"inputs":[]},"live":{"stream_name":"oud_stream"}},
          "uit":{"enabled":false}
        }}
        """.trimIndent()
        val cams = FrigateJson.parseCameras(body)
        assertEquals(listOf("voordeur", "oud", "uit"), cams.map { it.name })
        assertEquals(
            listOf(InputRef("voordeur", listOf("record")), InputRef("voordeur_sub", listOf("detect"))),
            cams[0].inputs,
        )
        assertEquals(listOf("voordeur", "voordeur_sub"), cams[0].liveStreams)
        assertEquals(listOf("oud_stream"), cams[1].liveStreams)
        assertFalse(cams[2].enabled)
    }

    @Test
    fun `go2rtc-verwijzingen herkennen`() {
        assertEquals("cam", FrigateJson.go2rtcStreamName("rtsp://127.0.0.1:8554/cam"))
        assertEquals("cam sub", FrigateJson.go2rtcStreamName("rtsp://localhost:8554/cam%20sub?x=1"))
        assertNull(FrigateJson.go2rtcStreamName("rtsp://192.168.1.5:554/cam"))
        assertNull(FrigateJson.go2rtcStreamName("rtsp://127.0.0.1:554/cam"))
    }

    @Test
    fun `credentials worden geredacteerd`() {
        assertEquals("rtsp://***@192.168.1.10:8554/cam", Redact.url("rtsp://admin:geheim@192.168.1.10:8554/cam"))
        assertEquals("http://h/x?token=***&a=1", Redact.text("http://h/x?token=abc&a=1"))
        assertEquals("Cookie: frigate_token=***; path=/", Redact.text("Cookie: frigate_token=eyJhbGci; path=/"))
        assertEquals("rtsp://192.168.1.10/cam", Redact.url("rtsp://192.168.1.10/cam"))
    }
}
