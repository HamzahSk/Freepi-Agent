package rofil.movie.app.data.remote

import rofil.movie.app.data.model.FilterCatalog
import rofil.movie.app.data.model.FilterOption
import rofil.movie.app.data.model.HomeData
import rofil.movie.app.data.model.ListPage
import rofil.movie.app.data.model.MovieCard
import rofil.movie.app.data.model.MovieDetail
import rofil.movie.app.data.model.SeriesEpisode
import rofil.movie.app.data.model.SeriesInfo
import rofil.movie.app.data.model.SeriesSeason
import rofil.movie.app.data.model.StreamSource
import rofil.movie.app.data.model.SubtitleTrack
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

/**
 * Parses the real markup of the data source. All selectors were verified against
 * archived copies of the site (the live host sits behind a bot challenge).
 *
 * Verified page shapes:
 *  - catalog grid cards:  article.widget-item / mega-item / featured-item
 *    with a[rel=bookmark], .grid-poster img, .rating, .quality, .duration
 *  - pagination:          #pagination  "Halaman X dari Y" + a.page-numbers
 *  - detail page:         #movie-detail, script[type=application/ld+json] (Movie)
 *  - providers:           #loadProviders li a[target=iframe] + .label qualities
 *  - search:              /search.php?s=... or /?s=...
 */
class Lk21Parser {

