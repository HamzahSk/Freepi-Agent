package rofil.movie.app.data.remote

import rofil.movie.app.data.model.ResolvedStream
import rofil.movie.app.data.model.SubtitleTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI

enum class PlayerStage {
    INITIAL_REQUEST, API_RESPONSE, EMBED_URL, PLAYER_HTML, DEPENDENCY_LOADING, PLAYER_INITIALIZATION, PLAYER_METADATA, STREAM_VALIDATION
}

data class PlayerResponse(
    val url: String,
    val body: String,
    val contentType: String = "",
    val status: Int = 200,
    val truncated: Boolean = false,
)
data class PopupRequest(val url: String, val host: String, val id: String)

/** The browser stays alive while each candidate is checked by native HTTP. */
typealias BrowserExtraction = suspend (String, String, String, suspend (JSONObject) -> Boolean) -> JSONObject

/** Pure parsing shared by HTTP, browser bridge and fixture tests. No DOM execution in Rhino. */
object PlayerMetadata {
    // Ordinary playback controls do not prove that ads/authentication are required.
    // Static HTML remains conservative for explicit overlays; Chromium checks visibility.
    private const val INTERACTION_ELEMENTS = "#overlay, #uyeouyeo.visible"
    fun absolute(raw: String, base: String): String? = raw.takeIf { it.isNotBlank() }?.let { runCatching {
        URI(base).resolve(raw.replace("\\/", "/").trim()).normalize().let {
            URI(it.toString().substringBefore("#"))
        }.takeIf {
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
        if (doc.selectFirst(INTERACTION_ELEMENTS) != null) return null
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
        val obj = runCatching { JSONObject(json) }.getOrNull()
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
        doc.select("iframe#video-iframe[src], iframe#main-player[src], iframe[allowfullscreen][src], iframe[allow*=fullscreen][src], .player iframe[src], #player iframe[src], #vstr iframe[src], embed[type^=video][src], object[type^=video][data]").forEach {
            absolute(it.attr("src").ifBlank { it.attr("data") }, base)?.let(frames::put)
        }
        val scripts = doc.select("script:not([src])").map { it.data() }
        // SoTrym's datas is opaque initialization data, not a URL or executable manifest.
        val expanded = scripts.filterNot { it.contains("SoTrym") && it.contains("datas") }
            .let { plain -> plain + plain.flatMap { JsUnpacker.unpackAll(it) + JsExtractor.decodeAtob(it) } }
        for (script in expanded) {
            Regex("""(?:["']?(?:fileUrl|file|src|source|url)["']?)\s*[:=]\s*["']([^"']+)["']""")
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
        doc.select("script[type=application/json]").forEach {
            val config = configuration(json(it.data()), base)
            for (key in listOf("media", "subtitles", "iframes")) {
                val source = config.getJSONArray(key)
                val target = when (key) { "media" -> media; "subtitles" -> tracks; else -> frames }
                for (i in 0 until source.length()) target.put(source.get(i))
            }
        }
        return JSONObject().put("media", media).put("subtitles", tracks).put("iframes", frames)
            .put("interactionRequired", doc.selectFirst(INTERACTION_ELEMENTS) != null)
    }

    fun json(raw: String): Any? = runCatching { org.json.JSONTokener(raw).nextValue() }.getOrNull()
        ?.takeIf { it is JSONObject || it is JSONArray }

    /** Walk data only, never execute a provider program. Accept playlist/config response shapes. */
    fun configuration(value: Any?, base: String): JSONObject {
        val media = JSONArray()
        val tracks = JSONArray()
        val frames = JSONArray()
        fun walk(node: Any?, depth: Int, trackContext: Boolean = false) {
            if (depth > 12) return
            when (node) {
                is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), depth + 1, trackContext)
                is JSONObject -> {
                    if (node.has("drm") && node.opt("drm") != false && !node.isNull("drm")) return
                    if (node.has("fileUrl") && node.has("status") && node.optString("status") != "success") return
                    val kind = node.optString("kind")
                    val raw = listOf("fileUrl", "file", "src", "url").firstNotNullOfOrNull {
                        (node.opt(it) as? String)?.takeIf(String::isNotBlank)
                    }
                    val url = raw?.let { absolute(it, base) }
                    if (url != null) {
                        if (trackContext || kind in listOf("captions", "subtitles")) tracks.put(JSONObject()
                            .put("url", url).put("label", node.optString("label", "Subtitle"))
                            .put("language", node.optString("srclang", node.optString("language"))))
                        else if (mime(url) != null || node.optString("type") in listOf("hls", "dash", "mp4", "webm") ||
                            node.has("fileUrl")) media.put(JSONObject().put("url", url).put("type", node.optString("type")))
                    }
                    (node.opt("embedUrl") as? String)?.let { absolute(it, base) }?.let(frames::put)
                    for (key in node.keys()) if (node.opt(key) is JSONObject || node.opt(key) is JSONArray)
                        walk(node.opt(key), depth + 1, key in listOf("tracks", "subtitles", "captions"))
                }
            }
        }
        walk(value, 0)
        return JSONObject().put("media", media).put("subtitles", tracks).put("iframes", frames)
    }

    /** Entry points/config data only. JWPlayer/HLS/P2P libraries are executed by the browser. */
    fun initializationResources(html: String, base: String): List<String> =
        Jsoup.parse(html).select("script[src]").mapNotNull { tag ->
            absolute(tag.attr("src"), base)?.takeIf { url ->
                val path = URI(url).path
                Regex("""(?:^|/)(?:init[_-]player|player[_-]config|playlist|config)\.(?:js|json)$""",
                    RegexOption.IGNORE_CASE).containsMatchIn(path)
            }
        }.distinct()

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
        if (response.status !in 200..299) return null
        val path = runCatching { URI(response.url).path }.getOrDefault("")
        if (Regex("""\.(?:ts|m4s|aac|m4a)$""", RegexOption.IGNORE_CASE).containsMatchIn(path)) return null
        val body = response.body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val type = response.contentType.substringBefore(';').lowercase()
        if (!response.truncated && body.lineSequence().firstOrNull() == "#EXTM3U" &&
            Regex("(?m)^#EXT(?:INF:|-X-STREAM-INF:)").containsMatchIn(body) &&
            body.lineSequence().any { it.isNotBlank() && !it.startsWith("#") }) return "application/x-mpegURL"
        if (!response.truncated && body.startsWith("<")) {
            val doc = Jsoup.parse(body, response.url, org.jsoup.parser.Parser.xmlParser())
            if (doc.children().singleOrNull()?.tagName()?.substringAfterLast(':') == "MPD" &&
                doc.allElements.any { it.tagName().substringAfterLast(':') == "Representation" &&
                    it.parent()?.tagName()?.substringAfterLast(':') == "AdaptationSet" }) return "application/dash+xml"
        }
        if (body.startsWith("<") || body.startsWith("{")) return null
        // A successful binary response with video MIME plus a recognizable container header.
        if (type.startsWith("video/") || type == "application/octet-stream") {
            if (response.body.drop(4).startsWith("ftyp")) return if (type == "video/quicktime") type else "video/mp4"
            if (response.body.startsWith("\u001aE\u00df\u00a3")) return "video/webm"
        }
        return null
    }
}

