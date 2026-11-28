package rofil.movie.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import rofil.movie.app.data.model.MovieCard
import rofil.movie.app.data.model.MovieDetail
import rofil.movie.app.data.model.SeriesEpisode
import rofil.movie.app.ui.components.ChipLabel
import rofil.movie.app.ui.components.EmptyState
import rofil.movie.app.ui.components.ErrorState
import rofil.movie.app.ui.components.DetailSkeleton
import rofil.movie.app.ui.components.MovieRowSection
import rofil.movie.app.ui.components.SectionHeader
import rofil.movie.app.ui.theme.Accent
import rofil.movie.app.ui.theme.Background
import rofil.movie.app.ui.theme.Outline
import rofil.movie.app.ui.theme.Primary
import rofil.movie.app.ui.theme.SurfaceElevated
import rofil.movie.app.ui.theme.TextSecondary
import rofil.movie.app.ui.viewmodel.DetailUiState
import rofil.movie.app.ui.viewmodel.PlayerViewModel

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
    inlinePlayer: PlayerViewModel? = null,
) {
    when {
        state.loading -> DetailSkeleton {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
        }
        state.error != null && state.detail == null -> ErrorState(state.error, onRetry)
        state.detail == null -> EmptyState("Movie details are unavailable.")
        else -> DetailContent(
            detail = state.detail,
            isFavorite = isFavorite,
            resumeMs = state.progress?.positionMs ?: 0L,
            resumeDurationMs = state.progress?.durationMs ?: 0L,
            completed = state.progress?.completed == true,
            loadingProgress = state.loadingProgress,
            progressError = state.progressError,
            onBack = onBack,
            onPlay = onPlay,
            onMovieClick = onMovieClick,
            onOpenEpisode = onOpenEpisode,
            onToggleFavorite = onToggleFavorite,
            onRemoveHistory = onRemoveHistory,
            inlinePlayer = inlinePlayer,
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
    loadingProgress: Boolean,
    progressError: String?,
    onBack: () -> Unit,
    onPlay: (MovieDetail, Int) -> Unit,
    onMovieClick: (MovieCard) -> Unit,
    onOpenEpisode: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onRemoveHistory: () -> Unit,
    inlinePlayer: PlayerViewModel?,
) {
    val uriHandler = LocalUriHandler.current
    var synopsisExpanded by remember { mutableStateOf(false) }
    var infoExpanded by remember { mutableStateOf(true) }
    val inlineStateState = if (inlinePlayer != null) {
        inlinePlayer.state.collectAsStateWithLifecycle()
    } else {
        remember { androidx.compose.runtime.mutableStateOf(rofil.movie.app.ui.viewmodel.PlayerUiState()) }
    }
    val inlineState by inlineStateState

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        if (inlinePlayer != null) {
            item {
                InlinePlayerSurface(
                    viewModel = inlinePlayer,
                    state = inlineState,
                    onFullscreen = { onPlay(detail, inlineState.sourceIndex) },
                )
            }
        }
        if (inlinePlayer != null) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(detail.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (inlineState.loadingDetail) "Preparing providers…"
                            else inlineState.resolved?.label ?: "Inline player",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                        )
                    }
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (isFavorite) "Remove favorite" else "Add favorite",
                            tint = if (isFavorite) Primary else TextSecondary,
                        )
                    }
                    TextButton(onClick = { infoExpanded = !infoExpanded }) {
                        Text(if (infoExpanded) "Hide info" else "Show info", color = Primary)
                    }
                }
            }
        }
        if (inlinePlayer == null || infoExpanded) {
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
        if (loadingProgress || progressError != null) {
            item {
                Text(
                    progressError ?: "Loading watch progress…",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
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
                    onClick = { inlinePlayer?.selectSource(index) ?: onPlay(detail, index) },
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
}

@Composable
private fun InlinePlayerSurface(
    viewModel: PlayerViewModel,
    state: rofil.movie.app.ui.viewmodel.PlayerUiState,
    onFullscreen: () -> Unit,
) {
    val duration = state.durationMs.coerceAtLeast(1L)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        player = viewModel.player
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setKeepContentOnPlayerReset(true)
                    }
                },
                update = { it.player = viewModel.player },
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0x99000000), Color.Transparent, Color(0xCC000000)),
                        )
                    ),
            )
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.resolved?.provider ?: if (state.loadingDetail) "Loading player" else "Provider",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onFullscreen) {
                    Icon(Icons.Filled.Fullscreen, contentDescription = "Open fullscreen player", tint = Color.White)
                }
            }
            if (state.loadingDetail || state.resolving || state.buffering) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary, strokeWidth = 3.dp)
                }
            }
            if (state.error != null && !state.resolving) {
                Column(
                    Modifier.align(Alignment.Center).padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Playback unavailable", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(
                        state.error,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = viewModel::retry) { Text("Retry", color = Primary) }
                }
            }
            if (state.resolved != null && state.error == null) {
                IconButton(
                    onClick = viewModel::togglePlay,
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(42.dp),
                    )
                }
            }
            if (state.resolved != null) {
                Slider(
                    value = state.positionMs.coerceIn(0, duration).toFloat(),
                    onValueChange = { viewModel.seekTo(it.toLong()) },
                    valueRange = 0f..duration.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Primary,
                        activeTrackColor = Primary,
                        inactiveTrackColor = Color(0x66FFFFFF),
                    ),
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp),
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
