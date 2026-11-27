Task: Deep Scraping Debugging & Browser-Based Reverse Engineering — LK21 and MovieBox

Work across both repositories:

- Main Android repository: "$GITHUB_WORKSPACE"
- Source repository: "$MOVIE_SOURCE_DIR" ("HamzahSk/movie-source", branch "main")

The existing LK21 and MovieBox scrapers still have functional bugs. Perform a thorough investigation and fix the actual scraping implementations, not just their symptoms.

IMPORTANT: Start by simulating the websites using a real Chromium browser. Do not rely solely on reading existing source code.

1. Browser-Based Investigation (Mandatory First Step)

Use Playwright or another available Chromium browser automation tool.

Visit the actual LK21 and MovieBox websites and study how they work.

For each website:

- Inspect the homepage visually.
- Understand the actual page structure and navigation.
- Interact with search fields, buttons, filters, pagination, and content cards.
- Open several movie and series detail pages.
- Observe how the website loads its content.
- Inspect browser DevTools network requests and responses.
- Identify the actual APIs, endpoints, parameters, headers, and response formats.
- Compare browser behavior with the current scraper implementation.

Do not simply inspect HTML or copy endpoint names from existing code.

Study the website as an actual user would, including its visual layout, loading behavior, dynamic content, and interactions.

Save useful request/response fixtures and screenshots when appropriate.

2. Fix LK21 Search

The LK21 search feature is currently broken: search results are missing, incorrect, or do not match the requested query.

Investigate the actual website search behavior using Chromium.

Specifically:

- Identify the correct search URL and request method.
- Inspect how search terms are encoded and submitted.
- Identify required query parameters and headers.
- Understand pagination and any filters.
- Inspect the actual response and HTML structure.
- Determine how movie titles, IDs, posters, and detail URLs are extracted.

Rewrite the scraper logic where necessary.

Expected result: Searching for a movie must return the same relevant results as the actual LK21 website, with correct titles, posters, URLs, and metadata.

Do not return homepage content as search results or use hardcoded sample data.

3. Fix LK21 Hydrax Stream Parser

The Hydrax stream parser is still failing.

Investigate the complete flow using the real "/lk21-request/" fixtures and fresh browser/network inspection.

- Follow the LK21 stream request until the video node PHP response.
- Inspect the actual Hydrax response, including "embedUrl".
- Open and study the corresponding embed page in Chromium.
- Inspect publicly accessible player structures and network responses.
- Identify where the current parser fails.
- Correct URL normalization, response parsing, metadata extraction, and error handling.
- Add fixtures based on verified responses.

Keep the existing architecture:

- "src/lk21/stream.js" handles LK21 requests only up to video node PHP.
- "extractor/p2p/" retains its existing working functionality.
- "extractor/hydrax/" handles Hydrax parsing separately.

Do not mix P2P and Hydrax parsing back into "stream.js".

Do not implement ad bypasses, authentication bypasses, or DRM circumvention. If a provider requires user interaction or restricts access, report that limitation accurately.

4. Fix MovieBox Homepage

MovieBox's homepage scraping currently produces errors.

Do not immediately patch the existing implementation.

First, visit the actual MovieBox website using Chromium and understand how its homepage is constructed.

Investigate:

- Initial page loading and rendering.
- Dynamic content and JavaScript-generated sections.
- Homepage API requests.
- Trending and featured sections.
- Movie and TV series categories.
- Poster and backdrop loading.
- Pagination or lazy loading.
- Required request headers and parameters.
- Differences between server-rendered HTML and dynamically loaded content.

Study both the visual website and its underlying network behavior.

Then compare these findings against the current JavaScript scraper and identify the exact reason the homepage fails.

Implement a proper fix that reproduces the website's actual content structure and response format.

Do not replace the homepage with static data or an incomplete list.

5. Verify Other MovieBox Features

After fixing the homepage, check whether the same underlying problems affect:

- Search.
- Movie details.
- Series details.
- Seasons and episodes.
- Categories and genres.
- Pagination.
- Images and metadata.
- Playback source resolution.
- Subtitles, where supported.

Fix related issues discovered during investigation.

Preserve the existing source interface and ensure all responses match the application's expected schema.

6. Browser-to-Scraper Comparison

For each affected feature, compare three things:

1. What the actual website displays in Chromium.
2. What the website returns through its network requests.
3. What the JavaScript scraper currently produces.

Use these comparisons to verify correctness.

Test multiple search queries and different movie/series pages instead of relying on a single successful example.

7. Android Integration and Regression Testing

Check that the Android application correctly consumes the updated source implementations.

Verify:

- LK21 search results display correctly.
- LK21 Hydrax and P2P extraction use the correct modules.
- MovieBox homepage loads correctly.
- MovieBox search and detail pages work.
- Images and metadata are displayed correctly.
- Loading and error states work properly.
- Existing sources remain unaffected.

Preserve existing diagnostics and error reporting.

