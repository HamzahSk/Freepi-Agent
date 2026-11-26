Task: Fix Scraping Reliability & Implement WebView-Based Data Extraction

https://tv13.lk21official.cc/

Refactor and debug the existing Freepi Stream application's data extraction system.

1. Architecture Requirements

The source website must ONLY act as a data provider. It must never become part of the application's UI.

Implement this architecture:

Website → Android WebView → JavaScript Data Extraction → JSON → Kotlin Repository → Native Application UI.

WebView is strictly an internal scraping/rendering engine.

All application screens, including Home, Catalog, Search, Movie Details, Stream Selection, and Player, must remain fully native.

2. WebView Scraping Engine

Implement a centralized, reusable WebView-based scraping engine.

- Load source pages using Android WebView.
- Support JavaScript execution and DOM inspection.
- Extract actual page content using JavaScript DOM selectors.
- Convert extracted information into structured JSON.
- Transfer JSON safely to Kotlin using JavascriptInterface or an equivalent bridge.
- Handle page loading, JavaScript execution, timeouts, redirects, and navigation failures.
- Properly manage WebView lifecycle and prevent memory leaks.
- Support multiple scraping tasks without conflicting requests.

Use WebView's built-in Chromium JavaScript engine. Rhino is optional and should only be introduced if a specific requirement justifies it.

3. JSON Data Layer

Create consistent JSON schemas for:

- Home catalog.
- Movie and series listings.
- Search results.
- Categories and filters.
- Pagination.
- Movie details and metadata.
- Streaming providers.
- Stream URLs and subtitles.

Validate extracted JSON before passing it to repositories.

Keep parsing, normalization, and data models independent from the UI.

4. Verify Every Endpoint and Page

Audit every existing endpoint and scraping selector.

Use desktop Chromium with Playwright to inspect and verify actual website behavior during development.

Compare:

- Actual browser-rendered DOM.
- Extracted JSON.
- Kotlin model parsing.
- Data displayed in the native application.

Test Home, Catalog, Search, Pagination, Details, Providers, and Subtitles individually.

Identify and fix broken selectors, incomplete responses, failed navigation, and inconsistent data structures.

Respect website access restrictions. Do not bypass anti-bot challenges or access controls.

5. Reliability

- Implement proper loading and completion callbacks.
- Add request timeouts and controlled retries.
- Handle malformed HTML and missing elements.
- Prevent concurrent WebView tasks from interfering with each other.
- Add structured logging for scraping stages and failures.
- Never silently return fake or empty data when scraping fails.
- Provide retryable errors to the native UI.

6. Native Application Integration

Ensure all existing native screens consume data through the repository layer.

No source website HTML or pages should be displayed to users.

Preserve existing navigation, Compose UI, Media3 player, Room database, caching, favorites, and watch history.

7. Final Testing

Verify the complete flow using real accessible website responses.

Run:

"./gradlew testDebugUnitTest"

"./gradlew assembleDebug"

Report:

- Root causes of scraping and connection failures.
- Endpoint verification results.
- Chromium and WebView test results.
- JSON extraction and validation results.
- Changes to the architecture.
- Remaining limitations.
- Build and test results.

Do not rewrite unrelated functionality. Ensure the final implementation is stable and production-ready.