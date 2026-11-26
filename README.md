# Freepi Stream

A native Android movie/series streaming client built with Kotlin, Jetpack Compose,
Media3 ExoPlayer and Room. The public catalog site is used only as a data source;
all UI, playback, caching, persistence and error handling live in this app.

## Features

- **Home** – featured hero, popular, new releases, recommendations, series and
  genre shortcuts.
- **Browse** – dynamic genre / country / year / quality filters plus ordering
  shortcuts (latest, popular, top rated, by year, series) and paginated grids.
- **Search** – debounced live search with pagination and suggestions.
- **Details** – poster/backdrop, synopsis, ratings, IMDb id, cast, director,
  country, duration, trailer, related titles, provider list and detected subtitles.
- **Native player** – Media3 ExoPlayer with HLS/DASH/progressive support,
  seek bar, ±10 s, playback speed, quality selection, subtitle selection,
  source switching, buffering/error handling, immersive landscape mode.
- **Library** – continue watching, full history, favorites and completed titles.
- **Resume** – exact playback position is saved every ~2 s and on pause/exit,
  then restored automatically (progress ≥ 92 % is marked completed).

## Project layout

```
app/src/main/java/com/freepi/lk21/
  core/                 TTL cache, UI error mapping
  data/
    model/              Immutable catalog/detail/stream models
    remote/             HTTP client, HTML parser, stream resolver, JS unpacker
    local/              Room entities, DAOs, database
    MovieRepository.kt  Single data facade for UI
  player/ (inside viewmodel) Media3 player state/controls
  ui/
    components/         Shared Compose cards, grids, states
    screens/            Home, Browse, Search, Detail, Player, Library
    viewmodel/          Screen state holders
    theme/              Dark cinema theme
  AppRoot.kt            Navigation + bottom bar
  Lk21App.kt            Manual DI container
```

## Data source analysis

The live host (`https://tv13.lk21official.cc/`) is protected by a Cloudflare
managed challenge, so its structure was verified against archived copies of the
same site. Verified endpoints and markup:

| Purpose | Endpoint / selector |
| --- | --- |
| Home catalog | `/` → `section#populer`, `#latest`, `#recomendation`, `#series-unggulan` |
| Cards | `article.widget-item` / `.mega-item` / `.featured-item`, `a[rel=bookmark]`, `.grid-poster img`, `.rating`, `.quality`, `.duration` |
| Pagination | `/page/N/`, `#pagination` (`Halaman X dari Y`), `a.next.page-numbers` |
| Genre/country/year/quality | `/genre/{slug}/`, `/country/{slug}/`, `/year/{year}/`, `/quality/{q}/` |
| Ordering | `/latest/`, `/populer/`, `/rating/`, `/release/`, `/latest-series/` |
| Search | `/search.php?s={query}` |
| Details | `/{slug}/`, `script[type=application/ld+json]` (`@type: Movie`) and `#movie-detail` |
| Providers | `#loadProviders li a` (embed host + quality labels) |
| Subtitles | `.srt` / `.vtt` URLs in detail/provider pages and HLS `EXT-X-MEDIA` |

Provider embeds are resolved natively: the app follows wrapper iframes, scans
`<video>/<source>/file:/sources:` fields, decodes `atob` blobs, unpacks the
classic `eval(function(p,a,c,k,e,d))` JS packer and parses HLS subtitle playlists.
No WebView is used anywhere in the app.

> The app respects the source's access controls. If the host answers with an
> anti-bot challenge or is unreachable, the UI shows a clear retryable error
> instead of pretending to have data. Only publicly listed content is consumed.

## Build

Requirements: JDK 17+, Android SDK 36.

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

`gradle.properties` pins `org.gradle.java.home` to the JDK 17 shipped in the CI
image; change or remove that line for a local Android Studio setup.

## Tests

`app/src/test/.../Lk21ParserTest.kt` exercises card parsing, pagination, filters,
full detail parsing (JSON-LD, metadata, providers, subtitles) against markup
matching the verified site structure. `JsUnpackerTest.kt` verifies the player
packer decoder.

## CI: downloadable APK (arm64-v8a)

`.github/workflows/android-build.yml` builds a **signed, installable APK** on
GitHub Actions and publishes it as a workflow artifact.

- Runs on every push to `main`, on pull requests, on `v*` tags and manually.
- Default target is **`arm64-v8a` only** (modern phones, smaller/faster build).
  Run it manually (`Actions -> Android APK -> Run workflow`) and pick
  `universal`, `armeabi-v7a` or `x86_64` if you need another target.
- Download: `Actions -> <run> -> Artifacts -> freepi-stream-arm64-v8a`
  (file name `freepi-stream-arm64-v8a.apk`). The run summary also shows size
  and SHA-256.
- On a `v*` tag the same APK is attached to the GitHub Release.

Sideload with `adb install -r freepi-stream-arm64-v8a.apk`, or copy it to the
phone and allow "install unknown apps" for your file manager.

Without signing secrets the release APK is signed with the runner's debug key,
so every CI run has a **different signature** — uninstall the old build before
installing a new one. To keep one stable signature (in-place updates), add these
repository secrets and the APK will be signed with yours instead:

| secret | value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |
