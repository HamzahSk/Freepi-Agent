package rofil.movie.app.data.scrape

import org.json.JSONArray
import org.json.JSONObject
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
import rofil.movie.app.data.remote.MovieBoxClient
import rofil.movie.app.data.remote.SourceException
import java.net.URI
import java.net.URLDecoder

class MovieBoxCatalogDataSource(private val client: MovieBoxClient) : CatalogDataSource {
    override suspend fun home(forceRefresh: Boolean): HomeData {
        val response = client.home()
        val data = requireData(response, "MovieBox home")
        val groups = data.optJSONArray("operatingList") ?: JSONArray()
        val sections = JSONObject().put("featured", JSONArray()).put("popular", JSONArray())
            .put("latest", JSONArray()).put("recommended", JSONArray()).put("series", JSONArray())
        val filters = LinkedHashMap<String, FilterOption>()
        for (i in 0 until groups.length()) {
            val group = groups.optJSONObject(i) ?: continue
            val items = group.optJSONArray("subjects") ?: JSONArray()
            val name = "${group.optString("type")} ${group.optString("title")}".lowercase()
            val target = when {
                name.contains("banner") -> sections.getJSONArray("featured")
                name.contains("series") || name.contains("tv") -> sections.getJSONArray("series")
                listOf("popular", "trending", "hot", "top").any(name::contains) -> sections.getJSONArray("popular")
                listOf("latest", "new", "update", "release").any(name::contains) -> sections.getJSONArray("latest")
                else -> sections.getJSONArray("recommended")
            }
            for (j in 0 until items.length()) card(items.optJSONObject(j))?.let(target::put)
            val filterArray = group.optJSONArray("filters") ?: continue
            for (j in 0 until filterArray.length()) {
                val item = filterArray.optJSONObject(j) ?: continue
                val label = item.optString("name").ifBlank { item.optString("title") }.ifBlank { item.optString("value") }
                val value = item.optString("value").ifBlank { item.optString("id") }.ifBlank { item.optString("name") }
                if (label.isNotBlank() && value.isNotBlank() && !label.equals("all", true)) {
                    val category = query(item.optString("query").substringAfter('?', ""))
                    val filterType = runCatching { JSONObject(category["filterType"].orEmpty()) }.getOrDefault(JSONObject())
                    val tabId = category["tabId"]?.toIntOrNull() ?: 2
                    val genre = filterType.optString("genre").ifBlank { value }
                    val route = "moviebox://filter?type=${if (tabId == 5) "series" else "movie"}&tabId=$tabId" +
                        "&genre=${enc(genre)}&country=${enc(filterType.optString("country").ifBlank { "ALL" })}" +
                        "&year=${enc(filterType.optString("year").ifBlank { "ALL" })}" +
                        "&language=${enc(filterType.optString("classify").ifBlank { filterType.optString("language") }.ifBlank { "ALL" })}" +
                        "&sort=${enc(filterType.optString("sort").ifBlank { "RECOMMEND" })}"
                    filters[label] = FilterOption(label, route)
                }
            }
        }
        val popular = cards(sections.getJSONArray("popular"))
        val latest = cards(sections.getJSONArray("latest"))
        val recommended = cards(sections.getJSONArray("recommended"))
        val series = cards(sections.getJSONArray("series"))
        return HomeData(
            featured = cards(sections.getJSONArray("featured")).ifEmpty { popular.take(5).ifEmpty { latest.take(5) } },
            popular = popular,
            latest = latest,
            recommended = recommended,
            series = series,
            filters = FilterCatalog(filters.values.toList(), emptyList(), emptyList(), emptyList()),
            updatedAt = System.currentTimeMillis(),
        )
    }

