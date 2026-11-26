package com.freepi.lk21.data

import com.freepi.lk21.core.TtlCache
import com.freepi.lk21.data.local.AppDatabase
import com.freepi.lk21.data.local.FavoriteEntity
import com.freepi.lk21.data.local.WatchProgressEntity
import com.freepi.lk21.data.model.HomeData
import com.freepi.lk21.data.model.ListPage
import com.freepi.lk21.data.model.MovieDetail
import com.freepi.lk21.data.model.ResolvedStream
import com.freepi.lk21.data.model.StreamSource
import com.freepi.lk21.data.remote.Lk21Client
import com.freepi.lk21.data.remote.Lk21Parser
import com.freepi.lk21.data.remote.ParseException
import com.freepi.lk21.data.remote.StreamResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.Locale

/**
 * Single place that combines the remote catalog, the stream resolver and the
 * local Room database. UI layers only ever talk to this class.
 */
class MovieRepository(
    private val client: Lk21Client,
    private val parser: Lk21Parser,
    private val resolver: StreamResolver,
    private val database: AppDatabase,
) {

    private val homeCache = TtlCache<String, HomeData>(maxEntries = 2)
    private val listCache = TtlCache<String, ListPage>(maxEntries = 120)
    private val detailCache = TtlCache<String, MovieDetail>(maxEntries = 80)

    private val progressDao = database.watchProgressDao()
    private val favoriteDao = database.favoriteDao()

    // ------------------------------------------------------------- catalog

    suspend fun home(forceRefresh: Boolean = false): HomeData {
        if (!forceRefresh) homeCache.get("home")?.let { return it }
        val doc = client.fetchDocument("/", forceRefresh, ttlMillis = 10 * 60_000L)
        val home = parser.parseHome(doc)
        if (home.latest.isEmpty() && home.popular.isEmpty() && home.recommended.isEmpty()) {
            throw ParseException("The source returned a page without recognizable movie cards.")
        }
        homeCache.put("home", home, 10 * 60_000L)
        return home
    }

    /** Load a catalog path like "/genre/action/" or an absolute/paginated URL. */
    suspend fun list(pathOrUrl: String, forceRefresh: Boolean = false): ListPage {
        val key = pathOrUrl
        if (!forceRefresh) listCache.get(key)?.let { return it }
        val doc = client.fetchDocument(pathOrUrl, forceRefresh, ttlMillis = 5 * 60_000L)
        val page = Regex("""/page/(\d+)""").find(doc.baseUri())?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val parsed = parser.parseList(doc, page)
        listCache.put(key, parsed, 5 * 60_000L)
        return parsed
    }

    suspend fun search(query: String, forceRefresh: Boolean = false): ListPage {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return list("/search.php?s=$encoded", forceRefresh)
    }

    suspend fun detail(slugOrUrl: String, forceRefresh: Boolean = false): MovieDetail {
        val url = if (slugOrUrl.startsWith("http")) slugOrUrl else "/$slugOrUrl/"
        if (!forceRefresh) detailCache.get(url)?.let { return it }
        val doc = client.fetchDocument(url, forceRefresh, ttlMillis = 15 * 60_000L)
        val detail = parser.parseDetail(doc, client.absolute(url))
        if (detail.title.isBlank()) {
            throw ParseException("Could not read the movie details. The source layout may have changed.")
        }
        detailCache.put(url, detail, 15 * 60_000L)
        return detail
    }

    suspend fun resolve(detail: MovieDetail, source: StreamSource): ResolvedStream = withContext(Dispatchers.IO) {
        resolver.resolve(source, detail.url).copy(provider = source.provider, label = source.label)
    }

    fun clearCaches() {
        homeCache.clear()
        listCache.clear()
        detailCache.clear()
        client.clearCache()
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

    fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val sec = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }
}
