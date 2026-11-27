package rofil.movie.app.data.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rhino integration tests. The input fixtures are the exact responses captured
 * in `lk21-request/` (copied verbatim into
 * `app/src/test/resources/lk21/captured_*.html` by the sync step).
 */
class JsExtractorTest {

    private val seriesHtml: String =
        javaClass.getResourceAsStream("/lk21/captured_series.html")!!.readBytes()
            .toString(Charsets.UTF_8)

    private val episodeHtml: String =
        javaClass.getResourceAsStream("/lk21/captured_episode.html")!!.readBytes()
            .toString(Charsets.UTF_8)

    private val movieHtml: String =
        javaClass.getResourceAsStream("/lk21/captured_movie.html")!!.readBytes()
            .toString(Charsets.UTF_8)

    // ------------------------------------------------------------- JSON blocks

    @Test
    fun `season-data JSON block is parsed with Rhino`() {
        val season = JsExtractor.seasonData(seriesHtml)
        assertNotNull("season-data block must be found", season)
        val episodes = season!!.optJSONArray("1")
        assertNotNull(episodes)
        assertEquals(8, episodes!!.length())
        val first = episodes.getJSONObject(0)
        assertEquals(1, first.getInt("episode_no"))
        assertEquals("visionquest-season-1-episode-1-2026", first.getString("slug"))
        assertTrue(first.getString("title").contains("Episode 1"))
    }

    @Test
    fun `watch-history JSON block is parsed with Rhino`() {
        val watch = JsExtractor.watchHistory(seriesHtml)
        assertNotNull(watch)
        assertEquals(34912, watch!!.getInt("id"))
        assertEquals("VisionQuest", watch.getString("title"))
        assertEquals("8.4", watch.getString("rating"))
        assertEquals(8, watch.getInt("total_eps"))
        assertEquals(1, watch.getInt("total_season"))
        assertTrue(watch.getString("poster").contains("film-visionquest-2026-lk21.jpg"))
    }

    @Test
    fun `episode watch block carries episode navigation`() {
        val watch = JsExtractor.watchHistory(episodeHtml)
        assertNotNull(watch)
        assertEquals("visionquest-season-1-episode-3-2026", watch!!.getString("next"))
        assertEquals(1, watch.getInt("current_season"))
        assertEquals(2, watch.getInt("current_eps"))
    }

    @Test
    fun `movie watch block carries runtime`() {
        val watch = JsExtractor.watchHistory(movieHtml)
        assertNotNull(watch)
        assertEquals(35308, watch!!.getInt("id"))
        assertEquals("The Slightest Touch", watch.getString("title"))
        assertEquals("01:42", watch.getString("runtime"))
    }

    @Test
    fun `JSON-LD blocks are extracted`() {
        val movieLd = JsExtractor.jsonLd(movieHtml)
        val movie = movieLd.firstOrNull { it.optString("@type") == "Movie" }
        assertNotNull(movie)
        assertEquals("8.3", movie!!.optJSONObject("aggregateRating")?.optString("ratingValue"))
        assertEquals(38, movie.optJSONObject("aggregateRating")?.optInt("ratingCount"))

        val seriesLd = JsExtractor.jsonLd(seriesHtml)
        val tvSeries = seriesLd.firstOrNull { it.optString("@type") == "TVSeries" }
        assertNotNull(tvSeries)

        val episodeLd = JsExtractor.jsonLd(episodeHtml)
        val tvEpisode = episodeLd.firstOrNull { it.optString("@type") == "TVEpisode" }
        assertNotNull(tvEpisode)
        assertEquals(2, tvEpisode!!.optInt("episodeNumber"))
        assertEquals("VisionQuest (2026)",
            tvEpisode.optJSONObject("partOfSeries")?.optString("name"))
    }

    @Test
    fun `missing season block yields null (not an error)`() {
        assertNull(JsExtractor.seasonData("<html><body>no blocks</body></html>"))
        assertNull(JsExtractor.watchHistory("<html></html>"))
        assertTrue(JsExtractor.jsonLd(null).isEmpty())
        assertTrue(JsExtractor.jsonLd("").isEmpty())
    }

    // --------------------------------------------------------------- helpers

    @Test
    fun `atob payloads are decoded`() {
        val b64 = java.util.Base64.getEncoder().encodeToString("https://cdn.example.com/movie.m3u8".toByteArray())
        val decoded = JsExtractor.decodeAtob("<script>src=atob('$b64')</script>")
        assertTrue(decoded.any { it.contains("https://cdn.example.com/movie.m3u8") })
    }

    @Test
    fun `evalExpression evaluates atob through Rhino`() {
        val value = JsExtractor.evalExpression("JSON.stringify({a:1,b:'x'})") as String
        assertEquals("{\"a\":1,\"b\":\"x\"}", value.replace(" ", ""))
    }

    @Test
    fun `packer payload is unpacked by Rhino`() {
        // Dean Edwards packer for: sources|https|cdn|example|com|movie|m3u8
        val packed = "eval(function(p,a,c,k,e,d){e=function(c){return(c<a?'':e(parseInt(c/a)))+" +
            "((c=c%a)>35?String.fromCharCode(c+29):c.toString(36))};if(!''.replace(/^/,String))" +
            "{while(c--){d[e(c)]=k[c]||e(c)}k=[function(e){return d[e]}];e=function(){return'\\\\w+'};c=1};" +
            "while(c--){if(k[c]){p=p.replace(new RegExp('\\\\b'+e(c)+'\\\\b','g'),k[c])}}return p}" +
            "('0=[{file:\"1://2.3.4/5.6\"}]',62,7,'sources|https|cdn|example|com|movie|m3u8'.split('|'),0,{}))"
        val unpacked = JsExtractor.unpackPacker(packed)
        assertTrue("expected unpacked sources, got $unpacked", unpacked.isNotEmpty())
        assertTrue(
            unpacked.any { it.contains("https://cdn.example.com/movie.m3u8") },
        )
    }

    @Test
    fun `parseJson handles primitives and nested objects`() {
        assertTrue(JsExtractor.parseJson("42") == 42L || JsExtractor.parseJson("42") == 42)
        val obj = JsExtractor.parseJson("""{"a":[1,2,{"b":"c"}]}""") as JSONObject
        assertEquals(2, obj.getJSONArray("a").getInt(1))
        assertEquals("c", obj.getJSONArray("a").getJSONObject(2).getString("b"))
    }

    @Test
    fun `malformed json returns null safely`() {
        assertNull(JsExtractor.parseJson("{oops"))
        assertNull(JsExtractor.parseJson(null))
        assertNull(JsExtractor.parseJson(""))
    }
}