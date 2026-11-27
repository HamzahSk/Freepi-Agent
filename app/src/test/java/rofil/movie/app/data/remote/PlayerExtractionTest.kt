package rofil.movie.app.data.remote

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PlayerExtractionTest {
    private val initial = "https://videonode.de/iframe3/hydrax/a9rCpj6LGAx-CefoBJ7rkg"
    private val catalog = "https://tv13.lk21official.cc/slightest-touch-2026/"
    private fun captured(): String {
        val root = listOf(File("../lk21-request"), File("lk21-request")).first { it.exists() }
        return File(root, "[84] response_videonode.de_message.txt").readText().substringAfter("<!DOCTYPE html>")
            .let { "<!DOCTYPE html>$it" }
    }
    private fun fixture(name: String) = javaClass.getResource("/lk21/player/$name")!!.readText()

    @Test fun capturedPopupExtractsActualPostAndDynamicValues() {
        val popup = PlayerMetadata.popup(captured(), initial)!!
        assertEquals("https://videonode.de/api.php", popup.url)
        assertEquals("hydrax", popup.host)
        assertEquals("a9rCpj6LGAx-CefoBJ7rkg", popup.id)
        val changed = captured().replace("hydrax", "another-provider").replace(popup.id, "different-id")
        assertEquals("different-id", PlayerMetadata.popup(changed, initial)!!.id)
        assertEquals("another-provider", PlayerMetadata.popup(changed, initial)!!.host)
        assertEquals(0, PlayerMetadata.parse(captured(), initial).getJSONArray("media").length())
    }

    @Test fun actualApiReturnsEmbedNotMedia() {
        val embed = PlayerMetadata.embed(fixture("live-api.json"), "https://videonode.de/api.php")
        assertEquals("https://abyssplayer.com/g34IONP9K", embed)
        assertNull(PlayerMetadata.mime(embed))
        val provider = PlayerMetadata.parse(fixture("live-provider.html"), embed)
        assertEquals(0, provider.getJSONArray("media").length())
    }

    @Test fun malformedApiFailsAtApiStage() = runBlocking {
        val extraction = PlayerExtraction(
            get = { url, _, _ -> PlayerResponse(url, captured()) },
            post = { req, _ -> PlayerResponse(req.url, "{\"error\":\"missing\"}") })
        val e = runCatching { extraction.resolve(initial, catalog) }.exceptionOrNull()!!
        assertTrue(e.message!!, e.message!!.startsWith("API_RESPONSE:"))
    }

    @Test fun stagesPreserveRequestAncestryAndValidateManifest() = runBlocking {
        val provider = "https://abyssplayer.com/g34IONP9K"
        val master = "https://cdn.example/master.m3u8"
        val stages = mutableListOf<PlayerStage>()
        val extraction = PlayerExtraction(
            get = { url, parent, media ->
                when (url) {
                    initial -> { assertEquals(catalog, parent); PlayerResponse(url, captured()) }
                    provider -> { assertEquals(initial, parent); PlayerResponse(url, "<script>var sources=[{file:'$master'}];</script>") }
                    master -> { assertTrue(media); assertEquals(provider, parent)
                        PlayerResponse(url, "#EXTM3U\n#EXT-X-MEDIA:URI=\"sub/id.m3u8\",LANGUAGE=\"id\",TYPE=SUBTITLES,NAME=\"Indonesia\"\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n480.m3u8\n", "application/vnd.apple.mpegurl") }
                    else -> error("Unexpected request $url")
                }
            }, post = { req, parent -> assertEquals(initial, parent)
                PlayerResponse(req.url, fixture("live-api.json")) },
            log = { stage, _, _ -> stages.add(stage) })
        val result = extraction.resolve(initial, catalog)
        assertEquals(master, result.url)
        assertEquals("application/x-mpegURL", result.mimeType)
        assertEquals(provider, result.headers["Referer"])
        assertEquals("https://abyssplayer.com", result.headers["Origin"])
        assertEquals("id", result.subtitles.single().language)
        assertEquals("https://cdn.example/sub/id.m3u8", result.subtitles.single().url)
        assertEquals(listOf(PlayerStage.INITIAL_REQUEST, PlayerStage.PLAYER_HTML, PlayerStage.API_RESPONSE, PlayerStage.EMBED_URL, PlayerStage.PLAYER_INITIALIZATION, PlayerStage.PLAYER_METADATA, PlayerStage.STREAM_VALIDATION), stages.distinct())
    }

    @Test fun browserMetadataIsCandidateUntilValidated() = runBlocking {
        val extensionless = "https://cdn.example/manifest?token=abc"
        val extraction = PlayerExtraction(
            get = { url, _, _ -> if (url == extensionless)
                PlayerResponse(url, "<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"><Period><AdaptationSet><Representation/></AdaptationSet></Period></MPD>", "application/dash+xml")
                else PlayerResponse(url, "<html><video></video></html>") },
            post = { _, _ -> error("no API") },
            browser = { _, _, _, _ -> JSONObject().put("pageUrl", initial).put("media", JSONArray()
                .put(JSONObject().put("url", extensionless))) })
        assertEquals("application/dash+xml", extraction.resolve(initial, catalog).mimeType)
    }

    @Test fun initializationFailureRetainsCorrectStage() = runBlocking {
        val extraction = PlayerExtraction(get = { u, _, _ -> PlayerResponse(u, "<html></html>") },
            post = { _, _ -> error("no API") }, browser = { _, _, _, _ -> error("browser unavailable") })
        assertTrue(runCatching { extraction.resolve(initial, catalog) }.exceptionOrNull()!!.message!!
            .startsWith("PLAYER_INITIALIZATION:"))
    }

    @Test fun providerHtmlAndSegmentsNeverValidateAsMedia() {
        assertNull(PlayerMetadata.mime("https://cdn.example/segment.ts"))
        assertNull(PlayerMetadata.mime("https://cdn.example/segment.m4s"))
        assertNull(PlayerMetadata.mime("https://example/player?file=movie.mp4"))
        assertNull(PlayerMetadata.validatedMime(PlayerResponse("https://example/a.m3u8", "<html>Forbidden</html>", "application/x-mpegURL")))
        assertEquals("video/mp4", PlayerMetadata.validatedMime(PlayerResponse("https://example/a.mp4", "\u0000\u0000\u0000\u0018ftypisom", "video/mp4")))
    }

    @Test fun initializationAndDatasReferencesAreNotMediaUrls() {
        assertNull(PlayerMetadata.mime("https://playcdn.de/init_player.js"))
        assertNull(PlayerMetadata.mime("https://playcdn.de/player?datas=eyJmaWxlIjoibW92aWUubTN1OCJ9"))
        assertNull(PlayerMetadata.validatedMime(PlayerResponse("https://playcdn.de/player", "<html>player init</html>", "text/html")))
    }

    @Test fun subtitleMetadataAndPackedDataRemainStructured() {
        val data = PlayerMetadata.parse("""<video><source src="movie.webm"><track kind="subtitles" src="sub.vtt" label="English" srclang="en"></video>""", "https://example/dir/embed")
        assertEquals("https://example/dir/movie.webm", data.getJSONArray("media").getJSONObject(0).getString("url"))
        assertEquals("en", PlayerMetadata.subtitles(data, initial).single().language)
    }

    @Test fun accessBlockNeverTriggersBrowserFallback() = runBlocking {
        var invoked = false
        val extraction = PlayerExtraction(get = { _, _, _ -> throw AccessBlockedException() },
            post = { _, _ -> error("no API") }, browser = { _, _, _, _ -> invoked = true; JSONObject() })
        assertTrue(runCatching { extraction.resolve(initial, catalog) }.exceptionOrNull() is AccessBlockedException)
        assertFalse(invoked)
    }
    @Test fun actualAccessibleSnapshotsDoNotInventHls() = runBlocking {
        val extraction = PlayerExtraction(
            get = { u, _, _ -> PlayerResponse(u, if (u == initial) captured() else fixture("live-provider.html")) },
            post = { r, _ -> PlayerResponse(r.url, fixture("live-api.json")) },
            browser = { _, _, _, _ -> JSONObject() })
        val error = runCatching { extraction.resolve(initial, catalog) }.exceptionOrNull()!!
        assertTrue(error.message!!, error.message!!.contains("provider requires user interaction or advertising flow"))
    }

    @Test fun dashSubtitleBasesAndLanguageArePreserved() {
        val tracks = PlayerMetadata.dashSubtitles("""<MPD><BaseURL>https://cdn.example/</BaseURL><Period>
            <BaseURL>movie/</BaseURL><AdaptationSet contentType="text" lang="en">
            <Representation><BaseURL>sub.vtt</BaseURL></Representation></AdaptationSet></Period></MPD>""", initial)
        assertEquals("https://cdn.example/movie/sub.vtt", tracks.single().url)
        assertEquals("en", tracks.single().language)
    }

    @Test fun javascriptTrackLiteralsUseRhinoOnlyForStrings() {
        val data = PlayerMetadata.parse("""<script>var tracks=[{file:'sub.srt', kind:'captions', label:'Indonesia', language:'id'}];</script>""", initial)
        assertEquals("id", PlayerMetadata.subtitles(data, initial).single().language)
    }

    @Test fun providerFetchFailureRetainsProviderStage() = runBlocking {
        val extraction = PlayerExtraction(
            get = { u, _, _ -> if (u == initial) PlayerResponse(u, captured()) else error("provider unavailable") },
            post = { r, _ -> PlayerResponse(r.url, fixture("live-api.json")) })
        assertTrue(runCatching { extraction.resolve(initial, catalog) }.exceptionOrNull()!!.message!!
            .startsWith("PLAYER_HTML:"))
    }

}
