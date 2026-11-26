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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.freepi.lk21.data.model.MovieCard
import com.freepi.lk21.ui.components.ChipLabel
import com.freepi.lk21.ui.components.EmptyState
import com.freepi.lk21.ui.components.ErrorState
import com.freepi.lk21.ui.components.LoadingState
import com.freepi.lk21.ui.components.MovieGrid
import com.freepi.lk21.ui.theme.Primary
import com.freepi.lk21.ui.theme.SurfaceElevated
import com.freepi.lk21.ui.theme.TextSecondary
import com.freepi.lk21.ui.viewmodel.SearchUiState

@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onMovieClick: (MovieCard) -> Unit,
    onLoadMore: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            "Search",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("Movies, series, artists, IMDb…", color = TextSecondary) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = TextSecondary)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                onSubmit()
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                unfocusedBorderColor = com.freepi.lk21.ui.theme.Outline,
                focusedContainerColor = SurfaceElevated,
                unfocusedContainerColor = SurfaceElevated,
                cursorColor = Primary,
            ),
        )
        Spacer(Modifier.height(12.dp))

        when {
            state.loading -> LoadingState("Searching…")
            state.error != null && state.items.isEmpty() -> ErrorState(state.error, null)
            state.query.trim().length < 2 -> SearchSuggestions(onPick = onQueryChange)
            state.searched && state.items.isEmpty() -> EmptyState(
                "No results for “${state.query}”. Try a different keyword."
            )
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
private fun SearchSuggestions(onPick: (String) -> Unit) {
    val suggestions = listOf("Action", "Comedy", "Horror", "Drama", "2025", "Sci-Fi", "Animation")
    Column(Modifier.padding(16.dp)) {
        Text(
            "Try searching for",
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(suggestions) { suggestion ->
                ChipLabel(text = suggestion, onClick = { onPick(suggestion) })
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = Primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Results come straight from the live catalog.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}
