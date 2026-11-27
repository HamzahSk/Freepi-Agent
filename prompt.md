# Task: Fix WebView Stream Extraction, Player Metadata & Diagnostics (`com.freepi.lk21`)

Fix why streaming extraction on player pages (such as `videonode.de` -> `playcdn.de` with "Bew Player", `SoTrym()`, `datas`, `init_player.js`, JWPlayer, and HLS/P2P scripts) is not finding playable streams, while preserving existing working functionality and architecture.

## Context & Observed Issues
1. **Intermediate player HTML vs. playable stream**: Initial player pages and Base64 `datas` / `SoTrym()` payloads are initialization documents/configurations, not final HLS/DASH/MP4 manifests.
2. **Bridge & observed media gap in `WebViewScrapeEngine.kt`**:
   - In `ScrapeBridge.postMessage` (`ScrapeKind.STREAM` with `onStreamMetadata != null`), payloads whose `pageUrl` is not normalized/registered in `session.trustedPlayerPages` are dropped silently.
   - `session.observedMedia` captured in `shouldInterceptRequest` is merged into `data.media` in the non-`onStreamMetadata` branch, not before invoking `onStreamMetadata` in the stream branch.
   - `shouldInterceptRequest` only records `observedMedia` when `PlayerMetadata.mime(target) != null`, a `Referer` header is present, and `parent in session.trustedPlayerPages`—which can miss dynamic manifest/playlist responses, cross-frame requests, or requests without a matching `Referer` in `trustedPlayerPages`.
3. **Browser script & initialization lifecycle (`ScrapeScriptLoader.kt` and `assets/scrape/`)**:
   - Inspect `assets/scrape/common.js` and the stream scrape script (`ScrapeKind.STREAM`) to ensure player frames/pages call `playerPage`, report accurate `playerState` / `javascriptError`, observe player initialization/network/configuration after `window.load` / `SoTrym()` / `init_player.js`, and send valid `media` / `subtitles` / `iframes` payloads without treating HTML or Base64 `datas` as a final stream URL.
4. **Stage flow & diagnostics (`PlayerExtraction.kt`, `WebViewStreamResolver.kt`, `ScrapeValidator.kt`)**:
   - Ensure clear stage progression and structured diagnostics across `INITIAL_REQUEST -> PLAYER_HTML -> DEPENDENCY_LOADING -> PLAYER_INITIALIZATION -> PLAYER_METADATA -> STREAM_VALIDATION`.
   - Never log sensitive cookies, tokens, or credentials.
   - Only pass validated, non-DRM playable stream metadata to downstream playback; if no authorized playable source is exposed, fail with a clear stage-aware error rather than returning an HTML document or unverified URL.

## Constraints & Architecture Rules
- Keep Kotlin, Jetpack Compose, existing WebView infrastructure, Rhino (data/expression evaluation only, never DOM execution), Media3 ExoPlayer, Room database, and existing repository/navigation architecture.
- Do not attempt to bypass DRM, authentication, anti-bot protections, or ad restrictions.
- Do not rewrite unrelated working modules or break existing home/catalog/search/detail flows.
- Follow existing loading/UI conventions cleanly without regressing working screens.

## Required Deliverables & Verification
1. Inspect and update the relevant files in `com.freepi.lk21` and `assets/scrape/` cleanly.
2. Add/update unit tests covering stream extraction, metadata/MIME validation, stage diagnostics, and failure reporting.
3. Run:
   - `./gradlew testDebugUnitTest`
   - `./gradlew assembleDebug`
4. Provide a concise report covering:
   - Root cause of WebView extraction failures
   - Changes to request/extraction architecture and scripts
   - Player initialization & stream extraction findings
   - Test and build results
