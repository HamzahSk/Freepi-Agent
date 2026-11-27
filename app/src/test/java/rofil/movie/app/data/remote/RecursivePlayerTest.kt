package rofil.movie.app.data.remote

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecursivePlayerTest {
    private val base = "https://player.test/start"
    private fun captured(name: String): String {
        val root = listOf(File("../lk21-request"), File("lk21-request")).first { it.exists() }
        return File(root, name).readText().replace("\r\n", "\n").substringAfter("\n\n")
    }
    private fun resolver(pages: Map<String, String>, requests: MutableList<String> = mutableListOf(),
        options: PlayerExtractionOptions = PlayerExtractionOptions(),
        browser: BrowserExtraction? = null) = PlayerExtraction(
        get = { url, _, _ -> requests.add(url); PlayerResponse(url, pages[url] ?: error("Unavailable")) },
        post = { _, _ -> error("Unexpected POST") }, options = options, browser = browser)

    @Test fun capturedPlaycdnApiAndInitializationResourcesAreNotMedia() {
        val api = captured("[148] response_videonode.de_message.txt")
        val embed = PlayerMetadata.embed(api, "https://videonode.de/api.php")
        val html = captured("[150] response_playcdn.de_message.txt")
        assertTrue(embed.startsWith("https://playcdn.de/"))
        assertEquals(0, PlayerMetadata.parse(html, embed).getJSONArray("media").length())
        assertEquals(listOf("https://playcdn.de/js/init_player.js?t=1.5.5"), PlayerMetadata.initializationResources(html, embed))
    }

    @Test fun traversesAdditionalLayersAndResolvesRelativeSources() = runBlocking {
        val requests = mutableListOf<String>()
        val pages = mapOf(base to "<iframe id=video-iframe src='/nested'></iframe>",
            "https://player.test/nested" to "<div id=player><iframe src='/deep'></iframe></div>",
            "https://player.test/deep" to "<video><source src='master.m3u8'></video>",
            "https://player.test/master.m3u8" to "#EXTM3U\n#EXTINF:10,\nsegment.ts")
        val result = resolver(pages, requests).resolve(base, "https://catalog.test/movie")
        assertEquals("https://player.test/master.m3u8", result.url)
        assertEquals("https://player.test/deep", result.headers["Referer"])
        assertEquals(4, requests.size)
    }

    @Test fun depthAndPageLimitsAreConfigurable() = runBlocking {
        val requests = mutableListOf<String>()
        resolver(mapOf(base to "<iframe allowfullscreen src='/nested'></iframe>"), requests,
            PlayerExtractionOptions(maxDepth = 0)).let { assertTrue(runCatching { it.resolve(base, base) }.isFailure) }
        assertEquals(listOf(base), requests)
        requests.clear()
        resolver(mapOf(base to "<iframe allowfullscreen src='/nested'></iframe>"), requests,
            PlayerExtractionOptions(maxPages = 1)).let { assertTrue(runCatching { it.resolve(base, base) }.isFailure) }
        assertEquals(1, requests.size)
    }

    @Test fun circularAndFragmentReferencesDoNotLoop() = runBlocking {
        val requests = mutableListOf<String>()
        val pages = mapOf(base to "<iframe allowfullscreen src='/next'></iframe>",
            "https://player.test/next" to "<iframe allowfullscreen src='/start#loop'></iframe>")
        assertTrue(runCatching { resolver(pages, requests).resolve(base, base) }.isFailure)
        assertEquals(listOf(base, "https://player.test/next"), requests)
        assertEquals("https://cdn.test/a%2Fb?token=a%2Fb%3D", PlayerMetadata.absolute("https://cdn.test/a%2Fb?token=a%2Fb%3D#fragment",base))
    }

    @Test fun failedSiblingDoesNotPreventWorkingPlayerAndDuplicatesValidateOnce() = runBlocking {
        val requests = mutableListOf<String>()
        val html = "<iframe allowfullscreen src='/missing'></iframe><iframe allowfullscreen src='/good'></iframe>"
        val pages = mapOf(base to html, "https://player.test/good" to """<video src='/bad.m3u8'></video>
            <script>var sources=[{file:'/bad.m3u8'},{file:'/good.m3u8'}]</script>""",
            "https://player.test/bad.m3u8" to "<html>Unavailable</html>",
            "https://player.test/good.m3u8" to "#EXTM3U\n#EXTINF:10,\nsegment.ts")
        assertEquals("https://player.test/good.m3u8", resolver(pages, requests).resolve(base, base).url)
        assertEquals(1, requests.count { it.endsWith("bad.m3u8") })
    }

    @Test fun initConfigJsonAndSubtitlesUseCorrectBase() = runBlocking {
        val pages = mapOf(base to "<script src='/config.json'></script><script src='/jwplayer.js'></script>",
            "https://player.test/config.json" to """{"playlist":[{"sources":[{"file":"/movie.mpd"}],"tracks":[{"kind":"captions","file":"/en.vtt","language":"en"}]}]}""",
            "https://player.test/movie.mpd" to "<MPD><Period><AdaptationSet><Representation/></AdaptationSet></Period></MPD>")
        val requests = mutableListOf<String>()
        val result = resolver(pages, requests).resolve(base, base)
        assertEquals("application/dash+xml", result.mimeType)
        assertEquals("en", result.subtitles.single().language)
        assertFalse(requests.any { it.endsWith("jwplayer.js") })
    }

    @Test fun browserObservesDynamicVerifyBeforeJwplayerPlaylistExists() = runBlocking {
        val metadata = PlayerMetadata.configuration(JSONObject("""{"status":"success","fileUrl":"https://media.test/master.m3u8","title":"Fixture"}"""), base)
        val result = resolver(mapOf(base to "<div id=vstr></div>",
            "https://media.test/master.m3u8" to "#EXTM3U\n#EXTINF:10,\nsegment.ts"),
            browser = { _, _, _, _ -> metadata.put("pageUrl", base) }).resolve(base, base)
        assertEquals("https://media.test/master.m3u8", result.url)
    }

    @Test fun stalledLayerTimesOutAndSiblingStillWorks() = runBlocking {
        val extraction = PlayerExtraction(get = { url, _, _ ->
            if (url.endsWith("slow")) delay(1000)
            PlayerResponse(url, when {
                url == base -> "<iframe allowfullscreen src='/slow'></iframe><iframe allowfullscreen src='/ok'></iframe>"
                url.endsWith("ok") -> "<video src='/video.webm'></video>"
                else -> "\u001aE\u00df\u00a3data"
            }, if (url.endsWith("webm")) "video/webm" else "text/html")
        }, post = { _, _ -> error("no POST") }, options = PlayerExtractionOptions(layerTimeoutMs = 100))
        assertEquals("video/webm", extraction.resolve(base, base).mimeType)
    }

    @Test fun browserRunsOnceForWholeFrameTreeAndCallerCancellationPropagates() = runBlocking {
        var calls = 0
        val pages = mapOf(base to "<iframe allowfullscreen src='/empty'></iframe>", "https://player.test/empty" to "<div/>")
        runCatching { resolver(pages, browser = { _, _, _, _ -> calls++; JSONObject() }).resolve(base, base) }
        assertEquals(1, calls)
        val extraction = PlayerExtraction(get = { _, _, _ -> delay(10000); error("no") }, post = { _, _ -> error("no") })
        val job = async { extraction.resolve(base, base) }
        delay(20); job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
    }
    @Test fun jsonPlaylistArraysAndImplicitCaptionKindsAreSupported() {
        val raw = """[{"sources":[{"file":"movie.mp4"}],"tracks":[{"file":"sub.vtt","label":"English","language":"en"}]}]"""
        val data = PlayerMetadata.configuration(PlayerMetadata.json(raw), "https://example.test/config/playlist.json")
        assertEquals("https://example.test/config/movie.mp4", data.getJSONArray("media").getJSONObject(0).getString("url"))
        assertEquals("en", PlayerMetadata.subtitles(data, base).single().language)
    }

    @Test fun emptyManifestsAndSegmentsAreRejectedAndBinaryContainersAccepted() {
        assertNull(PlayerMetadata.validatedMime(PlayerResponse("https://cdn.test/a.m3u8", "#EXTM3U\n")))
        assertNull(PlayerMetadata.validatedMime(PlayerResponse("https://cdn.test/a.mpd", "<MPD/>")))
        assertNull(PlayerMetadata.validatedMime(PlayerResponse("https://cdn.test/a.m4s", "#EXTM3U\n#EXTINF:10,\na.ts")))
        assertEquals("video/mp4", PlayerMetadata.validatedMime(PlayerResponse("https://cdn.test/file", "\u0000\u0000\u0000\u0018ftypisom", "application/octet-stream")))
        val mpd = "<dash:MPD xmlns:dash='urn:mpeg:dash:schema:mpd:2011'><dash:Period><dash:AdaptationSet><dash:Representation/></dash:AdaptationSet></dash:Period></dash:MPD>"
        assertEquals("application/dash+xml", PlayerMetadata.validatedMime(PlayerResponse("https://cdn.test/file", mpd)))
    }

    @Test fun subtitleManifestInTrackLiteralIsNeverReturnedAsVideo() = runBlocking {
        val requests = mutableListOf<String>()
        val pages = mapOf(base to """<script>var tracks=[{file:'/subs.m3u8',kind:'captions',label:'English'}];</script>""")
        assertTrue(runCatching { resolver(pages, requests).resolve(base, base) }.isFailure)
        assertEquals(listOf(base), requests)
    }

}
