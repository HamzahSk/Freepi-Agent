package com.freepi.lk21.data.remote

import com.freepi.lk21.data.model.ResolvedStream
import com.freepi.lk21.data.model.StreamSource
import com.freepi.lk21.data.model.SubtitleTrack
import java.net.URI
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a provider embed link into a media URL that ExoPlayer can play.
 *
 * The data source lists embed hosts (P2P/Hownetwork, Turbovid, FileLions,
 * FileMoon, Hydrax/short.ink, ...) wrapped in `playeriframe.lol/iframe.php`.
 * Those pages expose their media either as a direct <video>/<source> tag, a
 * `file:`/`sources:` JS field, a packed JS blob, or another iframe. We follow
 * those layers (bounded depth), unpack JS, collect subtitle tracks and return
 * the first URL with a recognizable media extension.
 */
class StreamResolver(private val client: Lk21Client) {

    companion object {
        private val MEDIA_EXT = Regex("""\.(m3u8|mp4|m4v|webm|mpd|mov)(\?|#|$)""", RegexOption.IGNORE_CASE)
        private val DIRECT_MEDIA = Regex(
            """https?://[^\s"'<>()]+?\.(?:m3u8|mp4|m4v|webm|mpd|mov)(?:\?[^\s"'<>()]*)?""",
            RegexOption.IGNORE_CASE
        )
        private const val MAX_DEPTH = 3
    }

    private val resolutionCache = ConcurrentHashMap<String, ResolvedStream>()

    suspend fun resolve(source: StreamSource, pageUrl: String): ResolvedStream {
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
                    referer = pageUrl,
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
            } catch (e: AccessBlockedException) {
                lastError = e
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (lastError is AccessBlockedException) throw AccessBlockedException(
            "The video provider is blocking access right now. Try another source.",
            lastError
        )
        throw StreamResolutionException(
            lastError?.message
                ?: "No playable stream was found for this provider. Try another source.",
            lastError
        )
    }

