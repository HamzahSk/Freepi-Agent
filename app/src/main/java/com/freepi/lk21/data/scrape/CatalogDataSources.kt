package com.freepi.lk21.data.scrape

import com.freepi.lk21.data.model.HomeData
import com.freepi.lk21.data.model.ListPage
import com.freepi.lk21.data.model.MovieDetail
import com.freepi.lk21.data.remote.Lk21Client
import com.freepi.lk21.data.remote.Lk21Parser
import com.freepi.lk21.data.remote.ParseException
import java.net.URL
import java.net.URLEncoder

/**
 * [CatalogDataSource] backed by the hidden WebView scraping engine.
 * This is the primary data path for Home / Browse / Search / Details.
 */
class WebViewCatalogDataSource(
    private val engine: WebViewScrapeEngine,
    private val client: Lk21Client,
    private val mapper: ScrapeMapper = ScrapeMapper(),
) : CatalogDataSource {

    override suspend fun home(forceRefresh: Boolean): HomeData {
        val url = client.absolute("/")
        val envelope = engine.scrape(
            ScrapeRequest(
                kind = ScrapeKind.HOME,
                url = url,
                timeoutMs = 40_000,
                retries = 1,
            )
        )
        client.noteWorkingBase(envelope.source.finalUrl)
        val data = ScrapeValidator.requireHome(envelope)
        return mapper.home(data)
    }

    override suspend fun list(pathOrUrl: String, forceRefresh: Boolean): ListPage {
        val url = client.absolute(pathOrUrl)
        val isSearch = pathOrUrl.contains("?s=") || pathOrUrl.contains("search")
        val envelope = engine.scrape(
            ScrapeRequest(
                kind = ScrapeKind.LIST,
                url = url,
                timeoutMs = 35_000,
                retries = 1,
            )
        )
        client.noteWorkingBase(envelope.source.finalUrl)
        val data = ScrapeValidator.requireList(envelope, isSearch = isSearch)
        return mapper.list(data, envelope.source.finalUrl.ifBlank { url })
    }

    override suspend fun search(query: String, forceRefresh: Boolean): ListPage {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        // The current theme answers on /?s=..., the classic one on /search.php?s=...
        val candidates = listOf(
            client.absolute("/?s=$encoded"),
            client.absolute("/search.php?s=$encoded"),
        )
        var lastError: Throwable? = null
        for (url in candidates) {
            try {
                val envelope = engine.scrape(
                    ScrapeRequest(
                        kind = ScrapeKind.LIST,
                        url = url,
                        timeoutMs = 35_000,
                        retries = 1,
                    )
                )
                client.noteWorkingBase(envelope.source.finalUrl)
                val data = ScrapeValidator.requireList(envelope, isSearch = true)
                val page = mapper.list(data, envelope.source.finalUrl.ifBlank { url })
                if (page.items.isNotEmpty() || page.page > 1) return page
                lastError = ParseException("The search returned no results.")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                lastError = t
            }
        }
        throw lastError ?: ParseException("The search could not be completed.")
    }

    override suspend fun detail(slugOrUrl: String, forceRefresh: Boolean): MovieDetail {
        val url = client.absolute(slugOrUrl)
        var current = url
        var lastData: org.json.JSONObject? = null
        var finalUrl = url

        repeat(3) {
            val envelope = engine.scrape(
                ScrapeRequest(
                    kind = ScrapeKind.DETAIL,
                    url = current,
                    timeoutMs = 40_000,
                    retries = 1,
                )
            )
            client.noteWorkingBase(envelope.source.finalUrl)
            val data = ScrapeValidator.requireDetail(envelope)
            finalUrl = envelope.source.finalUrl.ifBlank { current }
            val redirect = data.optString("redirectUrl")
                .takeIf { it.isNotBlank() }
                ?.let { mapperAbsolutize(it, finalUrl) }
            if (redirect != null && redirect != finalUrl && !redirect.startsWith("javascript:")) {
                // Series landing page -> episode host redirect.
                current = redirect
                lastData = null
            } else {
                lastData = data
                return@repeat
            }
        }

        val data = lastData ?: throw ParseException(
            "The series page redirected too many times; the episode list could not be read."
        )
        return mapper.detail(data, finalUrl)
    }

    private fun mapperAbsolutize(value: String, base: String): String? = runCatching {
        java.net.URL(java.net.URL(base), value).toString()
    }.getOrNull()
}

/** Same data, but reads pages through the HTTP client + Jsoup parser. */
class HttpCatalogDataSource(
    private val client: Lk21Client,
    private val parser: Lk21Parser = Lk21Parser(),
) : CatalogDataSource {

    override suspend fun home(forceRefresh: Boolean): HomeData {
        val doc = client.fetchDocument("/", forceRefresh, ttlMillis = 10 * 60_000L)
        val home = parser.parseHome(doc)
        if (home.latest.isEmpty() && home.popular.isEmpty() && home.recommended.isEmpty()) {
            throw ParseException("The source returned a page without recognizable movie cards.")
        }
        return home
    }

    override suspend fun list(pathOrUrl: String, forceRefresh: Boolean): ListPage {
        val doc = client.fetchDocument(pathOrUrl, forceRefresh, ttlMillis = 5 * 60_000L)
        val page = Regex("""/page/(\d+)""").find(doc.baseUri())?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return parser.parseList(doc, page)
    }

    override suspend fun search(query: String, forceRefresh: Boolean): ListPage {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return list("/search.php?s=$encoded", forceRefresh)
    }

    override suspend fun detail(slugOrUrl: String, forceRefresh: Boolean): MovieDetail {
        val url = if (slugOrUrl.startsWith("http")) slugOrUrl else client.absolute("/$slugOrUrl/")
        val doc = client.fetchDocument(url, forceRefresh, ttlMillis = 15 * 60_000L)
        val detail = parser.parseDetail(doc, client.absolute(url))
        if (detail.title.isBlank()) {
            throw ParseException("Could not read the movie details. The source layout may have changed.")
        }
        return detail
    }
}