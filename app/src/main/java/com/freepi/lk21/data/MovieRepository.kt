package com.freepi.lk21.data

import android.util.Log
import com.freepi.lk21.core.TtlCache
import com.freepi.lk21.core.SingleFlight
import com.freepi.lk21.data.local.AppDatabase
import com.freepi.lk21.data.local.FavoriteEntity
import com.freepi.lk21.data.local.WatchProgressEntity
import com.freepi.lk21.data.model.HomeData
import com.freepi.lk21.data.model.ListPage
import com.freepi.lk21.data.model.MovieDetail
import com.freepi.lk21.data.model.ResolvedStream
import com.freepi.lk21.data.model.StreamSource
import com.freepi.lk21.data.remote.AccessBlockedException
import com.freepi.lk21.data.remote.Lk21Client
import com.freepi.lk21.data.remote.SourceException
import com.freepi.lk21.data.scrape.CatalogDataSource
import com.freepi.lk21.data.scrape.StreamProvider
import com.freepi.lk21.data.scrape.ScrapeLogger
import com.freepi.lk21.data.scrape.ScrapeKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Single place that combines the remote catalog (HTTP parser, with a WebView
 * fallback for dynamic layouts), the stream resolver and the local
 * Room database. UI layers only ever talk to this class.
 */