    private suspend fun resolveUrl(
        url: String,
        referer: String,
        depth: Int,
        visited: MutableSet<String>,
        subtitles: MutableMap<String, SubtitleTrack>,
    ): ResolvedStream? {
        if (depth > MAX_DEPTH || !visited.add(url)) return null
        val cleanUrl = url.replace("\\/", "/").trim()

        if (MEDIA_EXT.containsMatchIn(cleanUrl)) {
            val finalSubtitles = if (isHls(cleanUrl)) fetchHlsSubtitles(cleanUrl, referer) else emptyList()
            finalSubtitles.forEach { subtitles[it.url] = it }
            return ResolvedStream(
                provider = "",
                url = cleanUrl,
                mimeType = mimeFor(cleanUrl),
                headers = headersFor(cleanUrl, referer),
                subtitles = finalSubtitles,
                label = "",
            )
        }

        val html = try {
            client.fetchPlayerPage(cleanUrl, referer)
        } catch (e: Exception) {
            return null
        }
        if (html.isBlank()) return null

        // Subtitles advertised anywhere on the page.
        Regex("""(?:https?:)?//[^\s"'<>\\]+?\.(?:srt|vtt)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
            .findAll(html.replace("\\/", "/"))
            .forEach { match ->
                val absolute = match.value.let { if (it.startsWith("//")) "https:$it" else it }
                subtitles[absolute] = SubtitleTrack(
                    label = absolute.substringAfterLast('/').substringBefore('?'),
                    url = absolute,
                )
            }
        Regex("""(?:file|src|url)\s*:\s*["']([^"']+\.(?:srt|vtt)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { match -> addSubtitle(match.groupValues[1], referer, subtitles) }

        // Expand packed JS before scanning for URLs.
        val expanded = buildString {
            append(html)
            JsUnpacker.unpackAll(html).forEach { append('\n').append(it) }
            decodeAtob(html).forEach { append('\n').append(it) }
        }

        val mediaCandidates = LinkedHashSet<String>()
        // Direct media URLs in markup or script.
        DIRECT_MEDIA.findAll(expanded.replace("\\/", "/")).forEach {
            mediaCandidates.add(it.value)
        }
        // file:/src:/source: fields (strip query-less player config values).
        Regex("""(?:file|src|source|url)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(expanded)
            .forEach { match ->
                val value = match.groupValues[1].replace("\\/", "/")
                if (MEDIA_EXT.containsMatchIn(value)) mediaCandidates.add(value)
            }

        for (candidate in mediaCandidates) {
            val absolute = absolutize(candidate, cleanUrl) ?: continue
            if (!absolute.startsWith("http")) continue
            if (MEDIA_EXT.containsMatchIn(absolute)) {
                val hlsSubs = if (isHls(absolute)) fetchHlsSubtitles(absolute, referer) else emptyList()
                hlsSubs.forEach { subtitles[it.url] = it }
                return ResolvedStream(
                    provider = "",
                    url = absolute,
                    mimeType = mimeFor(absolute),
                    headers = headersFor(absolute, referer),
                    subtitles = hlsSubs,
                    label = "",
                )
            }
        }

        // Follow iframes (the wrapper page -> actual host page).
        val frames = LinkedHashSet<String>()
        Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(expanded)
            .forEach { frames.add(it.groupValues[1]) }
        for (frame in frames) {
            val absolute = absolutize(frame, cleanUrl) ?: continue
            if (absolute.startsWith("http")) {
                resolveUrl(absolute, referer, depth + 1, visited, subtitles)?.let { return it }
            }
        }

        // Some hosts put the real player URL in an obfuscated redirect.
        Regex("""(?:location\.href|window\.location)\s*=\s*["']([^"']+)["']""")
            .findAll(expanded)
            .forEach { match ->
                val absolute = absolutize(match.groupValues[1], cleanUrl) ?: return@forEach
                if (absolute.startsWith("http") && depth < MAX_DEPTH) {
                    resolveUrl(absolute, referer, depth + 1, visited, subtitles)?.let { return it }
                }
            }

        return null
    }

    private fun addSubtitle(raw: String, referer: String, subtitles: MutableMap<String, SubtitleTrack>) {
        val absolute = absolutize(raw, referer) ?: return
        if (!absolute.startsWith("http")) return
        subtitles[absolute] = SubtitleTrack(
            label = absolute.substringAfterLast('/').substringBefore('?'),
            url = absolute,
        )
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
            else -> runCatching {
                val uri = URI(base)
                val basePath = uri.path.substringBeforeLast('/', "")
                "${uri.scheme}://${uri.host}$basePath/$v"
            }.getOrNull()
        }
    }

    private fun isHls(url: String): Boolean =
        url.lowercase(Locale.ROOT).contains(".m3u8")

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
        val origin = runCatching {
            val uri = URI(streamUrl)
            "${uri.scheme}://${uri.host}"
        }.getOrNull()
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
                out.add(decoded)
            }
        }
        return out
    }
}

/**
 * Minimal implementation of the classic Dean Edwards p.a.c.k.e.r used by many
 * video hosts to hide their `sources` array.
 */
object JsUnpacker {

    private val EVAL_REGEX = Regex(
        """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\)\s*\{[\s\S]*?\}\s*\(\s*'((?:\\.|[^'])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\.|[^'])*)'\s*\.\s*split\s*\(\s*'\|'\s*\)\s*(?:,\s*\d+\s*,\s*\{[^}]*\})?\s*\)\s*\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun unpackAll(html: String): List<String> {
        val results = ArrayList<String>()
        for (match in EVAL_REGEX.findAll(html)) {
            val payload = unescapeJs(match.groupValues[1])
            val radix = match.groupValues[2].toIntOrNull() ?: 62
            val count = match.groupValues[3].toIntOrNull() ?: 0
            val words = unescapeJs(match.groupValues[4]).split('|').toMutableList()
            while (words.size < count) words.add("")
            results.add(unpack(payload, radix, count, words))
        }
        return results
    }

    private fun unpack(payload: String, radix: Int, count: Int, words: MutableList<String>): String {
        for (i in 0 until words.size) {
            if (words[i].isEmpty()) words[i] = encode(i, radix)
        }
        var result = payload
        for (i in (count - 1) downTo 0) {
            if (i >= words.size || words[i].isEmpty()) continue
            val token = Regex.escape(encode(i, radix))
            result = result.replace(Regex("\\b$token\\b"), words[i])
        }
        return result
    }

    /** JS `e(c)` from the packer: base-N digits, with a custom 36+ alphabet. */
    private fun encode(c0: Int, radix: Int): String {
        var c = c0
        val sb = StringBuilder()
        if (c >= radix) sb.append(encode(c / radix, radix))
        val remainder = c % radix
        sb.append(
            if (remainder > 35) (remainder + 29).toChar()
            else remainder.toString(36)
        )
        return sb.toString()
    }

    private fun unescapeJs(input: String): String {
        var s = input
        s = s.replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\")
        s = s.replace("\\/", "/")
        s = s.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
        s = Regex("""\\x([0-9a-fA-F]{2})""").replace(s) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        s = Regex("""\\u([0-9a-fA-F]{4})""").replace(s) {
            it.groupValues[1].toInt(16).toChar().toString()
        }
        return s
    }
}
