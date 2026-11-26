package com.freepi.lk21.data.remote

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser contracts verified against the real page structure of the source
 * (see the archived markup used while implementing the app).
 */
class Lk21ParserTest {

    private val parser = Lk21Parser()

    private val listHtml = """
        <html><body>
        <section class="mega-grid" id="populer">
          <article class="widget-item">
            <figure class="grid-poster">
              <a href="https://tv13.lk21official.cc/venom-the-last-dance-2024/"
                 title="Venom: The Last Dance (2024) Film Subtitle Indonesia Streaming / Download"
                 rel="bookmark">
                <img src="//s7.lk21static.buzz/uploads/venom.jpg" alt="Venom: The Last Dance (2024)">
              </a>
              <div class="grid-meta">
                <div class="rating"><i class="fa-star"></i>6.1</div>
                <div class="quality quality-HD">HD</div>
                <div class="duration">01:49</div>
              </div>
            </figure>
            <header class="grid-header"><h1 class="grid-title">
              <a href="https://tv13.lk21official.cc/venom-the-last-dance-2024/" rel="bookmark">
                Venom: The Last Dance (2024)
              </a>
            </h1></header>
            <footer class="grid-action"><div class="grid-categories">
              <a href="/genre/action/" rel="category">Action</a>,
              <a href="/genre/sci-fi/" rel="category">Sci-fi</a>
            </div></footer>
          </article>
          <article class="widget-item">
            <figure class="grid-poster">
              <a href="https://tv13.lk21official.cc/study-group-2025/" rel="bookmark">
                <img src="//s7.lk21static.buzz/uploads/study.jpg" alt="Study Group (2025)">
              </a>
              <div class="grid-meta"><div class="rating">7.4</div><div class="quality">HD</div></div>
            </figure>
          </article>
        </section>
        <div id="pagination">
          <span>Halaman 1 dari 1,039</span>
          <nav><ul class="pagination">
            <li class="active"><span class="page-numbers current">1</span></li>
            <li><a class="page-numbers" href="https://tv13.lk21official.cc/page/2/">2</a></li>
            <li><a class="next page-numbers" href="https://tv13.lk21official.cc/page/2/">»</a></li>
          </ul></nav>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `parses catalog cards with metadata`() {
        val doc = Jsoup.parse(listHtml, "https://tv13.lk21official.cc/")
        val cards = parser.parseCards(doc)

        assertEquals(2, cards.size)
        val venom = cards.first { it.slug == "venom-the-last-dance-2024" }
        assertEquals("Venom: The Last Dance (2024)", venom.title)
        assertEquals("https://s7.lk21static.buzz/uploads/venom.jpg", venom.poster)
        assertEquals("6.1", venom.rating)
        assertEquals("HD", venom.quality)
        assertEquals("01:49", venom.duration)
        assertEquals(listOf("Action", "Sci-fi"), venom.genres)
        assertEquals("2024", venom.year)
    }

    @Test
    fun `parses pagination and next url`() {
        val doc = Jsoup.parse(listHtml, "https://tv13.lk21official.cc/")
        val page = parser.parseList(doc, 1)

        assertEquals(1, page.page)
        assertEquals(1039, page.totalPages)
        assertTrue(page.hasMore)
        assertEquals("https://tv13.lk21official.cc/page/2/", page.nextPageUrl)
    }

    @Test
    fun `parses filters from navigation links`() {
        val doc = Jsoup.parse(
            """
            <html><body><ul>
              <li><a href="https://tv13.lk21official.cc/genre/action/">Action</a></li>
              <li><a href="https://tv13.lk21official.cc/genre/drama/">Drama</a></li>
              <li><a href="/country/united-states/">United States</a></li>
              <li><a href="/year/2025/">2025</a></li>
              <li><a href="/quality/webdl/">WEBDL</a></li>
            </ul></body></html>
            """.trimIndent(),
            "https://tv13.lk21official.cc/"
        )
        val filters = parser.parseFilters(doc)
        assertEquals(2, filters.genres.size)
        assertEquals("Action", filters.genres.first().name)
        assertEquals(1, filters.countries.size)
        assertEquals("2025", filters.years.first().name)
        assertEquals("WEBDL", filters.qualities.first().name)
    }

    private val detailHtml = """
        <html><head>
        <script type="application/ld+json">{
          "@context": "http://schema.org",
          "@type": "Movie",
          "name": "Bridget Jones: Mad About the Boy (2025)",
          "image": "//s2.lk21static.buzz/uploads/bridget.jpg",
          "datePublished": "February 16, 2025",
          "sameAs": "http://www.imdb.com/title/tt32063050/",
          "director": [{"@type": "Person", "name": "Michael Morris"}],
          "genre": ["Comedy", "Drama", "Romance"],
          "dateCreated": "2025-02-12",
          "duration": "PT125M",
          "aggregateRating": {
            "@type": "AggregateRating",
            "ratingValue": "6.9",
            "reviewCount": "3180"
          }
        }</script>
        </head><body>
        <div id="loadProviders"><ul class="dropdown-menu">
          <li>
            <a href="https://playeriframe.lol/iframe.php?url=https%3A%2F%2Ffilemoon.sx%2Fe%2Fv39zpk2x375q"
               target="iframe" class="CAST" rel="1080">CAST</a>
            <div>
              <span class="label label-warning">480</span>
              <span class="label label-success">720</span>
              <span class="label label-info">1080</span>
            </div>
          </li>
        </ul></div>
        <div class="action-player">
          <a href="https://www.youtube.com/watch?v=7sSNobjiep4" class="fancybox">TRAILER</a>
        </div>
        <article class="post">
          <div class="content-wrapper" id="movie-detail">
            <div class="content-poster">
              <img class="img-thumbnail" src="//s2.lk21static.buzz/uploads/bridget.jpg">
            </div>
            <div class="content">
              <div><h2>Kualitas</h2><h3><a href="/quality/webdl/">WEBDL</a></h3></div>
              <div><h2>Negara</h2><h3><a>France</a></h3>, <h3><a>United Kingdom</a></h3></div>
              <div><h2>Bintang film</h2><h3><a>Renée Zellweger</a></h3>, <h3><a>Leo Woodall</a></h3></div>
              <div><h2>Sutradara</h2><h3><a>Michael Morris</a></h3></div>
              <div><h2>Genre</h2><h3><a>Comedy</a></h3>, <h3><a>Drama</a></h3></div>
              <div><h2>IMDb</h2><h3>6.9</h3> / <h3>10</h3> from <h3>3180</h3> users</div>
              <div><h2>Diterbitkan</h2><h3>February 12, 2025</h3></div>
              <div><h2>Durasi</h2><h3>2 jam 5 menit</h3></div>
              <blockquote>
                <strong>Synopsis</strong><br />
                <a href="https://tv13.lk21official.cc/bridget-jones-mad-about-the-boy-2025/">Bridget Jones: Mad About the Boy (2025)</a>
                Bridget Jones menjalani kehidupan sebagai seorang janda dan ibu tunggal.
                <br /><br />
                <span class='hidden'><br />Michael Morris<br />tt32063050</span>
              </blockquote>
            </div>
          </div>
        </article>
        <script>
          var subtitle = "https:\/\/subs.example.com\/bridget-id.srt";
        </script>
        </body></html>
    """.trimIndent()

    @Test
    fun `parses full detail page with sources and subtitle`() {
        val url = "https://tv13.lk21official.cc/bridget-jones-mad-about-the-boy-2025/"
        val doc = Jsoup.parse(detailHtml, url)
        val detail = parser.parseDetail(doc, url)

        assertEquals("Bridget Jones: Mad About the Boy (2025)", detail.title)
        assertEquals("https://s2.lk21static.buzz/uploads/bridget.jpg", detail.poster)
        assertEquals("6.9", detail.rating)
        assertEquals("3180", detail.ratingCount)
        assertEquals("2025", detail.year)
        assertEquals(125L * 60_000L, detail.durationMs)
        assertEquals("tt32063050", detail.imdbId)
        assertEquals(listOf("Comedy", "Drama", "Romance"), detail.genres)
        assertEquals(listOf("Michael Morris"), detail.directors)
        assertTrue(detail.synopsis.contains("janda dan ibu tunggal"))
        assertEquals("https://www.youtube.com/watch?v=7sSNobjiep4", detail.trailerUrl)

        assertEquals(1, detail.sources.size)
        val source = detail.sources.first()
        assertEquals("CAST", source.provider)
        assertEquals("https://filemoon.sx/e/v39zpk2x375q", source.decodedUrl)
        assertEquals(listOf("1080", "720", "480"), source.qualities)

        assertNotNull(detail.subtitles.firstOrNull { it.url.endsWith("bridget-id.srt") })
    }
}
