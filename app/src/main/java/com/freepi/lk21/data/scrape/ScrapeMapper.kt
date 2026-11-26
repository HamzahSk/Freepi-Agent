package com.freepi.lk21.data.scrape

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
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

/**
 * Converts validated extraction JSON into the immutable models consumed by the
 * native UI. This class has no Android dependency and no UI knowledge; unit
 * tests exercise it directly with payloads produced by the Playwright-verified
 * JavaScript extractors.
 */
class ScrapeMapper {

    // ------------------------------------------------------------------ home

    fun home(data: JSONObject, now: Long = System.currentTimeMillis()): HomeData {
        val sections = data.optJSONObject("sections") ?: JSONObject()
        fun cards(key: String): List<MovieCard> =
            cards(sections.optJSONArray(key), base = "")

        val popular = cards("popular")
        val latest = cards("latest")
        val recommended = cards("recommended")
        val series = cards("series")
        val featured = cards("featured").ifEmpty { popular.take(5) }.ifEmpty { latest.take(5) }

        return HomeData(
            featured = featured,
            popular = popular,
            latest = latest,
            recommended = recommended,
            series = series,
            filters = filters(data.optJSONObject("filters")),
            updatedAt = now,
        )
    }

    // ------------------------------------------------------------------ list

    fun list(data: JSONObject, baseUrl: String = ""): ListPage {
        val page = data.optInt("page", 1).coerceAtLeast(1)
        val totalPages = data.optInt("totalPages", page).coerceAtLeast(page)
        val next = data.optString("nextPageUrl").trim()
            .takeIf { it.isNotBlank() }
            ?.let { absolutize(it, baseUrl) }
        return ListPage(
            items = cards(data.optJSONArray("items"), baseUrl),
            page = page,
            totalPages = totalPages,
            nextPageUrl = next,
        )
    }

    // ---------------------------------------------------------------- detail

    fun detail(data: JSONObject, pageUrl: String): MovieDetail {
        val ld = data.optJSONObject("jsonLd")
        val meta = data.optJSONObject("meta")
        val infoTags = data.optJSONArray("infoTags")?.toStringList().orEmpty()

        val title = cleanTitle(
            data.optString("title")
                .ifBlank { ld?.optString("name").orEmpty() }
                .ifBlank { metaValue(meta, "Judul").orEmpty() }
        ).ifBlank { "Untitled" }

        val poster = firstUrl(
            data.optString("poster"),
            ld?.optString("image").orEmpty(),
            metaValue(meta, "Poster").orEmpty(),
        )?.let { absolutize(it, pageUrl) }

        val durationText = data.optString("durationText")
            .ifBlank { metaValue(meta, "Durasi").orEmpty() }
            .ifBlank { data.optString("runtime") }
        val durationMs = parseDuration(durationText)
            ?: parseIsoDuration(ld?.optString("duration"))
            ?: parseDuration(infoTags.firstOrNull { looksLikeDuration(it) })

        val year = data.optString("year")
            .ifBlank { data.optString("watchYear") }
            .ifBlank { ld?.optString("dateCreated").orEmpty().take(4) }
            .ifBlank { Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1).orEmpty() }
            .ifBlank {
                Regex("""(?:19|20)\d{2}""").find(
                    metaValue(meta, "Diterbitkan", "Release", "Tahun").orEmpty()
                )?.value.orEmpty()
            }
            .takeIf { it.matches(Regex("""\d{4}""")) }

        val genres = data.optJSONArray("genres")?.toStringList()
            ?.ifEmpty { split(metaValue(meta, "Genre")) }
            ?: split(metaValue(meta, "Genre"))
            .ifEmpty { ld?.optJSONArray("genre")?.toStringList().orEmpty() }

        val countries = data.optJSONArray("countries")?.toStringList()
            ?.ifEmpty { split(metaValue(meta, "Negara", "Country")) }
            ?: split(metaValue(meta, "Negara", "Country"))

        val directors = data.optJSONArray("directors")?.toStringList()
            ?.ifEmpty { split(metaValue(meta, "Sutradara", "Director")) }
            ?: split(metaValue(meta, "Sutradara", "Director"))
            .ifEmpty { ld?.optJSONArray("director")?.toStringList().orEmpty() }