    companion object {
        /** Paths whose first segment is a category, not a movie slug. */
        private val RESERVED_SEGMENTS = setOf(
            "genre", "country", "year", "quality", "size", "artist", "director", "tag",
            "playlist", "page", "movie", "series", "release", "rating", "title", "populer",
            "latest", "latest-series", "search.php", "search", "nd.php", "dmca", "wp-content",
            "wp-json", "feed", "rss", "amp", "rekomendasi-film-pintar", "daftar-playlist",
            "petunjuk-cara-download-film", "subtitle-maker", "author", "category",
        )

        private val SUBTITLE_REGEX = Regex(
            """(?:https?:)?//[^\s"'<>\\]+?\.(?:srt|vtt)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )
    }

    // ------------------------------------------------------------------ helpers

    fun slugOf(url: String): String? {
        val path = runCatching { URI(url).path }.getOrNull() ?: return null
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        if (segments.size != 1) return null
        val slug = segments.first()
        if (slug.lowercase(Locale.ROOT) in RESERVED_SEGMENTS) return null
        return slug
    }

    private fun normalizeUrl(raw: String?, base: String): String? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim()
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("/") -> {
                val host = runCatching { URL(base) }.getOrNull() ?: return null
                "${host.protocol}://${host.host}$value"
            }
            else -> value
        }
    }

    private fun cleanTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .substringBefore(" Film Subtitle")
            .substringBefore(" Sub Indo")
            .substringBefore(" | Streaming")
            .replace(Regex("^Lk21\\s+Nonton\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^Nonton\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^Streaming\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun firstNumber(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return Regex("""\d+(?:[.,]\d+)?""").find(text)?.value
    }

    private fun isLikelyCardContainer(el: Element): Boolean =
        el.tagName() == "article" ||
            el.hasClass("featured-item") ||
            el.hasClass("item") ||
            el.hasClass("slider") ||
            el.hasClass("slider-item") ||
            el.hasClass("item-media") ||
            el.hasClass("widget-item") ||
            el.hasClass("pop-movie-item")

    // -------------------------------------------------------------------- cards

    fun parseCards(root: Element): List<MovieCard> {
        val out = LinkedHashMap<String, MovieCard>()
        val base = root.baseUri().ifBlank { "https://tv13.lk21official.cc/" }
        // The captured theme links with a[itemprop=url] (li.slider > article),
        // the classic theme with a[rel=bookmark].
        val anchors = (root.select("a[rel=bookmark]") + root.select("a[itemprop=url]")).distinct()
        for (anchor in anchors) {
            val rawHref = anchor.attr("href")
            if (rawHref.isBlank() || rawHref.startsWith("javascript:") || rawHref == "#") continue
            val href = normalizeUrl(rawHref, base) ?: continue
            val slug = slugOf(href) ?: continue
            if (out.containsKey(slug)) continue

            // Skip the site logo link / breadcrumb links.
            val container: Element = anchor.parents().firstOrNull(::isLikelyCardContainer)
                ?: anchor.parents().firstOrNull { it.hasClass("row") }
                ?: anchor

            var img = container.selectFirst("img[src], img[data-src]")
                ?: anchor.selectFirst("img[src], img[data-src]")
            var title = cleanTitle(
                anchor.attr("title").ifBlank {
                    container.selectFirst(".poster-title, .grid-title, .entry-title, .video-title, .pop-movie-title, h1, h2, h3")
                        ?.text().orEmpty().ifBlank { img?.attr("alt").orEmpty() }
                }
            ).ifBlank {
                container.selectFirst(".poster-title, .grid-title, .entry-title, .video-title, h1, h2, h3")?.text()
                    ?.ifBlank { anchor.text() }
                    ?: anchor.text().ifBlank { img?.attr("alt").orEmpty() }
            }
            if (title.isBlank()) continue

            val poster = normalizeUrl(
                img?.attr("data-src").orEmpty()
                    .ifBlank { img?.attr("src").orEmpty() },
                base
            )
            val rating = firstNumber(container.selectFirst(".rating")?.text())
            val quality = container.selectFirst(".quality, .label-HD")?.text()?.trim()?.takeIf { it.isNotBlank() }
            val duration = container.selectFirst(".duration")?.text()?.trim()?.takeIf { it.isNotBlank() }
            val genres = container.select("a[href*=/genre/]").mapNotNull { g ->
                g.text().trim().takeIf { it.isNotBlank() }
            }.flatMap { it.split(",") }
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length < 40 }
                .distinct().take(3)
            val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)
                ?: container.selectFirst(".year, .video-year")?.text()?.trim()?.takeIf { it.matches(Regex("""\d{4}""")) }

            out[slug] = MovieCard(
                slug = slug,
                title = title,
                url = href,
                poster = poster,
                rating = rating,
                quality = quality,
                duration = duration,
                genres = genres,
                year = year,
            )
        }
        return out.values.toList()
    }

    // --------------------------------------------------------------------- home

    fun parseHome(doc: Document, now: Long = System.currentTimeMillis()): HomeData {
        fun section(id: String): List<MovieCard> =
            doc.selectFirst("#$id")?.let { parseCards(it) }.orEmpty()

        val popular = section("populer")
        val latest = section("latest").ifEmpty {
            doc.selectFirst(".mega-grid")?.let { parseCards(it) }.orEmpty()
        }
        val recommended = section("recomendation")
        val series = (section("series-unggulan") + section("series-terupdate")).distinctBy { it.slug }
        val featured = popular.take(5).ifEmpty { latest.take(5) }

        return HomeData(
            featured = featured,
            popular = popular,
            latest = latest,
            recommended = recommended,
            series = series,
            filters = parseFilters(doc),
            updatedAt = now,
        )
    }

    fun parseFilters(doc: Document): FilterCatalog {
        fun options(prefix: String): List<FilterOption> =
            doc.select("a[href*=$prefix]").mapNotNull { a ->
                val href = a.attr("href")
                val idx = href.indexOf(prefix)
                if (idx < 0) return@mapNotNull null
                val path = href.substring(maxOf(0, idx - 1)).let { if (it.startsWith("/")) it else "/$it" }
                val name = a.text().trim()
                if (name.isBlank() || path.contains(" ", ignoreCase = false)) null
                else FilterOption(name, path)
            }
                .filter { it.path.startsWith("/$prefix") }
                .distinctBy { it.path }
                .sortedBy { it.name.lowercase(Locale.ROOT) }

        return FilterCatalog(
            genres = options("genre/"),
            countries = options("country/"),
            years = options("year/").sortedByDescending { it.name },
            qualities = options("quality/"),
        )
    }

    // --------------------------------------------------------------------- list

    fun parseList(doc: Document, requestedPage: Int): ListPage {
        val pagination = doc.selectFirst("#pagination")
        val scope = pagination?.parents()?.firstOrNull { it.tagName() == "section" }
            ?: doc.selectFirst(".mega-grid")
            ?: doc

        var items = parseCards(scope)
        if (items.isEmpty()) items = parseCards(doc)

        val paginationText = pagination?.text().orEmpty()
        val range = Regex("""Halaman\s+(\d+)\s+dari\s+([\d.,]+)""", RegexOption.IGNORE_CASE)
            .find(paginationText)
        var totalPages = range?.groupValues?.get(2)
            ?.replace(".", "")?.replace(",", "")?.toIntOrNull()

        if (totalPages == null) {
            val pageNumbers = doc.select("#pagination a.page-numbers")
                .mapNotNull { Regex("""/page/(\d+)/?""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            totalPages = pageNumbers.maxOrNull()
        }
        val current = range?.groupValues?.get(1)?.toIntOrNull() ?: requestedPage
        if (totalPages == null) {
            // Unknown pagination: assume there is another page while we got a full grid.
            totalPages = if (items.size >= 10) current + 1 else current
        }
        val nextUrl = doc.selectFirst("#pagination a.next.page-numbers")?.attr("href")
            ?.let { normalizeUrl(it, doc.baseUri().ifBlank { "https://tv13.lk21official.cc/" }) }
            ?.takeIf { it.isNotBlank() }
        return ListPage(
            items = items,
            page = current,
            totalPages = totalPages,
            nextPageUrl = nextUrl,
        )
    }

