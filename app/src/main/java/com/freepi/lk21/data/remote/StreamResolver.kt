package com.freepi.lk21.data.remote

import com.freepi.lk21.data.model.MovieDetail
import com.freepi.lk21.data.model.ResolvedStream
import com.freepi.lk21.data.model.StreamSource
import com.freepi.lk21.data.scrape.StreamProvider
import com.freepi.lk21.data.scrape.ScrapeKind
import com.freepi.lk21.data.scrape.ScrapeLogger
import kotlinx.coroutines.CancellationException

/** HTTP stages plus Rhino data processing; browser initialization is supplied by WebViewStreamResolver. */
class StreamResolver(private val client: Lk21Client) : StreamProvider {
    override suspend fun resolve(detail: MovieDetail, source: StreamSource): ResolvedStream = resolve(source, detail.url)

    suspend fun resolve(source: StreamSource, pageUrl: String): ResolvedStream =
        resolvePlayerSources(client, source, pageUrl)
}

internal suspend fun resolvePlayerSources(
    client: Lk21Client,
    source: StreamSource,
    pageUrl: String,
    browser: (suspend (String, String, String) -> org.json.JSONObject)? = null,
): ResolvedStream {
    var last: Exception? = null
    // The documented wrapper carries host/id; a decoded URL can omit that context.
    for (url in listOf(source.embedUrl, source.decodedUrl).distinct()) {
        val start = PlayerMetadata.absolute(url, pageUrl) ?: continue
        try {
            return PlayerExtraction(
                get = { target, parent, media -> client.playerResponse(target, parent, media) },
                post = { request, parent -> client.playerResponse(request.url, parent, popup = request) },
                browser = browser,
                log = { stage, target, detail -> ScrapeLogger.stage("player", ScrapeKind.STREAM,
                    stage.name, target, detail = detail) },
            ).resolve(start, pageUrl).copy(provider = source.provider, label = source.label)
        } catch (e: CancellationException) { throw e }
        catch (e: AccessBlockedException) { throw e }
        catch (e: Exception) { last = e }
    }
    throw last ?: StreamResolutionException("INITIAL_PAGE: missing player URL")
}

object JsUnpacker {
    fun unpackAll(html: String): List<String> = JsExtractor.unpackPacker(html)
}
