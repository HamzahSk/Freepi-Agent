package com.freepi.lk21.data.scrape

import com.freepi.lk21.data.model.MovieDetail
import com.freepi.lk21.data.model.ResolvedStream
import com.freepi.lk21.data.model.StreamSource
import com.freepi.lk21.data.remote.Lk21Client
import com.freepi.lk21.data.remote.resolvePlayerSources

/** HTTP/API stages followed by real browser execution in the original iframe ancestry. */
class WebViewStreamResolver(
    private val engine: WebViewScrapeEngine,
    private val client: Lk21Client,
) : StreamProvider {
    override suspend fun resolve(detail: MovieDetail, source: StreamSource): ResolvedStream =
        resolvePlayerSources(client, source, detail.url) { initial, catalog, provider ->
            val envelope = engine.scrape(ScrapeRequest(
                kind = ScrapeKind.STREAM,
                url = initial,
                referer = catalog,
                embeddingPageUrl = catalog,
                streamProviderUrl = provider,
                timeoutMs = 30_000,
                retries = 0,
            ))
            ScrapeValidator.requireStream(envelope)
        }
}
