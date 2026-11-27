package rofil.movie.app.data.scrape

import rofil.movie.app.data.model.HomeData
import rofil.movie.app.data.model.ListPage
import rofil.movie.app.data.model.MovieDetail
import rofil.movie.app.data.remote.Lk21Client
import rofil.movie.app.data.remote.Lk21Parser
import rofil.movie.app.data.remote.ParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.net.URLEncoder

/**
 * [CatalogDataSource] backed by the hidden WebView scraping engine.
 * Used when HTTP succeeds but the catalog layout needs browser execution.
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
                retries = 0,
                forceRefresh = forceRefresh,
            )
        )
        client.noteWorkingBase(envelope.source.finalUrl)
        val data = ScrapeValidator.requireHome(envelope)
        return withContext(Dispatchers.Default) { mapper.home(data) }
    }

    override suspend fun list(pathOrUrl: String, forceRefresh: Boolean): ListPage {
        val url = client.absolute(pathOrUrl)
        val isSearch = pathOrUrl.contains("?s=") || pathOrUrl.contains("search")
        val envelope = engine.scrape(
            ScrapeRequest(
                kind = ScrapeKind.LIST,
                url = url,
                timeoutMs = 35_000,
                retries = 0,
                forceRefresh = forceRefresh,
            )
        )
        client.noteWorkingBase(envelope.source.finalUrl)
        val data = ScrapeValidator.requireList(envelope, isSearch = isSearch)
        return withContext(Dispatchers.Default) { mapper.list(data, envelope.source.finalUrl.ifBlank { url }) }
    }

    override suspend fun search(query: String, forceRefresh: Boolean): ListPage {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        // The captured theme answers on /search/?s=... (search form action="/search",
        // JSON-LD SearchAction urlTemplate .../search/?s={search_term_string}).
        // The classic theme answered on /search.php?s=... - keep it as a fallback.
        val candidates = listOf(
            client.absolute("/search/?s=$encoded"),
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
                        retries = 0,
                        forceRefresh = forceRefresh,
                    )
                )
                client.noteWorkingBase(envelope.source.finalUrl)
                val data = ScrapeValidator.requireList(envelope, isSearch = true)
                val page = withContext(Dispatchers.Default) { mapper.list(data, envelope.source.finalUrl.ifBlank { url }) }
                if (page.items.isNotEmpty() || page.page > 1 || data.optBoolean("emptyHint")) return page
                lastError = ParseException("The search returned no results.")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException || t is rofil.movie.app.data.remote.AccessBlockedException) throw t
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

        for (hop in 0 until 3) {
            val envelope = engine.scrape(
                ScrapeRequest(
                    kind = ScrapeKind.DETAIL,
                    url = current,
                    timeoutMs = 40_000,
                    retries = 0,
                    forceRefresh = forceRefresh,
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
                break
            }
        }

        val data = lastData ?: throw ParseException(
            "The series page redirected too many times; the episode list could not be read."
        )
        return withContext(Dispatchers.Default) { mapper.detail(data, finalUrl) }
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

    private suspend fun <T> parse(kind: ScrapeKind, url: String, block: () -> T): T =
        withContext(Dispatchers.Default) {
            val started = System.nanoTime()
            try { block() }
            finally { ScrapeLogger.stage("catalog", kind, "parse", url,
                elapsedMs = (System.nanoTime() - started) / 1_000_000) }
        }

    override suspend fun home(forceRefresh: Boolean): HomeData {
        val doc = client.fetchDocument("/", forceRefresh, ttlMillis = 10 * 60_000L)
        val home = parse(ScrapeKind.HOME, doc.baseUri()) { parser.parseHome(doc) }
        if (home.latest.isEmpty() && home.popular.isEmpty() && home.recommended.isEmpty()) {
            throw ParseException("The source returned a page without recognizable movie cards.")
        }
        return home
    }

    override suspend fun list(pathOrUrl: String, forceRefresh: Boolean): ListPage {
        val doc = client.fetchDocument(pathOrUrl, forceRefresh, ttlMillis = 5 * 60_000L)
        val page = Regex("""/page/(\d+)""").find(doc.baseUri())?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val result = parse(ScrapeKind.LIST, doc.baseUri()) { parser.parseList(doc, page) }
        if (result.items.isEmpty() && doc.selectFirst("main, #content, .content, .no-results, .not-found") == null)
            throw ParseException("The catalog layout was not recognized")
        return result
    }

    override suspend fun search(query: String, forceRefresh: Boolean): ListPage {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return list("/search/?s=$encoded", forceRefresh)
    }

    override suspend fun detail(slugOrUrl: String, forceRefresh: Boolean): MovieDetail {
        val url = if (slugOrUrl.startsWith("http")) slugOrUrl else client.absolute("/${slugOrUrl.trim('/')}/")
        var doc = client.fetchDocument(url, forceRefresh, ttlMillis = 15 * 60_000L)
        val visited = hashSetOf(doc.baseUri())
        for (hop in 0 until 3) {
            val redirect = doc.selectFirst("a#openNow[href]")?.absUrl("href")
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: break
            if (!visited.add(redirect)) throw ParseException("Circular episode redirect")
            doc = client.fetchDocument(redirect, forceRefresh, ttlMillis = 15 * 60_000L)
        }
        if (doc.selectFirst("a#openNow[href]") != null) throw ParseException("Too many episode redirects")
        val detail = parse(ScrapeKind.DETAIL, doc.baseUri()) { parser.parseDetail(doc, doc.baseUri()) }
        if (detail.title.isBlank() || detail.title == "Untitled" ||
            doc.selectFirst("h1, .post-header h2, script#watch-history-data, script[type=application/ld+json]") == null) {
            throw ParseException("Could not read the movie details. The source layout may have changed.")
        }
        return detail
    }
}
