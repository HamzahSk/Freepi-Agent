package com.freepi.lk21

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.freepi.lk21.data.remote.*
import com.freepi.lk21.data.scrape.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Real Android WebView + bridge + native HTTP validation, without live provider access. */
@RunWith(AndroidJUnit4::class)
class BrowserPipelineTest {
    @Test fun browserSurvivesInvalidMetadataAndValidatesLaterSourceInSameNavigation() = runBlocking {
        MockWebServer().use { server ->
            val paths = CopyOnWriteArrayList<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty(); paths += path
                    return when (path) {
                        "/catalog" -> MockResponse().setHeader("Content-Type", "text/html").setBody("<iframe id='main-player' src='/wrapper'></iframe>")
                        "/wrapper" -> MockResponse().setHeader("Content-Type", "text/html").setBody("<iframe allowfullscreen src='/provider'></iframe>")
                        "/provider" -> MockResponse().setHeader("Content-Type", "text/html").setBody("""
                            <script src='/dependency.js'></script><div id='player'></div>
                        """.trimIndent())
                        "/dependency.js" -> MockResponse().setHeader("Content-Type", "application/javascript").setBody("""
                            window.addEventListener('load',()=>{
                              let file='/bad.m3u8';
                              window.jwplayer=()=>({getState:()=> 'idle',getPlaylist:()=>[{sources:[{file,type:'hls'}]}]});
                              setTimeout(()=>{file='/good.m3u8'},1500);
                            });
                        """.trimIndent())
                        "/bad.m3u8" -> MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Not media</html>")
                        "/good.m3u8" -> MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl")
                            .setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\n480.m3u8\n")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val client = Lk21Client(File(context.cacheDir, "pipeline-${System.nanoTime()}").apply { mkdirs() })
            val engine = WebViewScrapeEngine(context)
            try {
                val start = server.url("/wrapper").toString()
                val catalog = server.url("/catalog").toString()
                val result = PlayerExtraction(
                    get = { url, parent, media -> client.playerResponse(url, parent, media) },
                    post = { _, _ -> error("Unexpected API") },
                    browser = { initial, parent, provider, validate ->
                        val envelope = engine.scrape(ScrapeRequest(ScrapeKind.STREAM, initial, referer = parent,
                            embeddingPageUrl = parent, streamProviderUrl = provider, timeoutMs = 15_000, retries = 0), validate)
                        ScrapeValidator.requireStream(envelope)
                    },
                ).resolve(start, catalog)
                assertEquals(server.url("/good.m3u8").toString(), result.url)
                assertEquals("application/x-mpegURL", result.mimeType)
                assertEquals(server.url("/provider").toString(), result.headers["Referer"])
                assertEquals(1, paths.count { it == "/dependency.js" })
                assertEquals(1, paths.count { it == "/bad.m3u8" })
                assertEquals(1, paths.count { it == "/good.m3u8" })
                // One HTTP discovery and one browser execution, no additional reload on rejection.
                assertEquals(2, paths.count { it == "/wrapper" })
                assertEquals(2, paths.count { it == "/provider" })
            } finally { engine.close() }
        }
    }
}
