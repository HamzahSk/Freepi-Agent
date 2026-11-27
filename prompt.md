Task: Fix LK21 Multi-Stage Streaming Extraction

The LK21 streaming parser is still failing. Investigate and fix its multi-stage player loading mechanism using the captured request/response data in "lk21-request/".

1. Analyze the Popup Player Response

One captured response contains:

- Page title: "LK21 - Popup Player Video"
- A JavaScript "DOMContentLoaded" handler.
- An asynchronous POST request to "/api.php".
- POST parameters: "host" and "id".
- A JSON response containing "embedUrl".
- The returned URL is assigned to "iframe#video-iframe".
- An additional user-interaction overlay is present.

This indicates that the initial player page does not necessarily contain the actual video source.

Investigate this mechanism and identify every subsequent request and response involved in loading the provider player.

2. Implement Multi-Stage Extraction

The scraper must understand the complete player-loading sequence:

1. Fetch and inspect the initial player page.
2. Identify the provider host and player ID.
3. Reproduce the documented page request and inspect its JavaScript behavior.
4. Identify the "/api.php" request and its actual JSON response structure.
5. Extract the returned "embedUrl".
6. Inspect the provider iframe and its subsequent loading requests.
7. Identify where actual stream metadata becomes available.
8. Parse available stream and subtitle metadata into structured JSON.

Do not assume that "embedUrl" is the final HLS URL.

3. Handle User-Interaction-Dependent Loading

Investigate whether the provider requires an actual user gesture before initializing playback or requesting stream metadata.

Compare network activity:

- Before interaction.
- Immediately after interaction.
- During player initialization.
- When the actual video source becomes available.

Use Chromium/Playwright for development diagnostics and the existing Android WebView infrastructure when browser execution is required.

Do not simulate ad clicks, follow advertising links, bypass anti-bot protections, or circumvent access restrictions. Only investigate legitimate player initialization behavior.

4. Rhino Integration

Use Rhino for extracting and processing JavaScript-generated metadata where appropriate.

Important:

- Rhino is not a browser and cannot independently execute DOM-dependent code.
- Use WebView when DOM, iframe navigation, or actual browser execution is required.
- Transfer extracted information through the existing JavaScript bridge.
- Normalize the final results into the existing Kotlin stream models.

5. Stream Validation

Support proper identification and validation of accessible stream metadata:

- HLS (".m3u8")
- DASH (".mpd")
- Progressive video sources
- Subtitle URLs and language metadata

Distinguish between:

- Initial player URL.
- Provider embed URL.
- Actual media manifest URL.
- Individual media segments.

Never treat an iframe URL as a confirmed playable stream.

6. Debugging & Tests

Add detailed logs for each stage:

"INITIAL_PAGE → API_RESPONSE → EMBED_URL → PROVIDER_PAGE → PLAYER_INITIALIZATION → STREAM_METADATA"

Use captured files in "lk21-request/" as test fixtures.

Test each stage independently and verify that failures are reported at the correct stage.

Do not hardcode the captured "host", "id", or response data as production values. Extract them dynamically from each player page.

7. Preserve Existing Architecture

- Keep the native Compose interface.
- Keep WebView as an internal extraction engine.
- Keep Rhino for appropriate JavaScript processing.
- Preserve Media3 ExoPlayer integration.
- Do not rewrite working catalog, search, details, or unrelated modules.
- Respect access restrictions and do not bypass advertising, authentication, DRM, or anti-bot mechanisms.

Final Verification

Test the complete extraction pipeline using actual accessible responses.

Run:

"./gradlew testDebugUnitTest"

"./gradlew assembleDebug"

Report the exact root cause, request sequence, extraction results, supported formats, and remaining limitations.

Do not claim successful HLS extraction merely because the initial page or provider iframe loaded successfully.