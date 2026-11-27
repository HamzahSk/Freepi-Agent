package com.freepi.lk21.data.scrape

import com.freepi.lk21.data.model.MovieDetail
import com.freepi.lk21.data.model.ResolvedStream
import com.freepi.lk21.data.model.StreamSource
import com.freepi.lk21.data.remote.Lk21Client
import com.freepi.lk21.data.remote.PlayerExtractionOptions
import com.freepi.lk21.data.remote.resolvePlayerSources

/** HTTP/API stages followed by real browser execution in the original iframe ancestry. */
class WebViewStreamResolver(
    private val engine: WebViewScrapeEngine,
    private val client: Lk21Client,
    private val options: PlayerExtractionOptions = PlayerExtractionOptions(),
) : StreamProvider {
    override suspend fun resolve(detail: MovieDetail, source: StreamSource): ResolvedStream =
        resolvePlayerSources(client, source, detail.url, options) { initial, catalog, provider, validate ->
            val envelope = engine.scrape(ScrapeRequest(
                kind = ScrapeKind.STREAM,
                url = initial,
                referer = catalog,
                embeddingPageUrl = catalog,
                streamProviderUrl = provider,
                streamMaxDepth = options.maxDepth,
                timeoutMs = options.browserTimeoutMs,
                retries = 0,
            ), onStreamMetadata = validate)
            ScrapeValidator.requireStream(envelope)
        }
}