class MovieRepository(
    private val catalog: CatalogDataSource,
    private val fallbackCatalog: CatalogDataSource?,
    private val streamProvider: StreamProvider,
    private val client: Lk21Client,
    private val database: AppDatabase,
) {

    companion object {
        private const val TAG = "FreepiRepository"
        private const val TTL_HOME = 10 * 60_000L
        private const val TTL_LIST = 5 * 60_000L
        private const val TTL_DETAIL = 15 * 60_000L
    }

    private val homeCache = TtlCache<String, HomeData>(maxEntries = 2)
    private val listCache = TtlCache<String, ListPage>(maxEntries = 120)
    private val detailCache = TtlCache<String, MovieDetail>(maxEntries = 80)

    private val homeFlight = SingleFlight<Boolean, HomeData>()
    private val listFlight = SingleFlight<Pair<String, Boolean>, ListPage>()
    private val detailFlight = SingleFlight<Pair<String, Boolean>, MovieDetail>()

    private val progressDao = database.watchProgressDao()
    private val favoriteDao = database.favoriteDao()

    // ------------------------------------------------------------- catalog

    suspend fun home(forceRefresh: Boolean = false): HomeData {
        if (!forceRefresh) homeCache.get("home")?.let { cacheHit(ScrapeKind.HOME); return it }
        return homeFlight.run(forceRefresh) {
            if (!forceRefresh) homeCache.get("home")?.let { return@run it }
            fromCatalog { it.home(forceRefresh) }.also { homeCache.put("home", it, TTL_HOME) }
        }
    }

    /** Load a catalog path like "/genre/action/" or an absolute/paginated URL. */
    suspend fun list(pathOrUrl: String, forceRefresh: Boolean = false): ListPage {
        val key = client.absolute(pathOrUrl)
        if (!forceRefresh) listCache.get(key)?.let { cacheHit(ScrapeKind.LIST); return it }
        return listFlight.run(key to forceRefresh) {
            if (!forceRefresh) listCache.get(key)?.let { return@run it }
            fromCatalog { it.list(pathOrUrl, forceRefresh) }.also { listCache.put(key, it, TTL_LIST) }
        }
    }

    suspend fun search(query: String, forceRefresh: Boolean = false): ListPage {
        val normalized = query.trim()
        val key = "search:$normalized"
        if (!forceRefresh) listCache.get(key)?.let { cacheHit(ScrapeKind.LIST); return it }
        return listFlight.run(key to forceRefresh) {
            if (!forceRefresh) listCache.get(key)?.let { return@run it }
            fromCatalog { it.search(normalized, forceRefresh) }.also { listCache.put(key, it, TTL_LIST) }
        }
    }

    suspend fun detail(slugOrUrl: String, forceRefresh: Boolean = false): MovieDetail {
        val url = if (slugOrUrl.startsWith("http")) slugOrUrl else client.absolute("/$slugOrUrl/")
        if (!forceRefresh) detailCache.get(url)?.let { cacheHit(ScrapeKind.DETAIL); return it }
        return detailFlight.run(url to forceRefresh) {
            if (!forceRefresh) detailCache.get(url)?.let { return@run it }
            fromCatalog { it.detail(url, forceRefresh) }.also { detailCache.put(url, it, TTL_DETAIL) }
        }
    }

    suspend fun resolve(detail: MovieDetail, source: StreamSource): ResolvedStream =
        streamProvider.resolve(detail, source)
            .copy(provider = source.provider, label = source.label)

    private fun cacheHit(kind: ScrapeKind) = ScrapeLogger.stage("catalog", kind, "cache", detail = "model hit")

    fun clearCaches() {
        homeCache.clear()
        listCache.clear()
        detailCache.clear()
        client.clearCache()
    }

    /**
     * Runs a catalog request against the lightweight parser first; unsupported
     * layouts fall back to browser execution. Challenges always propagate.
     */
    private suspend fun <T> fromCatalog(block: suspend (CatalogDataSource) -> T): T {
        try {
            return block(catalog)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccessBlockedException) {
            // A bot challenge is an access control: report it, do not bypass it.
            throw e
        } catch (e: SourceException) {
            val fallback = fallbackCatalog
            if (fallback == null) throw e
            Log.w(TAG, "Primary catalog failed (${e.javaClass.simpleName}); using fallback.")
            try {
                return block(fallback)
            } catch (f: CancellationException) {
                throw f
            } catch (f: Throwable) {
                e.addSuppressed(f)
                throw e
            }
        }
    }

    // ------------------------------------------------------------- library

    fun continueWatching(): Flow<List<WatchProgressEntity>> = progressDao.observeContinueWatching()
    fun history(): Flow<List<WatchProgressEntity>> = progressDao.observeAll()
    fun completed(): Flow<List<WatchProgressEntity>> = progressDao.observeCompleted()
    fun favorites(): Flow<List<FavoriteEntity>> = favoriteDao.observeAll()
    fun isFavorite(slug: String): Flow<Boolean> = favoriteDao.observe(slug).map { it != null }
    suspend fun getProgress(slug: String): WatchProgressEntity? = progressDao.get(slug)

    suspend fun saveProgress(
        detail: MovieDetail,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (positionMs < 0 || durationMs <= 0) return
        val completed = durationMs > 0 && positionMs >= durationMs * 0.92
        progressDao.upsert(
            WatchProgressEntity(
                slug = detail.slug,
                title = detail.title,
                poster = detail.poster,
                positionMs = positionMs,
                durationMs = durationMs,
                completed = completed,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun markUnwatched(slug: String) = progressDao.delete(slug)
    suspend fun clearHistory() = progressDao.clear()

    suspend fun toggleFavorite(detail: MovieDetail): Boolean {
        val existing = favoriteDao.get(detail.slug)
        return if (existing != null) {
            favoriteDao.delete(detail.slug)
            false
        } else {
            favoriteDao.insert(
                FavoriteEntity(
                    slug = detail.slug,
                    title = detail.title,
                    poster = detail.poster,
                    year = detail.year,
                    rating = detail.rating,
                    addedAt = System.currentTimeMillis(),
                )
            )
            true
        }
    }

    suspend fun removeFavorite(slug: String) = favoriteDao.delete(slug)

    suspend fun toggleFavorite(card: com.freepi.lk21.data.model.MovieCard): Boolean {
        val existing = favoriteDao.get(card.slug)
        return if (existing != null) {
            favoriteDao.delete(card.slug)
            false
        } else {
            favoriteDao.insert(
                FavoriteEntity(
                    slug = card.slug,
                    title = card.title,
                    poster = card.poster,
                    year = card.year,
                    rating = card.rating,
                    addedAt = System.currentTimeMillis(),
                )
            )
            true
        }
    }
}