package com.freepi.lk21.data.scrape

import com.freepi.lk21.data.model.MovieDetail
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Maps validated extraction JSON into the Kotlin models. Payloads below mirror
 * the output produced by the Playwright-verified JavaScript extractors
 * (tools/scrape-verify).
 */
class ScrapeMapperTest {

    private val mapper = ScrapeMapper()
    private val pageUrl = "https://tv13.lk21official.cc/gladiator-2-2024/"

    private fun card(slug: String, title: String, poster: String = "//s7.lk21static.buzz/uploads/p.jpg") =
        JSONObject()
            .put("slug", slug)
            .put("title", title)
            .put("url", "https://tv13.lk21official.cc/$slug/")
            .put("poster", poster)
            .put("rating", "6.9")
            .put("quality", "WEBDL")
            .put("duration", "2h 28m")
            .put("genres", JSONArray(listOf("Action", "Adventure")))
            .put("year", "2024")

    // ------------------------------------------------------------------ home

    @Test
    fun `home sections are mapped into HomeData`() {
        val sections = JSONObject()
            .put("featured", JSONArray(listOf(card("featured-a", "Featured A (2024)"))))
            .put("popular", JSONArray(listOf(card("pop-a", "Popular A (2024)"))))
            .put("latest", JSONArray(listOf(card("new-a", "New A (2024)"))))
            .put("recommended", JSONArray(listOf(card("rec-a", "Rec A (2024)"))))
            .put("series", JSONArray(listOf(card("series-a", "Series A (2024)"))))

        val filters = JSONObject()
            .put("genres", JSONArray(listOf(JSONObject().put("name", "Action").put("path", "/genre/action/"))))
            .put("countries", JSONArray(listOf(JSONObject().put("name", "US").put("path", "/country/united-states/"))))
            .put("years", JSONArray(listOf(JSONObject().put("name", "2025").put("path", "/year/2025/"))))
            .put("qualities", JSONArray(listOf(JSONObject().put("name", "WEBDL").put("path", "/quality/webdl/"))))

        val data = JSONObject().put("sections", sections).put("filters", filters)
        val home = mapper.home(data)

        assertEquals(1, home.featured.size)
        assertEquals(1, home.popular.size)
        assertEquals(1, home.latest.size)
        assertEquals(1, home.recommended.size)
        assertEquals(1, home.series.size)
        assertEquals("Featured A (2024)", home.featured.first().title)
        assertEquals(listOf("Action", "Adventure"), home.latest.first().genres)
        assertEquals("2024", home.latest.first().year)
        assertEquals("https://s7.lk21static.buzz/uploads/p.jpg", home.latest.first().poster)
        assertEquals("Action", home.filters.genres.first().name)
        assertEquals("2025", home.filters.years.first().name)
    }

    @Test
    fun `featured falls back to popular when empty`() {
        val sections = JSONObject()
            .put("featured", JSONArray())
            .put("popular", JSONArray(listOf(card("pop-a", "Pop A (2024)"))))
            .put("latest", JSONArray().put(card("new-x", "New X (2024)")))
        val data = JSONObject().put("sections", sections)
        val home = mapper.home(data)
        assertEquals("Pop A (2024)", home.featured.first().title)
    }

    // ------------------------------------------------------------------ list

    @Test
    fun `list page is mapped with pagination`() {
        val data = JSONObject()
            .put("title", "Genre: Action")
            .put("items", JSONArray(listOf(card("die-hard-1988", "Die Hard (1988)"), card("mad-max-2015", "Mad Max: Fury Road (2015)"))))
            .put("page", 2)
            .put("totalPages", 3)
            .put("nextPageUrl", "https://tv13.lk21official.cc/genre/action/page/3/")
            .put("emptyHint", false)

        val page = mapper.list(data, pageUrl)
        assertEquals(2, page.page)
        assertEquals(3, page.totalPages)
        assertTrue(page.hasMore)
        assertEquals(2, page.items.size)
        assertEquals("Die Hard (1988)", page.items.first().title)
        assertEquals("https://s7.lk21static.buzz/uploads/p.jpg", page.items.first().poster)
    }

