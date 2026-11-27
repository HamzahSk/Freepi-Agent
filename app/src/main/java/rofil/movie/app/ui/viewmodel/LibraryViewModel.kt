package rofil.movie.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import rofil.movie.app.data.MovieRepository
import rofil.movie.app.data.local.FavoriteEntity
import rofil.movie.app.data.local.WatchProgressEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val continueWatching: List<WatchProgressEntity> = emptyList(),
    val history: List<WatchProgressEntity> = emptyList(),
    val completed: List<WatchProgressEntity> = emptyList(),
    val favorites: List<FavoriteEntity> = emptyList(),
)

class LibraryViewModel(private val repository: MovieRepository) : ViewModel() {

    val state: StateFlow<LibraryUiState> = combine(
        repository.continueWatching(),
        repository.history(),
        repository.completed(),
        repository.favorites(),
    ) { cont, history, completed, favorites ->
        LibraryUiState(
            continueWatching = cont,
            history = history,
            completed = completed,
            favorites = favorites,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun removeHistory(slug: String) {
        viewModelScope.launch { runCatching { repository.markUnwatched(slug) } }
    }

    fun clearHistory() {
        viewModelScope.launch { runCatching { repository.clearHistory() } }
    }

    fun removeFavorite(slug: String) {
        viewModelScope.launch { runCatching { repository.removeFavorite(slug) } }
    }
}
