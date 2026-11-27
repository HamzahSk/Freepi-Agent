# WebView request handling and native loading report

Verified on November 27, 2026. This report distinguishes captured provider documents, synthetic media fixtures, Android WebView execution, and actual playback.

## 1. Root cause and audit findings

The repository contains two distinct provider documents. `lk21-request/[150] response_playcdn.de_message.txt` is titled **Bew Player** and loads HLS/P2P/JWPlayer libraries followed by `init_player.js`. `app/src/test/resources/lk21/player/live-provider.html` is the other capture: it loads JWPlayer and `core.bundle.js`, waits for `window.load`, falls back to `lite.bundle.js`, and calls `SoTrym(JSON.parse(atob(datas)))`. Neither document is a confirmed media manifest. The `datas` field is opaque initialization data, not a final stream URL.

The existing native resolver already checked response contents before producing `ResolvedStream`; it did not blindly pass these captured HTML documents to Media3. The browser fallback had separate problems:

- It replaced `window.fetch`. The SoTrym capture explicitly tests whether `fetch` is native. Changing it changes the provider's environment; this is a compatibility hazard, not proof that it caused the live failure.
- A fixed 20-second observer cutoff was independent of the configured browser timeout. The root could return empty metadata without explaining what happened inside the selected child player.
- The first candidate completed the browser operation and cleaned up the page before native validation. A rejected candidate prevented observation of a later source from the same player.
- JavaScript/dependency failures and child HTTP failures lacked useful state diagnostics.
- Native HTTP downloaded initialization programs that Chromium subsequently had to download and execute again.
- A manifest-looking URL returning HTML was not inspected as an intermediate player document. Conversely, extensionless manifest responses were not recognized directly at discovery.

Preserved and checked: real iframe ancestry, browser redirects, final HTTP response URLs for relative resolution, legitimately issued per-origin cookies, consistent user agents, referer context, TLS verification, document-start injection, cancellation, serialized warm WebView reuse, Room, repository/navigation boundaries, and Media3. A hidden popup advertising element is not treated as an already-active restriction; actual browser visibility is checked during execution.

## 2. Request architecture changes

The main stages now have explicit names:

`INITIAL_REQUEST → PLAYER_HTML → DEPENDENCY_LOADING → PLAYER_INITIALIZATION → PLAYER_METADATA → STREAM_VALIDATION`

`API_RESPONSE` and `EMBED_URL` identify transport substeps. Stages that are unnecessary for a direct media response are skipped.

- Ordinary HTML, documented popup form POSTs, JSON configurations, and bounded media validation use the shared cancellable OkHttp client.
- Browser initialization programs stay in Chromium when browser execution is available. Rhino retains data-expression/string/unpacking work and does not emulate DOM, fetch, player APIs, or SoTrym.
- Within one resolution, responses are shared across discovery and validation by URL/referer, including redirect aliases. Extensionless manifests validate using the response already received.
- A browser handoff executes one selected frame tree. Metadata is validated while WebView is alive. Rejected candidates leave observation running; duplicate candidate URLs are not downloaded repeatedly.
- Browser observation requires completed document loading, propagates ancestor dependency/interaction restrictions, checks public JWPlayer state/playlist APIs and callbacks, DOM media/tracks, completed accessible XHR JSON, and public network media references. `fetch` remains native; private fetch response bodies that are never exposed through public player/DOM/network metadata are an explicit extraction limitation.
- The page performs its own initialization, including `window.load` and the lite fallback. The observer never invokes SoTrym, clicks overlays, removes advertising elements, fabricates gestures, or synthesizes credentials.
- Per-layer HTTP timeout defaults to 15 seconds; browser timeout to 30 seconds; total extraction deadline to 90 seconds. Caller cancellation stops native calls and browser observation. A browser attempt is not repeated through a decoded alias after failure.
- Concurrent identical repository stream resolutions share in-flight work without caching signed results for subsequent playback sessions. Existing catalog caches, single-flight work, connection pools, and bounded HTTP concurrency remain.

Some document/API requests necessarily recur during the HTTP-to-browser handoff: Chromium must execute the original parent/frame ancestry under its real cookies, origin and CSP. HTML is not replayed through `loadData` and security headers are not stripped to avoid those legitimate context-establishing requests. The redundant native initialization-program download was removed.

Player requests retain required resources, images, and advertising/verification resources rather than applying the catalog's image/telemetry filter. Catalog resource filtering is preserved. Non-HTTP navigation and invalid TLS remain blocked.

## 3. Player initialization findings

The captured Bew document redirects when opened at top level and carries a `frame-ancestors` policy. Opening it as a detached top-level page, replacing its HTML, or stripping policy headers is not an equivalent browser context.

The SoTrym document initializes only after complete loading and can asynchronously request its lite bundle. DOM readiness alone does not establish player readiness. Its configuration includes opaque media data, and its overlay implements an advertising/interaction flow.

Offline Chromium tests execute both captured HTML documents unmodified. The accessible Bew entrypoint is also unmodified; missing dependency implementations and verification responses are synthetic test fixtures. The SoTrym test stubs bundles to verify the page's own load event and lite fallback. These tests prove orchestration and restriction handling, not live provider decoding or playback.

An active advertising/interaction gate, including one on an ancestor player frame, now produces an initialization limitation instead of a stream candidate. Dependencies that fail, JavaScript errors, setup errors, incomplete loading, and absent public metadata have distinct failure messages/states. No DRM or advertising access controls were bypassed.

## 4. Stream extraction and diagnostics results

