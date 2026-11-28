package rofil.movie.app.data.scrape

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import rofil.movie.app.data.remote.NetworkUnavailableException

class ExtensionCompatibilityTest {
    private val mapper = ScrapeMapper()

    @Test fun `MovieBox filters and search pagination preserve extension navigation tokens`() {
        val route = "moviebox://filter?type=series&page=2&genre=Science%20Fiction&year=2026"
        val args = ExtensionRoutes.arguments("moviebox", route)
        assertEquals("series", args.getString("type"))
        assertEquals("2", args.getString("page"))
        assertEquals("Science Fiction", args.getString("genre"))
        val search = ExtensionRoutes.arguments("moviebox", "moviebox://search?q=star+wars&page=3")
        assertEquals("star wars", search.getString("query"))
        assertThrows(IllegalArgumentException::class.java) { ExtensionRoutes.arguments("movy", route) }
        assertFalse(ScrapeValidator.isAcceptableUrl(route)) // never accepted as playable media
        val data = JSONObject().put("items", JSONArray()).put("emptyHint", true).put("page", 1).put("nextPageUrl", route)
        val envelope = ScrapeValidator.parseEnvelope(JSONObject().put("schema", ScrapeSchemas.LIST).put("ok", true).put("data", data), ScrapeKind.LIST)
        assertEquals(route, mapper.list(ScrapeValidator.requireList(envelope, false)).nextPageUrl)
        for (url in listOf("javascript:alert(1)", "intent://filter", "file://search", "moviebox://user:pass@filter", "moviebox://filter#bad")) assertFalse(url, ExtensionRoutes.isRoute(url))
    }

    @Test fun `detail identity and season navigation survive extension mapping`() {
        for (slug in listOf("moviebox:42:s1:e2", "movy:tv:1396:s1:e2")) {
            val data = JSONObject().put("slug", slug).put("title", "Series").put("poster", "https://cdn.example/poster.jpg")
                .put("backdrop", "https://cdn.example/backdrop.jpg").put("currentSeason", 1).put("currentEpisode", 2)
                .put("seasons", JSONArray().put(JSONObject().put("number", 1).put("episodes", JSONArray()
                    .put(JSONObject().put("number", 2).put("slug", slug).put("title", "Episode 2")))))
            val detail = mapper.detail(data, "https://source.example/moviedetail/title?id=42")
            assertEquals(slug, detail.slug)
            assertEquals("https://cdn.example/backdrop.jpg", detail.backdrop)
            assertEquals(slug, detail.series!!.episodes.single().slug)
        }
        assertEquals("movy:movie:27205", mapper.detail(JSONObject().put("title", "Inception"), "https://movy.example/movy:movie:27205").slug)
    }

    @Test fun `remote network errors retain network error type`() {
        assertThrows(NetworkUnavailableException::class.java) {
            ScrapeValidator.parseEnvelope(JSONObject("""{"schema":"freepi.error.v1","ok":false,"error":{"code":"NETWORK","message":"offline"}}"""), ScrapeKind.HOME)
        }
    }
}
