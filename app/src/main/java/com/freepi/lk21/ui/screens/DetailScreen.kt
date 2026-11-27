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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.freepi.lk21.data.model.MovieCard
import com.freepi.lk21.data.model.MovieDetail
import com.freepi.lk21.data.model.SeriesEpisode
import com.freepi.lk21.ui.components.ChipLabel
import com.freepi.lk21.ui.components.EmptyState
import com.freepi.lk21.ui.components.ErrorState
import com.freepi.lk21.ui.components.LoadingState
import com.freepi.lk21.ui.components.MovieRowSection
import com.freepi.lk21.ui.components.SectionHeader
import com.freepi.lk21.ui.theme.Accent
import com.freepi.lk21.ui.theme.Background
import com.freepi.lk21.ui.theme.Outline
import com.freepi.lk21.ui.theme.Primary
import com.freepi.lk21.ui.theme.SurfaceElevated
import com.freepi.lk21.ui.theme.TextSecondary
import com.freepi.lk21.ui.viewmodel.DetailUiState

@Composable
fun DetailScreen(
    state: DetailUiState,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onPlay: (MovieDetail, Int) -> Unit,
    onMovieClick: (MovieCard) -> Unit,
    onOpenEpisode: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onRetry: () -> Unit,
    onRemoveHistory: () -> Unit,
) {
    when {
        state.loading -> LoadingState("Loading details…")
        state.error != null && state.detail == null -> ErrorState(state.error, onRetry)
        state.detail == null -> EmptyState("Movie details are unavailable.")
        else -> DetailContent(
            detail = state.detail,
            isFavorite = isFavorite,
            resumeMs = state.progress?.positionMs ?: 0L,
            resumeDurationMs = state.progress?.durationMs ?: 0L,
            completed = state.progress?.completed == true,
            onBack = onBack,
            onPlay = onPlay,
            onMovieClick = onMovieClick,
            onOpenEpisode = onOpenEpisode,
            onToggleFavorite = onToggleFavorite,
            onRemoveHistory = onRemoveHistory,
        )
    }
}

