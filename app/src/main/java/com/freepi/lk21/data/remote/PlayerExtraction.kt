package com.freepi.lk21.data.remote

import com.freepi.lk21.data.model.ResolvedStream
import com.freepi.lk21.data.model.SubtitleTrack
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI

enum class PlayerStage {
    INITIAL_PAGE, API_RESPONSE, EMBED_URL, PROVIDER_PAGE, PLAYER_INITIALIZATION, STREAM_METADATA
}

data class PlayerResponse(val url: String, val body: String, val contentType: String = "")
data class PopupRequest(val url: String, val host: String, val id: String)

/** Pure parsing shared by HTTP, browser bridge and fixture tests. No DOM execution in Rhino. */
object PlayerMetadata {
    fun absolute(raw: String, base: String): String? = raw.takeIf { it.isNotBlank() }?.let { runCatching {
        URI(base).resolve(raw.replace("\\/", "/").trim()).takeIf {
            it.scheme in listOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null
        }?.toString()
    }.getOrNull() }

    fun mime(url: String): String? {
        // Match the path, never a query parameter containing a media filename.
        val path = runCatching { URI(url).path.lowercase() }.getOrNull() ?: return null
        return when (path.substringAfterLast('.')) {
            "m3u8" -> "application/x-mpegURL"
            "mpd" -> "application/dash+xml"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            else -> null // Includes individual ts/m4s/aac segments and iframe URLs.
        }
    }

    fun popup(html: String, pageUrl: String): PopupRequest? {
        val doc = Jsoup.parse(html)
        if (doc.selectFirst("iframe#video-iframe") == null) return null
        for (script in doc.select("script:not([src])")) {
            val text = script.data()
            val endpoint = Regex("""\.open\(\s*["']POST["']\s*,\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.get(1) ?: continue
            val api = absolute(endpoint, pageUrl) ?: continue
            if (URI(api).path != "/api.php" || URI(api).authority != URI(pageUrl).authority) continue
            if (!text.contains("embedUrl") || !text.contains(".send(")) continue
            fun value(name: String): String? = Regex("""\b(?:var|let|const)\s+$name\s*=\s*("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')""")
                .find(text)?.groupValues?.get(1)?.let { JsExtractor.evalExpression(it) as? String }
            val host = value("host") ?: continue
            val id = value("id") ?: continue
            if (host.isNotBlank() && id.isNotBlank()) return PopupRequest(api, host, id)
        }
        return null
    }

    fun embed(json: String, apiUrl: String): String {
        val obj = JsExtractor.parseJson(json) as? JSONObject
            ?: throw StreamResolutionException("API_RESPONSE: expected a JSON object")
        val raw = obj.opt("embedUrl") as? String
            ?: throw StreamResolutionException("API_RESPONSE: missing string embedUrl")
        return absolute(raw, apiUrl)?.takeIf { raw.isNotBlank() }
            ?: throw StreamResolutionException("EMBED_URL: invalid HTTP embedUrl")
    }

    fun parse(html: String, base: String): JSONObject {
        val doc = Jsoup.parse(html, base)
        val media = JSONArray()
        val tracks = JSONArray()
        val frames = JSONArray()
        val seen = HashSet<String>()
        fun media(raw: String, type: String = "") {
            val url = absolute(raw, base) ?: return
            val typed = Regex("""^(?:video/|application/(?:x-mpegurl|vnd\.apple\.mpegurl|dash\+xml)|hls$|dash$|mp4$|webm$)""", RegexOption.IGNORE_CASE)
                .containsMatchIn(type)
            if ((mime(url) != null || typed) && seen.add(url)) media.put(JSONObject().put("url", url).put("type", type))
        }
        fun track(raw: String, label: String?, language: String?) {
            val url = absolute(raw, base) ?: return
            tracks.put(JSONObject().put("url", url).put("label", label ?: "Subtitle")
                .put("language", language ?: JSONObject.NULL))
        }
        doc.select("video[src], video source[src]").forEach { media(it.attr("src"), it.attr("type")) }
        doc.select("track[src]").filter { it.attr("kind") in listOf("subtitles", "captions", "") }
            .forEach { track(it.attr("src"), it.attr("label"), it.attr("srclang")) }
        // Follow player frames only; never arbitrary redirects, links or advertising frames.
        doc.select("iframe#video-iframe[src], iframe#main-player[src], iframe[allowfullscreen][src]").forEach {
            if (it.attr("src").isNotBlank()) absolute(it.attr("src"), base)?.let(frames::put)
        }
        val scripts = doc.select("script:not([src])").map { it.data() }
        val expanded = scripts + scripts.flatMap { JsUnpacker.unpackAll(it) + JsExtractor.decodeAtob(it) }
        for (script in expanded) {
            Regex("""(?:["']?(?:file|src|source|url)["']?)\s*[:=]\s*["']([^"']+)["']""")
                .findAll(script.replace("\\/", "/")).forEach { media(it.groupValues[1]) }
            // Literal track objects only. Rhino processes data expressions, never the page's DOM code.
            Regex("""\{[^{}]{0,2000}["']?(?:kind|srclang|label)["']?\s*:[^{}]{0,2000}\}""")
                .findAll(script).forEach { match ->
                    val literal = match.value
                    val obj = JSONObject()
                    Regex("""["']?(kind|file|src|label|srclang|language)["']?\s*:\s*("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')""")
                        .findAll(literal).forEach { field ->
                            (JsExtractor.evalExpression(field.groupValues[2]) as? String)?.let {
                                obj.put(field.groupValues[1], it)
                            }
                        }
                    if (obj.optString("kind") in listOf("captions", "subtitles")) {
                        track(obj.optString("file", obj.optString("src")), obj.optString("label"), obj.optString("srclang", obj.optString("language")))
                    }
                }
        }
        return JSONObject().put("media", media).put("subtitles", tracks).put("iframes", frames)
    }

    fun subtitles(data: JSONObject, base: String): List<SubtitleTrack> {
        val array = data.optJSONArray("subtitles") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val url = absolute(obj.optString("url"), base) ?: return@mapNotNull null
            SubtitleTrack(obj.optString("label").ifBlank { "Subtitle" }, url,
                (obj.opt("language") as? String)?.takeIf { it.isNotBlank() })
        }
    }

    fun hlsSubtitles(body: String, base: String): List<SubtitleTrack> =
        body.lineSequence().filter { it.startsWith("#EXT-X-MEDIA:") }.mapNotNull { line ->
            val attrs = Regex("""([A-Z-]+)=(?:"([^"]*)"|([^,]*))""").findAll(line).associate {
                it.groupValues[1] to it.groupValues[2].ifEmpty { it.groupValues[3] }
            }
            if (attrs["TYPE"] != "SUBTITLES") return@mapNotNull null
            val url = absolute(attrs["URI"] ?: return@mapNotNull null, base) ?: return@mapNotNull null
            SubtitleTrack(attrs["NAME"] ?: "Subtitle", url, attrs["LANGUAGE"])
        }.toList()

    /** External DASH subtitle files only; segmented/embedded tracks remain Media3's responsibility. */
    fun dashSubtitles(body: String, base: String): List<SubtitleTrack> {
        val doc = Jsoup.parse(body, base, org.jsoup.parser.Parser.xmlParser())
        return doc.select("AdaptationSet").flatMap { adaptation ->
            val type = adaptation.attr("contentType") + " " + adaptation.attr("mimeType")
            if (!Regex("text|ttml|vtt", RegexOption.IGNORE_CASE).containsMatchIn(type)) return@flatMap emptyList()
            val language = adaptation.attr("lang").takeIf { it.isNotBlank() }
            val rootBase = doc.selectFirst("MPD > BaseURL")?.text()?.let { absolute(it, base) } ?: base
            val periodBase = adaptation.parent()?.children()?.firstOrNull { it.tagName() == "BaseURL" }
                ?.text()?.let { absolute(it, rootBase) } ?: rootBase
            val adaptationBase = adaptation.children().firstOrNull { it.tagName() == "BaseURL" }
                ?.text()?.let { absolute(it, periodBase) } ?: periodBase
            val urls = adaptation.select("Representation > BaseURL").mapNotNull { absolute(it.text(), adaptationBase) }
                .ifEmpty { listOf(adaptationBase) }
            urls.filter { Regex("""\.(vtt|srt|ttml)(?:\?|$)""").containsMatchIn(it) }
                .map { SubtitleTrack(language ?: "Subtitle", it, language) }
        }
    }

    fun validatedMime(response: PlayerResponse): String? {
        val path = runCatching { URI(response.url).path }.getOrDefault("")
        if (Regex("""\.(?:ts|m4s|aac|m4a)$""", RegexOption.IGNORE_CASE).containsMatchIn(path)) return null
        val body = response.body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val type = response.contentType.substringBefore(';').lowercase()
        if (body.lineSequence().firstOrNull() == "#EXTM3U") return "application/x-mpegURL"
        if (body.startsWith("<") && Jsoup.parse(body, response.url, org.jsoup.parser.Parser.xmlParser())
                .children().singleOrNull()?.tagName()?.substringAfterLast(':') == "MPD") return "application/dash+xml"
        if (body.startsWith("<") || body.startsWith("{")) return null
        // A successful binary response with video MIME plus a recognizable container header.
        if (type.startsWith("video/") && (response.body.drop(4).startsWith("ftyp") ||
                response.body.startsWith("\u001aE\u00df\u00a3"))) return type
        return null
    }
}