Validated test outputs include HLS, DASH, supported progressive container headers, relative URLs, extensionless manifests, subtitle metadata, redirect bases, and legitimate cookie/referer handling. HTML at a media-looking URL, HTTP errors, empty/malformed manifests, incomplete bounded manifest responses, and individual segments are rejected. Recognized protected DASH/SAMPLE-AES manifests report the need for an authorized DRM integration; this change does not implement license acquisition.

The Android integration test exercises real WebView, the JavaScript bridge, and native HTTP validation together. A public playlist first exposes an HTML response at a manifest URL, then a valid synthetic manifest. The first response is rejected once; the second is validated from the same browser navigation before cleanup.

**No captured provider has been demonstrated to deliver an authorized live playable source. No live Media3 first-frame success is claimed.** Captured-page tests report the remaining interaction/advertising limitation. Synthetic sources are kept in tests and are not production constants.

Diagnostics now include method, status, content type, redirection, loading state, failed resource requests including child resources, console severity/source/line, sanitized JavaScript error type/location, initialization state, rejected validation reasons, cache/request counters, parsing/stage timing, and total duration. URLs redact paths, queries, user info and fragments; stable URL hashes distinguish resources without exposing signed values. Provider error text, response payloads, cookies, credentials and tokens are not logged.

Use `adb logcat -s FreepiScrape` and correlate stage/request IDs, URL hashes and timestamps. Successful WebView resource response headers are not exposed by WebViewClient; full successful status/content-type diagnostics come from native HTTP, while WebView reports request methods and failed resource status/type.

## 5. Measured loading performance

`PlayerPerformanceTest` uses a local MockWebServer with **80 ms simulated body latency**, three warm samples per variant, and the same validated manifest. The before variant replays exactly the removed redundant native initialization-script GET. Chromium execution time is excluded from both variants.

| Controlled native extraction overhead | Before | After |
| --- | ---: | ---: |
| Median duration | 251 ms | 167 ms |
| Native requests | 3 | 2 |

This is an 84 ms reduction in the controlled test, not a live streaming speed claim. Rerun with `./gradlew testDebugUnitTest --tests '*PlayerPerformanceTest' --rerun-tasks`; results are printed in the test XML's `system-out`.

The existing captured-detail performance suite also checks shared concurrent requests, cache reuse, cancellation during body reads, cookies and redirect bases. These pre-existing cache improvements are retained, not attributed to this change. No device frame-time, live-network latency, or playback-start benchmark has been established.

## 6. Native screens and progressive loading

| Screen | Loading/progressive behavior |
| --- | --- |
| Home | Brand/search/refresh controls, reserved 420 dp featured hero, section headings and horizontal 2:3 poster rows; local continue-watching data can render independently. Existing root bottom navigation remains visible. |
| Catalog / Browse | Immediate local search field for loaded titles, working quick/filter controls, independent filter placeholders/error state, adaptive poster/title/metadata grid, and loading-more placeholders. |
| Search | Immediate editable input and static suggestions, result skeletons during the 450 ms debounce and fetch, clear empty/error/retry states, isolated pagination errors. The previous perpetual spinner beside static suggestions is removed. |
| Movie details | Reserved 470 dp backdrop, poster/title/metadata, synopsis, cast/provider/related placeholders and usable back action. Available details render before optional watch progress; its failure is isolated. Cast, providers and related cards already arrive in the same HTML response and are shown together, without invented secondary requests. |
| Player | Native PlayerView remains mounted, with an in-container pulse/status panel. Provider selection becomes available with details. Quality/subtitle/seek controls are disabled until applicable data is available; transport controls do not overlap initialization/errors. Watch progress loads independently of extraction, and a late resume applies only near playback start. |

Shared skeleton groups use a subtle draw-phase pulse, honor disabled Android animators, reserve matching hero/poster/grid dimensions, and use the existing dark palette/rounding/spacing. Image loading retains poster placeholders and existing image crossfades. Skeletons do not add network calls or full-screen blocking overlays. There is no artificial staged delay for fields already returned in one catalog document.

## 7. Verification and limits

- Baseline: 80 JVM tests passed before edits.
- `./gradlew testDebugUnitTest`: **89 passed, 0 failures** after changes.
- `./gradlew assembleDebug`: **passed**; the default build retains the existing arm64 ABI configuration.
- `./gradlew connectedDebugAndroidTest -Pabis=universal`: **7 passed, 0 failures**, Android 15/API 35 x86_64 emulator with System WebView 124. The universal variant is used only to run on that emulator.
- `node tools/scrape-verify/run.mjs fixtures`: **4 passed** (home, list, detail and media fixture).
- `node tools/scrape-verify/recursive-player.mjs`: **9 current-observer scenarios passed**. Supplying a saved baseline observer adds a tenth comparison scenario. Coverage includes both captured initialization flows, delayed external dependencies, native fetch/public JWPlayer metadata, rejected-then-updated metadata, script/JavaScript errors, ancestor gates and nested cross-origin players.
- Compose assertions cover loading screens, usable controls, suggestions, empty/error states, and details/provider selection while optional progress remains pending. Rendered skeleton captures were inspected for structure, spacing, aspect ratios, contrast and clipping.
- `git diff --check`: passed.

The automated flow is fixture-based. A complete live catalog-to-provider-to-Media3 playback session, actual provider authorization, real quality/subtitle renditions, rotation during live playback, and low-end physical-device performance remain unverified. Public metadata and an HTML page loading successfully do not establish those results.
