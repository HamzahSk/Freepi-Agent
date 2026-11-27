Task: Refactor LK21 Extractors & Add Complete MovieBox Source

Work across both repositories in the runner environment:

- Main Android repository: "$GITHUB_WORKSPACE"
- Movie source repository: "$MOVIE_SOURCE_DIR" ("https://github.com/HamzahSk/movie-source.git", branch "main")

Implement the following tasks completely while preserving existing functionality.

1. Inspect Existing Files and Architecture

Before making changes:

- Inspect the entire "movie-source" repository.
- Inspect the existing LK21 implementation, especially "src/lk21/stream.js".
- Inspect "/lk21-request/" for real Hydrax request/response examples.
- Locate and thoroughly inspect "moviebox.py", including all scraping logic and dependencies.
- Inspect the Android application's source registration, source loading, playback, and diagnostics implementation.
- Inspect the provided "stream.js" observer implementation if available.

Understand the existing architecture and response contracts before modifying anything.

2. Refactor LK21: Separate P2P and Hydrax

The existing P2P stream extraction is already working. Do not rewrite or break its existing logic. Only separate its parser/extractor into its own dedicated module.

Organize the implementation cleanly:

- "src/lk21/stream.js" — responsible only for LK21 stream requests up to the video node PHP response.
- "extractor/p2p/" — dedicated P2P stream parser/extractor, preserving existing working functionality.
- "extractor/hydrax/" — dedicated Hydrax parser/extractor.

LK21 stream.js

Refactor "stream.js" so its responsibility ends at the video node PHP stage.

It should:

- Perform the existing LK21 stream request flow.
- Capture and resolve the video node PHP response.
- Preserve the actual response payload and relevant metadata.
- Identify or expose the resulting extractor information, such as P2P/Hydrax or "embedUrl".
- Delegate subsequent parsing to the appropriate extractor module.

Do not mix P2P and Hydrax parsing logic inside LK21's "stream.js".

P2P Extractor

- Move the existing working P2P parser into "extractor/p2p/".
- Preserve its current request flow, parsing behavior, and supported stream formats.
- Ensure existing P2P playback continues working exactly as before.
- Only change what is necessary to separate the implementation.

Hydrax Extractor

Use the actual examples inside "/lk21-request/" as the primary reference.

- Inspect all relevant Hydrax requests and responses.
- Handle responses containing "embedUrl".
- Inspect the actual Hydrax embed page and available player structures.
- Implement the necessary parsing and stream metadata handling.
- Discover and verify missing endpoints through fresh network inspection when necessary.
- Handle errors and unsupported responses properly.

Do not invent endpoints or assume response structures without verification. Do not implement authentication bypasses, ad bypasses, or DRM circumvention.

3. Add MovieBox as a New Source

Create a complete MovieBox source inside "movie-source".

Use the existing "moviebox.py" as the primary reference and rewrite its scraping functionality in native JavaScript.

Do not simply translate individual functions. Understand the entire Python scraper, its request flow, parsing logic, dependencies, and response structures before implementing the equivalent JavaScript version.

Preserve all supported functionality, including:

- Homepage and trending content.
- Movie and TV series discovery.
- Search and pagination.
- Movie and series details.
- Genres and categories.
- Seasons and episode listings, where supported.
- Video source and playback URL resolution.
- Subtitle information, where supported.
- Posters, backdrops, thumbnails, and metadata.

Endpoint Discovery and Re-scraping

If "moviebox.py" is incomplete or some endpoints are missing:

- Perform fresh network inspection of publicly accessible MovieBox requests.
- Identify the actual endpoints, HTTP methods, headers, query parameters, and request payloads.
- Inspect real responses and their JSON structures.
- Follow the complete request flow to identify endpoint dependencies.
- Discover dynamically generated parameters or tokens required by legitimate client requests.
- Verify each required endpoint before implementing it.

Do not invent endpoints, hardcode sample responses, or leave incomplete functionality.

4. Follow the Existing Movie Source Architecture

Both LK21 and MovieBox must follow the existing "movie-source" conventions.

Ensure:

- Consistent module exports and source interfaces.
- Consistent response schemas.
- Proper URL normalization.
- Correct handling of nullable and missing fields.
- Consistent error handling.
- Proper separation of requests, parsers, and extractors.
- Correct registration in "manifest.json".

Update relevant "tools/" verification scripts and fixtures for the new architecture and MovieBox source.

Keep the source modules independent and avoid unnecessary duplication.

5. Integrate Everything into rofil.movie.app

Update "$GITHUB_WORKSPACE" to support the refactored LK21 architecture and the new MovieBox source.

LK21 Integration

- Update stream resolution to use the separated P2P and Hydrax extractors.
- LK21 stream observation must stop at the video node PHP response.
- Ensure the app can identify and delegate to the correct extractor.
- Fetch/sync the required extractor modules from "movie-source".
- Preserve local caching and retryable error states.

MovieBox Integration

Register MovieBox as a new selectable source and implement all necessary application wiring:

- Source registration and selection.
- Homepage and content browsing.
- Search and pagination.
- Movie and series details.
- Seasons and episodes.
- Playback integration.
- Subtitle handling, where supported.
- Loading, empty, and retryable error states.

Follow existing Android source contracts and shared UI architecture. Do not introduce unnecessary MovieBox-specific changes to unrelated components.

Diagnostics

Preserve existing diagnostic stages:

- "PLAYER_INITIALIZATION"
- "PLAYER_METADATA"
- "STREAM_VALIDATION"

Ensure failures from LK21 extractors and MovieBox playback are reported correctly without breaking existing diagnostics.

6. Verification and Testing

Movie Source Repository

Verify:

- "src/lk21/" structure.
- "extractor/p2p/" structure.
- "extractor/hydrax/" structure.
- MovieBox source implementation.
- "manifest.json" registrations.
- "tools/" scripts and fixtures.

Run relevant Node.js verification and tests.

Test LK21 P2P against its existing working behavior and Hydrax against the actual "/lk21-request/" examples.

Test MovieBox against real responses or saved fixtures, covering its complete supported scraping flow.

Android Repository

Run:

./gradlew testDebugUnitTest
./gradlew assembleDebug

Fix regressions introduced by these changes.

7. Final Requirements

- Preserve existing working P2P functionality.
- Keep LK21 "stream.js" limited to the video node PHP stage.
- Separate P2P and Hydrax responsibilities completely.
- Implement MovieBox as a complete independent source.
- Verify missing endpoints through actual network inspection.
- Do not break other existing movie sources.
- Do not introduce unrelated refactors.
- Do not leave placeholders, TODO implementations, or hardcoded fake data.
- Keep repositories clean and free of secrets or runner-specific absolute paths.

8. Final Report

Provide a clear summary covering:

1. Existing LK21 architecture and changes made.
2. Findings from "/lk21-request/" and Hydrax implementation.
3. How P2P was separated and how its existing functionality was preserved.
4. MovieBox Python-to-JavaScript implementation and discovered endpoints.
5. New files and manifest registrations.
6. Android integration changes.
7. Test and build results.
8. Any remaining limitations or unsupported functionality.

Important: Complete both tasks. Do not consider the work finished merely because the modules compile. Verify actual request/response flows and ensure the Android application can consume the new source implementations correctly.