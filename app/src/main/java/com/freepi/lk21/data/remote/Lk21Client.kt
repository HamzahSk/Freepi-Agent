package com.freepi.lk21.data.remote

import com.freepi.lk21.core.TtlCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
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
class Lk21Client(cacheDir: File) {

    companion object {
        val BASE_URLS = listOf(
            "https://tv13.lk21official.cc",
            "https://tv.lk21official.cc",
            "https://tv6.lk21.am.in",
        )
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        private val CHALLENGE_MARKERS = listOf(
            "Just a moment...",
            "__cf_chl",
            "cf-browser-verification",
            "challenge-platform",
            "Performing security verification",
        )
    }

    private val diskCache = Cache(File(cacheDir, "http_cache"), 40L * 1024 * 1024)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cache(diskCache)
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
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                )
                .header("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Cache-Control", if (original.cacheControl.noCache) "no-cache" else "max-age=300")
                .header("Upgrade-Insecure-Requests", "1")
                .build()
            chain.proceed(request)
        }
        .build()

    /** Shared client for ExoPlayer's OkHttp data source. */
    fun playerHttpClient(): OkHttpClient = client

    @Volatile
    var activeBase: String = BASE_URLS.first()
        private set

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
            val path = (uri.path.ifEmpty { "/" }) +
                (uri.query?.let { "?$it" } ?: "")
            val sameHost = listOf(pathOrUrl)
            val mirrors = BASE_URLS
                .filter { !pathOrUrl.startsWith(it) }
                .map { it.trimEnd('/') + path }
            return (sameHost + mirrors).distinct()
        }
        val normalized = if (pathOrUrl.startsWith("/")) pathOrUrl else "/$pathOrUrl"
        return BASE_URLS.map { it.trimEnd('/') + normalized }
    }

    suspend fun fetchHtml(
        pathOrUrl: String,
        forceRefresh: Boolean = false,
        ttlMillis: Long = 5 * 60_000L,
    ): String = withContext(Dispatchers.IO) {
        val first = absolute(pathOrUrl)
        if (!forceRefresh) {
            htmlCache.get(first)?.let { return@withContext it }
        }
        var lastError: Throwable? = null
        for (candidate in candidates(pathOrUrl).take(3)) {
            try {
                val body = execute(candidate, forceRefresh)
                val base = runCatching { URL(candidate) }.getOrNull()
                    ?.let { "${it.protocol}://${it.host}" }
                if (base != null) activeBase = base
                htmlCache.put(first, body, ttlMillis)
                return@withContext body
            } catch (e: AccessBlockedException) {
                lastError = e
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

    private fun execute(url: String, forceRefresh: Boolean): String {
        val builder = Request.Builder().url(url).get()
        if (forceRefresh) builder.cacheControl(okhttp3.CacheControl.Builder().noCache().build())
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 403 || response.code == 429 || isChallenge(body)) {
                throw AccessBlockedException()
            }
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $url")
            }
            if (body.isBlank()) throw IOException("Empty response for $url")
            return body
        }
    }

    private fun isChallenge(body: String): Boolean =
        body.length < 40_000 && CHALLENGE_MARKERS.any { body.contains(it, ignoreCase = true) }

    suspend fun fetchDocument(
        pathOrUrl: String,
        forceRefresh: Boolean = false,
        ttlMillis: Long = 5 * 60_000L,
    ): Document = withContext(Dispatchers.IO) {
        val html = fetchHtml(pathOrUrl, forceRefresh, ttlMillis)
        val baseUri = absolute(pathOrUrl)
        Jsoup.parse(html, baseUri)
    }

    /**
     * Fetch a third-party embed/player page while looking like it was opened from
     * the movie page. Used by the stream resolver.
     */
    suspend fun fetchPlayerPage(url: String, referer: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Referer", referer)
            .header("Accept", "*/*")
            .header("Sec-Fetch-Dest", "iframe")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "cross-site")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 403 || isChallenge(body)) throw AccessBlockedException()
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
                body
            }
        } catch (e: IOException) {
            throw NetworkUnavailableException("Could not open the video provider.", e)
        }
    }

    fun clearCache() {
        htmlCache.clear()
        runCatching { diskCache.evictAll() }
    }
}