    @Test
    fun `cards with blank slugs are skipped`() {
        val data = JSONObject()
            .put("items", JSONArray(listOf(
                card("good-one", "Good One (2024)"),
                JSONObject().put("title", "No slug here").put("url", "https://x/"),
            )))
            .put("page", 1).put("totalPages", 4)
        assertEquals(1, mapper.list(data, pageUrl).items.size)
    }

    @Test
    fun `title cleaning strips marketing suffixes`() {
        val data = JSONObject().put("items", JSONArray(listOf(
            JSONObject()
                .put("slug", "wicked-2024")
                .put("title", "Nonton Wicked (2024) Film Subtitle Indonesia Streaming")
                .put("url", "https://tv13.lk21official.cc/wicked-2024/")
        ))).put("page", 1).put("totalPages", 2)
        assertEquals("Wicked (2024)", mapper.list(data, pageUrl).items.first().title)
    }

    // ---------------------------------------------------------------- detail

    @Test
    fun `full detail payload maps every metadata field`() {
        val data = JSONObject()
            .put("title", "Gladiator II (2024)")
            .put("year", "2024")
            .put("poster", "https://s7.lk21static.buzz/uploads/g2.jpg")
            .put("synopsis", "Lucius kembali ke Roma.")
            .put(
                "jsonLd",
                JSONObject()
                    .put("name", "Gladiator II (2024)")
                    .put("dateCreated", "2024-11-22")
                    .put("duration", "PT148M")
                    .put("sameAs", "http://www.imdb.com/title/tt6470956/")
                    .put("aggregateRating", JSONObject().put("ratingValue", "6.9").put("reviewCount", "240000")),
            )
            .put("meta", JSONObject()
                .put("Sutradara", "Ridley Scott")
                .put("Bintang Film", "Paul Mescal, Denzel Washington")
                .put("Durasi", "2 jam 28 menit"))
            .put("infoTags", JSONArray(listOf("WEBDL", "2h 28m", "6.9", "18+")))
            .put("rating", "6.9")
            .put("ratingCount", "240000")
            .put("genres", JSONArray(listOf("Action", "Adventure")))
            .put("countries", JSONArray(listOf("United States")))
            .put("directors", JSONArray(listOf("Ridley Scott")))
            .put("cast", JSONArray(listOf("Paul Mescal", "Denzel Washington")))
            .put("quality", "WEBDL")
            .put("durationText", "2 jam 28 menit")
            .put("imdbId", "tt6470956")
            .put("sources", JSONArray().put(
                JSONObject()
                    .put("provider", "CAST")
                    .put("label", "CAST")
                    .put("embedUrl", "https://playeriframe.lol/iframe.php?url=https%3A%2F%2Ffilelions.to%2Fv%2Fabc123")
                    .put("decodedUrl", "https://filelions.to/v/abc123")
                    .put("maxQuality", "1080")
                    .put("qualities", JSONArray(listOf("1080", "720")))
            ))
            .put("subtitles", JSONArray().put(
                JSONObject().put("label", "gladiator-2-id.srt").put("url", "https://subs.example.com/g2.srt")
            ))
            .put("related", JSONArray().put(card("gladiator-2000", "Gladiator (2000)")))

        val detail: MovieDetail = mapper.detail(data, pageUrl)
        assertEquals("Gladiator II (2024)", detail.title)
        assertEquals("2024", detail.year)
        assertEquals("https://s7.lk21static.buzz/uploads/g2.jpg", detail.poster)
        assertEquals("6.9", detail.rating)
        assertEquals("240000", detail.ratingCount)
        assertEquals("tt6470956", detail.imdbId)
        assertEquals(148L * 60_000L, detail.durationMs)
        assertEquals(listOf("Action", "Adventure"), detail.genres)
        assertEquals(listOf("Ridley Scott"), detail.directors)
        assertEquals(listOf("Paul Mescal", "Denzel Washington"), detail.cast)
        assertEquals("WEBDL", detail.quality)
        assertEquals(1, detail.sources.size)
        val source = detail.sources.first()
        assertEquals("CAST", source.provider)
        assertEquals("https://filelions.to/v/abc123", source.decodedUrl)
        assertEquals(listOf("1080", "720"), source.qualities)
        assertEquals(1, detail.subtitles.size)
        assertEquals("https://subs.example.com/g2.srt", detail.subtitles.first().url)
        assertEquals(1, detail.related.size)
        assertEquals("gladiator-2000", detail.related.first().slug)
    }

