package com.freepi.lk21.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freepi.lk21.core.userMessage
import com.freepi.lk21.data.MovieRepository
import com.freepi.lk21.data.local.WatchProgressEntity
import com.freepi.lk21.data.model.HomeData
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

    fun load(forceRefresh: Boolean = false) {
        val current = _state.value
        _state.value = current.copy(
            loading = current.data == null,
            refreshing = current.data != null,
            error = null,
        )
        viewModelScope.launch {
            try {
                val data = repository.home(forceRefresh)
                _state.value = HomeUiState(loading = false, refreshing = false, data = data)
            } catch (t: Throwable) {
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
