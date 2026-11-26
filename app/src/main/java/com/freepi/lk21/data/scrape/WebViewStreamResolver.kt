package com.freepi.lk21.data.scrape

import com.freepi.lk21.data.model.MovieDetail
import com.freepi.lk21.data.model.ResolvedStream
import com.freepi.lk21.data.model.StreamSource
import com.freepi.lk21.data.model.SubtitleTrack
import com.freepi.lk21.data.remote.JsUnpacker
import com.freepi.lk21.data.remote.Lk21Client
import com.freepi.lk21.data.remote.StreamResolutionException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a provider embed into a playable media URL by rendering the embed
 * inside the same hidden WebView used for catalog scraping. Because the embed
 * page runs in Chromium, dynamically created <video> elements, iframes and
 * script objects are fully available to the injected extractor.
 *
 * Fallback to the HTTP resolver happens inside [CompositeStreamProvider].
 */
class WebViewStreamResolver(
    private val engine: WebViewScrapeEngine,
    private val client: Lk21Client,
    private val logger: ScrapeLogger = ScrapeLogger,
) : StreamProvider {

    companion object {
        private val MEDIA_EXT = Regex("""\.(m3u8|mp4|m4v|webm|mpd|mov)(\?|#|$)""", RegexOption.IGNORE_CASE)
        private const val MAX_DEPTH = 2
    }

    private val resolutionCache = ConcurrentHashMap<String, ResolvedStream>()

    override suspend fun resolve(detail: MovieDetail, source: StreamSource): ResolvedStream {
        resolutionCache[source.embedUrl]?.let { return it }

        val visited = HashSet<String>()
        val subtitles = LinkedHashMap<String, SubtitleTrack>()
        val attempts = LinkedHashSet<String>().apply {
            add(source.decodedUrl)
            add(source.embedUrl)
        }

        var lastError: Throwable? = null
        for (start in attempts) {
            if (start.isBlank() || !start.startsWith("http")) continue
            try {
                val resolved = resolveUrl(
                    url = start,
                    referer = source.embedUrl.takeIf { it.isNotBlank() } ?: detail.url,
                    depth = 0,
                    visited = visited,
                    subtitles = subtitles,
                )
                if (resolved != null) {
                    val withSubs = resolved.copy(
                        subtitles = (resolved.subtitles + subtitles.values).distinctBy { it.url }
                    )
                    resolutionCache[source.embedUrl] = withSubs
                    return withSubs
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                lastError = t
            }
        }
        throw StreamResolutionException(
            lastError?.message
                ?: "No playable stream was found for this provider. Try another source.",
            lastError,
        )
    }

    private suspend fun resolveUrl(
        url: String,
        referer: String,
        depth: Int,
        visited: MutableSet<String>,
        subtitles: MutableMap<String, SubtitleTrack>,
    ): ResolvedStream? {
        val cleanUrl = url.replace("\\/", "/").trim()
        if (depth > MAX_DEPTH || !visited.add(cleanUrl)) return null
        if (MEDIA_EXT.containsMatchIn(cleanUrl)) return buildStream(cleanUrl, referer, null, subtitles)

        val envelope = try {
            engine.scrape(
                ScrapeRequest(
                    kind = ScrapeKind.STREAM,
                    url = cleanUrl,
                    referer = referer,
                    timeoutMs = 25_000,
                    retries = 0,
                )
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            logger.warn("stream", ScrapeKind.STREAM, "embed_scrape_failed", t.message.orEmpty(), cleanUrl)
            return null
        }
        val data = try {
            ScrapeValidator.requireStream(envelope)
        } catch (t: Throwable) {
            logger.warn("stream", ScrapeKind.STREAM, "stream_validation_failed", t.message.orEmpty(), cleanUrl)
            return null
        }

        data.optJSONArray("subtitles")?.let(::collectSubtitles)?.forEach { subtitles[it.url] = it }

        // Media directly detected on the page.
        for (i in 0 until (data.optJSONArray("media")?.length() ?: 0)) {
            val item = data.optJSONArray("media")?.optJSONObject(i) ?: continue
            val mediaUrl = item.optString("url")
            if (MEDIA_EXT.containsMatchIn(mediaUrl)) {
                val built = buildStream(mediaUrl, cleanUrl, referer, subtitles)
                if (built != null) return built
            }
        }

        // Media hidden inside packed/obfuscated scripts.
        val scripts = data.optJSONArray("scripts") ?: JSONArray()
        val expanded = StringBuilder()
        for (i in 0 until scripts.length()) {
            val text = scripts.optString(i)
            expanded.append(text).append('\n')
            runCatching { JsUnpacker.unpackAll(text) }.getOrNull()?.forEach { expanded.append(it).append('\n') }
            runCatching { decodeAtob(text) }.getOrNull()?.forEach { expanded.append(it).append('\n') }
        }
        if (expanded.isNotEmpty()) {
            Regex("""(?:file|src|source|url)\s*[:=]\s*["']([^"']+?\.(?:m3u8|mp4|m4v|webm|mpd|mov)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
                .findAll(expanded.toString())
                .forEach { match ->
                    val candidate = match.groupValues[1].replace("\\/", "/")
                    val absolute = absolutize(candidate, cleanUrl)
                    if (absolute != null && MEDIA_EXT.containsMatchIn(absolute)) {
                        val built = buildStream(absolute, cleanUrl, referer, subtitles)
                        if (built != null) return built
                    }
                }
            Regex("""https?://[^\s"'<>()\\]+?\.(?:m3u8|mp4|m4v|webm|mpd|mov)(?:\?[^\s"'<>()\\]*)?""", RegexOption.IGNORE_CASE)
                .findAll(expanded.toString())
                .forEach { match ->
                    val absolute = absolutize(match.value, cleanUrl)
                    if (absolute != null) {
                        val built = buildStream(absolute, cleanUrl, referer, subtitles)
                        if (built != null) return built
                    }
                }
        }

        // Follow nested iframes (wrapper -> actual host).
        for (i in 0 until (data.optJSONArray("iframes")?.length() ?: 0)) {
            val frame = data.optJSONArray("iframes")?.optString(i).orEmpty()
            val absolute = absolutize(frame, cleanUrl)
            if (absolute != null && absolute.startsWith("http")) {
                val resolved = resolveUrl(absolute, referer, depth + 1, visited, subtitles)
                if (resolved != null) return resolved
            }
        }

        return null
    }

    private suspend fun buildStream(
        url: String,
        embedUrl: String,
        referer: String?,
        subtitles: MutableMap<String, SubtitleTrack>,
    ): ResolvedStream? {
        val clean = url.trim()
        if (!clean.startsWith("http")) return null
        val hlsSubs = if (isHls(clean)) fetchHlsSubtitles(clean, embedUrl) else emptyList()
        hlsSubs.forEach { subtitles[it.url] = it }
        return ResolvedStream(
            provider = "",
            url = clean,
            mimeType = mimeFor(clean),
            headers = headersFor(clean, embedUrl),
            subtitles = hlsSubs,
            label = "",
        )
    }

    private fun collectSubtitles(array: JSONArray): List<SubtitleTrack> {
        val out = LinkedHashMap<String, SubtitleTrack>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val raw = item.optString("url")
            if (raw.isBlank()) continue
            val absolute = absolutize(raw, "") ?: continue
            if (!absolute.startsWith("http")) continue
            out[absolute] = SubtitleTrack(
                label = item.optString("label").ifBlank {
                    absolute.substringAfterLast('/').substringBefore('?')
                },
                url = absolute,
                language = item.optString("language").takeIf { it.isNotBlank() },
            )
        }
        return out.values.toList()
    }

    private suspend fun fetchHlsSubtitles(masterUrl: String, referer: String): List<SubtitleTrack> {
        val text = runCatching { client.fetchPlayerPage(masterUrl, referer) }.getOrNull() ?: return emptyList()
        val out = LinkedHashMap<String, SubtitleTrack>()
        Regex("""#EXT-X-MEDIA:[^\n]*TYPE=SUBTITLES[^\n]*URI="([^"]+)"""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { match ->
                val url = absolutize(match.groupValues[1], masterUrl) ?: return@forEach
                val name = Regex("""NAME="([^"]+)"""").find(match.value)?.groupValues?.get(1)
                    ?: "Subtitle"
                out[url] = SubtitleTrack(label = name, url = url)
            }
        return out.values.toList()
    }

    private fun absolutize(value: String, base: String): String? {
        val v = value.trim()
        return when {
            v.startsWith("//") -> "https:$v"
            v.startsWith("http://") || v.startsWith("https://") -> v
            v.startsWith("/") -> runCatching {
                val uri = URI(base)
                "${uri.scheme}://${uri.host}$v"
            }.getOrNull()
            v.startsWith("data:") || v.startsWith("blob:") || v.isBlank() -> null
            base.isBlank() -> null
            else -> runCatching { java.net.URL(java.net.URL(base), v).toString() }.getOrNull()
        }
    }

    private fun isHls(url: String): Boolean = url.lowercase(Locale.ROOT).contains(".m3u8")

    private fun mimeFor(url: String): String {
        val path = url.lowercase(Locale.ROOT)
        return when {
            path.contains(".m3u8") -> "application/x-mpegURL"
            path.contains(".mpd") -> "application/dash+xml"
            path.contains(".webm") -> "video/webm"
            path.contains(".mov") -> "video/quicktime"
            else -> "video/mp4"
        }
    }

    private fun headersFor(streamUrl: String, pageUrl: String): Map<String, String> {
        val origin = runCatching { val uri = URI(streamUrl); "${uri.scheme}://${uri.host}" }.getOrNull()
        return buildMap {
            put("Referer", pageUrl)
            put("User-Agent", Lk21Client.USER_AGENT)
            if (origin != null) put("Origin", origin)
        }
    }

    private fun decodeAtob(html: String): List<String> {
        val out = ArrayList<String>()
        Regex("""atob\(\s*["']([A-Za-z0-9+/=\s]+)["']\s*\)""").findAll(html).forEach { match ->
            runCatching {
                val decoded = String(Base64.getDecoder().decode(match.groupValues[1].trim()))
                if (MEDIA_EXT.containsMatchIn(decoded) || decoded.contains("m3u8", true)) out.add(decoded)
            }
        }
        return out
    }
}