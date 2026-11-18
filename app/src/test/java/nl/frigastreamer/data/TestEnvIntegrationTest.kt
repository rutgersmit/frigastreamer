package nl.frigastreamer.data

import kotlinx.coroutines.runBlocking
import nl.frigastreamer.domain.AutoCamera
import nl.frigastreamer.domain.StreamMapper
import nl.frigastreamer.domain.SubstreamPattern
import nl.frigastreamer.fakes.RecordingLogger
import nl.frigastreamer.settings.ApiMode
import nl.frigastreamer.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Integratietest tegen de testomgeving in `testenv/` (MediaMTX + Frigate-mock).
 * Draait alleen als FS_TESTENV_HOST gezet is, bv.:
 *
 *   cd testenv && docker compose up -d
 *   FS_TESTENV_HOST=127.0.0.1 ./gradlew testDebugUnitTest --tests '*TestEnvIntegrationTest*'
 *
 * Met FS_TESTENV_TOKEN_TTL=<s> (gelijk aan TOKEN_TTL van de mock) wordt ook het automatisch
 * opnieuw inloggen na het verlopen van de JWT getest.
 */
class TestEnvIntegrationTest {
    private val host: String? = System.getenv("FS_TESTENV_HOST")

    private fun client(s: AppSettings, log: RecordingLogger = RecordingLogger()) = FrigateClient({ s }, log)

    @Test
    fun `automatische cameralijst uit go2rtc-streams en config`() = runBlocking {
        assumeTrue("FS_TESTENV_HOST niet gezet", host != null)
        val c = client(AppSettings(host = host!!))
        val streams = c.fetchStreamNames()
        assertTrue("minimaal 10 streams: $streams", streams.size >= 10)
        val result = StreamMapper.map(streams, FrigateJson.parseCameras(c.fetchConfigBody()), SubstreamPattern(SubstreamPattern.DEFAULT))
        val byName = result.cameras.associateBy { it.name }
        assertEquals(AutoCamera("voordeur", "voordeur", "voordeur_sub"), byName["voordeur"])
        assertEquals(AutoCamera("tuin", "tuin", "tuin_sub"), byName["tuin"])
        assertEquals(AutoCamera("voorkant_deurbel", "deurbel", null), byName["voorkant_deurbel"])
        assertEquals("losse stream met eigen substream", AutoCamera("carport", "carport", "carport_sub"), byName["carport"])
        assertEquals(setOf("zolder", "oude_camera"), result.unmatched.map { it.name }.toSet())
        assertTrue(c.probeRtsp())
        println("testenv: ${result.cameras.size} camera's: " + result.cameras.joinToString { "${it.name}(${it.mainStream}/${it.subStream ?: "-"})" })
    }

    @Test
    fun `geauthenticeerde modus - login, cookie en foutief wachtwoord`() = runBlocking {
        assumeTrue("FS_TESTENV_HOST niet gezet", host != null)
        val ok = AppSettings(host = host!!, apiMode = ApiMode.AUTHENTICATED, apiPort = 8971, apiHttps = false, apiUsername = "admin", apiPassword = "admin")
        val log = RecordingLogger()
        val c = client(ok, log)
        assertTrue(c.fetchStreamNames().isNotEmpty())
        assertEquals(1, log.count("login_ok"))
        assertTrue("geen wachtwoord/token in logs", log.lines.none { it.contains("admin\"") || it.contains("frigate_token=") })

        val ttl = System.getenv("FS_TESTENV_TOKEN_TTL")?.toLongOrNull()
        if (ttl != null) {
            Thread.sleep((ttl + 1) * 1000)
            assertTrue(c.fetchStreamNames().isNotEmpty())
            assertEquals("na 401 automatisch opnieuw ingelogd", 1, log.count("token_expired"))
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
    fun `onbereikbare API geeft een nette fout`() = runBlocking {
        assumeTrue("FS_TESTENV_HOST niet gezet", host != null)
        try {
            client(AppSettings(host = host!!, apiPort = 5999)).fetchStreamNames()
            fail("verwacht FrigateApiException")
        } catch (e: FrigateApiException) {
            assertTrue(e.message!!.contains("niet bereikbaar"))
        }
    }
}
