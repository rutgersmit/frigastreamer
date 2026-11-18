package nl.frigastreamer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import nl.frigastreamer.settings.ApiMode
import nl.frigastreamer.settings.AppSettings
import nl.frigastreamer.util.Logger
import nl.frigastreamer.util.Redact
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class FrigateApiException(message: String, val httpStatus: Int? = null, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * Minimale HTTP-client voor de Frigate-API (geen externe libraries).
 *
 * - Interne modus: `http://<host>:5000`, geen authenticatie.
 * - Geauthenticeerde modus: `https://<host>:8971` (Frigate gebruikt daar standaard TLS met een
 *   self-signed certificaat), inloggen via `POST /api/login` met `{"user","password"}`;
 *   het JWT komt terug als cookie `frigate_token` en wordt bij elk verzoek meegestuurd.
 *   Bij 401 wordt één keer opnieuw ingelogd en het verzoek herhaald.
 */
class FrigateClient(
    private val settingsProvider: () -> AppSettings,
    private val log: Logger,
) {
    private val tokenLock = Mutex()
    @Volatile private var token: String? = null
    @Volatile private var tokenFor: String? = null

    suspend fun fetchStreamNames(): List<String> = FrigateJson.parseStreamNames(get("/api/go2rtc/streams"))

    suspend fun fetchConfigBody(): String = get("/api/config")

    suspend fun fetchVersion(): String = FrigateJson.parseVersion(get("/api/version"))

    /** Test of de RTSP-poort (go2rtc) open staat. */
    suspend fun probeRtsp(timeoutMs: Int = 2_000): Boolean = withContext(Dispatchers.IO) {
        val s = settingsProvider()
        if (s.host.isBlank()) return@withContext false
        runCatching {
            Socket().use { it.connect(InetSocketAddress(s.host.trim(), s.rtspPort), timeoutMs) }
            true
        }.getOrDefault(false)
    }

    fun invalidateToken() {
        token = null
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val s = settingsProvider()
        if (s.host.isBlank()) throw FrigateApiException("Geen Frigate-host ingesteld")
        if (s.apiMode == ApiMode.AUTHENTICATED) {
            val key = sessionKey(s)
            if (token == null || tokenFor != key) login(s)
            val first = request(s, "GET", path, null)
            if (first.status == 401) {
                log.i(TAG, "event=token_expired action=relogin")
                tokenLock.withLock { token = null }
                login(s)
                return@withContext request(s, "GET", path, null).bodyOrThrow(path)
            }
            first.bodyOrThrow(path)
        } else {
            request(s, "GET", path, null).bodyOrThrow(path)
        }
    }

    private suspend fun login(s: AppSettings) = tokenLock.withLock {
        val key = sessionKey(s)
        if (token != null && tokenFor == key) return@withLock
        if (s.apiUsername.isBlank()) throw FrigateApiException("Geen API-gebruikersnaam ingesteld voor de geauthenticeerde modus")
        val body = Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("user", JsonPrimitive(s.apiUsername))
                put("password", JsonPrimitive(s.apiPassword))
            },
        )
        val r = request(s, "POST", "/api/login", body, sendToken = false)
        if (r.status == 401 || r.status == 400 || r.status == 403) {
            throw FrigateApiException("Inloggen bij Frigate mislukt (HTTP ${r.status}): controleer gebruikersnaam/wachtwoord", r.status)
        }
        if (r.status !in 200..299) throw FrigateApiException("Inloggen bij Frigate mislukt (HTTP ${r.status})", r.status)
        val t = r.cookies["frigate_token"] ?: r.cookies.entries.firstOrNull { it.key.contains("token") }?.value
            ?: throw FrigateApiException("Frigate gaf geen frigate_token-cookie terug")
        token = t
        tokenFor = key
        log.i(TAG, "event=login_ok user_set=true")
    }

    private data class Response(val status: Int, val body: String, val cookies: Map<String, String>) {
        fun bodyOrThrow(path: String): String {
            if (status in 200..299) return body
            throw FrigateApiException("HTTP $status op $path", status)
        }
    }

    private fun request(s: AppSettings, method: String, path: String, body: String?, sendToken: Boolean = true): Response {
        val url = URL(baseUrl(s) + path)
        val conn = url.openConnection() as HttpURLConnection
        try {
            if (conn is HttpsURLConnection && s.apiTrustSelfSigned) {
                conn.sslSocketFactory = trustAllFactory
                conn.hostnameVerifier = HostnameVerifier { host, _ -> host.equals(s.host.trim(), ignoreCase = true) }
            }
            conn.requestMethod = method
            conn.connectTimeout = 4_000
            conn.readTimeout = 8_000
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            if (sendToken && s.apiMode == ApiMode.AUTHENTICATED) {
                token?.let { conn.setRequestProperty("Cookie", "frigate_token=$it") }
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val cookies = HashMap<String, String>()
            conn.headerFields?.forEach { (k, values) ->
                if (k != null && k.equals("Set-Cookie", ignoreCase = true)) {
                    values.forEach { v ->
                        val pair = v.substringBefore(';')
                        val name = pair.substringBefore('=').trim()
                        if (name.isNotEmpty()) cookies[name] = pair.substringAfter('=', "").trim()
                    }
                }
            }
            return Response(status, text, cookies)
        } catch (e: IOException) {
            throw FrigateApiException("Frigate niet bereikbaar op ${Redact.url(url.toString())}: ${e.javaClass.simpleName}", null, e)
        } finally {
            conn.disconnect()
        }
    }

    private fun sessionKey(s: AppSettings) = "${s.host}|${s.apiPort}|${s.apiUsername}|${s.apiPassword.hashCode()}|${s.apiHttps}"

    companion object {
        private const val TAG = "FS.Api"

        fun baseUrl(s: AppSettings): String {
            val scheme = if (s.apiMode == ApiMode.AUTHENTICATED && s.apiHttps) "https" else "http"
            val h = s.host.trim().let { if (it.contains(':') && !it.startsWith("[")) "[$it]" else it }
            return "$scheme://$h:${s.apiPort}"
        }

        /** Alleen gebruikt voor de Frigate-host als "self-signed accepteren" aan staat (LAN). */
        private val trustAllFactory: SSLSocketFactory by lazy {
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), SecureRandom()) }.socketFactory
        }
    }
}
