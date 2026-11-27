Task: Fix Recursive LK21 Stream Extraction & Optimize Scraper Performance

The streaming parser still fails because LK21 uses multiple layers of embedded players. Additionally, application data loading is unnecessarily slow.

Investigate and fix both issues without breaking existing functionality.

1. Understand the Actual Multi-Layer Player Structure

Use the captured responses and requests in "lk21-request/" as the primary reference.

The verified loading sequence so far:

Layer 1: LK21 Popup Player

- Loads the initial player page.
- Sends a POST request to "/api.php".
- Receives JSON containing "embedUrl".

Example:
"{"embedUrl":"https://playcdn.de/...?..."} "

Layer 2: playcdn.de Player

- Loads the returned embed URL.
- Contains "#vstr", a JWPlayer initialization script, and several JavaScript dependencies:
  - "hls-0.14.17.js"
  - "p2p-jwplayer.hlsjs.min.js"
  - "p2p-media-loader-core.min.js"
  - "p2p-media-loader-hlsjs.min.js"
  - "jwplayer.js"
  - "init_player.js"

The actual stream may only become available after the second player initializes.

Do not stop extraction after obtaining "embedUrl".

2. Implement Recursive Player Inspection

Build a reusable, multi-layer player extraction pipeline.

- Follow legitimate player iframe/embed references.
- Inspect each newly discovered player page.
- Detect player initialization scripts and configuration sources.
- Identify dynamically generated media metadata.
- Inspect relevant network responses and player configuration.
- Detect HLS manifests and available subtitle metadata.
- Normalize the final results into the existing Kotlin stream models.

Support additional nested player layers when encountered instead of assuming a fixed depth of two.

Implement:

- Configurable maximum nesting depth.
- Visited URL tracking to prevent loops.
- Duplicate source detection.
- Per-layer timeout and error handling.
- Proper request context and relative URL resolution.

Do not recursively download every linked resource. Only inspect resources relevant to player initialization and media metadata.

3. Investigate JWPlayer Initialization

Inspect "init_player.js" and its actual runtime behavior.

Determine:

- Where player configuration comes from.
- Whether configuration is embedded in HTML or fetched separately.
- Which requests return media metadata.
- When the HLS manifest becomes available.
- How subtitles are declared.
- Whether the player requires an actual user gesture.

Use Chromium/Playwright during development to compare network activity and actual browser behavior.

Use the existing Android WebView infrastructure for browser-dependent extraction.

Do not mistake JavaScript library filenames for actual stream sources.

Do not simulate advertisement clicks, follow advertising links, bypass anti-bot protections, or circumvent access restrictions.

4. Fix Stream Validation

Only return a playable stream when its actual media metadata has been identified and validated.

Support:

- HLS (".m3u8")
- DASH (".mpd")
- Progressive video
- Subtitle URLs and language metadata

Distinguish clearly between:

- Original LK21 player URL.
- Provider embed URL.
- Nested player URL.
- Actual media manifest URL.
- Individual media segments.

Preserve required playback metadata when legitimately available.

5. Optimize Application Loading Performance

Investigate why catalog, details, and other data take too long to load.

Audit the entire request pipeline.

Look for:

- Duplicate HTTP requests.
- Unnecessary JavaScript and CSS downloads.
- Tracking and analytics requests.
- Unused images and resources.
- Repeated page loading.
- Excessive WebView initialization.
- Blocking operations on the main thread.
- Unnecessary parsing and JSON conversions.
- Sequential requests that could safely run concurrently.
- Missing caching.
- Excessive retry delays.
- Requests that continue after the user leaves a screen.

Performance improvements

- Reuse HTTP clients and connections.
- Reuse WebView instances where safe.
- Avoid initializing a new WebView for every extraction.
- Use lightweight HTML parsing whenever browser execution is unnecessary.
- Intercept and skip irrelevant resources where safe.
- Prioritize essential HTML, API responses, and player configuration.
- Cache reusable catalog and metadata responses with appropriate TTL.
- Deduplicate in-flight requests.
- Cancel obsolete requests.
- Use bounded concurrency instead of uncontrolled parallel requests.
- Avoid blocking the UI thread.
- Load nonessential metadata lazily.
- Prevent unnecessary repeated scraping when valid cached data exists.

Do not block essential JavaScript, API requests, player scripts, or other resources required for successful extraction.

6. Improve Logging and Diagnostics

Add timing measurements for each stage:

"INITIAL_PAGE → API_RESPONSE → EMBED_URL → PROVIDER_PAGE → PLAYER_CONFIG → MEDIA_METADATA → VALIDATION"

Record:

- Request duration.
- Parsing duration.
- Number of requests.
- Cache hits and misses.
- Nested player depth.
- Extraction success or failure.

Avoid logging sensitive cookies, tokens, or credentials.

7. Testing

Create tests for:

- The initial LK21 popup player.
- "/api.php" JSON parsing.
- "playcdn.de" player initialization.
- Nested iframe discovery.
- Dynamic player configuration.
- HLS manifest detection.
- Subtitle extraction.
- Recursive depth limits.
- Circular embed references.
- Duplicate requests.
- Timeout and unavailable providers.

Use captured traffic as test fixtures, but never hardcode captured response bodies as production data.

Compare extraction results against actual accessible browser responses.

8. Preserve Existing Functionality

- Keep the native Jetpack Compose UI.
- Keep WebView as an internal extraction engine.
- Keep Rhino for suitable JavaScript processing.
- Preserve Media3 ExoPlayer and Room.
- Do not rewrite working catalog, search, details, or unrelated modules.
- Respect website access restrictions and only use authorized content.

9. Final Verification

Run:

"./gradlew testDebugUnitTest"

"./gradlew assembleDebug"

Provide a report containing:

1. Exact root cause of the multi-layer streaming failure.
2. Complete verified player-loading sequence.
3. Successfully identified stream formats.
4. Performance bottlenecks discovered.
5. Number of unnecessary requests eliminated.
6. Before/after loading and extraction timings.
7. Test and build results.
8. Remaining limitations.

Do not claim performance improvements without measurements. Prioritize correctness first, then optimize without removing resources required for normal operation.