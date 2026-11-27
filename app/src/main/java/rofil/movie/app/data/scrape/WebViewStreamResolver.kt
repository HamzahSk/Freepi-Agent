package rofil.movie.app.data.scrape

import rofil.movie.app.data.model.MovieDetail
import rofil.movie.app.data.model.ResolvedStream
import rofil.movie.app.data.model.StreamSource
import rofil.movie.app.data.remote.Lk21Client
import rofil.movie.app.data.remote.PlayerExtractionOptions
import rofil.movie.app.data.remote.resolvePlayerSources

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
