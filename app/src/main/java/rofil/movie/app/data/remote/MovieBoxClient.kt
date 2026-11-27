package rofil.movie.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class MovieBoxPlaybackCandidate(
    val json: JSONObject,
    val source: String,
    val headers: Map<String, String>,
)

/** Signed MovieBox mobile API plus the public H5 catalog API used by moviebox.py. */
class MovieBoxClient(
    private val http: OkHttpClient,
    private val deviceId: String = UUID.randomUUID().toString().replace("-", ""),
) {
    companion object {
        private const val API_BASE = "https://h5-api.aoneroom.com/wefeed-h5api-bff"
        private const val NATIVE_API_BASE = "https://api3.aoneroom.com"
        private const val BOOTSTRAP_URL = "https://apig.inmoviebox.com/wefeed-mobile-bff/tab/ranking-list?tabId=0&categoryType=4516404531735022304&page=1&perPage=1"
        private const val API_USER_AGENT = "com.community.mbox.in/50020130 (Linux; U; Android 14; en_IN; Pixel 8; Build/UD1A.230803.041; Cronet/145.0.7582.0)"
        private const val WEB_USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"
        private const val SECRET_KEY_DEFAULT_B64 = "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val WEB_DOMAINS = listOf(
            "https://123moviesfree.club", "https://movie-box.co", "https://movieboxonline.net",
            "https://sflix.film", API_BASE, "https://netnaija.film", "https://movieboxhd.net",
        )
    }

    @Volatile private var bearerToken: String? = null

    suspend fun home(): JSONObject = webGet("/home?host=moviebox.ph")

    suspend fun filter(
        tabId: Int,
        page: Int,
        perPage: Int,
        sort: String = "RECOMMEND",
        genre: String = "ALL",
        country: String = "ALL",
        year: String = "ALL",
        language: String = "ALL",
    ): JSONObject = webPost("/subject/filter", JSONObject()
        .put("tabId", tabId)
        .put("filter", JSONObject().put("sort", sort).put("genre", genre).put("country", country).put("year", year).put("language", language))
        .put("page", page).put("perPage", perPage))

    suspend fun suggestions(query: String): JSONObject = webPost(
        "/subject/search-suggest", JSONObject().put("keyword", query).put("perPage", 10),
    )

    suspend fun search(query: String, page: Int, perPage: Int): JSONObject = apiRequest(
        "POST", "/wefeed-mobile-bff/subject-api/search", JSONObject()
            .put("keyword", query).put("page", page).put("perPage", perPage),
    )

    suspend fun details(subjectId: String): JSONObject = apiRequest(
        "GET", "/wefeed-mobile-bff/subject-api/get?subjectId=${encode(subjectId)}",
    )

    suspend fun seasons(subjectId: String): JSONObject = apiRequest(
        "GET", "/wefeed-mobile-bff/subject-api/season-info?subjectId=${encode(subjectId)}",
    )

    suspend fun streams(subjectId: String, season: Int, episode: Int, detailPath: String): List<MovieBoxPlaybackCandidate> = withContext(Dispatchers.IO) {
        val token = bootstrapToken()
        val result = LinkedHashMap<String, MovieBoxPlaybackCandidate>()
        for (domain in WEB_DOMAINS) {
            try {
                val base = domain.toHttpUrl()
                val url = base.newBuilder()
                    .addPathSegments("wefeed-h5api-bff/subject/play")
                    .addQueryParameter("subjectId", subjectId)
                    .addQueryParameter("se", season.toString())
                    .addQueryParameter("ep", episode.toString())
                    .addQueryParameter("detailPath", detailPath)
                    .addQueryParameter("streamSignType", "1")
                    .addQueryParameter("supportCodecs[hevc]", "1")
                    .addQueryParameter("supportCodecs[h264]", "1")
                    .build()
                val referer = "$domain/movies/${encode(detailPath)}?id=${encode(subjectId)}&type=/movie/detail&detailSe=&detailEp=&lang=en"
                val response = requestBody(url.toString(), "GET", null, mapOf(
                    "User-Agent" to WEB_USER_AGENT,
                    "Referer" to referer,
                    "Accept" to "application/json",
                    "x-client-info" to "{\"timezone\":\"Asia/Calcutta\"}",
                    "x-request-lang" to "en",
                    "x-vip-restrict" to "0",
                    "x-no-high-risk-restrict" to "0",
                    "x-source" to "moviebox",
                    "Authorization" to "Bearer $token",
                ))
                val data = response.optJSONObject("data") ?: continue
                val streams = data.optJSONArray("streams") ?: JSONArray()
                val dash = data.optJSONArray("dash") ?: JSONArray()
                addCandidates(result, streams, "Web CDN (${URI(domain).host})", mapOf(
                    "User-Agent" to "Mozilla/5.0", "Referer" to "$domain/", "Origin" to domain,
                ))
                addCandidates(result, dash, "Web DASH (${URI(domain).host})", mapOf(
                    "User-Agent" to "Mozilla/5.0", "Referer" to "$domain/", "Origin" to domain,
                ))
                if (result.isNotEmpty()) break
            } catch (e: AccessBlockedException) {
                if (e.message?.contains("HTTP 401") == true || e.message?.contains("HTTP 441") == true) throw e
            } catch (_: Exception) {
                // Try the next documented web front end; the native endpoint below remains the fallback.
            }
        }
        try {
            val data = apiRequest("GET", "/wefeed-mobile-bff/subject-api/play-info?subjectId=${encode(subjectId)}&se=$season&ep=$episode")
                .optJSONObject("data") ?: JSONObject()
            addCandidates(result, data.optJSONArray("streams") ?: JSONArray(), "Mobile Native CDN", mapOf(
                "User-Agent" to API_USER_AGENT, "Referer" to "$NATIVE_API_BASE/",
            ))
        } catch (e: Exception) {
            if (result.isEmpty()) throw e
        }
        result.values.toList()
    }

    suspend fun streamCandidates(
        subjectId: String,
        season: Int,
        episode: Int,
        detailPath: String,
    ): List<MovieBoxPlaybackCandidate> = streams(subjectId, season, episode, detailPath)

    suspend fun captions(subjectId: String, streamId: String?, season: Int, episode: Int): JSONArray {
        var selected = streamId
        if (selected.isNullOrBlank()) {
            val data = apiRequest("GET", "/wefeed-mobile-bff/subject-api/play-info?subjectId=${encode(subjectId)}&se=$season&ep=$episode")
                .optJSONObject("data") ?: JSONObject()
            selected = data.optJSONArray("streams")?.let { streams ->
                (0 until streams.length()).firstNotNullOfOrNull { index ->
                    streams.optJSONObject(index)?.takeIf { it.has("id") }?.optString("id")
                }
            }
        }
        val out = JSONArray()
        val seen = HashSet<String>()
        fun append(items: JSONArray?) {
            for (index in 0 until (items?.length() ?: 0)) {
                val item = items?.optJSONObject(index) ?: continue
                val url = item.optString("url").takeIf { isHttpUrl(it) } ?: continue
                if (seen.add(url)) out.put(item)
            }
        }
        if (!selected.isNullOrBlank()) {
            val data = apiRequest("GET", "/wefeed-mobile-bff/subject-api/get-stream-captions?subjectId=${encode(subjectId)}&streamId=${encode(selected)}")
                .optJSONObject("data")
            append(data?.optJSONArray("extCaptions") ?: data?.optJSONArray("captions"))
        }
        val resourceId = selected ?: "0"
        val external = apiRequest("GET", "/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=${encode(subjectId)}&resourceId=${encode(resourceId)}&episode=$episode")
        val data = external.opt("data")
        when (data) {
            is JSONArray -> append(data)
            is JSONObject -> append(data.optJSONArray("extCaptions") ?: data.optJSONArray("captions"))
        }
        return out
    }

    private fun addCandidates(
        target: MutableMap<String, MovieBoxPlaybackCandidate>,
        values: JSONArray,
        source: String,
        baseHeaders: Map<String, String>,
    ) {
        for (index in 0 until values.length()) {
            val original = values.optJSONObject(index) ?: continue
            val item = JSONObject(original.toString())
            val policyUrl = extractPolicyResource(item.optString("signCookie"))
            if (policyUrl != null) item.put("url", policyUrl)
            val url = item.optString("url")
            if (!isHttpUrl(url)) continue
            val headers = baseHeaders.toMutableMap()
            item.optString("signCookie").takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
            target.putIfAbsent(url, MovieBoxPlaybackCandidate(item, source, headers))
        }
    }

    private fun extractPolicyResource(signCookie: String): String? {
        if (signCookie.isBlank()) return null
        Regex("Edge-Cache-Cookie=urlprefix=([^:;\\s]+)").find(signCookie)?.groupValues?.getOrNull(1)?.let { encoded ->
            runCatching {
                val prefix = String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).trimEnd('/')
                "$prefix/index.mpd"
            }.getOrNull()?.takeIf(::isHttpUrl)?.let { return it }
        }
        Regex("CloudFront-Policy=([^;]+)").find(signCookie)?.groupValues?.getOrNull(1)?.let { encoded ->
            val candidates = listOf(
                encoded.replace('-', '+').replace('_', '='),
                encoded.replace('-', '+').replace('_', '/'),
            )
            for (value in candidates) runCatching {
                val padded = value + "=".repeat((4 - value.length % 4) % 4)
                val json = JSONObject(String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8))
                val statements = json.optJSONArray("Statement") ?: JSONArray()
                for (index in 0 until statements.length()) {
                    val resource = statements.optJSONObject(index)?.optString("Resource") ?: continue
                    val uri = resource.trimEnd('*').trimEnd('/') + "/index.mpd"
                    if (isHttpUrl(uri)) return uri
                }
            }
        }
        return null
    }

    private suspend fun webGet(path: String): JSONObject = webRequest(path, "GET")
    private suspend fun webPost(path: String, body: JSONObject): JSONObject = webRequest(path, "POST", body)

    private suspend fun webRequest(path: String, method: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val url = "$API_BASE$path"
        try {
            requestJson(url, method, body, mapOf(
                "User-Agent" to WEB_USER_AGENT,
                "Referer" to "https://moviebox.ph/",
                "Accept" to "application/json",
            ))
        } catch (e: MovieBoxHttpException) { throw e.asSourceException() }
    }

    private suspend fun apiRequest(method: String, path: String, body: JSONObject? = null, retry: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http://") || path.startsWith("https://")) path else "$NATIVE_API_BASE$path"
        val token = bootstrapToken()
        val bodyText = body?.toString()
        val headers = authHeaders(method, url, bodyText, token)
        try {
            requestBody(url, method, bodyText?.toRequestBody(JSON), headers)
        } catch (e: MovieBoxHttpException) {
            if (retry && e.code in listOf(401, 441)) {
                bearerToken = null
                bootstrapToken(force = true)
                apiRequest(method, path, body, retry = false)
            } else throw e.asSourceException()
        }
    }

    private suspend fun bootstrapToken(force: Boolean = false): String = withContext(Dispatchers.IO) {
        if (!force) bearerToken?.let { return@withContext it }
        val headers = authHeaders("GET", BOOTSTRAP_URL, null, null)
        val response = rawRequest(BOOTSTRAP_URL, "GET", null, headers)
        val xUser = response.header("x-user")
        val payload = response.use { it.body?.string().orEmpty() }
        if (!response.isSuccessful) throw MovieBoxHttpException(response.code, "MovieBox token bootstrap").asSourceException()
        val token = try { JSONObject(xUser ?: "{}").optString("token") } catch (e: Exception) {
            throw SourceException("MovieBox token bootstrap returned an invalid response.", e)
        }
        if (token.isBlank()) {
            val upstream = runCatching { JSONObject(payload).optString("message") }.getOrNull().orEmpty()
            throw SourceException(upstream.ifBlank { "MovieBox token bootstrap did not return a token." })
        }
        bearerToken = token
        token
    }

    private fun requestJson(url: String, method: String, body: JSONObject?, headers: Map<String, String>): JSONObject =
        requestBody(url, method, body?.toString()?.toRequestBody(JSON), headers)

    private fun requestBody(url: String, method: String, body: RequestBody?, headers: Map<String, String>): JSONObject {
        val response = rawRequest(url, method, body, headers)
        val text = response.use { it.body?.string().orEmpty() }
        if (!response.isSuccessful) throw MovieBoxHttpException(response.code, "MovieBox API")
        val json = try { JSONObject(text) } catch (e: Exception) { throw SourceException("MovieBox returned invalid JSON.", e) }
        if (json.optInt("code", 0) !in listOf(0, 200)) {
            throw SourceException(json.optString("message").ifBlank { "MovieBox rejected the request." })
        }
        return json
    }

    private fun rawRequest(url: String, method: String, body: RequestBody?, headers: Map<String, String>): okhttp3.Response {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        when (method.uppercase()) {
            "POST" -> builder.post(body ?: ByteArray(0).toRequestBody(JSON))
            "GET" -> builder.get()
            else -> throw SourceException("MovieBox request method is unsupported.")
        }
        return try { http.newCall(builder.build()).execute() }
        catch (e: Exception) { throw SourceException("Could not reach MovieBox. Check your connection and retry.", e) }
    }

    private fun authHeaders(method: String, rawUrl: String, body: String?, token: String?): Map<String, String> {
        val timestamp = System.currentTimeMillis().toString()
        val bodyBytes = body?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
        val bodyLength = if (bodyBytes.isEmpty()) "" else bodyBytes.size.toString()
        val bodyHash = if (bodyBytes.isEmpty()) "" else md5(bodyBytes.copyOfRange(0, minOf(bodyBytes.size, 102400)))
        val url = rawUrl.toHttpUrl()
        val query = url.encodedQuery?.split('&')?.filter(String::isNotBlank)
            ?.sortedBy { it.substringBefore('=') }?.joinToString("&").orEmpty()
        val canonicalUrl = url.encodedPath + if (query.isBlank()) "" else "?$query"
        val canonical = listOf(method.uppercase(), "application/json", "application/json", bodyLength, timestamp, bodyHash, canonicalUrl).joinToString("\n")
        val innerKey = Base64.getDecoder().decode(Base64.getDecoder().decode(SECRET_KEY_DEFAULT_B64).toString(Charsets.UTF_8))
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(innerKey, "HmacMD5"))
        val signature = Base64.getEncoder().encodeToString(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))
        val clientInfo = JSONObject()
            .put("package_name", "com.community.mbox.in").put("version_name", "4.0.03.0920.03").put("version_code", 50020130)
            .put("os", "android").put("os_version", "14").put("device_id", deviceId).put("install_store", "official")
            .put("gaid", "1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d").put("brand", "Google").put("model", "Pixel 8")
            .put("system_language", "en").put("net", "NETWORK_WIFI").put("region", "IN").put("timezone", "Asia/Calcutta").put("sp_code", "")
        val result = linkedMapOf(
            "User-Agent" to API_USER_AGENT,
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Connection" to "keep-alive",
            "x-client-token" to "$timestamp,${md5(timestamp.reversed().toByteArray(Charsets.UTF_8))}",
            "x-tr-signature" to "$timestamp|2|$signature",
            "x-client-info" to clientInfo.toString(),
            "x-client-status" to "0",
        )
        token?.let { result["Authorization"] = "Bearer $it" }
        return result
    }

    private fun md5(value: ByteArray): String = MessageDigest.getInstance("MD5").digest(value).joinToString("") { "%02x".format(it) }
    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
    private fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    private data class MovieBoxHttpException(val code: Int, private val context: String) : Exception() {
        fun asSourceException(): SourceException = SourceException(
            if (code == 403) "MovieBox denied this request in the current region or account context. Try again later."
            else "$context failed with HTTP $code. Try again.",
            this,
        )
    }
}
