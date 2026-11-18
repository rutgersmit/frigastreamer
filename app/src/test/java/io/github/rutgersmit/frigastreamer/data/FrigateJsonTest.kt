package io.github.rutgersmit.frigastreamer.data

import io.github.rutgersmit.frigastreamer.domain.InputRef
import io.github.rutgersmit.frigastreamer.util.Redact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FrigateJsonTest {
    @Test
    fun `go2rtc stream list`() {
        val body = """{"front_door":{"producers":[{"url":"rtsp://..."}],"consumers":null},"front_door_sub":{},"garden":{}}"""
        assertEquals(listOf("front_door", "front_door_sub", "garden"), FrigateJson.parseStreamNames(body))
    }

    @Test
    fun `frigate config with inputs, roles and live streams`() {
        val body = """
        {"mqtt":{},"cameras":{
          "front_door":{"enabled":true,"ffmpeg":{"inputs":[
             {"path":"rtsp://127.0.0.1:8554/front_door","roles":["record"]},
             {"path":"rtsp://user:pw@localhost:8554/front_door_sub?video=copy","roles":["detect"]},
             {"path":"rtsp://192.168.1.50:554/direct","roles":["audio"]}
          ]},"live":{"streams":{"Main":"front_door","Sub":"front_door_sub"}}},
          "old":{"ffmpeg":{"inputs":[]},"live":{"stream_name":"old_stream"}},
          "off":{"enabled":false}
        }}
        """.trimIndent()
        val cams = FrigateJson.parseCameras(body)
        assertEquals(listOf("front_door", "old", "off"), cams.map { it.name })
        assertEquals(
            listOf(InputRef("front_door", listOf("record")), InputRef("front_door_sub", listOf("detect"))),
            cams[0].inputs,
        )
        assertEquals(listOf("front_door", "front_door_sub"), cams[0].liveStreams)
        assertEquals(listOf("old_stream"), cams[1].liveStreams)
        assertFalse(cams[2].enabled)
    }

    @Test
    fun `recognize go2rtc references`() {
        assertEquals("cam", FrigateJson.go2rtcStreamName("rtsp://127.0.0.1:8554/cam"))
        assertEquals("cam sub", FrigateJson.go2rtcStreamName("rtsp://localhost:8554/cam%20sub?x=1"))
        assertNull(FrigateJson.go2rtcStreamName("rtsp://192.168.1.5:554/cam"))
        assertNull(FrigateJson.go2rtcStreamName("rtsp://127.0.0.1:554/cam"))
    }

    @Test
    fun `credentials are redacted`() {
        assertEquals("rtsp://***@192.168.1.10:8554/cam", Redact.url("rtsp://admin:secret@192.168.1.10:8554/cam"))
        assertEquals("http://h/x?token=***&a=1", Redact.text("http://h/x?token=abc&a=1"))
        assertEquals("Cookie: frigate_token=***; path=/", Redact.text("Cookie: frigate_token=eyJhbGci; path=/"))
        assertEquals("rtsp://192.168.1.10/cam", Redact.url("rtsp://192.168.1.10/cam"))
    }
}
