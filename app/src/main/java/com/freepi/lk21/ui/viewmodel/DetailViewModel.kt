package com.freepi.lk21.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freepi.lk21.core.userMessage
import com.freepi.lk21.data.MovieRepository
import com.freepi.lk21.data.local.WatchProgressEntity
import com.freepi.lk21.data.model.MovieDetail
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

    fun load(forceRefresh: Boolean = false) {
        _state.value = _state.value.copy(loading = _state.value.detail == null, error = null)
        viewModelScope.launch {
            try {
                val detail = repository.detail(slug, forceRefresh)
                val progress = repository.getProgress(detail.slug)
                _state.value = DetailUiState(loading = false, detail = detail, progress = progress)
            } catch (t: Throwable) {
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
