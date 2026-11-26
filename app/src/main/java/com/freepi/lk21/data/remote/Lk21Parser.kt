package com.freepi.lk21.data.remote

import com.freepi.lk21.data.model.FilterCatalog
import com.freepi.lk21.data.model.FilterOption
import com.freepi.lk21.data.model.HomeData
import com.freepi.lk21.data.model.ListPage
import com.freepi.lk21.data.model.MovieCard
import com.freepi.lk21.data.model.MovieDetail
import com.freepi.lk21.data.model.StreamSource
import com.freepi.lk21.data.model.SubtitleTrack
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
            .substringBefore(" Nonton ")
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
            el.hasClass("slider-item") ||
            el.hasClass("item-media") ||
            el.hasClass("widget-item")

    // -------------------------------------------------------------------- cards

    fun parseCards(root: Element): List<MovieCard> {
        val out = LinkedHashMap<String, MovieCard>()
        val base = root.baseUri().ifBlank { "https://tv13.lk21official.cc/" }
        for (anchor in root.select("a[rel=bookmark]")) {
            val href = normalizeUrl(anchor.attr("href"), base) ?: continue
            val slug = slugOf(href) ?: continue
            if (out.containsKey(slug)) continue

            // Skip the site logo link / breadcrumb links.
            val container: Element = anchor.parents().firstOrNull(::isLikelyCardContainer)
                ?: anchor.parents().firstOrNull { it.hasClass("row") }
                ?: anchor

            val img = container.selectFirst("img[src]")
                ?: anchor.selectFirst("img[src]")
            val title = cleanTitle(
                anchor.attr("title").ifBlank {
                    img?.attr("alt").orEmpty().ifBlank { anchor.text() }
                }
            ).ifBlank {
                anchor.text().ifBlank { img?.attr("alt").orEmpty() }
            }
            if (title.isBlank()) continue

            val poster = normalizeUrl(
                img?.attr("src").orEmpty()
                    .ifBlank { img?.attr("data-src").orEmpty() },
                base
            )
            val rating = firstNumber(container.selectFirst(".rating")?.text())
            val quality = container.selectFirst(".quality")?.text()?.trim()?.takeIf { it.isNotBlank() }
            val duration = container.selectFirst(".duration")?.text()?.trim()?.takeIf { it.isNotBlank() }
            val genres = container.select("a[href*=/genre/]").mapNotNull { g ->
                g.text().trim().takeIf { it.isNotBlank() }
            }.distinct().take(3)
            val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)

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
        val ld = findMovieJsonLd(doc)
        val meta = parseMetaMap(doc)

        val title = cleanTitle(
            ld?.optString("name").orEmpty().ifBlank {
                doc.selectFirst("#movie-detail .post-header h2, .post-header h2, h1")?.text()
                    ?: doc.title().substringBefore("Nonton").substringBefore("Lk21")
            }
        )
        val poster = normalizeUrl(
            doc.selectFirst("#movie-detail img.img-thumbnail")?.attr("src")
                ?: ld?.optString("image").orEmpty().ifBlank { null },
            url
        )
        val backdrop = poster

        val synopsis = parseSynopsis(doc, title)
        val rating = firstNumber(
            ld?.optJSONObject("aggregateRating")?.optString("ratingValue")
                ?: meta["IMDb"]
        )
        val ratingCount = ld?.optJSONObject("aggregateRating")?.optString("reviewCount")
            ?.takeIf { it.isNotBlank() }
            ?: meta["IMDb"]?.let { Regex("""from\s+([\d.,]+)""").find(it)?.groupValues?.get(1) }

        val genres = ld?.optJSONArray("genre")?.toStringList()
            ?: splitValues(meta["Genre"])
        val countries = splitValues(meta["Negara"])
        val directors = ld?.optJSONArray("director")?.toStringList()
            ?: splitValues(meta["Sutradara"])
        val cast = splitValues(meta["Bintang film"])
        val quality = splitValues(meta["Kualitas"]).firstOrNull()

        val durationText = meta["Durasi"]
        val durationMs = ld?.optString("duration")?.let(::parseIsoDuration)
            ?: durationText?.let(::parseIndonesianDuration)

        val year = ld?.optString("dateCreated")?.take(4)?.takeIf { it != "null" }
            ?: Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)
            ?: Regex("""(?:19|20)\d{2}""").find(meta["Diterbitkan"].orEmpty())?.value

        val imdbUrl = ld?.optString("sameAs").orEmpty()
        val imdbId = Regex("""tt\d+""").find(imdbUrl)?.value
            ?: Regex("""tt\d+""").find(doc.outerHtml())?.value

        val sources = parseSources(doc, url)
        val subtitles = parseSubtitleTracks(doc.outerHtml().replace("\\/", "/"), url)
        val relatedSection = doc.selectFirst("#related")?.parents()
            ?.firstOrNull { it.tagName() == "li" }
        val related = relatedSection?.let { parseCards(it) }.orEmpty()
            .filter { it.slug != slugOf(url) }

        val trailer = doc.selectFirst(
            "a.fancybox[href*=youtube], a[href*=youtube.com/watch], a[href*=youtu.be]"
        )?.attr("href")

        return MovieDetail(
            slug = slugOf(url) ?: title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-"),
            title = title.ifBlank { "Untitled" },
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
            quality = quality ?: ld?.optString("contentRating")?.takeIf { it.isNotBlank() },
            countries = countries,
            genres = genres,
            directors = directors,
            cast = cast,
            translator = meta["Penerjemah"],
            published = meta["Diterbitkan"] ?: ld?.optString("datePublished"),
            uploaded = meta["Diunggah"],
            trailerUrl = trailer,
            sources = sources,
            subtitles = subtitles,
            related = related,
        )
    }

    private fun findMovieJsonLd(doc: Document): JSONObject? {
        for (script in doc.select("script[type=application/ld+json]")) {
            val text = script.data().trim()
            if (!text.contains("\"Movie\"")) continue
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: continue
            if (obj.optString("@type").contains("Movie", ignoreCase = true)) return obj
        }
        return null
    }

    private fun parseMetaMap(doc: Document): Map<String, String> {
        val content = doc.selectFirst("#movie-detail .content") ?: return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (row in content.children()) {
            if (row.tagName() != "div") continue
            val label = row.selectFirst("h2")?.text()?.trim() ?: continue
            val values = row.select("h3").map { it.text().trim() }.filter { it.isNotBlank() }
            if (label.isNotBlank() && values.isNotEmpty()) {
                map[label] = values.joinToString(", ")
            }
        }
        return map
    }

    private fun parseSynopsis(doc: Document, title: String): String {
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
