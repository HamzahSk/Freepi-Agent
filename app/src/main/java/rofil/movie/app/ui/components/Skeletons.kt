package rofil.movie.app.ui.components

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import rofil.movie.app.ui.theme.SurfaceElevated
import rofil.movie.app.ui.theme.TextSecondary

private val LocalPulse = staticCompositionLocalOf<State<Float>> { mutableStateOf(0.65f) }

/** One shared draw-phase animation for a visible skeleton group, respecting disabled animators. */
@Composable
fun SkeletonLoading(label: String, content: @Composable () -> Unit) {
    val animate = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
    val alpha = if (animate) rememberInfiniteTransition(label = "Skeleton pulse").animateFloat(
        initialValue = 0.45f, targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "Skeleton opacity",
    ) else remember { mutableStateOf(0.65f) }
    CompositionLocalProvider(LocalPulse provides alpha) {
        Box(Modifier.clearAndSetSemantics { contentDescription = label }) { content() }
    }
}

@Composable
fun SkeletonBlock(modifier: Modifier = Modifier) {
    val pulse = LocalPulse.current
    Box(modifier.drawBehind {
        drawRoundRect(SurfaceElevated.copy(alpha = pulse.value), cornerRadius = CornerRadius(10.dp.toPx()))
    })
}

@Composable
fun PosterSkeleton(modifier: Modifier = Modifier) {
    Column(modifier) {
        SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(2f / 3f))
        Spacer(Modifier.height(6.dp))
        SkeletonBlock(Modifier.fillMaxWidth(0.9f).height(14.dp))
        Spacer(Modifier.height(5.dp))
        SkeletonBlock(Modifier.fillMaxWidth(0.55f).height(12.dp))
    }
}

@Composable
fun SkeletonPosterRow(label: String = "Loading more titles") {
    SkeletonLoading(label) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(5) { PosterSkeleton(Modifier.width(124.dp)) }
        }
    }
}

@Composable
fun MovieGridSkeleton(label: String = "Loading titles") {
    SkeletonLoading(label) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp), modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { items(12) { PosterSkeleton() } }
    }
}

@Composable
fun FilterSkeleton() {
    SkeletonLoading("Loading filter options") {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { SkeletonBlock(Modifier.weight(1f).height(32.dp)) }
        }
    }
}

@Composable
fun HomeSkeleton(header: @Composable () -> Unit, continueWatching: @Composable () -> Unit = {}) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item { header() }
        item {
            SkeletonLoading("Loading featured title") {
                Box(Modifier.fillMaxWidth().height(420.dp).padding(horizontal = 12.dp)) {
                    SkeletonBlock(Modifier.fillMaxSize())
                    Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                        SkeletonBlock(Modifier.width(130.dp).height(14.dp))
                        Spacer(Modifier.height(12.dp))
                        SkeletonBlock(Modifier.fillMaxWidth(0.75f).height(36.dp))
                        Spacer(Modifier.height(16.dp))
                        SkeletonBlock(Modifier.width(140.dp).height(42.dp))
                    }
                }
            }
        }
        item { continueWatching() }
        items(3) { index ->
            Column {
                SectionHeader(listOf("Popular now", "New releases", "Recommended for you")[index])
                SkeletonPosterRow("Loading movie section")
            }
        }
    }
}

@Composable
fun DetailSkeleton(backButton: @Composable () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(470.dp)) {
                SkeletonLoading("Loading title details") {
                    Box(Modifier.fillMaxSize()) {
                        SkeletonBlock(Modifier.fillMaxSize())
                        Row(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalAlignment = Alignment.Bottom) {
                            SkeletonBlock(Modifier.width(96.dp).aspectRatio(2f / 3f))
                            Spacer(Modifier.width(16.dp))
                            Column {
                                SkeletonBlock(Modifier.fillMaxWidth().height(32.dp))
                                Spacer(Modifier.height(12.dp))
                                SkeletonBlock(Modifier.fillMaxWidth(0.7f).height(14.dp))
                            }
                        }
                    }
                }
                Box(Modifier.padding(8.dp)) { backButton() }
            }
        }
        item {
            SkeletonLoading("Loading synopsis") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SkeletonBlock(Modifier.fillMaxWidth().height(48.dp))
                    repeat(4) { SkeletonBlock(Modifier.fillMaxWidth(if (it == 3) 0.65f else 1f).height(14.dp)) }
                }
            }
        }
        item { SectionHeader("Cast"); FilterSkeleton() }
        item { SectionHeader("Stream providers"); FilterSkeleton() }
        item { SectionHeader("You may also like"); SkeletonPosterRow() }
    }
}

@Composable
fun PlayerLoading(message: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SkeletonLoading("Initializing player") {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(5) { index -> SkeletonBlock(Modifier.width(5.dp).height((16 + (2 - kotlin.math.abs(2 - index)) * 10).dp)) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        Text("Playback starts after a source is validated", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    }
}
