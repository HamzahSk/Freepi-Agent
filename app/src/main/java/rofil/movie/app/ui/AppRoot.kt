package rofil.movie.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import rofil.movie.app.ui.screens.BrowseScreen
import rofil.movie.app.ui.screens.DetailScreen
import rofil.movie.app.ui.screens.HomeScreen
import rofil.movie.app.ui.screens.LibraryScreen
import rofil.movie.app.ui.screens.PlayerScreen
import rofil.movie.app.ui.screens.SearchScreen
import rofil.movie.app.ui.screens.RemoteSourceOption
import rofil.movie.app.ui.screens.SettingsScreen
import rofil.movie.app.data.PlaybackPreferences
import rofil.movie.app.data.SourceSelection
import rofil.movie.app.ui.theme.Background
import rofil.movie.app.ui.theme.Primary
import rofil.movie.app.ui.theme.TextSecondary
import rofil.movie.app.ui.viewmodel.BrowseViewModel
import rofil.movie.app.ui.viewmodel.DetailViewModel
import rofil.movie.app.ui.viewmodel.HomeViewModel
import rofil.movie.app.ui.viewmodel.LibraryViewModel
import rofil.movie.app.ui.viewmodel.PlayerViewModel
import rofil.movie.app.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private object Routes {
    const val HOME = "home"
    const val SEARCH = "search"
    const val FAVORITE = "favorite"
    const val SETTINGS = "settings"
    const val DETAIL = "detail/{slug}"
    const val BROWSE = "browse?path={path}&title={title}"
    const val PLAYER = "player/{slug}?source={source}"
}

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val container = rememberContainer()
    val context = LocalContext.current
    var selectedSource by remember(container) { mutableStateOf(container.sourceSelection.selectedId) }
    val playbackPreferences = remember(context) { PlaybackPreferences(context) }
    var autoPlayInline by remember(context) { mutableStateOf(playbackPreferences.autoPlayInline) }
    var remoteSources by remember {
        mutableStateOf(
            listOf(
                RemoteSourceOption(SourceSelection.LK21, "LK21"),
                RemoteSourceOption(SourceSelection.MOVIEBOX, "MovieBox"),
            )
        )
    }
    var manifestStatus by remember { mutableStateOf("Manifest not synced in this session") }
    var syncingManifest by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun syncManifest(force: Boolean = true) {
        if (syncingManifest) return
        scope.launch {
            syncingManifest = true
            runCatching {
                withContext(Dispatchers.IO) { container.moduleStore.refresh(force) }
            }.onSuccess { manifest ->
                remoteSources = manifestSources(manifest)
                manifestStatus = "Synced v${manifest.optString("version", "1")} · ${remoteSources.size} sources"
            }.onFailure { error ->
                manifestStatus = "Using cached registry · ${error.message ?: "sync unavailable"}"
            }
            syncingManifest = false
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in setOf(Routes.HOME, Routes.FAVORITE, Routes.SETTINGS)
    val isPlayer = currentRoute == Routes.PLAYER

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Background,
        contentWindowInsets = if (isPlayer) WindowInsets(0, 0, 0, 0)
        else ScaffoldDefaults.contentWindowInsets,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = Color(0xFF111118), tonalElevation = 8.dp) {
                    NavigationBarItem(
                        selected = currentRoute == Routes.HOME,
                        onClick = { navigateTab(navController, Routes.HOME) },
                        icon = { Icon(Icons.Filled.Home, contentDescription = "Home") },
                        label = { Text("Home") },
                        colors = navItemColors(),
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.FAVORITE,
                        onClick = { navigateTab(navController, Routes.FAVORITE) },
                        icon = { Icon(Icons.Filled.Favorite, contentDescription = "Favorite") },
                        label = { Text("Favorite") },
                        colors = navItemColors(),
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.SETTINGS,
                        onClick = { navigateTab(navController, Routes.SETTINGS) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                        label = { Text("Setting") },
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
                LaunchedEffect(selectedSource) { vm.refresh() }
                HomeScreen(
                    state = state,
                    continueWatching = continueWatching,
                    onMovieClick = { navController.navigateDetail(it.slug) },
                    onResume = { navController.navigateDetail(it.slug) },
                    onBrowse = { path, title -> navController.navigateBrowse(path, title) },
                    onSearch = { navController.navigate(Routes.SEARCH) },
                    onRefresh = vm::refresh,
                    sourceId = selectedSource,
                    sourceOptions = remoteSources.map { it.id to it.name },
                    onSourceSelect = { sourceId ->
                        if (sourceId != selectedSource) {
                            container.repository.selectSource(sourceId)
                            selectedSource = sourceId
                            vm.refresh()
                        }
                    },
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

            composable(Routes.FAVORITE) {
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

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    selectedSource = selectedSource,
                    sources = remoteSources,
                    manifestStatus = manifestStatus,
                    syncing = syncingManifest,
                    autoPlayInline = autoPlayInline,
                    onSourceSelect = { sourceId ->
                        if (sourceId != selectedSource) {
                            container.repository.selectSource(sourceId)
                            selectedSource = sourceId
                        }
                    },
                    onSync = { syncManifest(true) },
                    onClearCache = {
                        container.repository.clearCaches()
                        container.remoteRuntime.reset()
                        container.moduleStore.clear()
                        manifestStatus = "Cache cleared · sync the registry to reconnect"
                    },
                    onAutoPlayChange = {
                        autoPlayInline = it
                        playbackPreferences.autoPlayInline = it
                    },
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
                val inlinePlayer: PlayerViewModel = viewModel(
                    key = "inline-player:$slug",
                    factory = viewModelFactory {
                        PlayerViewModel(
                            context = context,
                            repository = container.repository,
                            okHttpClient = container.client.playerHttpClient(),
                            appScope = container.appScope,
                            slug = slug,
                            initialSourceIndex = 0,
                            autoPlay = autoPlayInline,
                        )
                    },
                )
                DetailScreen(
                    state = state,
                    isFavorite = favorite,
                    onBack = { navController.popBackStack() },
                    onPlay = { detail, index ->
                        navController.navigate("player/${Uri.encode(detail.slug)}?source=$index")
                    },
                    onMovieClick = { navController.navigateDetail(it.slug) },
                    onOpenEpisode = { slug -> navController.navigate("detail/${Uri.encode(slug)}") },
                    onToggleFavorite = vm::toggleFavorite,
                    onRetry = { vm.load(forceRefresh = true) },
                    onRemoveHistory = vm::removeFromHistory,
                    inlinePlayer = inlinePlayer,
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
                            autoPlay = true,
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

private fun manifestSources(manifest: JSONObject): List<RemoteSourceOption> {
    val sources = manifest.optJSONArray("sources") ?: return listOf(
        RemoteSourceOption(SourceSelection.LK21, "LK21"),
        RemoteSourceOption(SourceSelection.MOVIEBOX, "MovieBox"),
    )
    return (0 until sources.length()).mapNotNull { index ->
        val item = sources.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").takeIf { it in SourceSelection.SOURCE_IDS } ?: return@mapNotNull null
        RemoteSourceOption(id, item.optString("name").ifBlank { id.replaceFirstChar(Char::uppercase) })
    }.ifEmpty {
        listOf(
            RemoteSourceOption(SourceSelection.LK21, "LK21"),
            RemoteSourceOption(SourceSelection.MOVIEBOX, "MovieBox"),
        )
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
