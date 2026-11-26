package com.freepi.lk21.ui

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.freepi.lk21.ui.screens.BrowseScreen
import com.freepi.lk21.ui.screens.DetailScreen
import com.freepi.lk21.ui.screens.HomeScreen
import com.freepi.lk21.ui.screens.LibraryScreen
import com.freepi.lk21.ui.screens.PlayerScreen
import com.freepi.lk21.ui.screens.SearchScreen
import com.freepi.lk21.ui.theme.Background
import com.freepi.lk21.ui.theme.Primary
import com.freepi.lk21.ui.theme.TextSecondary
import com.freepi.lk21.ui.viewmodel.BrowseViewModel
import com.freepi.lk21.ui.viewmodel.DetailViewModel
import com.freepi.lk21.ui.viewmodel.HomeViewModel
import com.freepi.lk21.ui.viewmodel.LibraryViewModel
import com.freepi.lk21.ui.viewmodel.PlayerViewModel
import com.freepi.lk21.ui.viewmodel.SearchViewModel

private object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val DETAIL = "detail/{slug}"
    const val BROWSE = "browse?path={path}&title={title}"
    const val PLAYER = "player/{slug}?source={source}"
}

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val container = rememberContainer()
    val context = LocalContext.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in setOf(Routes.HOME, Routes.SEARCH, Routes.LIBRARY)
    val isPlayer = currentRoute == Routes.PLAYER

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Background,
        contentWindowInsets = if (isPlayer) WindowInsets(0, 0, 0, 0)
        else ScaffoldDefaults.contentWindowInsets,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = Color(0xFF111118)) {
                    NavigationBarItem(
                        selected = currentRoute == Routes.HOME,
                        onClick = { navigateTab(navController, Routes.HOME) },
                        icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                        label = { Text("Home") },
                        colors = navItemColors(),
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.SEARCH,
                        onClick = { navigateTab(navController, Routes.SEARCH) },
                        icon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
                        label = { Text("Search") },
                        colors = navItemColors(),
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.LIBRARY,
                        onClick = { navigateTab(navController, Routes.LIBRARY) },
                        icon = { Icon(Icons.Filled.Favorite, contentDescription = "Library") },
                        label = { Text("Library") },
                        colors = navItemColors(),
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(Routes.HOME) {
                val vm: HomeViewModel = viewModel(
                    factory = viewModelFactory { HomeViewModel(container.repository) }
                )
                val state by vm.state.collectAsStateWithLifecycleCompat()
                val continueWatching by vm.continueWatching.collectAsStateWithLifecycleCompat()
                HomeScreen(
                    state = state,
                    continueWatching = continueWatching,
                    onMovieClick = { navController.navigateDetail(it.slug) },
                    onResume = { navController.navigateDetail(it.slug) },
                    onBrowse = { path, title -> navController.navigateBrowse(path, title) },
                    onSearch = { navigateTab(navController, Routes.SEARCH) },
                    onRefresh = vm::refresh,
                )
            }

            composable(Routes.SEARCH) {
                val vm: SearchViewModel = viewModel(
                    factory = viewModelFactory { SearchViewModel(container.repository) }
                )
                val state by vm.state.collectAsStateWithLifecycleCompat()
                SearchScreen(
                    state = state,
                    onQueryChange = vm::onQueryChange,
                    onSubmit = vm::submit,
                    onMovieClick = { navController.navigateDetail(it.slug) },
                    onLoadMore = vm::loadMore,
                )
            }

            composable(Routes.LIBRARY) {
                val vm: LibraryViewModel = viewModel(
                    factory = viewModelFactory { LibraryViewModel(container.repository) }
                )
                val state by vm.state.collectAsStateWithLifecycleCompat()
                LibraryScreen(
                    state = state,
                    onOpen = { navController.navigateDetail(it) },
                    onRemoveHistory = vm::removeHistory,
                    onRemoveFavorite = vm::removeFavorite,
                    onClearHistory = vm::clearHistory,
                )
            }

            composable(
                route = Routes.DETAIL,
                arguments = listOf(navArgument("slug") { type = NavType.StringType }),
            ) { entry ->
                val slug = entry.arguments?.getString("slug").orEmpty()
                val vm: DetailViewModel = viewModel(
                    key = "detail:$slug",
                    factory = viewModelFactory { DetailViewModel(container.repository, slug) },
                )
                val state by vm.state.collectAsStateWithLifecycleCompat()
                val favorite by vm.isFavorite.collectAsStateWithLifecycleCompat()
                DetailScreen(
                    state = state,
                    isFavorite = favorite,
                    onBack = { navController.popBackStack() },
                    onPlay = { detail, index ->
                        navController.navigate("player/${Uri.encode(detail.slug)}?source=$index")
                    },
                    onMovieClick = { navController.navigateDetail(it.slug) },
                    onToggleFavorite = vm::toggleFavorite,
                    onRetry = { vm.load(forceRefresh = true) },
                    onRemoveHistory = vm::removeFromHistory,
                )
            }

            composable(
                route = Routes.BROWSE,
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType },
                ),
            ) { entry ->
                val path = entry.arguments?.getString("path") ?: "/latest/"
                val title = entry.arguments?.getString("title") ?: "Browse"
                val vm: BrowseViewModel = viewModel(
                    key = "browse:$path",
                    factory = viewModelFactory { BrowseViewModel(container.repository, path, title) },
                )
                val state by vm.state.collectAsStateWithLifecycleCompat()
                BrowseScreen(
                    state = state,
                    onBack = { navController.popBackStack() },
                    onMovieClick = { navController.navigateDetail(it.slug) },
                    onSelect = vm::loadFirst,
                    onLoadMore = vm::loadMore,
                    onRetry = vm::retry,
                )
            }

            composable(
                route = Routes.PLAYER,
                arguments = listOf(
                    navArgument("slug") { type = NavType.StringType },
                    navArgument("source") {
                        type = NavType.IntType
                        defaultValue = 0
                    },
                ),
            ) { entry ->
                val slug = entry.arguments?.getString("slug").orEmpty()
                val source = entry.arguments?.getInt("source") ?: 0
                val vm: PlayerViewModel = viewModel(
                    key = "player:$slug:$source",
                    factory = viewModelFactory {
                        PlayerViewModel(
                            context = context,
                            repository = container.repository,
                            okHttpClient = container.client.playerHttpClient(),
                            appScope = container.appScope,
                            slug = slug,
                            initialSourceIndex = source,
                        )
                    },
                )
                PlayerScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun navItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Primary,
    selectedTextColor = Primary,
    indicatorColor = Primary.copy(alpha = 0.16f),
    unselectedIconColor = TextSecondary,
    unselectedTextColor = TextSecondary,
)

private fun navigateTab(navController: NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavHostController.navigateDetail(slug: String) {
    navigate("detail/${Uri.encode(slug)}")
}

private fun NavHostController.navigateBrowse(path: String, title: String) {
    navigate("browse?path=${Uri.encode(path)}&title=${Uri.encode(title)}")
}

/**
 * collectAsStateWithLifecycle is the recommended API. Kept behind a tiny helper
 * so the import surface stays in one place.
 */
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateWithLifecycleCompat():
    androidx.compose.runtime.State<T> = collectAsStateWithLifecycle()
