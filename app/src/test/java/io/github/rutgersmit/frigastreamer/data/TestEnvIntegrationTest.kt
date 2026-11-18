package io.github.rutgersmit.frigastreamer.data

import kotlinx.coroutines.runBlocking
import io.github.rutgersmit.frigastreamer.domain.AutoCamera
import io.github.rutgersmit.frigastreamer.domain.StreamMapper
import io.github.rutgersmit.frigastreamer.domain.SubstreamPattern
import io.github.rutgersmit.frigastreamer.fakes.RecordingLogger
import io.github.rutgersmit.frigastreamer.settings.ApiMode
import io.github.rutgersmit.frigastreamer.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Integration test against the test environment in `testenv/` (MediaMTX + Frigate mock).
 * Only runs when FS_TESTENV_HOST is set, e.g.:
 *
 *   cd testenv && docker compose up -d
 *   FS_TESTENV_HOST=127.0.0.1 ./gradlew testDebugUnitTest --tests '*TestEnvIntegrationTest*'
 *
 * With FS_TESTENV_TOKEN_TTL=<s> (equal to the mock's TOKEN_TTL), automatically logging in
 * again after the JWT expires is tested as well.
 */
class TestEnvIntegrationTest {
    private val host: String? = System.getenv("FS_TESTENV_HOST")

    private fun client(s: AppSettings, log: RecordingLogger = RecordingLogger()) = FrigateClient({ s }, log)

    @Test
    fun `automatic camera list from go2rtc streams and config`() = runBlocking {
        assumeTrue("FS_TESTENV_HOST not set", host != null)
        val c = client(AppSettings(host = host!!))
        val streams = c.fetchStreamNames()
        assertTrue("at least 10 streams: $streams", streams.size >= 10)
        val result = StreamMapper.map(streams, FrigateJson.parseCameras(c.fetchConfigBody()), SubstreamPattern(SubstreamPattern.DEFAULT))
        val byName = result.cameras.associateBy { it.name }
        assertEquals(AutoCamera("front_door", "front_door", "front_door_sub"), byName["front_door"])
        assertEquals(AutoCamera("garden", "garden", "garden_sub"), byName["garden"])
        assertEquals(AutoCamera("front_doorbell", "doorbell", null), byName["front_doorbell"])
        assertEquals("standalone stream with its own substream", AutoCamera("carport", "carport", "carport_sub"), byName["carport"])
        assertEquals(setOf("attic", "old_camera"), result.unmatched.map { it.name }.toSet())
        assertTrue(c.probeRtsp())
        println("testenv: ${result.cameras.size} cameras: " + result.cameras.joinToString { "${it.name}(${it.mainStream}/${it.subStream ?: "-"})" })
    }

    @Test
    fun `authenticated mode - login, cookie and wrong password`() = runBlocking {
        assumeTrue("FS_TESTENV_HOST not set", host != null)
        val ok = AppSettings(host = host!!, apiMode = ApiMode.AUTHENTICATED, apiPort = 8971, apiHttps = false, apiUsername = "admin", apiPassword = "admin")
        val log = RecordingLogger()
        val c = client(ok, log)
        assertTrue(c.fetchStreamNames().isNotEmpty())
        assertEquals(1, log.count("login_ok"))
        assertTrue("no password/token in logs", log.lines.none { it.contains("admin\"") || it.contains("frigate_token=") })

        val ttl = System.getenv("FS_TESTENV_TOKEN_TTL")?.toLongOrNull()
        if (ttl != null) {
            Thread.sleep((ttl + 1) * 1000)
            assertTrue(c.fetchStreamNames().isNotEmpty())
            assertEquals("logged in again automatically after 401", 1, log.count("token_expired"))
            assertEquals(2, log.count("login_ok"))
        }

        try {
            client(ok.copy(apiPassword = "fout")).fetchStreamNames()
            fail("verwacht FrigateApiException")
        } catch (e: FrigateApiException) {
            assertEquals(401, e.httpStatus)
        }
    }

    @Test
    fun `unreachable API gives a clean error`() = runBlocking {
        assumeTrue("FS_TESTENV_HOST not set", host != null)
        try {
            client(AppSettings(host = host!!, apiPort = 5999)).fetchStreamNames()
            fail("verwacht FrigateApiException")
        } catch (e: FrigateApiException) {
            assertTrue(e.message!!.contains("not reachable"))
        }
    }
}