        val cast = data.optJSONArray("cast")?.toStringList()
            ?.ifEmpty { split(metaValue(meta, "Bintang film", "Bintang Film", "Cast")) }
            ?: split(metaValue(meta, "Bintang film", "Bintang Film", "Cast"))

        val rating = data.optString("rating")
            .ifBlank { ld?.optJSONObject("aggregateRating")?.optString("ratingValue").orEmpty() }
            .ifBlank { metaValue(meta, "IMDb").orEmpty() }
            .let(::firstNumber)

        val ratingCount = data.optString("ratingCount")
            .ifBlank { ld?.optJSONObject("aggregateRating")?.optString("reviewCount").orEmpty() }
            .ifBlank { metaValue(meta, "Votes").orEmpty() }
            .ifBlank {
                Regex("""from\s+([\d.,]+)""", RegexOption.IGNORE_CASE)
                    .find(metaValue(meta, "IMDb").orEmpty())?.groupValues?.get(1).orEmpty()
            }
            .ifBlank { infoTags.firstOrNull { Regex("""\d{3,}""").containsMatchIn(it) }.orEmpty() }
            .takeIf { it.isNotBlank() }

        val imdbId = data.optString("imdbId").ifBlank {
            Regex("""tt\d+""").find(
                data.optString("imdbUrl") + " " + metaValue(meta, "IMDb").orEmpty()
            )?.value.orEmpty()
        }.takeIf { it.isNotBlank() }

        val quality = data.optString("quality")
            .ifBlank { split(metaValue(meta, "Kualitas", "Quality")).firstOrNull().orEmpty() }
            .ifBlank { infoTags.firstOrNull(::looksLikeQuality).orEmpty() }
            .ifBlank { ld?.optString("contentRating").orEmpty() }
            .takeIf { it.isNotBlank() }

        val sources = sources(data.optJSONArray("sources"), pageUrl)
        val subtitles = subtitles(data.optJSONArray("subtitles"), pageUrl)
        val related = cards(data.optJSONArray("related"), pageUrl)
            .filter { it.slug != slugFromUrl(pageUrl) }

        val trailer = firstUrl(data.optString("trailerUrl"))
            ?.let { absolutize(it, pageUrl) }

        val synopsis = data.optString("synopsis").replace(Regex("\\s+"), " ").trim()
        val redirect = data.optString("redirectUrl")
            .takeIf { it.isNotBlank() }
            ?.let { absolutize(it, pageUrl) }