8. Verification

Run all relevant JavaScript tests and fixture verification scripts.

Run Android tests and build:

./gradlew testDebugUnitTest
./gradlew assembleDebug

Perform real browser-based verification after implementing fixes.

Do not consider a feature fixed just because the code compiles or a mock fixture passes.

9. Final Report

Provide:

1. Browser investigation findings for LK21 and MovieBox.
2. Root causes of LK21 search failures.
3. Root causes of Hydrax parser failures.
4. Root causes of MovieBox homepage failures.
5. Actual endpoints and request/response structures discovered.
6. Files and modules changed.
7. Browser testing results and screenshots, where available.
8. Automated test and Android build results.
9. Remaining limitations.

Critical Instructions

- Browser simulation is mandatory before implementation.
- Visually inspect the websites, not just their source code.
- Study actual network requests and responses.
- Do not invent endpoints or response structures.
- Fix root causes instead of adding temporary workarounds.
- Preserve working P2P functionality.
- Avoid unrelated refactoring.
- Do not leave TODOs, placeholders, or fake data.
- Complete and verify both LK21 and MovieBox tasks.

Priority: Actual website behavior is the source of truth. The scraper must reproduce what the website genuinely provides, not what the existing implementation assumes it provides.

10. Critical Architecture: Fully Remote Movie Source System

IMPORTANT: All movie source implementations and scraping logic must live exclusively in the "movie-source" repository.

The main Android repository ("rofil.movie.app") must act only as a visual application framework and generic source client.

A. Responsibilities of "movie-source"

All source-related logic must be implemented and maintained in:

"https://github.com/HamzahSk/movie-source"

This includes:

- All source definitions and registrations.
- Homepage scraping.
- Search and pagination.
- Movie and series detail scraping.
- Category and genre parsing.
- Season and episode extraction.
- Stream request handling.
- P2P and Hydrax extractors.
- All other stream parsers and resolvers.
- Subtitle extraction and metadata.
- Response normalization.
- Source-specific request headers and parameters.
- Source-specific error handling.
- Source manifests and version information.

The repository must expose a consistent, versioned interface that the Android application can consume remotely.

B. Responsibilities of "rofil.movie.app"

The main Android repository must remain source-agnostic.

It should only handle:

- UI and visual presentation.
- Generic movie and series models.
- Generic source discovery and registration.
- Remote source manifest loading.
- Downloading and caching source modules.
- Executing source modules through the existing scripting/runtime engine.
- Passing generic requests and arguments to source modules.
- Rendering normalized responses.
- Generic player integration.
- Generic loading, error, and diagnostic states.

Do not implement LK21-specific or MovieBox-specific scraping, parsing, endpoints, or extraction logic inside the Android repository.

C. Dynamic Source Discovery

Implement a mechanism that allows the Android application to discover new sources through the remote manifest.

When a new source is added to "movie-source":

1. Update the remote manifest.
2. Publish the required source modules.
3. The Android application fetches the updated manifest.
4. It discovers the new source automatically.
5. It downloads and caches the required modules.
6. It makes the source available in the application.

Adding a new movie source must NOT require modifying Android source code, adding hardcoded source registrations, or releasing a new APK.

D. Dynamic Extractor and Parser Loading

Extractors and parsers must also be independently managed in "movie-source".

For example:

- LK21 delegates to P2P or Hydrax modules.
- Another source can use a completely different extractor.
- New extractors can be added without modifying the Android application.

Define clear module dependencies and ensure all required modules are available before executing a source.

E. Remote Updates, Caching and Compatibility

Implement:

- Manifest versioning.
- Individual module versioning or content hashes.
- Local module caching.
- Cache invalidation when remote modules change.
- Dependency resolution.
- Retryable download failures.
- Compatibility checks between the app runtime and source modules.
- Safe fallback to previously cached compatible versions when remote updates fail.

Avoid downloading every module repeatedly when nothing has changed.

F. Generic Source Execution Contract

Define a consistent interface for all source modules.

The Android application must not need to know the internal implementation of individual sources.

Source-specific functionality must be exposed through the shared contract, including:

- "home"
- "search"
- "detail"
- "episodes"
- "stream"
- Other supported capabilities declared by the source manifest.

Use the existing project conventions where possible instead of introducing an incompatible interface.

G. Architecture Verification

After implementing the changes, verify this scenario:

1. Add a hypothetical new source directly to "movie-source".
2. Register its modules and dependencies in the remote manifest.
3. Publish the updated repository.
4. Refresh the source manifest from the Android application.
5. Confirm the new source appears without modifying or rebuilding the Android application.
6. Verify its homepage, search, details, and playback through the generic runtime.

This is a critical acceptance test.

Final architectural rule: "movie-source" owns all scraping and extraction intelligence. "rofil.movie.app" owns the presentation and generic execution environment. Adding or updating sources must be possible entirely through "movie-source", without changing the main Android repository.