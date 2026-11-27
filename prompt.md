# Task: Migrate LK21 JS Scrapers/Extractors to `HamzahSk/movie-source` (`main`) & Refactor App to `rofil.movie.app`

You have two repositories available in the runner environment:
- **Main Android repository (`rofil.movie.app` base)**: `$GITHUB_WORKSPACE` (currently structured around `com.freepi.lk21`, `WebViewScrapeEngine.kt`, `ScrapeValidator.kt`, `ScrapeMapper.kt`, `WebViewStreamResolver.kt`, `PlayerExtraction.kt`, `data/remote/`, `assets/scrape/*.js`, `tools/scrape-verify/`, and unit tests)[span_5](start_span)[span_5](end_span)[span_6](start_span)[span_6](end_span).
- **Public source/extractor repository (`movie-source`)**: `$MOVIE_SOURCE_DIR` (target GitHub repo: `https://github.com/HamzahSk/movie-source.git`, branch: `main`, public repository)[span_7](start_span)[span_7](end_span).

Your goal is to:
1. Move/organize LK21 scraping scripts, selectors/parsers, stream resolver logic, and verification tooling into `$MOVIE_SOURCE_DIR` (`HamzahSk/movie-source` on branch `main`) written in **JavaScript (`.js`)**, structured so additional movie/series sources and stream extractors can be added easily[span_8](start_span)[span_8](end_span)[span_9](start_span)[span_9](end_span).
2. Refactor the Android app in `$GITHUB_WORKSPACE` into a base client under `rofil.movie.app` that fetches/syncs JS source & extractor scripts from `https://raw.githubusercontent.com/HamzahSk/movie-source/main/...` (with local caching and clear retryable error states).

---

## 1. Target Structure for `movie-source` (`$MOVIE_SOURCE_DIR`, branch `main`, JS-based)[span_10](start_span)[span_10](end_span)

Organize `$MOVIE_SOURCE_DIR` using **JavaScript (`.js`)** modules/scripts across a multi-source, multi-extractor layout:

- `src/lk21/` — LK21 JS extraction/parser/resolver scripts (adapted from `assets/scrape/*.js`[span_11](start_span)[span_11](end_span)[span_12](start_span)[span_12](end_span) and LK21 parsing/resolution logic[span_13](start_span)[span_13](end_span)), plus LK21 metadata/config in JS/JSON as needed.
- `src/<other_source>/` (and/or `src/sources/`) — clean extensible layout so additional sources beyond `lk21` can be added as JS modules without modifying core app code.
- `extractor/p2p/` — P2P stream extractor JS scripts/modules.
- `extractor/hydrax/` — Hydrax stream extractor JS scripts/modules.
- `tools/` — JS verification/fixture/test scripts (adapted from `tools/scrape-verify/`[span_14](start_span)[span_14](end_span) and runnable with Node.js where applicable).
- `manifest.json` (at repo root) — index/registry of available sources (`lk21`, future sources) and extractors (`p2p`, `hydrax`, etc.) with paths/URLs on branch `main`, versions, and capabilities so `rofil.movie.app` can load them dynamically.

Ensure no secrets, tokens, or runner-specific absolute paths are committed into `$MOVIE_SOURCE_DIR`.

---

## 2. Refactor Main App into `rofil.movie.app` Base Architecture

In `$GITHUB_WORKSPACE`:
- Refactor package structure, `applicationId` / `namespace`, manifests, DI container, tests, and references to `rofil.movie.app`.
- Keep native Android app responsibilities intact: Jetpack Compose UI, Media3 ExoPlayer playback, Room history/library/resume state, caching, and retryable error handling[span_15](start_span)[span_15](end_span).
- Wire the app's source/extractor loader to fetch JS scripts and `manifest.json` from `https://raw.githubusercontent.com/HamzahSk/movie-source/main/...` (caching fetched JS locally so repeated runs/screens work cleanly, with explicit errors when offline/unreachable/invalid).
- Preserve stage diagnostics (`PLAYER_INITIALIZATION`, `PLAYER_METADATA`, `STREAM_VALIDATION`) and do not include ad-bypass, auth-bypass, or DRM-bypass mechanisms[span_16](start_span)[span_16](end_span).

---

## 3. Verification & Deliverables

1. In `$MOVIE_SOURCE_DIR` (branch `main`):
   - Confirm files are placed cleanly under `src/lk21/`, extensible source layout, `extractor/p2p/`, `extractor/hydrax/`, `tools/`, and `manifest.json` using JavaScript (`.js`) for the source/extractor logic.
   - Run any JS/Node verification or test scripts in `tools/` if present.
   - Leave changes ready in `$MOVIE_SOURCE_DIR` for the workflow push step to `HamzahSk/movie-source` (`main`).
2. In `$GITHUB_WORKSPACE`:
   - Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` and verify they pass.
3. Provide a concise summary covering:
   - JS files/folders created or moved in `HamzahSk/movie-source` (`main`),
   - How `rofil.movie.app` was refactored and connected to `https://raw.githubusercontent.com/HamzahSk/movie-source/main/...`,
   - How to add a new source or extractor in the future,
   - Gradle test/build results and any follow-up notes.
