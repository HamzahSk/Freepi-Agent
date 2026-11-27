package com.freepi.lk21.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.freepi.lk21.core.userMessage
import com.freepi.lk21.data.MovieRepository
import com.freepi.lk21.data.model.FilterCatalog
import com.freepi.lk21.data.model.MovieCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BrowseUiState(
    val title: String = "",
    val path: String = "",
    val filters: FilterCatalog? = null,
    val items: List<MovieCard> = emptyList(),
    val page: Int = 1,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val empty: Boolean = false,
)

class BrowseViewModel(
    private val repository: MovieRepository,
    initialPath: String,
    initialTitle: String,
) : ViewModel() {

    private var nextUrl: String? = null
    private var pageJob: kotlinx.coroutines.Job? = null
    private var loadingJob: Boolean = false

    private val _state = MutableStateFlow(
        BrowseUiState(title = initialTitle, path = initialPath, loading = true)
    )
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    init {
        loadFilters()
        loadFirst(initialPath, initialTitle)
    }

    private fun loadFilters() {
        viewModelScope.launch {
            val filters = runCatching { repository.home().filters }.getOrNull()
            if (filters != null) _state.value = _state.value.copy(filters = filters)
        }
    }

    fun loadFirst(path: String, title: String) {
        pageJob?.cancel()
        _state.value = _state.value.copy(
            path = path,
            title = title,
            loading = true,
            loadingMore = false,
            error = null,
            empty = false,
            items = emptyList(),
        )
        nextUrl = null
        pageJob = viewModelScope.launch {
            try {
                val page = repository.list(path)
                nextUrl = page.nextPageUrl
                _state.value = _state.value.copy(
                    items = page.items,
                    page = page.page,
                    loading = false,
                    hasMore = page.hasMore,
                    empty = page.items.isEmpty(),
                    error = null,
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.value = _state.value.copy(
                    loading = false,
                    error = t.userMessage(),
                )
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        val url = nextUrl ?: return
        if (loadingJob || current.loadingMore) return
        loadingJob = true
        _state.value = current.copy(loadingMore = true)
        pageJob = viewModelScope.launch {
            try {
                val page = repository.list(url)
                nextUrl = page.nextPageUrl
                val merged = (current.items + page.items).distinctBy { it.slug }
                _state.value = _state.value.copy(
                    items = merged,
                    page = page.page,
                    loadingMore = false,
                    hasMore = page.hasMore,
                )
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.value = _state.value.copy(
                    loadingMore = false,
                    error = t.userMessage(),
                )
            } finally {
                loadingJob = false
            }
        }
    }

    fun retry() = loadFirst(_state.value.path, _state.value.title)
}
