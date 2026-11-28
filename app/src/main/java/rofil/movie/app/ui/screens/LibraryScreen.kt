package rofil.movie.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import rofil.movie.app.data.local.FavoriteEntity
import rofil.movie.app.data.local.WatchProgressEntity
import rofil.movie.app.ui.components.EmptyState
import rofil.movie.app.ui.components.PosterImage
import rofil.movie.app.ui.theme.Accent
import rofil.movie.app.ui.theme.Background
import rofil.movie.app.ui.theme.Primary
import rofil.movie.app.ui.theme.SurfaceElevated
import rofil.movie.app.ui.theme.TextSecondary
import rofil.movie.app.ui.viewmodel.LibraryUiState

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onOpen: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onRemoveFavorite: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(2) }
    val tabs = listOf("Continue", "History", "Favorites", "Completed")
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.padding(top = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Favorites",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (state.history.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) {
                    Text("Clear history", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
        TabRow(
            selectedTabIndex = tab,
            containerColor = Background,
            contentColor = Primary,
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(title, fontSize = 12.sp) },
                )
            }
        }
        when (tab) {
            0 -> ProgressList(
                entries = state.continueWatching,
                emptyMessage = "Nothing in progress yet. Start a movie and it will appear here.",
                onOpen = onOpen,
                onRemove = onRemoveHistory,
            )
            1 -> ProgressList(
                entries = state.history,
                emptyMessage = "Your watch history will appear here.",
                onOpen = onOpen,
                onRemove = onRemoveHistory,
            )
            2 -> FavoriteList(
                entries = state.favorites,
                onOpen = onOpen,
                onRemove = onRemoveFavorite,
            )
            else -> ProgressList(
                entries = state.completed,
                emptyMessage = "Completed titles will appear here.",
                onOpen = onOpen,
                onRemove = onRemoveHistory,
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear watch history?") },
            text = { Text("This removes all saved playback positions and completed markers. Favorites are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    onClearHistory()
                    confirmClear = false
                }) { Text("Clear", color = Primary) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProgressList(
    entries: List<WatchProgressEntity>,
    emptyMessage: String,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    if (entries.isEmpty()) {
        EmptyState(emptyMessage)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(entries, key = { it.slug }) { entry ->
            val fraction = if (entry.durationMs > 0)
                (entry.positionMs.toFloat() / entry.durationMs).coerceIn(0f, 1f) else 0f
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(SurfaceElevated, RoundedCornerShape(12.dp))
                    .clickable { onOpen(entry.slug) }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(width = 64.dp, height = 92.dp)) {
                    PosterImage(entry.poster, Modifier.fillMaxSize(), entry.title)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (entry.completed) "Completed" else
                            "${formatDuration(entry.positionMs)} of ${formatDuration(entry.durationMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (entry.completed) Accent else TextSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (!entry.completed) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = Primary,
                            trackColor = Color(0xFF2C2C3A),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { onOpen(entry.slug) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Resume", tint = Primary)
                }
                IconButton(onClick = { onRemove(entry.slug) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun FavoriteList(
    entries: List<FavoriteEntity>,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    if (entries.isEmpty()) {
        EmptyState("No favorites yet. Tap the heart on any title to save it here.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(entries, key = { it.slug }) { entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(SurfaceElevated, RoundedCornerShape(12.dp))
                    .clickable { onOpen(entry.slug) }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(width = 64.dp, height = 92.dp)) {
                    PosterImage(entry.poster, Modifier.fillMaxSize(), entry.title)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        entry.year?.let {
                            Text(it, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.width(8.dp))
                        }
                        entry.rating?.let {
                            Icon(Icons.Filled.Star, null, tint = Accent, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(it, color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                IconButton(onClick = { onRemove(entry.slug) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove favorite", tint = TextSecondary)
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
