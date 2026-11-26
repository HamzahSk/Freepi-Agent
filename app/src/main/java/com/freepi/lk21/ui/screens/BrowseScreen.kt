package com.freepi.lk21.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.freepi.lk21.data.model.MovieCard
import com.freepi.lk21.ui.components.ChipLabel
import com.freepi.lk21.ui.components.EmptyState
import com.freepi.lk21.ui.components.ErrorState
import com.freepi.lk21.ui.components.LoadingState
import com.freepi.lk21.ui.components.MovieGrid
import com.freepi.lk21.ui.theme.Background
import com.freepi.lk21.ui.theme.Primary
import com.freepi.lk21.ui.theme.TextSecondary
import com.freepi.lk21.ui.viewmodel.BrowseUiState

private data class BrowseKind(val key: String, val label: String)

private val browseKinds = listOf(
    BrowseKind("genre", "Genre"),
    BrowseKind("country", "Country"),
    BrowseKind("year", "Year"),
    BrowseKind("quality", "Quality"),
)

private val quickSections = listOf(
    "Latest" to "/latest/",
    "Popular" to "/populer/",
    "Top rated" to "/rating/",
    "By year" to "/release/",
    "Series" to "/latest-series/",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    state: BrowseUiState,
    onBack: () -> Unit,
    onMovieClick: (MovieCard) -> Unit,
    onSelect: (path: String, title: String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf("genre") }
    val options = when (kind) {
        "country" -> state.filters?.countries.orEmpty()
        "year" -> state.filters?.years.orEmpty()
        "quality" -> state.filters?.qualities.orEmpty()
        else -> state.filters?.genres.orEmpty()
    }

    Column(Modifier.padding(top = 4.dp)) {
        TopAppBar(
            title = {
                Column {
                    Text(state.title.ifBlank { "Browse" }, maxLines = 1)
                    Text(
                        "Catalog browser",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(quickSections, key = { it.second }) { (label, path) ->
                ChipLabel(
                    text = label,
                    selected = state.path == path,
                    onClick = { onSelect(path, label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(browseKinds, key = { it.key }) { browseKind ->
                ChipLabel(
                    text = browseKind.label,
                    selected = kind == browseKind.key,
                    onClick = { kind = browseKind.key },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (options.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(options, key = { it.path }) { option ->
                    ChipLabel(
                        text = option.name,
                        selected = state.path == option.path,
                        onClick = { onSelect(option.path, option.name) },
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        when {
            state.loading -> LoadingState("Loading ${state.title}…")
            state.error != null && state.items.isEmpty() -> ErrorState(state.error, onRetry)
            state.empty -> EmptyState("No titles found for this filter.")
            else -> MovieGrid(
                movies = state.items,
                onMovieClick = onMovieClick,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                footer = {
                    GridFooter(
                        loadingMore = state.loadingMore,
                        hasMore = state.hasMore,
                        onLoadMore = onLoadMore,
                    )
                },
            )
        }
    }
}

@Composable
fun GridFooter(loadingMore: Boolean, hasMore: Boolean, onLoadMore: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadingMore -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Primary,
                )
                Spacer(Modifier.width(10.dp))
                Text("Loading more…", color = TextSecondary)
            }
            hasMore -> TextButton(onClick = onLoadMore) {
                Text("Load more", color = Primary)
            }
            else -> Text(
                "You have reached the end",
                color = TextSecondary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
