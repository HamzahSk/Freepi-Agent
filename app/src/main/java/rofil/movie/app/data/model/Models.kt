package rofil.movie.app.data.model

/** A movie/series card as rendered in catalog grids. */
data class MovieCard(
    val slug: String,
    val title: String,
    val url: String,
    val poster: String?,
    val rating: String?,
    val quality: String?,
    val duration: String?,
    val genres: List<String>,
    val year: String?,
)

/** One catalog page of cards. */
data class ListPage(
    val items: List<MovieCard>,
    val page: Int,
    val totalPages: Int,
    val nextPageUrl: String? = null,
) {
    val hasMore: Boolean get() = nextPageUrl != null || page < totalPages
}

/** Home screen aggregate payload. */
data class FilterOption(val name: String, val path: String)

data class FilterCatalog(
    val genres: List<FilterOption>,
    val countries: List<FilterOption>,
    val years: List<FilterOption>,
    val qualities: List<FilterOption>,
)

data class HomeData(
    val featured: List<MovieCard>,
    val popular: List<MovieCard>,
    val latest: List<MovieCard>,
    val recommended: List<MovieCard>,
    val series: List<MovieCard>,
    val filters: FilterCatalog,
    val updatedAt: Long,
)

/** An embeddable streaming provider listed on a detail page. */
data class StreamSource(
    val provider: String,
    val label: String,
    val embedUrl: String,
    val decodedUrl: String,
    val maxQuality: String?,
    val qualities: List<String>,
)

data class SubtitleTrack(
    val label: String,
    val url: String,
    val language: String? = null,
)

data class MovieDetail(
    val slug: String,
    val title: String,
    val url: String,
    val poster: String?,
    val backdrop: String?,
    val synopsis: String,
    val year: String?,
    val durationText: String?,
    val durationMs: Long?,
    val rating: String?,
    val ratingCount: String?,
    val imdbId: String?,
    val quality: String?,
    val countries: List<String>,
    val genres: List<String>,
    val directors: List<String>,
    val cast: List<String>,
    val translator: String?,
    val published: String?,
    val uploaded: String?,
    val trailerUrl: String?,
    val sources: List<StreamSource>,
    val subtitles: List<SubtitleTrack>,
    val related: List<MovieCard>,
    /** Series pages may redirect to the actual episode/show host. */
    val redirectUrl: String? = null,
    /** Captured-theme series metadata (seasons/episodes/series links). */
    val series: SeriesInfo? = null,
    /** Site-internal post id (used for watch-history and subscriptions). */
    val postId: String? = null,
)

/** One episode entry, as emitted by the `#season-data` JSON block. */
data class SeriesEpisode(
    val number: Int,
    val title: String,
    val slug: String,
)

/** One season (episodes in wave order). */
data class SeriesSeason(
    val number: Int,
    val episodes: List<SeriesEpisode>,
)

/** Aggregated series metadata found on TVSeries hub pages and episode pages. */
data class SeriesInfo(
    val totalSeasons: Int = 0,
    val totalEpisodes: Int = 0,
    val currentSeason: Int? = null,
    val currentEpisode: Int? = null,
    val firstSlug: String? = null,
    val latestSlug: String? = null,
    val nextSlug: String? = null,
    val prevSlug: String? = null,
    val seriesSlug: String? = null,
    val seriesTitle: String? = null,
    val seasons: List<SeriesSeason> = emptyList(),
) {
    val episodes: List<SeriesEpisode> get() = seasons.flatMap { it.episodes }
}

/** A resolved, directly playable media URL. */
data class ResolvedStream(
    val provider: String,
    val url: String,
    val mimeType: String?,
    val headers: Map<String, String>,
    val subtitles: List<SubtitleTrack>,
    val label: String,
)

/** A catalog filter (genre, country, year, quality, ordering). */
enum class FilterKind(val path: String, val displayName: String) {
    GENRE("genre", "Genre"),
    COUNTRY("country", "Country"),
    YEAR("year", "Year"),
    QUALITY("quality", "Quality"),
    ORDER("", "Order"),
}