/** Limits apply to one resolution; signed player metadata is never cached across resolutions. */
data class PlayerExtractionOptions(
    val maxDepth: Int = 6,
    val maxPages: Int = 16,
    val maxInitializationResources: Int = 4,
    val layerTimeoutMs: Long = 15_000,
    val browserTimeoutMs: Long = 30_000,
    val totalTimeoutMs: Long = 90_000,
) {
    init { require(maxDepth in 0..32 && maxPages > 0 && maxInitializationResources >= 0 &&
        layerTimeoutMs > 0 && browserTimeoutMs > 0 && totalTimeoutMs > 0) }
}

/** HTTP traversal first, then one browser observation of the original real frame ancestry. */
class PlayerExtraction(
    private val get: suspend (String, String, Boolean) -> PlayerResponse,
    private val post: suspend (PopupRequest, String) -> PlayerResponse,
    private val browser: BrowserExtraction? = null,
    private val log: (PlayerStage, String, String) -> Unit = { _, _, _ -> },
    private val options: PlayerExtractionOptions = PlayerExtractionOptions(),
) {
    suspend fun resolve(initial: String, catalog: String): ResolvedStream {
        var activeStage = PlayerStage.INITIAL_REQUEST
        return withTimeoutOrNull(options.totalTimeoutMs) {
            resolveWithinDeadline(initial, catalog) { activeStage = it }
        } ?: throw StreamResolutionException("$activeStage: total extraction deadline exceeded")
    }

    private suspend fun resolveWithinDeadline(initial: String, catalog: String, onStage: (PlayerStage) -> Unit): ResolvedStream = withContext(Dispatchers.Default) {
        val visited = HashSet<String>()
        val validated = HashSet<Pair<String, String>>()
        val responses = HashMap<Pair<String, String>, PlayerResponse>()
        val subtitles = LinkedHashMap<String, SubtitleTrack>()
        var success = false
        var requests = 0
        var hits = 0
        var deepest = 0
        var provider = initial
        var last: Exception? = null
        val started = System.nanoTime()
        fun elapsed(from: Long) = (System.nanoTime() - from) / 1_000_000
        suspend fun <T> timed(stage: PlayerStage, url: String, timeout: Long = options.layerTimeoutMs,
            block: suspend () -> T): T {
            val before = System.nanoTime()
            onStage(stage)
            try {
                return (withTimeoutOrNull(timeout) { block() to true }
                    ?: throw StreamResolutionException("$stage: layer timeout")).first
            } catch (e: CancellationException) { throw e }
            catch (e: AccessBlockedException) { throw e }
            catch (e: Exception) {
                if (e is SourceException) throw e
                throw StreamResolutionException("$stage: operation failed", e)
            } finally { log(stage, url, "durationMs=${elapsed(before)} requests=$requests cacheHits=$hits cacheMisses=$requests depth=$deepest") }
        }
        suspend fun fetch(url: String, parent: String, media: Boolean, stage: PlayerStage): PlayerResponse {
            val key = url to parent
            responses[key]?.let { hits++; return it }
            requests++
            return timed(stage, url) { get(url, parent, media) }.also {
                responses[key] = it
                if (it.url != url) responses[it.url to parent] = it
                log(stage, it.url, "method=GET status=${it.status} contentType=${it.contentType.substringBefore(';')} redirected=${it.url != url}")
            }
        }
        suspend fun validate(url: String, parent: String): ResolvedStream? {
            if (!validated.add(url to parent)) return null
            log(PlayerStage.PLAYER_METADATA, url, "candidate; validation pending")
            val response = fetch(url, parent, true, PlayerStage.STREAM_VALIDATION)
            val mime = PlayerMetadata.validatedMime(response) ?: run {
                val reason = when {
                    response.status !in 200..299 -> "http_status"
                    response.truncated -> "truncated_document"
                    PlayerMetadata.json(response.body) != null -> "json_metadata_not_manifest"
                    else -> "not_media_document"
                }
                last = StreamResolutionException("STREAM_VALIDATION: candidate rejected ($reason)")
                log(PlayerStage.STREAM_VALIDATION, response.url, "rejected reason=$reason status=${response.status}")
                return null
            }
            if ((mime == "application/dash+xml" && response.body.contains("ContentProtection", true)) ||
                (mime == "application/x-mpegURL" && Regex("METHOD=SAMPLE-AES", RegexOption.IGNORE_CASE).containsMatchIn(response.body))) {
                throw StreamResolutionException("STREAM_VALIDATION: protected media requires an authorized DRM playback integration")
            }
            if (mime == "application/x-mpegURL") PlayerMetadata.hlsSubtitles(response.body, response.url)
                .forEach { subtitles[it.url] = it }
            if (mime == "application/dash+xml") PlayerMetadata.dashSubtitles(response.body, response.url)
                .forEach { subtitles[it.url] = it }
            log(PlayerStage.STREAM_VALIDATION, response.url, "success mime=$mime subtitles=${subtitles.size}")
            val origin = URI(parent).let { "${it.scheme}://${it.authority}" }
            return ResolvedStream("", response.url, mime, mapOf("Referer" to parent, "Origin" to origin,
                "User-Agent" to Lk21Client.USER_AGENT), subtitles.values.toList(), "")
        }
        suspend fun candidates(data: JSONObject, base: String): ResolvedStream? {
            PlayerMetadata.subtitles(data, base).forEach { subtitles[it.url] = it }
            val media = data.optJSONArray("media") ?: JSONArray()
            for (i in 0 until media.length()) {
                val item = media.optJSONObject(i) ?: continue
                val url = PlayerMetadata.absolute(item.optString("url"), base) ?: continue
                if (url in subtitles) continue // A subtitle rendition is never a standalone video source.
                try {
                    validate(url, PlayerMetadata.absolute(item.optString("pageUrl"), base) ?: base)?.let { return it }
                } catch (e: CancellationException) { throw e }
                catch (e: AccessBlockedException) { throw e }
                catch (e: Exception) { last = e; log(PlayerStage.STREAM_VALIDATION, url, "candidate failed ${e.javaClass.simpleName}") }
            }
            return null
        }
        suspend fun visit(url: String, parent: String, depth: Int): ResolvedStream? {
            if (depth > options.maxDepth || visited.size >= options.maxPages || !visited.add(url)) return null
            deepest = maxOf(deepest, depth)
            val stage = if (depth == 0) PlayerStage.INITIAL_REQUEST else PlayerStage.PLAYER_HTML
            try {
                val response = fetch(url, parent, PlayerMetadata.mime(url) != null, stage)
                if (PlayerMetadata.validatedMime(response) != null) return validate(url, parent)
                if (response.url != url && !visited.add(response.url)) return null
                if (depth > 0) provider = response.url
                log(PlayerStage.PLAYER_HTML, response.url, "document received; media=false")
                val popup = PlayerMetadata.popup(response.body, response.url)
                if (popup != null) {
                    requests++
                    val api = timed(PlayerStage.API_RESPONSE, popup.url) { post(popup, response.url) }
                    val embed = PlayerMetadata.embed(api.body, api.url)
                    log(PlayerStage.EMBED_URL, embed, "provider reference depth=${depth + 1}")
                    visit(embed, response.url, depth + 1)?.let { return it }
                }
                val parsed = System.nanoTime()
                val json = PlayerMetadata.json(response.body)
                val data = if (json != null) PlayerMetadata.configuration(json, response.url)
                    else PlayerMetadata.parse(response.body, response.url)
                log(PlayerStage.PLAYER_INITIALIZATION, response.url, "parseMs=${elapsed(parsed)} depth=$depth")
                log(PlayerStage.PLAYER_METADATA, response.url,
                    "source=${if (json != null) "json_configuration" else "html"} candidates=${data.optJSONArray("media")?.length() ?: 0}")
                if (data.optBoolean("interactionRequired")) {
                    last = StreamResolutionException("PLAYER_INITIALIZATION: provider requires user interaction or advertising flow")
                    return null // A child frame must not circumvent its parent's interaction requirements.
                }
                candidates(data, response.url)?.let { return it }
                val frames = data.optJSONArray("iframes") ?: JSONArray()
                for (i in 0 until frames.length()) visit(frames.getString(i), response.url, depth + 1)?.let { return it }
                for (resource in PlayerMetadata.initializationResources(response.body, response.url)
                    // Browser entrypoint programs need Chromium, not a second native download.
                    .filter { browser == null || URI(it).path.endsWith(".json", true) }
                    .take(options.maxInitializationResources)) {
                    try {
                        val config = fetch(resource, response.url, false, PlayerStage.DEPENDENCY_LOADING)
                        val parsedConfig = PlayerMetadata.json(config.body)
                        val metadata = if (parsedConfig != null) PlayerMetadata.configuration(parsedConfig, config.url)
                            else PlayerMetadata.parse("<script>${config.body}</script>", response.url)
                        candidates(metadata, response.url)?.let { return it }
                        val embeds = metadata.optJSONArray("iframes") ?: JSONArray()
                        for (i in 0 until embeds.length()) visit(embeds.getString(i), response.url, depth + 1)?.let { return it }
                    } catch (e: CancellationException) { throw e }
                    catch (e: AccessBlockedException) { throw e }
                    catch (e: Exception) { last = e }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: AccessBlockedException) { throw e }
            catch (e: Exception) {
                last = if (e is StreamResolutionException) e else StreamResolutionException("$stage: request failed", e)
                log(stage, url, "failed ${e.javaClass.simpleName} depth=$depth")
            }
            return null
        }
        try {
            visit(PlayerMetadata.absolute(initial, catalog) ?: throw StreamResolutionException("INITIAL_REQUEST: invalid URL"), catalog, 0)?.let { return@withContext it.also { success = true } }
            if (browser != null) {
                var accepted: ResolvedStream? = null
                val runtime = timed(PlayerStage.PLAYER_INITIALIZATION, provider, options.browserTimeoutMs) {
                    browser.invoke(initial, catalog, provider) { metadata ->
                        val base = PlayerMetadata.absolute(metadata.optString("pageUrl"), provider) ?: provider
                        if (metadata.optInt("depth", 0) > options.maxDepth) false
                        else {
                            accepted = candidates(metadata, base)
                            accepted != null
                        }
                    }
                }
                accepted?.let { return@withContext it.also { success = true } }
                val runtimeDepth = runtime.optInt("depth", 0)
                if (runtimeDepth > options.maxDepth) throw StreamResolutionException("PLAYER_INITIALIZATION: browser depth limit")
                deepest = maxOf(deepest, runtimeDepth)
                val base = PlayerMetadata.absolute(runtime.optString("pageUrl"), provider) ?: provider
                candidates(runtime, base)?.let { return@withContext it.also { success = true } }
            }
            throw last ?: StreamResolutionException("PLAYER_METADATA: no authorized playable source exposed by the player; browser interaction, provider restrictions or unavailable metadata may prevent native playback")
        } finally {
            log(PlayerStage.STREAM_VALIDATION, initial, "complete success=$success durationMs=${elapsed(started)} requests=$requests cacheHits=$hits depth=$deepest validatedCandidates=${validated.size}")
        }
    }
}