    @Test
    fun `duration parsing supports common formats`() {
        assertEquals(148L * 60_000L, mapper.parseDuration("PT148M"))
        assertEquals(125L * 60_000L, mapper.parseDuration("2 jam 5 menit"))
        assertEquals(109L * 60_000L, mapper.parseDuration("1h 49m"))
        assertEquals(133L * 60_000L, mapper.parseDuration("02:13"))
        assertEquals(95L * 60_000L, mapper.parseDuration("95 min"))
        assertNull(mapper.parseDuration("WEBDL"))
    }

    @Test
    fun `redirect url is preserved for series pages`() {
        val data = JSONObject()
            .put("title", "Series X (2025)")
            .put("sources", JSONArray())
            .put("redirectUrl", "https://dramamu.lk21.de/series-x-episode-1/")
        val detail = mapper.detail(data, "https://tv13.lk21official.cc/series-x-2025/")
        assertEquals("https://dramamu.lk21.de/series-x-episode-1/", detail.redirectUrl)
        assertNotNull(detail.related)
    }

    @Test
    fun `series info from season-data is mapped into the model`() {
        val seasons = JSONArray().put(
            JSONObject()
                .put("number", 1)
                .put(
                    "episodes",
                    JSONArray(listOf(
                        JSONObject()
                            .put("number", 1)
                            .put("title", "VisionQuest - Season 1 Episode 1 (2026)")
                            .put("slug", "visionquest-season-1-episode-1-2026"),
                        JSONObject()
                            .put("number", 2)
                            .put("title", "VisionQuest - Season 1 Episode 2 (2026)")
                            .put("slug", "visionquest-season-1-episode-2-2026"),
                    ))
                )
        )
        val data = JSONObject()
            .put("title", "VisionQuest (2026)")
            .put("sources", JSONArray())
            .put("seasons", seasons)
            .put("totalSeasons", 1)
            .put("totalEpisodes", 8)
            .put("firstSlug", "visionquest-season-1-episode-1-2026")
            .put("latestSlug", "visionquest-season-1-episode-8-2026")
            .put("nextSlug", "visionquest-season-1-episode-3-2026")
            .put("currentSeason", 1)
            .put("currentEpisode", 2)
            .put("seriesSlug", "visionquest-2026")
            .put("seriesTitle", "VisionQuest (2026)")

        val detail = mapper.detail(data, "https://tv13.lk21online.de/visionquest-2026")

        val series = detail.series
        assertNotNull(series)
        assertEquals(1, series!!.totalSeasons)
        assertEquals(8, series.totalEpisodes)
        assertEquals(2, series.episodes.size)
        assertEquals("visionquest-season-1-episode-2-2026", series.episodes[1].slug)
        assertEquals("visionquest-season-1-episode-1-2026", series.firstSlug)
        assertEquals("visionquest-season-1-episode-8-2026", series.latestSlug)
        assertEquals("visionquest-season-1-episode-3-2026", series.nextSlug)
        assertEquals(1, series.currentSeason)
        assertEquals(2, series.currentEpisode)
        assertEquals("visionquest-2026", series.seriesSlug)
    }

    @Test
    fun `series info is null when no season data exists`() {
        val data = JSONObject()
            .put("title", "A Movie (2024)")
            .put("sources", JSONArray())
        val detail = mapper.detail(data, "https://tv13.lk21official.cc/a-movie-2024/")
        assertNull(detail.series)
    }

    @Test
    fun `post id is carried through`() {
        val data = JSONObject()
            .put("title", "The Slightest Touch (2026)")
            .put("sources", JSONArray())
            .put("postId", "35308")
        val detail = mapper.detail(data, "https://tv13.lk21official.cc/slightest-touch-2026")
        assertEquals("35308", detail.postId)
    }
}