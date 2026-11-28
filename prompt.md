# Task: Upgrade Android UI/UX (`rofil.movie.app`) & Create Comprehensive `README.md` Documentation

Work across both repositories in the runner environment:
- **Main Android repository (`rofil.movie.app`)**: `$GITHUB_WORKSPACE`
- **Public source/extractor repository (`movie-source`)**: `$MOVIE_SOURCE_DIR` (`https://github.com/HamzahSk/movie-source.git`, branch `main`)

Preserve the fully remote, source-agnostic architecture: all scraping and extraction intelligence remains in `movie-source`, while `rofil.movie.app` remains a generic, dynamically synced presentation and playback client. Do not hardcode source-specific logic in the Android app.

---

## 1. Upgrade UI/UX in `rofil.movie.app` (`$GITHUB_WORKSPACE`)

Inspect the current Jetpack Compose screens, theme, components, navigation, and viewmodels, then apply a cohesive UI/UX upgrade:

### A. Visual Polish (Replace Flat Appearance)
- Move away from flat, plain dark surfaces by adding subtle tonal elevation, soft gradient scrims over hero/poster artwork, refined hairline borders, layered cards/containers, improved spacing, and clearer typographic hierarchy.
- Clean up raw/empty metadata badges (for example, avoid rendering raw `NULL` tags when quality/rating metadata is missing or null).
- Keep Search easily accessible from the top app bar / discovery header even after updating the bottom navigation.

### B. Bottom Navigation: `Home`, `Favorite`, `Setting`
- Update the bottom navigation bar to three destinations:
  1. **Home** — dynamic catalog/discovery feed synced from the active remote source (`movie-source`).
  2. **Favorite** — saved/favorite items (integrated cleanly with existing Room persistence/library/history without breaking source switching).
  3. **Setting** — app settings & source management (viewing/switching remote sources, syncing/refreshing `manifest.json` & cached JS modules, cache clearing, playback preferences, and runtime/diagnostics status).
- Ensure smooth tab switching, proper back-stack behavior, and seamless synchronization when switching active sources.

### C. Detail Screen: YouTube-Style Inline Video Player + Hideable/Collapsible Info
- Redesign the **Detail screen** so the **video player sits at the top of the screen like YouTube**, starting/resolving playback inline upon entering detail (or selecting an episode/provider) rather than showing only a static poster with a separate Play button.
- Support switching providers/servers and episodes directly on the detail screen and updating the inline player smoothly.
- Keep the **details/info section** (title, metadata chips, synopsis, provider selector, episodes, credits, recommendations) below the player **with a clear toggle/collapse button to hide or show the info** so users can either watch cleanly or inspect details on demand.
- Preserve fullscreen player mode, resume position, loading states, and retryable error states.

---

## 2. Create `README.md` Documentation

Create clean, accurate, well-structured `README.md` files based on the actual codebase (no placeholders or fabricated features):

1. **`$GITHUB_WORKSPACE/README.md` (`rofil.movie.app`)**:
   - Project overview & architecture (source-agnostic Jetpack Compose + Media3 ExoPlayer + Room + remote JS runtime).
   - Key features (dynamic remote source discovery & caching, `Home / Favorite / Setting` navigation, YouTube-style inline detail player with hideable info, stage diagnostics).
   - Project structure overview & how to build/test (`./gradlew testDebugUnitTest`, `./gradlew assembleDebug`).
   - How `rofil.movie.app` connects to `HamzahSk/movie-source` (`manifest.json` sync & caching flow).

2. **`$MOVIE_SOURCE_DIR/README.md` (`HamzahSk/movie-source`)**:
   - Repository purpose & architecture (`src/<source>/`, `extractor/<extractor>/`, `manifest.json`, `tools/`).
   - Generic source execution contract (`home`, `search`, `detail`, `episodes`, `stream`, etc.) and normalized response schema.
   - Currently registered sources & extractors (`lk21`, `moviebox`, `p2p`, `hydrax`, etc.).
   - Step-by-step guide on how to add a new source or stream extractor without modifying the Android app, plus how to run verification scripts in `tools/`.

---

## 3. Verification & Deliverables

1. In `$GITHUB_WORKSPACE`:
   - Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` and verify both pass without errors.
2. In `$MOVIE_SOURCE_DIR` (branch `main`):
   - Verify `README.md` accurately matches the existing files, `manifest.json`, and scripts in `tools/`.
3. Provide a clear final report summarizing:
   - UI/UX improvements made across theme, navigation (`Home / Favorite / Setting`), and the YouTube-style detail screen,
   - Key sections included in both `README.md` files,
   - Gradle test and debug APK build results.
