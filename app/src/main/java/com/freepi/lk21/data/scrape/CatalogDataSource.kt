package com.freepi.lk21.data.scrape

import com.freepi.lk21.data.model.HomeData
import com.freepi.lk21.data.model.ListPage
import com.freepi.lk21.data.model.MovieDetail
import com.freepi.lk21.data.model.ResolvedStream
import com.freepi.lk21.data.model.StreamSource

/**
 * Repository-facing source of catalog data. The production implementation uses
 * the hidden WebView engine; an HTTP implementation exists only as a fallback
 * for devices where Android System WebView is unavailable.
 */
interface CatalogDataSource {
    suspend fun home(forceRefresh: Boolean = false): HomeData
    suspend fun list(pathOrUrl: String, forceRefresh: Boolean = false): ListPage
    suspend fun search(query: String, forceRefresh: Boolean = false): ListPage
    suspend fun detail(slugOrUrl: String, forceRefresh: Boolean = false): MovieDetail
}

interface StreamProvider {
    suspend fun resolve(detail: MovieDetail, source: StreamSource): ResolvedStream
}

/** Tries the WebView provider first, then the legacy HTTP resolver. */
class CompositeStreamProvider(
    private val primary: StreamProvider?,
    private val fallback: StreamProvider,
) : StreamProvider {

    override suspend fun resolve(detail: MovieDetail, source: StreamSource): ResolvedStream {
        var primaryError: Throwable? = null
        if (primary != null) {
            try {
                return primary.resolve(detail, source)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                if (t is com.freepi.lk21.data.remote.AccessBlockedException) throw t
                primaryError = t
                ScrapeLogger.warn(
                    requestId = "stream",
                    kind = ScrapeKind.STREAM,
                    stage = "primary_provider_failed",
                    detail = "${t.javaClass.simpleName}: ${t.message}",
                    url = source.embedUrl,
                )
            }
        }
        try {
            return fallback.resolve(detail, source)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            if (primaryError != null) t.addSuppressed(primaryError)
            throw t
        }
    }
}
