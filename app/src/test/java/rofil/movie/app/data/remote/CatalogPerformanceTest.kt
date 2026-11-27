package rofil.movie.app.data.remote

import rofil.movie.app.data.scrape.HttpCatalogDataSource
import rofil.movie.app.data.scrape.ScrapeResourcePolicy
import rofil.movie.app.data.scrape.ScrapeLogger
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CatalogPerformanceTest {
    @get:Rule val temp = TemporaryFolder()
    private fun fixture() = javaClass.getResource("/lk21/captured_movie.html")!!.readText()
    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000

    @Test fun measuredCapturedDetailCacheAndConcurrentDeduplication() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(fixture())
                    .setBodyDelay(80, TimeUnit.MILLISECONDS)
            }
            val client = Lk21Client(temp.newFolder())
            val source = HttpCatalogDataSource(client)
            val url = server.url("/slightest-touch-2026/").toString()
            // Reproduce the previous successful-detail loop count, at the HTTP transport boundary.
            var start = System.nanoTime()
            repeat(3) { source.detail(url, forceRefresh = true) }
            val repeatedMs = elapsed(start)
            assertEquals(3, server.requestCount)
            client.clearCache()
            start = System.nanoTime()
            val details = coroutineScope { (1..8).map { async { source.detail(url) } }.awaitAll() }
            val sharedMs = elapsed(start)
            assertTrue(details.all { it.title == details.first().title })
            assertEquals(4, server.requestCount)
            start = System.nanoTime()
            source.detail(url)
            val cachedMs = elapsed(start)
            assertEquals(4, server.requestCount)
            println("PERFORMANCE fixture=captured_detail delayMs=80 repeatedLoads=3 repeatedMs=$repeatedMs concurrentConsumers=8 sharedRequests=1 sharedMs=$sharedMs cachedRequests=0 cachedMs=$cachedMs")
        }
    }

    @Test fun challengeNeverAttemptsMirrorsOrFallbackRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
            val client = Lk21Client(temp.newFolder())
            assertTrue(runCatching { client.fetchHtml(server.url("/").toString()) }.exceptionOrNull() is AccessBlockedException)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancellationStopsObsoleteBodyReadsAndDoesNotPoisonCache() = runBlocking {
        MockWebServer().use { server ->
            val bodyStarted = CompletableDeferred<Unit>()
            val count = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (count.incrementAndGet() > 1) return MockResponse().setBody("fresh")
                    bodyStarted.complete(Unit)
                    return MockResponse().setBody("obsolete").setBodyDelay(1, TimeUnit.SECONDS)
                }
            }
            val client = Lk21Client(temp.newFolder())
            val job = async { client.fetchHtml(server.url("/slow").toString()) }
            bodyStarted.await(); delay(40)
            val start = System.nanoTime()
            job.cancelAndJoin()
            assertTrue(elapsed(start) < 1000)
            assertEquals("fresh", client.fetchHtml(server.url("/slow").toString()))
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun oneDepartingConsumerDoesNotCancelAnother() = runBlocking {
        val flight = rofil.movie.app.core.SingleFlight<String, String>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val first = async { flight.run("page") { calls.incrementAndGet(); started.complete(Unit); release.await(); "ok" } }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { flight.run("page") { error("duplicate") } }
        first.cancelAndJoin(); release.complete(Unit)
        assertEquals("ok", second.await())
        assertEquals(1, calls.get())
    }

    @Test fun skipOnlySafeResourceClassesAndRedactSignedPaths() {
        assertTrue(ScrapeResourcePolicy.skip("https://static.cloudflareinsights.com/beacon.min.js"))
        assertTrue(ScrapeResourcePolicy.skip("https://cdn.test/poster.jpg?x=1"))
        for (url in listOf("https://playcdn.de/js/init_player.js?t=1", "https://playcdn.de/verify/id",
            "https://playcdn.de/js/jwplayer.js", "https://a.test/api.php", "https://a.test/master.m3u8",
            "https://a.test/style.css", "https://a.test/cdn-cgi/challenge-platform/js/main.js"))
            assertFalse(url, ScrapeResourcePolicy.skip(url))
        val safe = ScrapeLogger.safeUrl("https://user:secret@cdn.test/signed-secret?token=secret#secret")
        assertEquals("https://cdn.test/[redacted]", safe)
    }

    @Test fun headersCookiesAndRedirectBaseArePreserved() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Set-Cookie", "session=legitimate; Path=/").setResponseCode(302).setHeader("Location", "/final/"))
            server.enqueue(MockResponse().setBody("<a href='movie'>Movie</a>"))
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:10,\nsegment.ts"))
            val client = Lk21Client(temp.newFolder())
            val doc = client.fetchDocument(server.url("/old/").toString())
            assertEquals(server.url("/final/movie").toString(), doc.selectFirst("a")!!.absUrl("href"))
            assertEquals("session=legitimate", client.playbackHeaders(server.url("/master.m3u8").toString())["Cookie"])
            client.playerResponse(server.url("/master.m3u8").toString(), doc.baseUri(), true)
            server.takeRequest(); assertEquals("session=legitimate", server.takeRequest().getHeader("Cookie"))
            assertEquals("session=legitimate", server.takeRequest().getHeader("Cookie"))
        }
    }
    @Test fun playbackSharesConnectionPoolButHasNoWholeCallDeadline() {
        val client = Lk21Client(temp.newFolder())
        assertSame(client.playerHttpClient(), client.playerHttpClient())
        assertEquals(0, client.playerHttpClient().callTimeoutMillis)
    }

}
