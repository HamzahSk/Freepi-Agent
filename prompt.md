Task: Fix and Rebuild LK21 Scraper Using Captured Requests & Responses

The LK21 scraper still has reliability and data extraction issues. Investigate and fix it thoroughly using the captured website traffic stored in the "lk21-request/" directory.

1. Analyze Captured Traffic

First, inspect the entire "lk21-request/" directory.

It contains TXT files with actual HTTP requests and responses captured from the LK21 website.

Use these files as the primary reference to understand:

- Actual request URLs and endpoints.
- HTTP methods, headers, cookies, and query parameters.
- Response formats and content structures.
- HTML and JavaScript-generated data.
- Data relationships between catalog, details, providers, and streaming pages.
- How pagination, search, and filtering work.

Do not assume the website structure based on old selectors or documentation. Derive the implementation from the captured data.

2. Rebuild the Data Extraction Logic

Fix the scraper to correctly extract and normalize real website data.

Cover all available features:

- Homepage and featured catalogs.
- Latest releases and popular titles.
- Search results.
- Categories, genres, and filters.
- Pagination.
- Movie and series details.
- Metadata, posters, ratings, and descriptions.
- Streaming provider information.
- Available stream metadata and subtitles.

Ensure each scraper module uses the correct request parameters and response structure.

3. Rhino Integration

The application uses Rhino for JavaScript-based data extraction.

Review the existing Rhino implementation and fix it where necessary.

- Use Rhino to execute the appropriate extraction scripts.
- Correctly process captured HTML, JavaScript, and structured responses.
- Extract dynamic data where applicable.
- Normalize results into consistent JSON objects.
- Ensure compatibility between Rhino and Android.
- Avoid unnecessary JavaScript engine initialization.
- Handle execution errors and malformed responses safely.

Keep WebView as the browser-rendering layer when actual browser execution is required. Do not confuse Rhino with a full browser environment.

4. Request & Response Handling

Reproduce the relevant captured requests in the scraper implementation.

Verify:

- Required headers and request parameters.
- Redirect handling.
- Response encoding and character sets.
- Content types.
- Cookies and session-dependent responses.
- Empty, malformed, and unexpected responses.

Do not hardcode captured response bodies as application data. They are reference fixtures for understanding and testing the actual extraction logic.

Do not bypass authentication, anti-bot challenges, or other access restrictions.

5. Testing

Create automated tests using the captured TXT request/response files wherever possible.

Test each extraction module against actual captured response samples.

Verify:

- Correct JSON output.
- Required fields are populated.
- Missing fields are handled safely.
- Pagination works correctly.
- Search and filtering produce valid results.
- Details and provider extraction work independently.

Compare the extracted JSON against the original captured responses.

6. Application Integration

Integrate the repaired scraper into the existing repository architecture.

Ensure all native application screens receive correctly structured data.

Preserve existing:

- Jetpack Compose UI.
- WebView scraping infrastructure.
- Rhino integration.
- Media3 player.
- Room database.
- Caching and navigation.

Do not rewrite unrelated modules or introduce mock functionality.

7. Final Verification

Run all relevant unit tests and:

"./gradlew testDebugUnitTest"

"./gradlew assembleDebug"

Provide a report containing:

1. Problems discovered in the old scraper.
2. Findings from "lk21-request/".
3. Actual request/response structures identified.
4. Rhino extraction changes.
5. Modules and endpoints successfully tested.
6. Remaining unsupported or inaccessible data.
7. Build and test results.

Important: The "lk21-request/" directory is the primary source of truth for this task. Thoroughly inspect its contents before modifying the scraper. Do not guess endpoint structures, invent selectors, or implement functionality without validating it against the captured data.