package rofil.movie.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import rofil.movie.app.core.userMessage
import rofil.movie.app.data.MovieRepository
import rofil.movie.app.data.local.WatchProgressEntity
import rofil.movie.app.data.model.HomeData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val data: HomeData? = null,
    val error: String? = null,
)

class HomeViewModel(private val repository: MovieRepository) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    val continueWatching: StateFlow<List<WatchProgressEntity>> =
        repository.continueWatching()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        load()
    }

    private var loadJob: kotlinx.coroutines.Job? = null

    fun load(forceRefresh: Boolean = false) {
        loadJob?.cancel()
        val current = _state.value
        _state.value = current.copy(
            loading = current.data == null,
            refreshing = current.data != null,
            error = null,
        )
        loadJob = viewModelScope.launch {
            try {
                val data = repository.home(forceRefresh)
                _state.value = HomeUiState(loading = false, refreshing = false, data = data)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.value = _state.value.copy(
                    loading = false,
                    refreshing = false,
                    error = t.userMessage(),
                )
            }
        }
    }

    fun refresh() = load(forceRefresh = true)
}
