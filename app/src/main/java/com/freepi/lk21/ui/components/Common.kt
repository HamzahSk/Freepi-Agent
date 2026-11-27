package com.freepi.lk21.ui.components

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.freepi.lk21.data.model.MovieCard
import com.freepi.lk21.data.model.SubtitleTrack
import com.freepi.lk21.ui.theme.Accent
import com.freepi.lk21.ui.theme.Outline
import com.freepi.lk21.ui.theme.Primary
import com.freepi.lk21.ui.theme.SurfaceElevated
import com.freepi.lk21.ui.theme.TextSecondary

@Composable
fun PosterImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    var loading by remember(url) { mutableStateOf(!url.isNullOrBlank()) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceElevated)
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .crossfade(250)
                    .build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onLoading = { loading = true },
                onSuccess = { loading = false },
                onError = { loading = false },
            )
            if (loading) SkeletonBlock(Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No poster", color = TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun RatingBadge(rating: String?, modifier: Modifier = Modifier) {
    if (rating.isNullOrBlank()) return
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Star,
            contentDescription = null,
            tint = Accent,
            modifier = Modifier.size(11.dp),
        )
        Spacer(Modifier.width(2.dp))
        Text(rating, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun QualityBadge(quality: String?, modifier: Modifier = Modifier) {
    if (quality.isNullOrBlank()) return
    Text(
        text = quality.uppercase(),
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Primary)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
fun MoviePosterCard(
    movie: MovieCard,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
    progress: Float? = null,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
        ) {
            PosterImage(
                url = movie.poster,
                contentDescription = movie.title,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0x66000000)),
                            startY = 220f,
                        )
                    )
            )
            RatingBadge(
                rating = movie.rating,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            )
            QualityBadge(
                quality = movie.quality,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
            )
            if (progress != null && progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color(0x55000000))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Primary)
                    )
                }
            }
        }
        if (showTitle) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = movie.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    onClickSeeAll: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (onClickSeeAll != null) {
            TextButton(onClick = onClickSeeAll) {
                Text("See all", color = TextSecondary, fontSize = 12.sp)
                Icon(
                    Icons.Filled.ArrowForward,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
fun MovieRowSection(
    title: String,
    movies: List<MovieCard>,
    onMovieClick: (MovieCard) -> Unit,
    onSeeAll: (() -> Unit)? = null,
) {
    if (movies.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        SectionHeader(title, onSeeAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(movies, key = { it.slug }) { movie ->
                MoviePosterCard(
                    movie = movie,
                    onClick = { onMovieClick(movie) },
                    modifier = Modifier.width(124.dp),
                )
            }
        }
    }
}

@Composable
fun MovieGrid(
    movies: List<MovieCard>,
    onMovieClick: (MovieCard) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (header != null) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                header()
            }
        }
        items(movies, key = { it.slug }) { movie ->
            MoviePosterCard(movie = movie, onClick = { onMovieClick(movie) })
        }
        if (footer != null) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                footer()
            }
        }
    }
}

@Composable
fun LoadingState(message: String = "Loading…", modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Primary, strokeWidth = 3.dp)
            Spacer(Modifier.height(12.dp))
            Text(message, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Something went wrong",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(14.dp))
                TextButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = Primary)
                    Spacer(Modifier.width(6.dp))
                    Text("Retry", color = Primary)
                }
            }
        }
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun ChipLabel(
    text: String,
    selected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) Primary else SurfaceElevated,
        contentColor = if (selected) Color.White else TextSecondary,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, Outline),
        modifier = modifier,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

@Composable
fun SubtitleChip(track: SubtitleTrack, modifier: Modifier = Modifier) {
    Text(
        text = track.label,
        color = TextSecondary,
        fontSize = 11.sp,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceElevated)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
