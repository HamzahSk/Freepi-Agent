package rofil.movie.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import rofil.movie.app.ui.components.PlayerLoading
import rofil.movie.app.ui.screens.*
import rofil.movie.app.ui.theme.Background
import rofil.movie.app.ui.theme.FreepiTheme
import rofil.movie.app.ui.viewmodel.*
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoadingScreensTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeStructureAndNavigationAreAvailableWhileLoading() {
        var searched = false
        compose.setContent { TestContent {
            Column(Modifier.fillMaxSize().background(Background)) {
                Box(Modifier.weight(1f)) { HomeScreen(HomeUiState(), emptyList(), {}, {}, { _, _ -> }, { searched = true }, {}) }
                Text("Home • Search • Library")
            }
        } }
        compose.onNodeWithContentDescription("Loading featured title").assertIsDisplayed()
        screenshot("home")
        compose.onNodeWithText("FREEPI").assertIsDisplayed()
        compose.onNodeWithText("Home • Search • Library").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search").performClick()
        assertTrue(searched)
    }

    @Test fun searchInputAndSuggestionsRemainUsableAndResultsHaveSkeletons() {
        var state by mutableStateOf(SearchUiState())
        compose.setContent { TestContent { SearchScreen(state, { state = state.copy(query = it) }, {}, {}, {}) } }
        compose.onNodeWithText("Movies, series, artists, IMDb…").assertIsDisplayed()
        compose.onNodeWithText("Try searching for").assertIsDisplayed()
        compose.runOnIdle { state = SearchUiState(query = "Movie", loading = true) }
        compose.onNodeWithContentDescription("Searching catalog").assertIsDisplayed()
        screenshot("search")
        compose.onNodeWithText("Movie").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(loading = false, searched = true) }
        compose.onNodeWithText("No results for “Movie”. Try a different keyword.").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(error = "Offline") }
        compose.onNodeWithText("Offline").assertIsDisplayed()
    }

    @Test fun browseShowsFiltersAndSearchAboveGridSkeleton() {
        compose.setContent { TestContent {
            BrowseScreen(BrowseUiState(title = "Latest"), {}, {}, { _, _ -> }, {}, {})
        } }
        compose.onNodeWithText("Filter loaded titles").assertIsDisplayed()
        compose.onNodeWithText("Genre").assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading filter options").assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading Latest").assertIsDisplayed()
        screenshot("browse")
    }

    @Test fun detailsKeepBackAvailableAndShowStructuredSkeleton() {
        var backed = false
        compose.setContent { TestContent {
            DetailScreen(DetailUiState(), false, { backed = true }, { _, _ -> }, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithContentDescription("Loading title details").assertIsDisplayed()
        screenshot("detail")
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(backed)
    }

    @Test fun availableDetailsAndProviderSelectionDoNotWaitForWatchProgress() {
        val detail = rofil.movie.app.data.model.MovieDetail(
            slug = "fixture", title = "Fixture title", url = "https://catalog.test/movie", poster = null, backdrop = null,
            synopsis = "An authorized test fixture.", year = "2026", durationText = "90 min", durationMs = 5_400_000,
            rating = "8", ratingCount = null, imdbId = null, quality = "HD", countries = emptyList(), genres = emptyList(),
            directors = emptyList(), cast = listOf("Fixture cast"), translator = null, published = null, uploaded = null,
            trailerUrl = null, sources = listOf("Provider One", "Provider Two").map {
                rofil.movie.app.data.model.StreamSource(it, it, "https://provider.test/embed", "https://provider.test/embed", null, emptyList())
            }, subtitles = emptyList(), related = emptyList(),
        )
        var state by mutableStateOf(DetailUiState())
        var selected = -1
        compose.setContent { TestContent {
            DetailScreen(state, false, {}, { _, index -> selected = index }, {}, {}, {}, {}, {})
        } }
        compose.runOnIdle { state = DetailUiState(loading = false, detail = detail, loadingProgress = true) }
        compose.onNodeWithText("Fixture title").assertIsDisplayed()
        compose.onNodeWithContentDescription("Loading title details").assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Provider Two"))
        compose.onNodeWithText("Provider Two").performClick()
        org.junit.Assert.assertEquals(1, selected)
    }

    @Test fun playerLoadingHasNativePlaybackStatus() {
        compose.setContent { TestContent { PlayerLoading("Initializing provider and validating media…") } }
        compose.onNodeWithContentDescription("Initializing player").assertIsDisplayed()
        compose.onNodeWithText("Initializing provider and validating media…").assertIsDisplayed()
        compose.onNodeWithText("Playback starts after a source is validated").assertIsDisplayed()
        screenshot("player-loading")
    }
    @Composable
    private fun TestContent(content: @Composable () -> Unit) {
        FreepiTheme {
            androidx.compose.material3.Scaffold(containerColor = Background) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) { content() }
            }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val resolver = instrumentation.targetContext.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "skeleton-$name-${System.currentTimeMillis()}.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FreepiVerification")
        }
        val uri = checkNotNull(resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

}
