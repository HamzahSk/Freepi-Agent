package com.freepi.lk21.data.remote

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlayerPipelineTest {
    private val page = "https://provider.test/player"
    private val catalog = "https://catalog.test/movie"
    private val manifest = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n480.m3u8\n"
    private fun candidate(url: String) = JSONObject().put("pageUrl", page).put("media",
        JSONArray().put(JSONObject().put("url", url)))

    @Test fun extensionlessManifestsValidateWithoutASecondRequestOrBrowser() = runBlocking {
        var calls = 0
        val stream = PlayerExtraction(get = { url, _, _ -> calls++; PlayerResponse(url, manifest, "application/vnd.apple.mpegurl") },
            post = { _, _ -> error("Unexpected API") }, browser = { _, _, _, _ -> error("Unexpected browser") })
            .resolve(page, catalog)
        assertEquals(page, stream.url)
        assertEquals(1, calls)
    }

    @Test fun misleadingManifestPathStillAllowsPlayerHtmlExtraction() = runBlocking {
        val urls = mutableListOf<String>()
        val stream = PlayerExtraction(get = { url, _, _ ->
            urls += url
            PlayerResponse(url, if (url.endsWith("wrapper.m3u8")) "<video src='/actual.m3u8'></video>" else manifest)
        }, post = { _, _ -> error("Unexpected API") }).resolve("https://provider.test/wrapper.m3u8", catalog)
        assertEquals("https://provider.test/actual.m3u8", stream.url)
        assertEquals(2, urls.size)
    }

    @Test fun rejectedBrowserCandidateDoesNotEndObservationOrReload() = runBlocking {
        var browserCalls = 0
        val fetched = mutableListOf<String>()
        val extraction = PlayerExtraction(get = { url, _, _ ->
            fetched += url
            PlayerResponse(url, if (url.endsWith("good.m3u8")) manifest else "<html>intermediate page</html>")
        }, post = { _, _ -> error("Unexpected API") }, browser = { _, _, _, validate ->
            browserCalls++
            val bad = candidate("https://media.test/bad.m3u8")
            assertFalse(validate(bad))
            assertFalse(validate(bad))
            val good = candidate("https://media.test/good.m3u8")
            assertTrue(validate(good))
            good
        })
        assertEquals("https://media.test/good.m3u8", extraction.resolve(page, catalog).url)
        assertEquals(1, browserCalls)
        assertEquals(1, fetched.count { it.endsWith("bad.m3u8") })
        assertEquals(1, fetched.count { it.endsWith("good.m3u8") })
    }

    @Test fun browserDependencyFailureRetainsExactLimitation() = runBlocking {
        val reason = "DEPENDENCY_LOADING: script requests failed (1); no playable metadata available"
        val error = runCatching { PlayerExtraction(get = { url, _, _ -> PlayerResponse(url, "<html/>") },
            post = { _, _ -> error("Unexpected API") }, browser = { _, _, _, _ -> throw ScrapeTimeoutException(reason) })
            .resolve(page, catalog) }.exceptionOrNull()
        assertEquals(reason, error?.message)
    }

    @Test fun browserProgramsAreNotDownloadedTwiceByNativeTransport() = runBlocking {
        val urls = mutableListOf<String>()
        runCatching { PlayerExtraction(get = { url, _, _ ->
            urls += url; PlayerResponse(url, "<script src='/init_player.js'></script>")
        }, post = { _, _ -> error("Unexpected API") }, browser = { _, _, _, _ -> JSONObject() }).resolve(page, catalog) }
        assertEquals(listOf(page), urls)
    }

    @Test fun base64DatasAndAdvertisingGatesDoNotBecomeNativeStreams() = runBlocking {
        val encoded = java.util.Base64.getEncoder().encodeToString("{\"file\":\"https://media.test/a.m3u8\"}".toByteArray())
        val html = "<script>const datas='$encoded';window.addEventListener('load',()=>window.SoTrym(JSON.parse(atob(datas))))</script>"
        assertEquals(0, PlayerMetadata.parse(html, page).getJSONArray("media").length())
        var calls = 0
        val error = runCatching { PlayerExtraction(get = { url, _, _ -> calls++
            PlayerResponse(url, "<div id='overlay'></div><video src='/ad-gated.m3u8'></video><iframe allowfullscreen src='/child'></iframe>")
        }, post = { _, _ -> error("Unexpected API") }).resolve(page, catalog) }.exceptionOrNull()
        assertNotNull(error)
        assertEquals(1, calls)
    }

    @Test fun protectedManifestsAndHttpErrorsAreRejected() = runBlocking {
        assertNull(PlayerMetadata.validatedMime(PlayerResponse(page, manifest, status = 403)))
        assertNull(PlayerMetadata.validatedMime(PlayerResponse(page, manifest, truncated = true)))
        val error = runCatching { PlayerExtraction(get = { url, _, _ -> PlayerResponse(url,
            "<MPD><Period><AdaptationSet><ContentProtection/><Representation/></AdaptationSet></Period></MPD>")
        }, post = { _, _ -> error("Unexpected API") }).resolve(page, catalog) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("authorized DRM"))
    }

    @Test fun totalDeadlineBoundsRecursiveWork() = runBlocking {
        val before = System.nanoTime()
        val error = runCatching { PlayerExtraction(get = { _, _, _ -> delay(10_000); error("Unreachable") },
            post = { _, _ -> error("Unexpected API") }, options = PlayerExtractionOptions(totalTimeoutMs = 100))
            .resolve(page, catalog) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("total extraction deadline"))
        assertTrue((System.nanoTime() - before) / 1_000_000 < 2000)
    }
}
