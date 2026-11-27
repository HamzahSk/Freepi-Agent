package rofil.movie.app.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlayerTransportTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun documentedApiUsesEncodedFormAndPageReferer() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"embedUrl\":\"https://example/embed\"}"))
            val api = server.url("/api.php").toString()
            val parent = server.url("/iframe3/provider/id").toString()
            val response = Lk21Client(temp.newFolder()).playerResponse(api, parent,
                popup = PopupRequest(api, "provider & name", "id+with/special=?"))
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api.php", request.path)
            assertEquals(parent, request.getHeader("Referer"))
            assertEquals("application/x-www-form-urlencoded", request.getHeader("Content-Type"))
            assertEquals("host=provider%20%26%20name&id=id%2Bwith%2Fspecial%3D%3F", request.body.readUtf8())
            assertEquals("https://example/embed", PlayerMetadata.embed(response.body, response.url))
        }
    }

    @Test fun mediaValidationFetchesBoundedRangeAndRejectsHtml() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>not a manifest</html>"))
            val response = Lk21Client(temp.newFolder()).playerResponse(server.url("/a.m3u8").toString(),
                server.url("/embed").toString(), media = true)
            assertEquals("bytes=0-524287", server.takeRequest().getHeader("Range"))
            assertNull(PlayerMetadata.validatedMime(response))
        }
    }

    @Test fun telemetryIsNotChallengeButAccessErrorsAre() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<title>LK21 - Popup Player Video</title><script src='/cdn-cgi/challenge-platform/scripts/jsd/main.js'></script>"))
            server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
            val client = Lk21Client(temp.newFolder())
            val url = server.url("/embed").toString()
            assertTrue(client.playerResponse(url, url).body.contains("Popup Player"))
            assertTrue(runCatching { client.playerResponse(url, url) }.exceptionOrNull() is AccessBlockedException)
        }
    }
}