@Composable
private fun DetailContent(
    detail: MovieDetail,
    isFavorite: Boolean,
    resumeMs: Long,
    resumeDurationMs: Long,
    completed: Boolean,
    onBack: () -> Unit,
    onPlay: (MovieDetail, Int) -> Unit,
    onMovieClick: (MovieCard) -> Unit,
    onOpenEpisode: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onRemoveHistory: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var synopsisExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(470.dp)
            ) {
                if (!detail.backdrop.isNullOrBlank() || !detail.poster.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(detail.backdrop ?: detail.poster)
                            .crossfade(true)
                            .build(),
                        contentDescription = detail.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0x99000000),
                                    Color(0x33000000),
                                    Background,
                                )
                            )
                        )
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .background(Color(0x66000000), RoundedCornerShape(50))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier
                            .background(Color(0x66000000), RoundedCornerShape(50))
                    ) {
                        Icon(
                            if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (isFavorite) "Remove favorite" else "Add favorite",
                            tint = if (isFavorite) Primary else Color.White,
                        )
                    }
                }
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(16.dp)
                ) {
                    Text(
                        detail.title,
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        detail.year?.let { MetaText(it) }
                        detail.durationText?.let { MetaText(it) }
                        detail.rating?.let {
                            Icon(Icons.Filled.Star, null, tint = Accent, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(3.dp))
                            MetaText("$it / 10")
                        }
                        detail.quality?.let {
                            Text(
                                it.uppercase(),
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(Primary, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (detail.genres.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(detail.genres) { genre ->
                                Text(
                                    genre,
                                    color = TextSecondary,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier
                                        .background(SurfaceElevated, RoundedCornerShape(50))
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { onPlay(detail, 0) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    enabled = detail.sources.isNotEmpty() || detail.series?.firstSlug != null,
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (resumeMs > 5_000 && !completed) "Resume" else "Play",
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (!detail.trailerUrl.isNullOrBlank()) {
                    OutlinedButton(
                        onClick = { runCatching { uriHandler.openUri(detail.trailerUrl) } },
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Outline),
                    ) {
                        Text("Trailer", color = MaterialTheme.colorScheme.onBackground)
                    }
                }
            }
            val seriesForPicker = detail.series
            if (seriesForPicker != null && seriesForPicker.seasons.isNotEmpty()) {
                EpisodePicker(detail, onOpenEpisode)
            }
        }
        if (resumeMs > 5_000 && !completed) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "You stopped at ${formatTime(resumeMs)} of ${formatTime(resumeDurationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRemoveHistory) {
                        Text("Remove", color = TextSecondary, fontSize = 11.sp)
                    }
                }
            }
        }
        if (detail.synopsis.isNotBlank()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text("Synopsis", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        detail.synopsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        maxLines = if (synopsisExpanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(
                        onClick = { synopsisExpanded = !synopsisExpanded },
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(if (synopsisExpanded) "Show less" else "Read more", color = Primary)
                    }
                }
            }
        }
        item {
            InfoGrid(detail)
        }
        item {
            SectionHeader("Sources & playback")
            Text(
                "Select a provider. Playback and subtitle tracks are handled by the built-in player.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        if (detail.sources.isEmpty()) {
            item {
                Text(
                    "No streaming providers are listed for this title right now.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            items(detail.sources.size) { index ->
                val source = detail.sources[index]
                Surface(
                    onClick = { onPlay(detail, index) },
                    color = SurfaceElevated,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .background(Primary.copy(alpha = 0.18f), RoundedCornerShape(9.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, tint = Primary)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                source.provider,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                            val qualities = source.qualities.ifEmpty {
                                listOfNotNull(source.maxQuality)
                            }
                            if (qualities.isNotEmpty()) {
                                Text(
                                    qualities.joinToString(" · ") { "${it}p" }.ifBlank { "Auto" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                )
                            }
                        }
                        Text("Play", color = Primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (detail.subtitles.isNotEmpty()) {
            item {
                Text(
                    "Subtitles detected",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(detail.subtitles) { track ->
                        Text(
                            track.label,
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .background(SurfaceElevated, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
        val credits = buildList {
            if (detail.directors.isNotEmpty()) add("Director" to detail.directors.joinToString(", "))
            if (detail.cast.isNotEmpty()) add("Cast" to detail.cast.take(8).joinToString(", "))
            if (detail.countries.isNotEmpty()) add("Country" to detail.countries.joinToString(", "))
            if (!detail.translator.isNullOrBlank()) add("Translator" to detail.translator)
            if (!detail.imdbId.isNullOrBlank()) add("IMDb" to detail.imdbId)
        }
        if (credits.isNotEmpty()) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text("Credits", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    credits.forEach { (label, value) ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelLarge,
                                color = TextSecondary,
                                modifier = Modifier.width(76.dp),
                            )
                            Text(
                                value,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                }
            }
        }
        if (detail.related.isNotEmpty()) {
            item {
                MovieRowSection(
                    title = "More like this",
                    movies = detail.related,
                    onMovieClick = onMovieClick,
                )
            }
        }
    }
}

@Composable
private fun MetaText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = TextSecondary,
        modifier = Modifier.padding(end = 10.dp),
    )
}

@Composable
private fun InfoGrid(detail: MovieDetail) {
    val rows = buildList {
        if (detail.published != null) add("Published" to detail.published)
        if (detail.durationText != null) add("Duration" to detail.durationText)
        if (detail.ratingCount != null) add("Votes" to detail.ratingCount!!)
        if (detail.uploaded != null) add("Uploaded" to detail.uploaded)
    }
    if (rows.isEmpty()) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        rows.forEach { (label, value) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    modifier = Modifier.width(80.dp),
                )
                Text(
                    value,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun EpisodePicker(detail: MovieDetail, onOpenEpisode: (String) -> Unit) {
    val series = detail.series ?: return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            "Episodes",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        if (series.seasons.size > 1) {
            Text(
                "${series.totalSeasons} seasons \u00b7 ${series.totalEpisodes} episodes",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
        series.seasons.forEach { season ->
            if (series.seasons.size > 1) {
                Text(
                    "Season ${season.number}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(season.episodes) { episode ->
                    DetailEpisodeChip(episode, onOpenEpisode)
                }
            }
        }
    }
}

@Composable
private fun DetailEpisodeChip(episode: SeriesEpisode, onOpenEpisode: (String) -> Unit) {
    Surface(
        onClick = { onOpenEpisode(episode.slug) },
        color = SurfaceElevated,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.width(140.dp),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                "E${episode.number}",
                style = MaterialTheme.typography.labelSmall,
                color = Primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                episode.title.ifBlank { "Episode ${episode.number}" },
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
