package com.freepi.lk21.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.freepi.lk21.data.local.WatchProgressEntity
import com.freepi.lk21.data.model.HomeData
import com.freepi.lk21.data.model.MovieCard
import com.freepi.lk21.ui.components.ChipLabel
import com.freepi.lk21.ui.components.EmptyState
import com.freepi.lk21.ui.components.ErrorState
import com.freepi.lk21.ui.components.HomeSkeleton
import com.freepi.lk21.ui.components.MoviePosterCard
import com.freepi.lk21.ui.components.MovieRowSection
import com.freepi.lk21.ui.components.SectionHeader
import com.freepi.lk21.ui.theme.Accent
import com.freepi.lk21.ui.theme.Primary
import com.freepi.lk21.ui.theme.TextSecondary
import com.freepi.lk21.ui.viewmodel.HomeUiState

@Composable
fun HomeScreen(
    state: HomeUiState,
    continueWatching: List<WatchProgressEntity>,
    onMovieClick: (MovieCard) -> Unit,
    onResume: (WatchProgressEntity) -> Unit,
    onBrowse: (path: String, title: String) -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
) {
    when {
        state.loading -> HomeSkeleton(
            header = { BrandBar(false, onSearch, onRefresh) },
            continueWatching = {
                if (continueWatching.isNotEmpty()) {
                    SectionHeader("Continue watching")
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(continueWatching, key = { it.slug }) { entry ->
                            MoviePosterCard(entry.toCard(), { onResume(entry) }, Modifier.width(124.dp))
                        }
                    }
                }
            },
        )
        state.error != null && state.data == null -> ErrorState(state.error, onRefresh)
        state.data == null -> EmptyState("No movies available right now.")
        else -> HomeContent(
            data = state.data,
            refreshing = state.refreshing,
            continueWatching = continueWatching,
            onMovieClick = onMovieClick,
            onResume = onResume,
            onBrowse = onBrowse,
            onSearch = onSearch,
            onRefresh = onRefresh,
        )
    }
}

@Composable
private fun HomeContent(
    data: HomeData,
    refreshing: Boolean,
    continueWatching: List<WatchProgressEntity>,
    onMovieClick: (MovieCard) -> Unit,
    onResume: (WatchProgressEntity) -> Unit,
    onBrowse: (String, String) -> Unit,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            BrandBar(
                refreshing = refreshing,
                onSearch = onSearch,
                onRefresh = onRefresh,
            )
        }
        data.featured.firstOrNull()?.let { featured ->
            item {
                HeroBanner(
                    movie = featured,
                    onClick = { onMovieClick(featured) },
                )
            }
        }
        if (continueWatching.isNotEmpty()) {
            item {
                SectionHeader("Continue watching")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(continueWatching, key = { it.slug }) { entry ->
                        MoviePosterCard(
                            movie = entry.toCard(),
                            onClick = { onResume(entry) },
                            modifier = Modifier.width(124.dp),
                            progress = if (entry.durationMs > 0)
                                entry.positionMs.toFloat() / entry.durationMs else null,
                            subtitle = "${progressPercent(entry)} watched",
                        )
                    }
                }
            }
        }
        item {
            MovieRowSection(
                title = "Popular now",
                movies = data.popular,
                onMovieClick = onMovieClick,
                onSeeAll = { onBrowse("/populer/", "Popular now") },
            )
        }
        item {
            MovieRowSection(
                title = "New releases",
                movies = data.latest,
                onMovieClick = onMovieClick,
                onSeeAll = { onBrowse("/latest/", "New releases") },
            )
        }
        item {
            MovieRowSection(
                title = "Recommended for you",
                movies = data.recommended,
                onMovieClick = onMovieClick,
                onSeeAll = { onBrowse("/rekomendasi-film-pintar", "Recommended") },
            )
        }
        item {
            MovieRowSection(
                title = "Series & drama",
                movies = data.series,
                onMovieClick = onMovieClick,
                onSeeAll = { onBrowse("/latest-series/", "Series & drama") },
            )
        }
        if (data.filters.genres.isNotEmpty()) {
            item {
                SectionHeader("Browse by genre")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(data.filters.genres.take(24), key = { it.path }) { genre ->
                        ChipLabel(
                            text = genre.name,
                            onClick = { onBrowse(genre.path, genre.name) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChipLabel(
                        text = "Top rated",
                        onClick = { onBrowse("/rating/", "Top rated") },
                    )
                    ChipLabel(
                        text = "By year",
                        onClick = { onBrowse("/release/", "By year") },
                    )
                    ChipLabel(
                        text = "All movies",
                        onClick = { onBrowse("/latest/", "All movies") },
                    )
                }
            }
        }
        item {
            Text(
                text = "Catalog data is provided by lk21official. Freepi Stream is an independent player and is not affiliated with the source.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )
        }
    }
}

@Composable
private fun BrandBar(
    refreshing: Boolean,
    onSearch: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(28.dp)
                .background(Primary, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "FREEPI",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
            )
            Text(
                "STREAM",
                style = MaterialTheme.typography.labelSmall,
                color = Primary,
                letterSpacing = 3.sp,
            )
        }
        if (refreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = Primary,
            )
            Spacer(Modifier.width(8.dp))
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = TextSecondary)
        }
        IconButton(onClick = onSearch) {
            Icon(Icons.Filled.Search, contentDescription = "Search", tint = TextSecondary)
        }
    }
}

@Composable
private fun HeroBanner(movie: MovieCard, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(420.dp)
            .padding(horizontal = 12.dp)
            .background(Color.Black, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        if (!movie.poster.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(movie.poster)
                    .crossfade(true)
                    .build(),
                contentDescription = movie.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x66000000),
                            Color(0xF20B0B0F),
                        )
                    )
                )
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!movie.year.isNullOrBlank()) {
                    Text(
                        movie.year,
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (!movie.rating.isNullOrBlank()) {
                    Icon(Icons.Filled.Star, null, tint = Accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(
                        movie.rating,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (!movie.quality.isNullOrBlank()) {
                    Text(
                        movie.quality.uppercase(),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Primary, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                movie.title,
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (movie.genres.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    movie.genres.joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .background(Primary, RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Watch now", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun WatchProgressEntity.toCard(): MovieCard = MovieCard(
    slug = slug,
    title = title,
    url = "/$slug/",
    poster = poster,
    rating = null,
    quality = null,
    duration = null,
    genres = emptyList(),
    year = null,
)

private fun progressPercent(entry: WatchProgressEntity): String {
    if (entry.durationMs <= 0) return "0%"
    return "${(entry.positionMs * 100 / entry.durationMs).coerceIn(0, 100)}%"
}
