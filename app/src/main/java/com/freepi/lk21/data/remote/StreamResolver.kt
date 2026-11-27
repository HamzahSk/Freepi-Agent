package com.freepi.lk21.data.remote

import com.freepi.lk21.data.model.MovieDetail
import com.freepi.lk21.data.model.ResolvedStream
import com.freepi.lk21.data.model.StreamSource
import com.freepi.lk21.data.scrape.StreamProvider
import com.freepi.lk21.data.scrape.ScrapeKind
import com.freepi.lk21.data.scrape.ScrapeLogger
import kotlinx.coroutines.CancellationException

/** HTTP stages plus Rhino data processing; browser initialization is supplied by WebViewStreamResolver. */
class StreamResolver(private val client: Lk21Client, private val options: PlayerExtractionOptions = PlayerExtractionOptions()) : StreamProvider {
    override suspend fun resolve(detail: MovieDetail, source: StreamSource): ResolvedStream = resolve(source, detail.url)

    suspend fun resolve(source: StreamSource, pageUrl: String): ResolvedStream =
        resolvePlayerSources(client, source, pageUrl, options)
}

internal suspend fun resolvePlayerSources(
    client: Lk21Client,
    source: StreamSource,
    pageUrl: String,
    options: PlayerExtractionOptions = PlayerExtractionOptions(),
    browser: BrowserExtraction? = null,
): ResolvedStream {
    var last: Exception? = null
    val requestId = java.util.UUID.randomUUID().toString()
    val started = System.nanoTime()
    var browserAttempted = false
    // The documented wrapper carries host/id; a decoded URL can omit that context.
    for (start in listOf(source.embedUrl, source.decodedUrl).mapNotNull { PlayerMetadata.absolute(it, pageUrl) }.distinct()) {
        try {
            return PlayerExtraction(
                get = { target, parent, media -> client.playerResponse(target, parent, media) },
                post = { request, parent -> client.playerResponse(request.url, parent, popup = request) },
                browser = browser?.let { execute -> { initial, catalog, provider, validate ->
                    browserAttempted = true
                    execute(initial, catalog, provider, validate)
                } },
                options = options,
                log = { stage, target, detail -> ScrapeLogger.stage(requestId, ScrapeKind.STREAM,
                    stage.name, target, elapsedMs = (System.nanoTime() - started) / 1_000_000, detail = detail) },
            ).resolve(start, pageUrl).copy(provider = source.provider, label = source.label)
        } catch (e: CancellationException) { throw e }
        catch (e: AccessBlockedException) { throw e }
        catch (e: Exception) {
            last = e
            // A second alias must not reload an already-observed frame tree.
            if (browserAttempted) throw e
        }
    }
    throw last ?: StreamResolutionException("INITIAL_REQUEST: missing player URL")
}

object JsUnpacker {
    fun unpackAll(html: String): List<String> = JsExtractor.unpackPacker(html)
}
