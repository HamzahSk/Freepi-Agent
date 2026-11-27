package com.freepi.lk21.data.remote

import com.freepi.lk21.core.TtlCache
import com.freepi.lk21.core.SingleFlight
import com.freepi.lk21.data.scrape.ScrapeLogger
import com.freepi.lk21.data.scrape.ScrapeKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * HTTP access to the public data source. The site is fronted by a bot challenge,
 * so we use realistic browser headers, connection pooling, a disk cache and an
 * automatic host failover list. Every failure is surfaced as a typed exception
 * so the UI can show a useful message instead of a blank screen.
 */
class Lk21Client(cacheDir: File,
    private val browserCookies: (String) -> String? = { null },
    private val receiveBrowserCookie: (String, String) -> Unit = { _, _ -> },
) {

    companion object {
        val BASE_URLS = listOf(
            // Captured hosts (lk21-request/) first, then the known mirrors.
            "https://tv13.lk21official.cc",
            "https://tv.lk21online.de",
            "https://tv.lk21official.cc",
            "https://tv6.lk21.am.in",
        )
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36"


    }

    private val diskCache = Cache(File(cacheDir, "http_cache"), 40L * 1024 * 1024)

    private val storedCookies = java.util.concurrent.ConcurrentHashMap<String, List<okhttp3.Cookie>>()
    private val cookieJar = object : okhttp3.CookieJar {
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            storedCookies.compute(url.host) { _, old ->
                ((old.orEmpty().filter { prior -> cookies.none { it.name == prior.name && it.path == prior.path } }) + cookies)
                    .filter { it.expiresAt > System.currentTimeMillis() }.takeLast(100)
            }
            cookies.forEach { cookie -> runCatching { receiveBrowserCookie(url.toString(), cookie.toString()) } }
        }
        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> =
            storedCookies.values.flatten().filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
    }

    /** Only cookies legitimately issued by these origins are forwarded to playback. */
    fun playbackHeaders(url: String): Map<String, String> {
        val cookie = runCatching { browserCookies(url) }.getOrNull()?.takeIf { it.isNotBlank() } ?: runCatching {
            cookieJar.loadForRequest(url.toHttpUrl()).joinToString("; ") { "${it.name}=${it.value}" }
        }.getOrDefault("")
        return if (cookie.isBlank()) emptyMap() else mapOf("Cookie" to cookie)
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cache(diskCache)
        .cookieJar(cookieJar)
        .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 12; maxRequestsPerHost = 4 })
        .callTimeout(25, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val original = chain.request()
            val request = original.newBuilder()
                .header("User-Agent", USER_AGENT)
                .header(
                    "Accept",
                    original.header("Accept") ?: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                .header("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
                .header("Cache-Control", original.header("Cache-Control") ?: "max-age=300")
                .header("Upgrade-Insecure-Requests", "1")
                .build()
            chain.proceed(request)
        }
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            // Re-evaluate cookies for every redirect/segment origin, never reuse a Cookie header across hosts.
            val cookie = playbackHeaders(request.url.toString())["Cookie"]
            val scoped = request.newBuilder().removeHeader("Cookie")
            if (!cookie.isNullOrBlank()) scoped.header("Cookie", cookie)
            chain.proceed(scoped.build())
        }
        .build()

    // Share connections/cookies/cache, but long progressive playback must not inherit a scrape call deadline.
    private val playbackClient: OkHttpClient by lazy {
        client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    /** Shared client for ExoPlayer's OkHttp data source. */
    fun playerHttpClient(): OkHttpClient = playbackClient

    @Volatile
    var activeBase: String = BASE_URLS.first()
        private set

    private val htmlFlight = SingleFlight<Pair<String, Boolean>, String>()
    private val catalogPermits = Semaphore(4)
    private val finalUrls = TtlCache<String, String>(maxEntries = 250)
    private val htmlCache = TtlCache<String, String>(maxEntries = 250)

    /** Resolve a path ("/page/2/") or absolute URL against the currently working host. */
    fun absolute(pathOrUrl: String): String {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) return pathOrUrl
        val base = activeBase.trimEnd('/')
        return if (pathOrUrl.startsWith("/")) base + pathOrUrl else "$base/$pathOrUrl"
    }

    /** Update the working base host after a successful page load/resolve. */
    fun noteWorkingBase(finalUrl: String) {
        if (finalUrl.isBlank()) return
        runCatching {
            val u = URL(finalUrl)
            val base = "${u.protocol}://${u.host}"
            if (u.host.isNotBlank()) activeBase = base
        }
    }

    /** Candidates for a URL: the URL itself followed by the same path on other mirrors. */
    private fun candidates(pathOrUrl: String): List<String> {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            val uri = runCatching { URL(pathOrUrl) }.getOrNull() ?: return listOf(pathOrUrl)
            if (BASE_URLS.none { URL(it).host == uri.host }) return listOf(pathOrUrl)
            val path = (uri.path.ifEmpty { "/" }) +
                (uri.query?.let { "?$it" } ?: "")
            val sameHost = listOf(pathOrUrl)
            val mirrors = BASE_URLS
                .filter { !pathOrUrl.startsWith(it) }
                .map { it.trimEnd('/') + path }
            return (sameHost + mirrors).distinct()
        }
        val normalized = if (pathOrUrl.startsWith("/")) pathOrUrl else "/$pathOrUrl"
        return (listOf(activeBase) + BASE_URLS).distinct().map { it.trimEnd('/') + normalized }
    }

    suspend fun fetchHtml(
        pathOrUrl: String,
        forceRefresh: Boolean = false,
        ttlMillis: Long = 5 * 60_000L,
    ): String = withContext(Dispatchers.IO) {
        val first = absolute(pathOrUrl)
        if (!forceRefresh) htmlCache.get(first)?.let {
            ScrapeLogger.stage("catalog", ScrapeKind.LIST, "cache", first, detail = "hit")
            return@withContext it
        }
        htmlFlight.run(first to forceRefresh) {
            catalogPermits.withPermit {
                if (!forceRefresh) htmlCache.get(first)?.let { return@withPermit it }
                ScrapeLogger.stage("catalog", ScrapeKind.LIST, "cache", first, detail = "miss")
                var lastError: Throwable? = null
                for (candidate in candidates(pathOrUrl).take(3)) {
                    try {
                        val response = execute(candidate, forceRefresh)
                        val body = response.body
                        finalUrls.put(first, response.url, ttlMillis)
                        val base = runCatching { URL(response.url) }.getOrNull()
                            ?.let { "${it.protocol}://${it.host}" }
                        if (base != null) activeBase = base
                        htmlCache.put(first, body, ttlMillis)
                        return@withPermit body
                    } catch (e: CancellationException) { throw e
                    } catch (e: AccessBlockedException) {
                        throw e
                    } catch (e: IOException) {
                        lastError = e
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
                when (lastError) {
                    is AccessBlockedException -> throw lastError as AccessBlockedException
                    is SocketTimeoutException -> throw NetworkUnavailableException(
                        "The data source timed out. Check your connection and try again.", lastError
                    )
                    is IOException -> throw NetworkUnavailableException(
                        "Could not reach the data source. Check your connection and try again.", lastError
                    )
                    else -> throw SourceException(
                        lastError?.message ?: "Could not load data from the source.", lastError
                    )
                }
            }
        }
    }

    private suspend fun execute(url: String, forceRefresh: Boolean): PlayerResponse {
        val builder = Request.Builder().url(url).get()
        if (forceRefresh) builder.cacheControl(okhttp3.CacheControl.Builder().noCache().build())
        val response = exchange(builder.build())
        if (isChallenge(response.body)) throw AccessBlockedException()
        if (response.body.isBlank()) throw IOException("Empty catalog response")
        return response
    }

    private fun isChallenge(body: String): Boolean =
        Regex("<title>\\s*(Just a moment|Attention Required)", RegexOption.IGNORE_CASE).containsMatchIn(body) ||
            body.contains("id=\"challenge-form\"")

    suspend fun fetchDocument(
        pathOrUrl: String,
        forceRefresh: Boolean = false,
        ttlMillis: Long = 5 * 60_000L,
    ): Document = withContext(Dispatchers.IO) {
        val requested = absolute(pathOrUrl)
        val html = fetchHtml(pathOrUrl, forceRefresh, ttlMillis)
        val baseUri = finalUrls.get(requested) ?: requested
        Jsoup.parse(html, baseUri)
    }

    /**
     * Fetch a third-party embed/player page while looking like it was opened from
     * the movie page. Used by the stream resolver.
     */
    suspend fun fetchPlayerPage(url: String, referer: String): String = withContext(Dispatchers.IO) {
        playerResponse(url, referer).body
    }

    /** Player exchanges never use catalog mirror failover or cached API responses. */
    suspend fun playerResponse(url: String, referer: String, media: Boolean = false,
        popup: PopupRequest? = null): PlayerResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
            .header("Referer", referer)
            .header("Origin", java.net.URI(referer).let { "${it.scheme}://${it.authority}" })
            .cacheControl(okhttp3.CacheControl.Builder().noCache().build())
        if (popup != null) builder.post(okhttp3.FormBody.Builder()
            .add("host", popup.host).add("id", popup.id).build())
        if (media) builder.header("Range", "bytes=0-524287")
        exchange(builder.build(), 524288).also { response ->
            if (isChallenge(response.body)) throw AccessBlockedException()
        }
    }

    /** Cancellable through response-body reading, not just through receipt of headers. */
    private suspend fun exchange(request: Request, limit: Long? = null): PlayerResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            val started = System.nanoTime()
            val kind = if (request.header("Referer") != null) ScrapeKind.STREAM else ScrapeKind.LIST
            ScrapeLogger.stage("http", kind, "request", request.url.toString(), detail = "method=${request.method}")
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            var hop: Response? = it.priorResponse
                            while (hop != null) {
                                ScrapeLogger.stage("http", kind, "redirect", hop.request.url.toString(),
                                    detail = "status=${hop.code} destination=${hop.header("Location")?.let { location -> hop?.request?.url?.resolve(location) }}")
                                hop = hop.priorResponse
                            }
                            ScrapeLogger.stage("http", kind, "headers", it.request.url.toString(),
                                detail = "method=${request.method} status=${it.code} contentType=${it.header("Content-Type").orEmpty().substringBefore(';')}")
                            if (it.code in listOf(401, 403, 429)) throw AccessBlockedException()
                            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
                            val type = it.header("Content-Type").orEmpty()
                            var truncated = false
                            val body = if (limit != null) {
                                val bytes = it.peekBody(limit + 1).bytes()
                                val contentRange = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it.header("Content-Range").orEmpty())
                                truncated = bytes.size > limit || (contentRange != null &&
                                    contentRange.groupValues[2].toLong() + 1 < contentRange.groupValues[3].toLong())
                                String(if (bytes.size > limit) bytes.copyOf(limit.toInt()) else bytes, if (type.startsWith("video/", true) || type.startsWith("application/octet-stream", true))
                                    Charsets.ISO_8859_1 else Charsets.UTF_8)
                            } else it.body?.string().orEmpty()
                            ScrapeLogger.stage("http", kind, "response", it.request.url.toString(),
                                elapsedMs = (System.nanoTime() - started) / 1_000_000,
                                detail = "requests=1 cacheHit=${it.cacheResponse != null} bytes=${body.length}")
                            PlayerResponse(it.request.url.toString(), body, type, it.code, truncated)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }

    fun clearCache() {
        htmlCache.clear()
        finalUrls.clear()
        runCatching { diskCache.evictAll() }
    }
}
