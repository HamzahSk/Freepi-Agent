Task: Rework WebView Request Handling, Player Extraction & Native UI/UX

The application still has issues with WebView-based data extraction, especially streaming pages. Additionally, the loading experience is too minimal and needs a proper skeleton UI.

Investigate the existing implementation and improve the architecture without breaking working functionality.

1. Investigate the Current WebView Request Method

Use the latest captured player HTML as a reference.

The response is a "Bew Player" HTML page containing:

- External JWPlayer scripts.
- "core.bundle.js" and "lite.bundle.js".
- Dynamic initialization through "window.load".
- A Base64-encoded "datas" configuration.
- A "SoTrym()" initialization function.
- An overlay and player initialization logic.

This is not an actual HLS manifest. It is an intermediate player document.

Investigate whether the current implementation incorrectly treats the initial HTML response as the final stream data.

Audit:

- WebView navigation lifecycle.
- JavaScript execution timing.
- DOM readiness versus complete page loading.
- External JavaScript dependencies.
- Redirect handling.
- Cookies and legitimate session context.
- Request headers and content types.
- WebView resource interception.
- JavaScript bridge communication.
- WebView destruction and premature cancellation.

2. Rework the Request and Extraction Pipeline

Separate the extraction process into clearly defined stages:

"INITIAL_REQUEST → PLAYER_HTML → DEPENDENCY_LOADING → PLAYER_INITIALIZATION → PLAYER_METADATA → STREAM_VALIDATION"

Requirements:

- Use native HTTP requests for ordinary HTML and JSON endpoints.
- Use WebView only when actual browser execution or DOM interaction is necessary.
- Do not use Rhino as a substitute for browser APIs.
- Wait for the appropriate player initialization state instead of relying only on fixed delays.
- Detect when the page is still loading dependencies.
- Capture relevant JavaScript errors and failed resource requests.
- Avoid repeatedly reloading the same page.
- Handle redirects and relative URLs correctly.
- Apply appropriate timeouts and cancellation.

Use WebView's built-in Chromium JavaScript engine for browser-dependent processing.

Do not execute the same request through both HTTP and WebView unnecessarily.

3. Improve Player Metadata Extraction

Investigate the player configuration and initialization process.

Determine whether the player exposes legitimate media metadata through:

- JWPlayer configuration.
- Player initialization callbacks.
- Documented player APIs.
- Accessible network responses.
- Publicly available manifest references.

Distinguish:

- HTML player documents.
- Embed URLs.
- Player configuration.
- Actual media manifests.
- Individual media segments.

Only pass validated and authorized stream metadata to Media3 ExoPlayer.

Do not assume the Base64 payload is the final stream URL. Do not attempt to bypass DRM, authentication, anti-bot protection, or advertising restrictions.

If the player cannot expose an authorized playable source, report the exact limitation rather than returning an HTML document as a stream.

4. Improve Extraction Diagnostics

Add structured logging for:

- Request URL and method.
- HTTP status and content type.
- Redirect destination.
- Page loading state.
- JavaScript execution errors.
- Failed resource requests.
- Player initialization state.
- Metadata extraction results.
- Total extraction duration.

Never log sensitive cookies, tokens, or credentials.

Make it possible to identify exactly which stage failed.

5. Redesign Loading UI/UX

The current application mostly displays a spinning loading indicator. Replace this with a polished skeleton-loading experience.

Important: The application should display its actual screen structure immediately, even while data is still being fetched.

Implement skeleton states for:

Home

- Featured hero banner skeleton.
- Horizontal movie card placeholders.
- Section headings.
- Poster placeholders.
- Bottom navigation remains visible.

Catalog / Browse

- Search bar.
- Filter controls.
- Movie grid with poster skeletons.
- Title and metadata placeholders.
- Pagination or loading-more indicators.

Search

- Search input displayed immediately.
- Suggestion loading state.
- Search result skeleton cards.
- Empty and error states.

Movie Details

- Backdrop placeholder.
- Poster placeholder.
- Title and metadata skeletons.
- Synopsis placeholder.
- Cast and provider sections.
- Loading states for individual sections.

Streaming / Player

- Native player container.
- Loading animation inside the player.
- Stream provider selection.
- Subtitle and quality controls in appropriate loading states.
- Clear player initialization and error messages.

6. Progressive Data Loading

Do not wait for every request to finish before rendering the screen.

Implement progressive rendering:

1. Render the screen layout immediately.
2. Load essential data first.
3. Populate available sections as soon as their data arrives.
4. Load secondary metadata independently.
5. Keep unavailable sections isolated instead of blocking the entire screen.

Use separate loading states for individual sections.

For example, a movie poster and title should appear as soon as their data is available, even if cast information or provider metadata is still loading.

Avoid unnecessary full-screen loading overlays.

7. Loading Animation and Visual Quality

Improve the visual presentation:

- Use subtle skeleton shimmer or pulse animations.
- Use consistent poster aspect ratios.
- Apply appropriate rounded corners and spacing.
- Avoid excessive animations that consume resources.
- Use smooth transitions from skeleton to actual content.
- Prevent layout shifts when data arrives.
- Respect the existing dark cinema theme.
- Maintain responsive layouts.

The loading state should resemble the final screen, not a collection of unrelated gray boxes.

8. Performance Optimization

Investigate slow loading throughout the application.

- Eliminate duplicate requests.
- Reuse HTTP clients and WebView instances where safe.
- Cache reusable responses.
- Deduplicate in-flight requests.
- Cancel obsolete requests.
- Avoid unnecessary external resource downloads.
- Use bounded concurrency.
- Avoid blocking the main thread.
- Load secondary data lazily.
- Avoid repeatedly initializing JavaScript engines.
- Measure request and parsing durations.

Do not block essential player scripts or required resources.

9. Preserve Existing Architecture

Keep:

- Kotlin.
- Jetpack Compose.
- Existing WebView infrastructure.
- Rhino where appropriate.
- Media3 ExoPlayer.
- Room database.
- Existing repository and navigation architecture.

Do not rewrite unrelated working modules.

10. Final Verification

Test the complete application flow:

- Home catalog.
- Search and suggestions.
- Movie details.
- Provider selection.
- Player initialization.
- Stream metadata validation.
- Loading and error states.

Run:

"./gradlew testDebugUnitTest"

"./gradlew assembleDebug"

Provide a report containing:

1. Root cause of WebView extraction failures.
2. Changes to the request architecture.
3. Player initialization findings.
4. Stream extraction results.
5. Loading performance improvements with before/after measurements.
6. Screens updated with skeleton loading.
7. Test and build results.

Do not claim a player works simply because its HTML page loaded successfully.