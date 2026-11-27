package com.freepi.lk21.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

/** Controlled transport comparison, not a claim about live provider playback or device rendering. */
class PlayerPerformanceTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun browserEntrypointIsDownloadedOnlyByBrowser() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                    .setHeader("Content-Type", if (request.path == "/master.m3u8") "application/vnd.apple.mpegurl" else "text/html")
                    .setBodyDelay(80, TimeUnit.MILLISECONDS).setBody(when (request.path) {
                        "/player" -> "<script src='/init_player.js'></script>"
                        "/master.m3u8" -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n480.m3u8\n"
                        else -> "window.addEventListener('load', function(){ /* browser-dependent initialization */ });"
                    })
            }
            val client = Lk21Client(temp.newFolder())
            val page = server.url("/player").toString()
            suspend fun measure(replayRemovedRequest: Boolean): Pair<Long, Int> {
                val count = server.requestCount
                val started = System.nanoTime()
                PlayerExtraction(get = { u, p, m -> client.playerResponse(u, p, m) }, post = { _, _ -> error("No POST") },
                    browser = { _, _, _, validate ->
                        // Replay precisely the redundant native entrypoint GET removed by this change.
                        // Chromium execution time is deliberately excluded from BOTH measurements.
                        if (replayRemovedRequest) client.playerResponse(server.url("/init_player.js").toString(), page)
                        val data = JSONObject().put("pageUrl", page).put("media", JSONArray()
                            .put(JSONObject().put("url", server.url("/master.m3u8").toString())))
                        assertTrue(validate(data)); data
                    }).resolve(page, page)
                return (System.nanoTime() - started) / 1_000_000 to (server.requestCount - count)
            }
            measure(false) // Warm transport, parser and VM before both sets.
            val before = List(3) { measure(true) }
            val after = List(3) { measure(false) }
            assertTrue(before.all { it.second == 3 })
            assertTrue(after.all { it.second == 2 })
            println("PLAYER_PERFORMANCE simulatedLatencyMs=80 samples=3 beforeMedianMs=${before.map { it.first }.sorted()[1]} " +
                "afterMedianMs=${after.map { it.first }.sorted()[1]} beforeNativeRequests=3 afterNativeRequests=2 browserExecutionExcluded=true")
        }
    }
}