/** Bounded staged resolution. Browser runs only when static metadata is insufficient. */
class PlayerExtraction(
    private val get: suspend (String, String, Boolean) -> PlayerResponse,
    private val post: suspend (PopupRequest, String) -> PlayerResponse,
    private val browser: (suspend (String, String, String) -> JSONObject)? = null,
    private val log: (PlayerStage, String, String) -> Unit = { _, _, _ -> },
) {
    suspend fun resolve(initial: String, catalog: String): ResolvedStream {
        val visited = HashSet<String>()
        val subtitles = LinkedHashMap<String, SubtitleTrack>()
        suspend fun validate(url: String, parent: String): ResolvedStream? {
            log(PlayerStage.STREAM_METADATA, url, "validating response; iframe/extension alone is insufficient")
            val response = get(url, parent, true)
            val mime = PlayerMetadata.validatedMime(response) ?: return null
            if (mime == "application/x-mpegURL") PlayerMetadata.hlsSubtitles(response.body, response.url)
                .forEach { subtitles[it.url] = it }
            if (mime == "application/dash+xml") PlayerMetadata.dashSubtitles(response.body, response.url)
                .forEach { subtitles[it.url] = it }
            log(PlayerStage.STREAM_METADATA, response.url, "validated mime=$mime subtitles=${subtitles.size}")
            val origin = URI(parent).let { "${it.scheme}://${it.authority}" }
            return ResolvedStream("", response.url, mime, mapOf("Referer" to parent, "Origin" to origin,
                "User-Agent" to Lk21Client.USER_AGENT), subtitles.values.toList(), "")
        }
        suspend fun candidates(data: JSONObject, base: String): ResolvedStream? {
            PlayerMetadata.subtitles(data, base).forEach { subtitles[it.url] = it }
            val media = data.optJSONArray("media") ?: JSONArray()
            for (i in 0 until media.length()) {
                val raw = media.optJSONObject(i)?.optString("url") ?: continue
                val url = PlayerMetadata.absolute(raw, base) ?: continue
                try { validate(url, PlayerMetadata.absolute(media.optJSONObject(i)?.optString("pageUrl").orEmpty(), base) ?: base)?.let { return it } }
                catch (e: CancellationException) { throw e }
                catch (e: AccessBlockedException) { throw e }
                catch (e: Exception) { log(PlayerStage.STREAM_METADATA, url, "candidate failed: ${e.message}") }
            }
            return null
        }
        suspend fun visit(url: String, parent: String, depth: Int): ResolvedStream? {
            if (depth > 5 || !visited.add(url)) return null
            var stage = if (depth == 0) PlayerStage.INITIAL_PAGE else PlayerStage.PROVIDER_PAGE
            try {
                log(stage, url, "GET depth=$depth")
                if (PlayerMetadata.mime(url) != null) validate(url, parent)?.let { return it }
                val response = get(url, parent, false)
                val popup = PlayerMetadata.popup(response.body, response.url)
                if (popup != null) {
                    stage = PlayerStage.API_RESPONSE
                    log(stage, popup.url, "POST form host/id extracted from page")
                    val api = post(popup, response.url)
                    log(stage, api.url, "received type=${api.contentType} bytes=${api.body.length}")
                    val embed = PlayerMetadata.embed(api.body, api.url)
                    stage = PlayerStage.EMBED_URL
                    log(stage, embed, "provider iframe; not confirmed media")
                    return visit(embed, response.url, depth + 1)
                }
                val data = PlayerMetadata.parse(response.body, response.url)
                candidates(data, response.url)?.let { return it }
                val frames = data.optJSONArray("iframes") ?: JSONArray()
                for (i in 0 until frames.length()) {
                    visit(frames.getString(i), response.url, depth + 1)?.let { return it }
                }
                if (browser != null) {
                    stage = PlayerStage.PLAYER_INITIALIZATION
                    log(stage, response.url, "browser observation; no synthetic gestures or ad clicks")
                    val runtime = browser.invoke(initial, catalog, response.url)
                    val runtimeBase = runtime.optString("pageUrl", response.url)
                    candidates(runtime, runtimeBase)?.let { return it }
                    val runtimeScripts = runtime.optJSONArray("scripts") ?: JSONArray()
                    val scriptHtml = (0 until runtimeScripts.length()).joinToString("\n") {
                        "<script>" + runtimeScripts.optString(it) + "</script>"
                    }
                    candidates(PlayerMetadata.parse(scriptHtml, runtimeBase), runtimeBase)?.let { return it }
                }
                stage = PlayerStage.STREAM_METADATA
                throw StreamResolutionException("$stage: no accessible media metadata; initialization may require interaction or be restricted")
            } catch (e: CancellationException) { throw e }
            catch (e: AccessBlockedException) { log(stage, url, "access blocked"); throw e }
            catch (e: Exception) {
                log(stage, url, "failed: ${e.message}")
                if (e is StreamResolutionException) throw e
                throw StreamResolutionException("$stage: ${e.message}", e)
            }
        }
        return visit(initial, catalog, 0) ?: throw StreamResolutionException("STREAM_METADATA: player nesting exhausted")
    }
}