    override suspend fun list(pathOrUrl: String, forceRefresh: Boolean): ListPage {
        val url = runCatching { URI(pathOrUrl) }.getOrNull()
        val query = url?.rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate {
            val key = it.substringBefore('=')
            key to runCatching { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
        }
        val type = query["type"] ?: if (pathOrUrl.contains("series", true)) "series" else "movie"
        val page = query["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val perPage = query["perPage"]?.toIntOrNull()?.coerceIn(1, 100) ?: 24
        if (url?.host.equals("search", true)) {
            val search = client.search(query["q"].orEmpty(), page, perPage)
            val data = requireData(search, "MovieBox search")
            val pager = data.optJSONObject("pager") ?: JSONObject()
            val actualPage = pager.optInt("page", page).coerceAtLeast(1)
            val total = pager.optInt("totalCount", 0)
            val pageSize = pager.optInt("perPage", perPage).coerceAtLeast(1)
            val totalPages = (total + pageSize - 1) / pageSize
            val next = if (actualPage < totalPages) "moviebox://search?q=${enc(query["q"].orEmpty())}&page=${actualPage + 1}&perPage=$pageSize" else null
            return ListPage(cards(data.optJSONArray("items") ?: data.optJSONArray("list")), actualPage, maxOf(actualPage, totalPages), next)
        }
        val filterType = runCatching { JSONObject(query["filterType"].orEmpty()) }.getOrDefault(JSONObject())
        val tabId = query["tabId"]?.toIntOrNull()
            ?: if (type.equals("series", true) || type.equals("tv", true)) 5 else 2
        val pathGenre = Regex("/(?:genre|category)/([^/?]+)", RegexOption.IGNORE_CASE).find(pathOrUrl)?.groupValues?.get(1)
        val response = client.filter(
            tabId = tabId,
            page = page,
            perPage = perPage,
            sort = query["sort"] ?: "RECOMMEND",
            genre = query["genre"] ?: filterType.optString("genre").takeIf(String::isNotBlank) ?: pathGenre?.let(::dec) ?: "ALL",
            country = query["country"] ?: filterType.optString("country").takeIf(String::isNotBlank) ?: "ALL",
            year = query["year"] ?: filterType.optString("year").takeIf(String::isNotBlank) ?: "ALL",
            language = query["language"] ?: filterType.optString("classify").takeIf(String::isNotBlank)
                ?: filterType.optString("language").takeIf(String::isNotBlank) ?: "ALL",
        )
        val data = requireData(response, "MovieBox listing")
        val pager = data.optJSONObject("pager") ?: JSONObject()
        val actualPage = pager.optInt("page", page).coerceAtLeast(1)
        val total = pager.optInt("totalCount", 0)
        val pageSize = pager.optInt("perPage", perPage).coerceAtLeast(1)
        val totalPages = (total + pageSize - 1) / pageSize
        val hasMore = pager.optBoolean("hasMore", actualPage < totalPages)
        val next = if (hasMore) "moviebox://filter?type=$type&page=${pager.optInt("nextPage", actualPage + 1)}&perPage=$pageSize&sort=${enc(query["sort"] ?: "RECOMMEND")}&genre=${enc(query["genre"] ?: "ALL")}&country=${enc(query["country"] ?: "ALL")}&year=${enc(query["year"] ?: "ALL")}&language=${enc(query["language"] ?: "ALL")}" else null
        return ListPage(
            items = cards(data.optJSONArray("items") ?: data.optJSONArray("subjects")),
            page = actualPage,
            totalPages = maxOf(actualPage, totalPages),
            nextPageUrl = next,
        )
    }

    override suspend fun search(query: String, forceRefresh: Boolean): ListPage {
        val response = client.search(query, 1, 20)
        val data = requireData(response, "MovieBox search")
        val pager = data.optJSONObject("pager") ?: JSONObject()
        val page = pager.optInt("page", 1)
        val pageSize = pager.optInt("perPage", 20).coerceAtLeast(1)
        val total = pager.optInt("totalCount", 0)
        val pages = maxOf(page, (total + pageSize - 1) / pageSize)
        val next = if (page < pages) "moviebox://search?q=${enc(query)}&page=${page + 1}&perPage=$pageSize" else null
        return ListPage(cards(data.optJSONArray("items") ?: data.optJSONArray("list")), page, pages, next)
    }

    override suspend fun detail(slugOrUrl: String, forceRefresh: Boolean): MovieDetail {
        val route = Regex("moviebox:(\\d+)(?::s(\\d+):e(\\d+))?").find(slugOrUrl)
        val url = runCatching { URI(slugOrUrl) }.getOrNull()
        val id = route?.groupValues?.get(1)
            ?: url?.rawQuery?.split('&')?.firstOrNull { it.startsWith("id=") }?.substringAfter('=')?.let(::dec)
            ?: slugOrUrl.takeIf { it.all(Char::isDigit) }
            ?: throw SourceException("MovieBox could not identify this title. Search again and retry.")
        val season = route?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        val episode = route?.groupValues?.getOrNull(3)?.toIntOrNull() ?: 0
        val response = client.details(id)
        val data = requireData(response, "MovieBox details")
        val isMovie = data.optInt("subjectType") == 1
        val seasonResponse = if (isMovie) null else client.seasons(id)
        val seasonData = seasonResponse?.optJSONObject("data")?.optJSONArray("seasons") ?: JSONArray()
        val seasons = ArrayList<SeriesSeason>()
        var totalEpisodes = 0
        for (i in 0 until seasonData.length()) {
            val item = seasonData.optJSONObject(i) ?: continue
            val number = item.optInt("se", 1)
            val count = item.optInt("maxEp", 0).coerceIn(0, 500)
            totalEpisodes += count
            val episodes = (1..count).map { ep -> SeriesEpisode(ep, "Episode $ep", "moviebox:$id:s$number:e$ep") }
            if (episodes.isNotEmpty()) seasons += SeriesSeason(number, episodes)
        }
        val detailUrl = "https://moviebox.ph/movies/${enc(data.optString("detailPath"))}?id=${enc(id)}"
        val detailPath = data.optString("detailPath").ifBlank {
            data.optString("detailUrl").substringAfterLast('/').substringBefore('?')
        }
        val poster = cover(data.optJSONObject("cover"))
        val streamSource = StreamSource(
            provider = "MovieBox",
            label = if (episode > 0) "MovieBox S${season} E$episode" else "MovieBox",
            embedUrl = detailUrl,
            decodedUrl = detailUrl,
            maxQuality = null,
            qualities = emptyList(),
            sourceId = "moviebox",
            externalId = id,
            detailPath = detailPath,
            season = season,
            episode = episode,
        )
        return MovieDetail(
            slug = "moviebox:$id${if (episode > 0) ":s$season:e$episode" else ""}",
            title = data.optString("title").ifBlank { "Untitled" },
            url = detailUrl,
            poster = poster,
            backdrop = data.optJSONArray("stills")?.optJSONObject(0)?.optString("url")?.takeIf(String::isNotBlank) ?: poster,
            synopsis = data.optString("description"),
            year = Regex("\\d{4}").find(data.optString("releaseDate"))?.value,
            durationText = data.optLong("duration").takeIf { it > 0 }?.let { formatDuration(it / 60) },
            durationMs = data.optLong("duration").takeIf { it > 0 }?.times(1000),
            rating = data.optString("imdbRatingValue").takeIf(String::isNotBlank),
            ratingCount = data.optString("imdbRatingCount").takeIf(String::isNotBlank),
            imdbId = null,
            quality = null,
            countries = data.optString("countryName").split(',').map(String::trim).filter(String::isNotBlank),
            genres = data.optString("genre").split(Regex("[,|]")).map(String::trim).filter(String::isNotBlank),
            directors = emptyList(),
            cast = emptyList(),
            translator = null,
            published = data.optString("releaseDate").takeIf(String::isNotBlank),
            uploaded = null,
            trailerUrl = data.optJSONObject("trailer")?.optString("url")?.takeIf(String::isNotBlank),
            sources = listOf(streamSource),
            subtitles = emptyList(),
            related = emptyList(),
            series = if (isMovie) null else SeriesInfo(
                totalSeasons = seasons.size,
                totalEpisodes = totalEpisodes,
                currentSeason = season.takeIf { it > 0 },
                currentEpisode = episode.takeIf { it > 0 },
                firstSlug = seasons.firstOrNull()?.episodes?.firstOrNull()?.slug,
                latestSlug = seasons.lastOrNull()?.episodes?.lastOrNull()?.slug,
                seriesSlug = "moviebox:$id",
                seriesTitle = data.optString("title"),
                seasons = seasons,
            ),
        )
    }

    private fun card(item: JSONObject?): MovieCard? {
        if (item == null || !item.has("subjectId")) return null
        val id = item.optString("subjectId")
        val path = item.optString("detailPath")
        val title = item.optString("title").ifBlank { "Untitled" }
        val url = if (path.isNotBlank()) "https://moviebox.ph/movies/${enc(path)}?id=${enc(id)}" else "https://moviebox.ph/?id=${enc(id)}"
        return MovieCard(
            slug = "moviebox:$id",
            title = title,
            url = url,
            poster = cover(item.optJSONObject("cover")),
            rating = item.optString("imdbRatingValue").takeIf(String::isNotBlank),
            quality = null,
            duration = item.optLong("duration").takeIf { it > 0 }?.let { formatDuration(it / 60) },
            genres = item.optString("genre").split(Regex("[,|]")).map(String::trim).filter(String::isNotBlank),
            year = Regex("\\d{4}").find(item.optString("releaseDate"))?.value,
        )
    }

    private fun cards(values: JSONArray?): List<MovieCard> = (0 until (values?.length() ?: 0))
        .mapNotNull { card(values?.optJSONObject(it)) }.distinctBy { it.slug }

    private fun cover(value: JSONObject?): String? = value?.optString("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    private fun requireData(response: JSONObject, label: String): JSONObject {
        if (response.optInt("code", 0) !in listOf(0, 200)) throw SourceException("${response.optString("message").ifBlank { "$label was rejected." }} Try again later.")
        return response.optJSONObject("data") ?: throw SourceException("$label returned no data. Try again.")
    }
    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
    private fun dec(value: String): String = URLDecoder.decode(value, "UTF-8")
    private fun query(raw: String): Map<String, String> = raw.split('&').filter(String::isNotBlank).associate { pair ->
        val key = pair.substringBefore('=')
        key to runCatching { dec(pair.substringAfter('=', "")) }.getOrDefault("")
    }
    private fun formatDuration(minutes: Long): String = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
