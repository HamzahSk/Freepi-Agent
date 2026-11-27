package rofil.movie.app.data.remote

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests against the *actual* captured responses in `lk21-request/`.
 *
 * The same bytes are copied to `app/src/test/resources/lk21/captured_*.html`
 * (the capture files also contain the HTTP status line + headers, which the
 * fixture sync strips). Every assertion below is derived from the captured
 * traffic, not invented.
 */
class CapturedLk21ParserTest {

    private val parser = Lk21Parser()

    private fun captured(name: String): String =
        javaClass.getResourceAsStream("/lk21/$name")!!.readBytes().toString(Charsets.UTF_8)

    private val movieHtml = captured("captured_movie.html")
    private val seriesHtml = captured("captured_series.html")
    private val episodeHtml = captured("captured_episode.html")

    // ------------------------------------------------------------------- movie

    @Test
    fun `movie page from lk21-request parses full metadata`() {
        val url = "https://tv13.lk21official.cc/slightest-touch-2026"
        val doc = Jsoup.parse(movieHtml, url)
        val detail = parser.parseDetail(doc, url)

        assertEquals("The Slightest Touch (2026)", detail.title)
        assertEquals("2026", detail.year)
        assertEquals("8.3", detail.rating)
        assertEquals("38", detail.ratingCount)
        assertEquals("https://poster.assetsy.de/wp-content/uploads/2026/11/film-slightest-touch-2026-lk21.jpg", detail.poster)
        assertTrue(detail.synopsis.contains("Epidermolysis Bullosa"))
        assertEquals(listOf("Rachel Fleit"), detail.directors)
        assertTrue(detail.cast.contains("Colin Farrell"))
        assertTrue(detail.cast.contains("Emma Fogarty"))
        assertEquals(listOf("Documentary", "Adventure"), detail.genres)
        assertTrue(detail.countries.contains("Ireland"))
        assertEquals("workkeerr", detail.translator)
        assertEquals("35308", detail.postId)
        // 3 providers: P2P / CAST / HYDRAX (videonode.de iframes)
        assertEquals(3, detail.sources.size)
        assertTrue(detail.sources.all { it.embedUrl.startsWith("https://videonode.de/iframe3/") })
        assertNotNull(detail.sources.firstOrNull { it.provider.equals("P2P", true) })
        assertNotNull(detail.sources.firstOrNull { it.provider.equals("HYDRAX", true) })
        assertTrue(detail.related.isNotEmpty())
        assertTrue(detail.trailerUrl?.contains("youtube") == true)
        // Movies carry no season data.
        assertNull(detail.series)
    }

    // ------------------------------------------------------------------ series

    @Test
    fun `series hub page from lk21-request exposes episodes and nav`() {
        val url = "https://tv.lk21online.de/visionquest-2026"
        val doc = Jsoup.parse(seriesHtml, url)
        val detail = parser.parseDetail(doc, url)

        assertEquals("VisionQuest (2026)", detail.title)
        assertEquals("2026", detail.year)
        assertEquals("8.4", detail.rating)
        assertEquals("3072", detail.ratingCount)
        val series = detail.series
        assertNotNull(series)
        assertEquals(1, series!!.totalSeasons)
        assertEquals(8, series.totalEpisodes)
        assertEquals(1, series.seasons.size)
        assertEquals(8, series.seasons.first().episodes.size)
        assertEquals("visionquest-season-1-episode-1-2026", series.firstSlug)
        assertEquals("visionquest-season-1-episode-8-2026", series.latestSlug)
        assertEquals("visionquest-season-1-episode-1-2026", series.seasons.first().episodes.first().slug)
        assertEquals(1, series.seasons.first().episodes.first().number)
        // The series hub itself has no player sources.
        assertTrue(detail.sources.isEmpty())
        assertTrue(detail.trailerUrl?.contains("youtube.com/embed") == true)
    }

    @Test
    fun `episode page from lk21-request has providers and episode nav`() {
        val url = "https://tv.lk21online.de/visionquest-season-1-episode-2-2026"
        val doc = Jsoup.parse(episodeHtml, url)
        val detail = parser.parseDetail(doc, url)

        assertEquals("VisionQuest - Season 1 Episode 2 (2026)", detail.title)
        assertEquals("34912", detail.postId)
        assertEquals(3, detail.sources.size)
        val series = detail.series
        assertNotNull(series)
        assertEquals(2, series!!.currentEpisode)
        assertEquals(1, series.currentSeason)
        assertEquals(8, series.totalEpisodes)
        assertEquals("visionquest-season-1-episode-3-2026", series.nextSlug)
        assertEquals("visionquest-season-1-episode-1-2026", series.prevSlug)
        assertEquals("visionquest-2026", series.seriesSlug)
    }

    // ------------------------------------------------------------------ cards

    @Test
    fun `related cards from captured theme parse`() {
        val url = "https://tv13.lk21official.cc/slightest-touch-2026"
        val doc = Jsoup.parse(movieHtml, url)
        val related = parser.parseCards(doc)
        assertTrue(related.isNotEmpty())
        val first = related.first()
        assertTrue(first.slug.isNotBlank())
        assertTrue(first.title.isNotBlank())
        assertTrue(first.url.startsWith("https://"))
        assertNotNull(first.poster)
    }
}