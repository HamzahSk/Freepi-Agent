package com.freepi.lk21.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freepi.lk21.core.userMessage
import com.freepi.lk21.data.MovieRepository
import com.freepi.lk21.data.model.MovieCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val items: List<MovieCard> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val searched: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
)

class SearchViewModel(private val repository: MovieRepository) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var debounceJob: Job? = null
    private var nextUrl: String? = null

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
        debounceJob?.cancel()
        if (query.trim().length < 2) {
            nextUrl = null
            _state.value = _state.value.copy(
                items = emptyList(),
                loading = false,
                searched = false,
                error = null,
            )
            return
        }
        debounceJob = viewModelScope.launch {
            delay(450)
            runSearch(query)
        }
    }

    fun submit() {
        val query = _state.value.query
        if (query.trim().length >= 2) {
            debounceJob?.cancel()
            viewModelScope.launch { runSearch(query) }
        }
    }

    private suspend fun runSearch(query: String) {
        _state.value = _state.value.copy(loading = true, error = null, searched = true)
        nextUrl = null
        try {
            val page = repository.search(query)
            nextUrl = page.nextPageUrl
            _state.value = _state.value.copy(
                items = page.items,
                loading = false,
                hasMore = page.hasMore,
            )
        } catch (t: Throwable) {
            _state.value = _state.value.copy(loading = false, error = t.userMessage())
        }
    }

    fun loadMore() {
        val url = nextUrl ?: return
        if (_state.value.loading || _state.value.loadingMore) return
        _state.value = _state.value.copy(loadingMore = true)
        viewModelScope.launch {
            try {
                val page = repository.list(url)
                nextUrl = page.nextPageUrl
                _state.value = _state.value.copy(
                    items = (_state.value.items + page.items).distinctBy { it.slug },
                    loadingMore = false,
                    hasMore = page.hasMore,
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    loadingMore = false,
                    error = t.userMessage(),
                )
            }
        }
    }
}