        return MovieDetail(
            slug = slugFromUrl(pageUrl).ifBlank {
                title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-")
            },
            title = title,
            url = pageUrl,
            poster = poster,
            backdrop = poster,
            synopsis = synopsis,
            year = year,
            durationText = durationText.takeIf { it.isNotBlank() }
                ?: durationMs?.let(::formatDuration),
            durationMs = durationMs,
            rating = rating,
            ratingCount = ratingCount,
            imdbId = imdbId,
            quality = quality,
            countries = countries,
            genres = genres,
            directors = directors,
            cast = cast,
            translator = metaValue(meta, "Penerjemah", "Translator"),
            published = data.optString("published")
                .ifBlank { metaValue(meta, "Diterbitkan", "Release").orEmpty() }
                .ifBlank { ld?.optString("datePublished").orEmpty() }
                .takeIf { it.isNotBlank() },
            uploaded = data.optString("uploaded")
                .ifBlank { metaValue(meta, "Diunggah", "Updated").orEmpty() }
                .takeIf { it.isNotBlank() },
            trailerUrl = trailer,
            sources = sources,
            subtitles = subtitles,
            related = related,
            redirectUrl = redirect,
        )
    }

    // --------------------------------------------------------------- helpers

    private fun cards(array: JSONArray?, base: String): List<MovieCard> {
        if (array == null) return emptyList()
        val out = LinkedHashMap<String, MovieCard>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val slug = item.optString("slug").trim()
            val title = cleanTitle(item.optString("title"))
            if (slug.isBlank() || title.isBlank()) continue
            val url = absolutize(item.optString("url"), base)
                ?.takeIf { ScrapeValidator.isAcceptableUrl(it) }
                ?: continue
            val poster = firstUrl(item.optString("poster"))
                ?.let { absolutize(it, base) }
            val year = item.optString("year")
                .takeIf { it.matches(Regex("""\d{4}""")) }
                ?: Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)
            out[slug] = MovieCard(
                slug = slug,
                title = title,
                url = url,
                poster = poster,
                rating = firstNumber(item.optString("rating")),
                quality = item.optString("quality").trim().takeIf { it.isNotBlank() },
                duration = item.optString("duration").trim().takeIf { it.isNotBlank() },
                genres = item.optJSONArray("genres")?.toStringList().orEmpty().take(5),
                year = year,
            )
        }
        return out.values.toList()
    }

    private fun filters(json: JSONObject?): FilterCatalog {
        if (json == null) return FilterCatalog(emptyList(), emptyList(), emptyList(), emptyList())
        fun options(key: String): List<FilterOption> {
            val array = json.optJSONArray(key) ?: return emptyList()
            return (0 until array.length())
                .mapNotNull { array.optJSONObject(it) }
                .mapNotNull { item ->
                    val name = item.optString("name").trim()
                    val path = item.optString("path").trim()
                    if (name.isBlank() || path.isBlank()) null else FilterOption(name, path)
                }
                .distinctBy { it.path }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
        }
        return FilterCatalog(
            genres = options("genres"),
            countries = options("countries"),
            years = options("years").sortedByDescending { it.name },
            qualities = options("qualities"),
        )
    }

    private fun sources(array: JSONArray?, pageUrl: String): List<StreamSource> {
        if (array == null) return emptyList()
        val out = LinkedHashMap<String, StreamSource>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val embed = absolutize(item.optString("embedUrl"), pageUrl) ?: continue
            if (!ScrapeValidator.isAcceptableUrl(embed)) continue
            val provider = item.optString("provider").trim().ifBlank { "Provider ${i + 1}" }
            val decoded = item.optString("decodedUrl").trim()
                .ifBlank { decodeEmbeddedUrl(embed) }
            val qualities = item.optJSONArray("qualities")?.toStringList().orEmpty()
                .filter { it.matches(Regex("""\d{3,4}""")) }
                .distinct()
                .sortedByDescending { it.toIntOrNull() ?: 0 }
            val maxQuality = item.optString("maxQuality")
                .takeIf { it.matches(Regex("""\d{3,4}""")) }
                ?: qualities.firstOrNull()
            val key = "$provider|$embed"
            out[key] = StreamSource(
                provider = provider,
                label = item.optString("label").trim().ifBlank { provider },
                embedUrl = embed,
                decodedUrl = absolutize(decoded, pageUrl) ?: embed,
                maxQuality = maxQuality,
                qualities = qualities,
            )
        }
        return out.values.toList()
    }

    private fun subtitles(array: JSONArray?, pageUrl: String): List<SubtitleTrack> {
        if (array == null) return emptyList()
        val out = LinkedHashMap<String, SubtitleTrack>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val raw = item.optString("url").trim()
            if (raw.isBlank()) continue
            val url = absolutize(raw, pageUrl) ?: continue
            if (!ScrapeValidator.isAcceptableUrl(url)) continue
            if (!url.contains(".srt", true) && !url.contains(".vtt", true)) continue
            out[url] = SubtitleTrack(
                label = item.optString("label").trim()
                    .ifBlank { url.substringAfterLast('/').substringBefore('?') },
                url = url,
                language = item.optString("language").takeIf { it.isNotBlank() },
            )
        }
        return out.values.toList()
    }

    private fun metaValue(meta: JSONObject?, vararg keys: String): String? {
        if (meta == null) return null
        val names = meta.keys().asSequence().toList()
        for (key in keys) {
            val match = names.firstOrNull {
                it.equals(key, ignoreCase = true) || it.contains(key, ignoreCase = true)
            } ?: continue
            val value = meta.optString(match).trim()
            if (value.isNotBlank() && !value.equals("N/A", true)) return value
        }
        return null
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until length()) {
            when (val value = opt(i)) {
                is String -> value.trim().takeIf { it.isNotBlank() }?.let(out::add)
                is JSONObject -> value.optString("name").trim()
                    .takeIf { it.isNotBlank() }?.let(out::add)
            }
        }
        return out.distinct()
    }

    private fun split(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(",", "/")
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("N/A", true) }
            .distinct()
    }

    private fun cleanTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim()
            .split(" Film Subtitle")[0]
            .split(" Sub Indo")[0]
            .replace(Regex("^Nonton\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun firstNumber(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return Regex("""\d+(?:[.,]\d+)?""").find(raw)?.value
    }

    private fun firstUrl(vararg values: String): String? =
        values.map { it.trim() }.firstOrNull { it.isNotBlank() }

    private fun looksLikeQuality(value: String): Boolean =
        Regex("""(?:WEBDL|WEB-DL|WEBRip|BluRay|BDRip|HDCAM|HDRip|HDTS|CAM|DVD|1080p|720p|480p|HD)""", RegexOption.IGNORE_CASE)
            .containsMatchIn(value)

    private fun looksLikeDuration(value: String): Boolean =
        Regex("""\d{1,2}:\d{2}|\d+\s*(?:h|jam|min|menit|m)\b""", RegexOption.IGNORE_CASE)
            .containsMatchIn(value)

    fun parseDuration(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val clean = text.trim()

        Regex("""^(\d{1,2}):(\d{2})$""").find(clean)?.let {
            val h = it.groupValues[1].toLongOrNull() ?: 0
            val m = it.groupValues[2].toLongOrNull() ?: 0
            return ((h * 60) + m) * 60_000L
        }
        Regex("""(\d{1,2})\s*(?:h|hr|hrs|hour|hours|jam)\s*(?:(\d{1,2})\s*(?:m|min|mins|menit))?""", RegexOption.IGNORE_CASE)
            .find(clean)?.let {
                val h = it.groupValues[1].toLongOrNull() ?: 0
                val m = it.groupValues[2].toLongOrNull() ?: 0
                val total = ((h * 60) + m) * 60_000L
                if (total > 0) return total
            }
        Regex("""(\d{1,3})\s*(?:m|min|mins|menit)\b""", RegexOption.IGNORE_CASE)
            .find(clean)?.let {
                val m = it.groupValues[1].toLongOrNull() ?: 0
                val total = m * 60_000L
                if (total > 0) return total
            }
        return null
    }

    fun parseIsoDuration(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        val hours = Regex("""(\d+)H""").find(iso)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val minutes = Regex("""(\d+)M""").find(iso)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val total = (hours * 60 + minutes) * 60_000L
        return total.takeIf { it > 0 }
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms / 60_000
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) "%dh %02dm".format(h, m) else "${m}m"
    }

    private fun decodeEmbeddedUrl(url: String): String {
        return runCatching {
            val uri = URI(url)
            val encoded = uri.rawQuery
                ?.split("&")
                ?.firstOrNull { it.startsWith("url=") }
                ?.substringAfter("url=")
                ?: return url
            URLDecoder.decode(encoded, "UTF-8")
        }.getOrDefault(url)
    }

    private fun absolutize(raw: String?, base: String): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("data:") || value.startsWith("blob:") -> null
            value.startsWith("/") -> runCatching {
                val uri = URI(base)
                "${uri.scheme}://${uri.host}$value"
            }.getOrNull()
            base.isBlank() -> null
            else -> runCatching { URL(URL(base), value).toString() }.getOrNull()
        }
    }

    fun slugFromUrl(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull() ?: return ""
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        if (segments.size != 1) return ""
        val slug = segments.first()
        return slug.takeUnless { it.lowercase(Locale.ROOT) in RESERVED_SEGMENTS } ?: ""
    }

    companion object {
        private val RESERVED_SEGMENTS = setOf(
            "genre", "country", "year", "quality", "size", "artist", "director", "tag",
            "playlist", "page", "movie", "series", "release", "rating", "title", "populer",
            "latest", "latest-series", "search.php", "search", "nd.php", "dmca", "wp-content",
            "wp-json", "feed", "rss", "amp", "rekomendasi-film-pintar", "daftar-playlist",
            "petunjuk-cara-download-film", "subtitle-maker", "author", "category",
        )
    }
}
