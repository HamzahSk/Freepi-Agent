# Task: Re-align LK21 & Stream Extractors (`p2p` vs `hydrax`) in `movie-source` and Update `rofil.movie.app`

Work across both repositories in the runner environment:
- **Main Android repository (`rofil.movie.app`)**: `$GITHUB_WORKSPACE`
- **Public source/extractor repository (`movie-source`)**: `$MOVIE_SOURCE_DIR` (`https://github.com/HamzahSk/movie-source.git`, branch `main`)

---

## 1. Inspect `/lk21-request/` First
- Check the `/lk21-request/` directory in the project (search across `$GITHUB_WORKSPACE` / `$MOVIE_SOURCE_DIR` / workspace root) for sample request and response fixtures for **Hydrax** (including responses returning `{"embedUrl": "..."}`).
- Use the real request/response examples in `/lk21-request/` as ground truth for how LK21 video node PHP responses and Hydrax embed flows look, without inventing unsupported endpoints or bypasses.

---

## 2. Separate `p2p` and `hydrax` Extractors & Scope LK21 `stream.js` up to Video Node PHP
In `$MOVIE_SOURCE_DIR` (branch `main`):
1. **Keep existing working P2P stream support intact**, and separate parser/extractor responsibilities cleanly between `p2p` and `hydrax`:
   - `extractor/p2p/` — JS modules/scripts dedicated to P2P stream parsing/extraction (preserving the existing working P2P logic).
   - `extractor/hydrax/` — JS modules/scripts dedicated to Hydrax handling/extraction based on `/lk21-request/` examples (including handling `embedUrl` responses and embed player structures cleanly without ad-bypass, auth-bypass, or DRM-bypass).
2. **Scope LK21 `stream.js` (`src/lk21/stream.js` or corresponding LK21 stream script)** so its responsibility stops at **video node PHP** (capturing/resolving the video node PHP response/payload and delegating/exposing extractor selection or `embedUrl` / stream metadata cleanly to the app/extractor layer) rather than mixing P2P and Hydrax parsing inside a single monolithic LK21 script.
3. Update `manifest.json` and `tools/` JS verification/fixture scripts so the separated `src/lk21/`, `extractor/p2p/`, and `extractor/hydrax/` modules are accurately registered and tested.

---

## 3. Update `rofil.movie.app` (`$GITHUB_WORKSPACE`)
- Update the Android app (`rofil.movie.app`) stream resolution / source loading / diagnostics wiring so it works with:
  - LK21 stream observation stopping at video node PHP, and
  - Separate `p2p` and `hydrax` extractors fetched/synced from `https://raw.githubusercontent.com/HamzahSk/movie-source/main/...` (with local caching and retryable error states).
- Preserve existing stage diagnostics (`PLAYER_INITIALIZATION`, `PLAYER_METADATA`, `STREAM_VALIDATION`) and do not add ad-bypass, auth-bypass, or DRM-bypass logic.

---

## 4. Verification & Summary
1. In `$MOVIE_SOURCE_DIR` (branch `main`):
   - Verify file layout under `src/lk21/`, `extractor/p2p/`, `extractor/hydrax/`, `tools/`, and `manifest.json`.
   - Run relevant Node/JS verification or test scripts in `tools/` if present.
   - Leave `$MOVIE_SOURCE_DIR` clean with no secrets or runner-specific absolute paths.
2. In `$GITHUB_WORKSPACE`:
   - Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` and verify they pass.
3. Provide a clear summary of:
   - What was found in `/lk21-request/`,
   - How `stream.js` (up to video node PHP), `extractor/p2p/`, and `extractor/hydrax/` were separated,
   - How `rofil.movie.app` was updated,
   - Test/build results and any remaining gaps.
