# rofil.movie.app

`rofil.movie.app` is a native Android catalog and playback client. It presents
normalized data from a remote source registry and keeps source-specific scraping
and extraction code in [`HamzahSk/movie-source`](https://github.com/HamzahSk/movie-source).
The app does not embed a source site's selectors or provider rules in its UI.

## Architecture

The app is built with Kotlin, Jetpack Compose, Material 3, Media3 ExoPlayer,
Room, OkHttp, Coil, and an isolated Android WebView runtime. The main data path
is:

```text
movie-source/manifest.json
        ↓ verified manifest + hash-addressed runtime modules
RemoteSourceRuntime (isolated WebView + native HTTPS bridge)
        ↓ freepi.*.v1 normalized envelopes
ScrapeValidator → ScrapeMapper → MovieRepository
        ↓
Compose screens / Media3 player / Room favorites and watch history
```

`MovieRepository` is the UI's single catalog, detail, stream, cache, and local
library facade. Remote modules are executed in a blank WebView with native
network requests; the site's HTML is never shown as the app UI. The legacy HTML
parser and WebView scraper remain fallback infrastructure for compatible paths.

## User-facing features

- **Home**: featured, popular, latest, recommended, series, genre shortcuts,
  continue-watching, refresh, and an always-visible discovery search action.
- **Favorite**: favorites backed by Room alongside continue-watching, history,
  and completed playback records. Switching remote sources does not erase the
  local library.
- **Setting**: active source selection, manifest/runtime module sync, verified
  cache clearing, inline autoplay preference, and runtime diagnostics guidance.
- **Detail playback**: a YouTube-style inline 16:9 Media3 player starts or
  resolves the selected provider at the top of the detail screen. Provider and
  episode choices stay on the detail screen, while a toggle hides or shows the
  title metadata, synopsis, credits, subtitles, and recommendations. Fullscreen
  playback, resume position, buffering, retry states, quality, subtitles, and
  source switching remain available.
- **Search and Browse**: search is opened from the Home discovery header;
  Browse provides remote filter paths and pagination.
- **Diagnostics**: scraping stages are logged with the `FreepiScrape` tag while
  redacting URLs and page contents (`adb logcat -s FreepiScrape`).

Missing quality, rating, duration, and similar values are omitted from badges
and metadata instead of being rendered as literal `NULL`/`N/A` tags.

## Project structure

```text
app/src/main/java/rofil/movie/app/
  MovieApp.kt, MainActivity.kt       application container and Compose entrypoint
  core/                              cache and UI error helpers
  data/
    local/                           Room database, entities, and DAOs
    model/                           source-neutral catalog/detail/playback models
    remote/                          HTTP/parser/player extraction fallback code
    scrape/                          remote runtime, manifest store, validation, mapping
    MovieRepository.kt               single facade for screens and playback
  extractor/                         native safety adapters for registered providers
  ui/
    AppRoot.kt                       navigation and Home/Favorite/Setting tabs
    components/                      cards, grids, skeletons, and shared states
    screens/                         Home, Browse, Search, Detail, Player, Library, Settings
    viewmodel/                       lifecycle-aware screen and player state
    theme/                           Material 3 dark cinema palette and typography
```

## Build and test

Requirements: JDK 17 and Android SDK 36.

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The default split debug APK is written to
`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (the exact filename is
listed in `output-metadata.json`). Pass `-Pabis=universal` for a universal APK.

## Connection to `movie-source`

The app reads the `main` branch registry from:

```text
https://raw.githubusercontent.com/HamzahSk/movie-source/main/manifest.json
```

`SourceModuleStore` validates the schema/runtime version, source IDs, module
paths, dependencies, and SHA-256 values before committing `active.json` and
module snapshots under the app's files directory. A failed refresh keeps the
last verified snapshot when its modules still hash correctly. `RemoteSourceRuntime`
then loads the module named by the active source's `runtime.module` and calls
the declared operation (`home`, `search`, `list`, `detail`, `episodes`, or
`stream`). The Android layer consumes only the normalized `freepi.home.v1`,
`freepi.list.v1`, `freepi.detail.v1`, `freepi.episodes.v1`, and
`freepi.stream.v1` envelopes.

Changing the active source clears repository catalog caches, and the Setting
screen can explicitly refresh or clear the manifest/module cache. Adding or
updating a source belongs in `movie-source`; the Android client remains a
generic presentation and playback layer.