    // ------------------------------------------------------------------- detail

    fun parseDetail(doc: Document, url: String): MovieDetail {
        val ld = findContentJsonLd(doc)
        val meta = parseMetaMap(doc)
        val html = doc.outerHtml().replace("\\/", "/")
        val watch = JsExtractor.watchHistory(html)
        val postId = watch?.optString("id")?.takeIf { it.isNotBlank() }

        val title = cleanTitle(
            doc.selectFirst(".movie-info h1, #movie-detail .post-header h2, .post-header h2, h1")?.text()
                ?: (watch?.optString("title")?.let {
                    "$it" + (watch.optString("year").takeIf { y -> y.isNotBlank() }?.let { " ($it)" } ?: "")
                })
                ?: ld?.optString("name").orEmpty()
                ?: doc.title()
        ).ifBlank { "Untitled" }

        val poster = normalizeUrl(
            doc.select(".detail.hidden a img, .detail a img, .movie-info a[href*=\"poster.assetsy\"] img, img[itemprop=\"image\"]")
                .firstOrNull { isPosterImage(it, title) }
                ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?: doc.selectFirst("#movie-detail img.img-thumbnail")?.attr("src")
                ?: doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
                ?: ld?.optString("image").orEmpty()
                ?: watch?.optString("poster").orEmpty()
                ?: "",
            url
        )
        val backdrop = poster

        val synopsis = parseSynopsis(doc, title)
        val rating = firstNumber(
            doc.selectFirst(".rating-number")?.attr("data-base-rating")
                ?: watch?.optString("rating")
                ?: ld?.optJSONObject("aggregateRating")?.optString("ratingValue")
                ?: meta["IMDb"]
        )
        val ratingCount = doc.selectFirst(".rating-users")?.attr("data-base-votes")
            ?.takeIf { it.isNotBlank() }
            ?: ld?.optJSONObject("aggregateRating")?.optString("reviewCount")
            ?.takeIf { it.isNotBlank() }
            ?: meta["IMDb"]?.let { Regex("""from\s+([\d.,]+)""").find(it)?.groupValues?.get(1) }

        val tags = doc.select(".tag-list .tag a, .tag-list a, .tag a[href]").mapNotNull { a ->
            val href = a.attr("href")
            val type = href.trim('/').split('/').firstOrNull { it.isNotBlank() } ?: ""
            val name = a.text().trim()
            if (name.isNotBlank()) type to name else null
        }
        val genres = tags.filter { it.first == "genre" }.map { it.second }.ifEmpty {
            ld?.optJSONArray("genre")?.toStringList().orEmpty()
        }.ifEmpty { splitValues(meta["Genre"]) }
        val countries = tags.filter { it.first == "country" }.map { it.second }.ifEmpty {
            splitValues(meta["Negara"])
        }
        val directors = ld?.optJSONArray("director")?.toStringList()
            ?: splitValues(meta["Sutradara"])
        val cast = ld?.optJSONArray("actor")?.toStringList()
            ?: splitValues(meta["Bintang film"] ?: meta["Bintang Film"])
        val translator = meta["Subtitle"]?.takeIf { it.isNotBlank() }

        // info tags: [17+, WEBDL|1080p|1h 42m, West, Ongoing] -> quality/duration.
        val infoTags = doc.select(".info-tag span").mapNotNull { it.text().trim().takeIf { t -> t.isNotBlank() } }
        val quality = infoTags.firstOrNull {
            Regex("""WEBDL|WEB-DL|WEBRip|BluRay|BDRip|HDCAM|HDTS|HDRip|CAM|DVD|1080p|720p|480p""", RegexOption.IGNORE_CASE)
                .containsMatchIn(it)
        }
        val durationText = infoTags.firstOrNull {
            Regex("""\d{1,2}:\d{2}|\d+\s*(?:h|jam|min|menit)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(it) && !it.matches(Regex("""\d{3,4}"""))
        } ?: watch?.optString("runtime")?.takeIf { it.isNotBlank() }
        val durationMs = ld?.optString("duration")?.let(::parseIsoDuration)
            ?: durationText?.let(::parseIndonesianDuration)
            ?: durationText?.let(::parseClockDuration)

        val year = watch?.optString("year")?.takeIf { it.matches(Regex("""\d{4}""")) }
            ?: (ld?.optString("dateCreated")?.let { Regex("""(?:19|20)\d{2}""").find(it)?.value })
            ?: (ld?.optString("datePublished")?.let { Regex("""(?:19|20)\d{2}""").find(it)?.value })
            ?: Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)
            ?: Regex("""(?:19|20)\d{2}""").find(meta["Diterbitkan"].orEmpty())?.value

        val imdbUrl = ld?.optString("sameAs").orEmpty()
        val imdbId = Regex("""tt\d+""").find(imdbUrl)?.value ?: Regex("""tt\d+""").find(html)?.value

        val sources = parseSources(doc, url)
        val subtitles = parseSubtitleTracks(html, url)
        val relatedRaw = doc.select(".mob-related-video .sliders li.slider, .mob-related-series .sliders li.slider, #related .sliders li.slider")
        val related = if (relatedRaw.isNotEmpty()) {
            val container = Element("div")
            relatedRaw.forEach { container.appendChild(it.clone()) }
            parseCards(container)
        } else {
            parseCards(doc)
        }.filter { it.slug != slugOf(url) }.distinctBy { it.slug }.take(24)

        val trailer = doc.selectFirst(
            "a.yt-lightbox[href*=\"youtube\"], a.fancybox[href*=\"youtube\"], a[href*=\"youtube.com/watch\"], a[href*=\"youtu.be\"], .trailer-series iframe, iframe[src*=\"youtube.com/embed\"]"
        )?.let { it.attr("src").ifBlank { it.attr("href") } }

        val seriesInfo = parseSeriesInfo(doc, watch)

        return MovieDetail(
            slug = slugOf(url) ?: title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-"),
            title = title,
            url = url,
            poster = poster,
            backdrop = backdrop,
            synopsis = synopsis,
            year = year,
            durationText = durationText ?: durationMs?.let(::formatDuration),
            durationMs = durationMs,
            rating = rating,
            ratingCount = ratingCount,
            imdbId = imdbId,
            quality = quality ?: splitValues(meta["Kualitas"]).firstOrNull(),
            countries = countries,
            genres = genres,
            directors = directors,
            cast = cast,
            translator = translator,
            published = meta["Diterbitkan"] ?: meta["Release"] ?: ld?.optString("datePublished") ?: ld?.optString("dateCreated"),
            uploaded = meta["Updated"] ?: meta["Diunggah"],
            trailerUrl = trailer?.let { normalizeUrl(it, url) },
            sources = sources,
            subtitles = subtitles,
            related = related,
            series = seriesInfo,
            postId = postId,
        )
    }

    /** Captured-theme: `#season-data` + `#watch-history-data` + nav links. */
    private fun parseSeriesInfo(doc: Document, watch: JSONObject?): SeriesInfo? {
        // 1) season-data JSON (the definitive episode list on the series hub
        //    and on every episode page - parsed with Rhino).
        val seasons = ArrayList<SeriesSeason>()
        val seasonJson = JsExtractor.seasonData(doc.outerHtml())
        if (seasonJson != null) {
            for (key in seasonJson.keys()) {
                val list = seasonJson.optJSONArray(key) ?: continue
                val episodes = ArrayList<SeriesEpisode>()
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    val slug = item.optString("slug").trim()
                    if (slug.isBlank()) continue
                    episodes.add(
                        SeriesEpisode(
                            number = item.optInt("episode_no", i + 1),
                            title = item.optString("title"),
                            slug = slug,
                        )
                    )
                }
                if (episodes.isNotEmpty()) {
                    seasons.add(SeriesSeason(number = key.toIntOrNull() ?: 0, episodes = episodes))
                }
            }
        }
        seasons.sortBy { it.number }
        val hasSeasonData = seasons.isNotEmpty()

        val firstSlug = seasons.firstOrNull()
            ?.episodes?.firstOrNull()?.slug
        val latestSlug = seasons.lastOrNull()
            ?.episodes?.lastOrNull()?.slug

        // A movie page may still carry a watch-history block (id/title/rating/
        // poster), but without season/episode data it is not a TV series.
        val hasEpisodeMeta = (watch?.optInt("total_eps") ?: 0) > 0 ||
            (watch?.optInt("total_season") ?: 0) > 0 ||
            watch?.has("current_season") == true ||
            watch?.has("current_eps") == true
        if (!hasSeasonData && !hasEpisodeMeta) return null

        val totalSeasons = (watch?.optInt("total_season") ?: 0).takeIf { it > 0 } ?: seasons.size
        val totalEpisodes = (watch?.optInt("total_eps") ?: 0).takeIf { it > 0 }
            ?: seasons.sumOf { it.episodes.size }
        val currentSeason = watch?.optString("current_season")?.toIntOrNull()
        val currentEpisode = watch?.optString("current_eps")?.toIntOrNull()

        // Episode navigation: previous/next from .player-action .action-right
        // (the anchor before the current episode, then the one after it). This
        // wins over the watch payload for prev; next prefers watch.next.
        var prevSlug: String? = null
        var nextSlug: String? = watch?.optString("next")?.takeIf { it.isNotBlank() }
        val epsLinks = doc.select(".player-action .action-right li a[href]")
            .mapNotNull { slugOf(normalizeUrl(it.attr("href"), doc.baseUri()) ?: "") }
            .filter { it.isNotBlank() }
        if (epsLinks.isNotEmpty()) prevSlug = epsLinks.first()
        if (epsLinks.size > 1 && nextSlug.isNullOrBlank()) nextSlug = epsLinks[1]

        val seriesSlug = doc.select(".detail p").mapNotNull { p ->
            val span = p.selectFirst("span")
            val a = p.selectFirst("a[href]")
            if (span != null && a != null && Regex("series", RegexOption.IGNORE_CASE)
                    .containsMatchIn(span.text()))
                slugOf(normalizeUrl(a.attr("href"), doc.baseUri()) ?: "")
            else null
        }.firstOrNull()
        val seriesTitle = doc.select(".detail p").mapNotNull { p ->
            val span = p.selectFirst("span")
            val a = p.selectFirst("a[href]")
            if (span != null && a != null && Regex("series", RegexOption.IGNORE_CASE)
                    .containsMatchIn(span.text())) a.text() else null
        }.firstOrNull()

        val playAwal = doc.select(".movie-action a[href]").firstOrNull { a ->
            Regex("play\\s*awal", RegexOption.IGNORE_CASE).containsMatchIn(a.text())
        }?.attr("href")?.let { slugOf(normalizeUrl(it, doc.baseUri()) ?: "") }
        val playTerbaru = doc.select(".movie-action a[href]").firstOrNull { a ->
            Regex("play\\s*terbaru", RegexOption.IGNORE_CASE).containsMatchIn(a.text())
        }?.attr("href")?.let { slugOf(normalizeUrl(it, doc.baseUri()) ?: "") }

        return SeriesInfo(
            totalSeasons = totalSeasons,
            totalEpisodes = totalEpisodes,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            firstSlug = firstSlug ?: playAwal,
            latestSlug = latestSlug ?: playTerbaru,
            nextSlug = nextSlug,
            prevSlug = prevSlug,
            seriesSlug = seriesSlug,
            seriesTitle = seriesTitle,
            seasons = seasons,
        )
    }

    /** Finds the Movie / TVSeries / TVEpisode JSON-LD node anywhere in the doc. */
    private fun findContentJsonLd(doc: Document): JSONObject? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val text = script.data().trim()
            if (!text.contains("\"@type\"")) continue
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: continue
            val type = obj.optString("@type")
            if (type.contains("Movie", true) || type.contains("TVSeries", true) ||
                type.contains("TVEpisode", true) || type.contains("VideoObject", true) ||
                type.contains("CreativeWork", true)
            ) return obj
        }
        // A JSON-LD @graph wrapper (captured Website block) - search its nodes.
        for (script in doc.select("script[type=application/ld+json]")) {
            val text = script.data().trim()
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: continue
            val graph = obj.optJSONArray("@graph") ?: continue
            for (i in 0 until graph.length()) {
                val node = graph.optJSONObject(i) ?: continue
                val type = node.optString("@type")
                if (type.contains("Movie", true) || type.contains("TVSeries", true) ||
                    type.contains("TVEpisode", true) || type.contains("VideoObject", true)
                ) return node
            }
        }
        return null
    }

    private fun parseMetaMap(doc: Document): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        // Captured theme: .detail p / .meta-info p with a <span> label.
        for (p in doc.select(".detail p, .meta-info p")) {
            if (p.closest(".synopsis, .trailer-series") != null) continue
            val span = p.selectFirst("span") ?: continue
            val label = span.text().trim().trimEnd(':').trim()
            if (label.isBlank()) continue
            val clone = p.clone()
            clone.select("span").remove()
            val value = clone.text().trim()
            if (value.isNotBlank() && !map.containsKey(label)) map[label] = value
        }
        // Classic theme: #movie-detail .content > div with h2 label + h3 values.
        val content = doc.selectFirst("#movie-detail .content")
        if (content != null) {
            for (row in content.children()) {
                if (row.tagName() != "div") continue
                val label = row.selectFirst("h2")?.text()?.trim() ?: continue
                val values = row.select("h3").map { it.text().trim() }.filter { it.isNotBlank() }
                if (label.isNotBlank() && values.isNotEmpty() && !map.containsKey(label)) {
                    map[label] = values.joinToString(", ")
                }
            }
        }
        return map
    }

    private fun parseSynopsis(doc: Document, title: String): String {
        val synopsis = doc.selectFirst(".synopsis, .meta-info .synopsis")
        if (synopsis != null) {
            val full = synopsis.attr("data-full")
            if (full.length > 20) return full.replace(Regex("\\s+"), " ").trim()
            val clone = synopsis.clone()
            clone.select("a, button, script, style").remove()
            return clone.text().replace(Regex("\\s+"), " ").trim()
        }
        val blockquote = doc.selectFirst("#movie-detail blockquote") ?: return ""
        val clone = blockquote.clone()
        clone.select("strong, .hidden, script, style").remove()
        clone.select("a").remove()
        var text = clone.text().replace(Regex("\\s+"), " ").trim()
        if (text.startsWith(title)) text = text.removePrefix(title).trim()
        return text.trim(' ', '-', ':', '|')
    }

    private fun parseSources(doc: Document, baseUrl: String): List<StreamSource> {
        val out = LinkedHashMap<String, StreamSource>()
        // Captured theme: #player-list li a[data-url][data-server] + #main-player.
        for (anchor in doc.select("#player-list li a[data-url], #player-select option[value]")) {
            val raw = anchor.attr("data-url").ifBlank {
                anchor.attr("value")
            }
            if (raw.isBlank() || raw == "#" || raw.startsWith("javascript")) continue
            val anchorEl = if (anchor.tagName() == "option") null else anchor
            val embed = normalizeUrl(raw, baseUrl) ?: continue
            val decoded = decodeEmbeddedUrl(embed)
            val server = anchor.attr("data-server").trim()
                .ifBlank { anchorEl?.text()?.trim().orEmpty() }
                .uppercase()
            val provider = server.ifBlank { "Provider" }
            val qualities = anchorEl?.parents()?.firstOrNull { it.tagName() == "li" }
                ?.select(".label, .quality")?.map { it.text().trim() }
                ?.filter { it.matches(Regex("""\d{3,4}""")) }?.distinct()?.sortedByDescending { it.toIntOrNull() ?: 0 }
                ?: emptyList()
            val key = "$provider|$embed"
            if (out.containsKey(key)) continue
            out[key] = StreamSource(
                provider = provider,
                label = provider,
                embedUrl = embed,
                decodedUrl = decoded,
                maxQuality = qualities.firstOrNull(),
                qualities = qualities,
            )
        }
        // Classic theme: #loadProviders li a[target=iframe].
        for (anchor in doc.select("#loadProviders li a, #loadProviders a[target=iframe]")) {
            val rawHref = anchor.attr("href")
            if (rawHref.isBlank() || rawHref.startsWith("javascript")) continue
            val embed = normalizeUrl(rawHref, baseUrl) ?: continue
            val decoded = decodeEmbeddedUrl(embed)
            val li: Element = anchor.parents().firstOrNull { it.tagName() == "li" } ?: anchor
            val qualities = li.select(".label").map { it.text().trim() }
                .filter { it.matches(Regex("""\d{3,4}""")) }
                .distinct()
                .sortedByDescending { it.toIntOrNull() ?: 0 }
            val provider = anchor.text().trim().ifBlank {
                anchor.attr("class").split(" ").firstOrNull().orEmpty()
            }
            val key = "$provider|$embed"
            out[key] = StreamSource(
                provider = provider,
                label = anchor.text().trim().ifBlank { provider },
                embedUrl = embed,
                decodedUrl = decoded,
                maxQuality = anchor.attr("rel").takeIf { it.matches(Regex("""\d{3,4}""")) }
                    ?: qualities.firstOrNull(),
                qualities = qualities,
            )
        }
        return out.values.toList()
    }

    /** True if the image is the detail-page hero poster (not a related card). */
    private fun isPosterImage(el: Element, title: String): Boolean {
        val alt = el.attr("alt").trim()
        if (alt.isNotBlank()) {
            if (alt.equals(title, ignoreCase = true) || alt.contains(title.substringBefore('('), true)) return true
            return false
        }
        val titleAttr = el.attr("title").trim()
        return titleAttr.isBlank() || titleAttr.equals(title, true)
    }

    private fun parseClockDuration(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(text.trim()) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0
        val min = m.groupValues[2].toLongOrNull() ?: 0
        val total = (h * 60 + min) * 60_000L
        return total.takeIf { it > 0 }
    }

    /** `/iframe.php?url=<encoded>` wrappers are used by the site's own player. */
    private fun decodeEmbeddedUrl(url: String): String {
        return try {
            val uri = URI(url)
            val query = uri.rawQuery ?: return url
            val encoded = query.split("&").firstOrNull { it.startsWith("url=") }
                ?.substringAfter("url=") ?: return url
            URLDecoder.decode(encoded, "UTF-8")
        } catch (_: Exception) {
            url
        }
    }

    fun parseSubtitleTracks(html: String, baseUrl: String): List<SubtitleTrack> {
        val tracks = LinkedHashMap<String, SubtitleTrack>()
        for (match in SUBTITLE_REGEX.findAll(html)) {
            val raw = match.value.replace("\\/", "/")
            val absolute = normalizeUrl(raw, baseUrl) ?: continue
            val name = absolute.substringAfterLast('/').substringBefore('?')
            tracks[absolute] = SubtitleTrack(label = name, url = absolute)
        }
        return tracks.values.toList()
    }

    private fun splitValues(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(",", "/")
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("N/A", true) }
            .distinct()
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until length()) {
            when (val value = opt(i)) {
                is String -> if (value.isNotBlank()) out.add(value.trim())
                is JSONObject -> value.optString("name").takeIf { it.isNotBlank() }?.let(out::add)
            }
        }
        return out.distinct()
    }

    fun parseIsoDuration(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        val hours = Regex("""(\d+)H""").find(iso)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val minutes = Regex("""(\d+)M""").find(iso)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val total = (hours * 60 + minutes) * 60_000L
        return total.takeIf { it > 0 }
    }

    private fun parseIndonesianDuration(text: String): Long? {
        val hours = Regex("""(\d+)\s*jam""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val minutes = Regex("""(\d+)\s*menit""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val total = (hours * 60 + minutes) * 60_000L
        return total.takeIf { it > 0 }
    }

    fun formatDuration(ms: Long): String {
        val totalMinutes = ms / 60_000
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) "%dh %02dm".format(h, m) else "${m}m"
    }
}
