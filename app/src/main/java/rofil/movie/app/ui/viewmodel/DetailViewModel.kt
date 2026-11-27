package rofil.movie.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import rofil.movie.app.core.userMessage
import rofil.movie.app.data.MovieRepository
import rofil.movie.app.data.local.WatchProgressEntity
import rofil.movie.app.data.model.MovieDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DetailUiState(
    val loading: Boolean = true,
    val detail: MovieDetail? = null,
    val error: String? = null,
    val progress: WatchProgressEntity? = null,
    val loadingProgress: Boolean = false,
    val progressError: String? = null,
)

class DetailViewModel(
    private val repository: MovieRepository,
    private val slug: String,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    val isFavorite: StateFlow<Boolean> = repository.isFavorite(slug)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        load()
    }

    private var loadJob: kotlinx.coroutines.Job? = null

    fun load(forceRefresh: Boolean = false) {
        loadJob?.cancel()
        _state.value = _state.value.copy(loading = _state.value.detail == null, error = null)
        loadJob = viewModelScope.launch {
            try {
                val detail = repository.detail(slug, forceRefresh)
                _state.value = DetailUiState(loading = false, detail = detail, loadingProgress = true)
                try {
                    val progress = repository.getProgress(detail.slug)
                    _state.value = _state.value.copy(progress = progress, loadingProgress = false)
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    _state.value = _state.value.copy(loadingProgress = false, progressError = "Watch progress is unavailable.")
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.value = _state.value.copy(loading = false, error = t.userMessage())
            }
        }
    }

    fun toggleFavorite() {
        val detail = _state.value.detail ?: return
        viewModelScope.launch {
            runCatching { repository.toggleFavorite(detail) }
        }
    }

    fun removeFromHistory() {
        viewModelScope.launch {
            runCatching { repository.markUnwatched(slug) }
            _state.value = _state.value.copy(progress = null)
        }
    }
}
