# Freepi Stream — scraping reliability worklog

Structured notes and verification evidence for the "Fix Scraping Reliability &
Implement WebView-Based Data Extraction" change. See the README for the final
architecture overview; this file records what was probed, what was found, and
what remains.

## 1. Live host verification (Playwright + curl)

All probes were run with a plain desktop Chromium 131 + realistic headers.
**No anti-bot protection was bypassed** — the host serves a Cloudflare managed
challenge, and the app reports it as a retryable `AccessBlockedException`.

| Endpoint | HTTP | Response |
| --- | --- | --- |
| `https://tv13.lk21official.cc/` | 403 | `"Tunggu sebentar..."` Cloudflare challenge |
| `https://tv13.lk21official.cc/page/2/` | 403 | challenge |
| `https://tv13.lk21official.cc/genre/action/` | 403 | challenge |
| `https://tv13.lk21official.cc/search.php?s=venom` | 403 | challenge |
| `https://tv.lk21official.cc/` | 302 | → tv13 host |
| `https://tv6.lk21.am.in/` | 301 | → lk21.de |
| `https://web.archive.org/web/2025/https://tv13.lk21official.cc/` | 404 | no snapshot |

Conclusion: the live DOM cannot be scraped from CI and is equally inaccessible
to the Android app right now (the same challenge). Both the WebView engine and
the HTTP client detect the challenge and surface a clear retryable error.

Because the live DOM was unavailable, selectors were rebuilt from the current
public site structure documented by several public scrapers of this exact site
family (e.g. `tv10.lk21official.cc` and `tv12.lk21official.cc` themes:
`.widget[data-type]`, `li.slider`, `#player-list a[data-url]`,
`#watch-history-data`, `.detail p`, `#openNow` redirects, `/?s=` search) **and**
the classic theme the legacy parser already knew (`#movie-detail`,
`#loadProviders`, `#pagination`, `a[rel=bookmark]`, `search.php`). Both shapes
are handled by every extractor.

## 2. Extractors verified in real Chromium (Playwright)

`tools/scrape-verify/run.mjs` runs the *exact* files shipped in
`app/src/main/assets/scrape/` against fixture pages:

| Fixture | Script | Schema | Result |
| --- | --- | --- | --- |
| home.html (both themes: featured slider, `#populer` mega-item, `li.slider` widgets, nav filters, pagination) | home.js | `freepi.home.v1` | OK — 5 sections, filters, ratings/qualities/years |
| list.html (gallery-grid articles, pagination) | list.js | `freepi.list.v1` | OK — 3 cards, page/total/next parsed |
| detail.html (LD+JSON, `#watch-history-data`, `.detail p`, tags, synopsis, `#player-list` with quality labels, subtitles, related) | detail.js | `freepi.detail.v1` | OK — title, year, rating+count, imdb id, WEBDL, duration, cast/directors, 3 sources w/ decoded embeds, subtitle, related |
| embed.html (`<video><source>` + JS config) | stream.js | `freepi.stream.v1` | OK — media URLs + scripts captured |

Asserted contract: home with no cards ⇒ error (never a silent empty rail);
search with zero hits ⇒ allowed (JS `emptyHint`); `javascript:`/`blob:` links
rejected; pagination URLs validated.

## 3. Unit tests

`./gradlew testDebugUnitTest` → **27 tests, 0 failures**:

| Suite | Tests |
| --- | --- |
| `ScrapeValidatorTest` | 14 (schema gate, challenge→AccessBlocked, home non-empty rule, empty-search allowance, invalid URLs, malformed cards skipped, stream requirements) |
| `ScrapeMapperTest` | 8 (home sections, list pagination, full detail mapping, title cleaning, duration formats, redirect preservation) |
| `Lk21ParserTest` (legacy) | 4 |
| `JsUnpackerTest` (legacy) | 1 |

## 4. Build

`./gradlew assembleDebug` → **BUILD SUCCESSFUL**
`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (≈22 MB).

## 5. Root causes found before this change

1. **The old scraper only knew the archived classic theme.** The current site
   family uses a different card/detail/provider markup; several selectors would
   yield zero items. New extractors support both themes.
2. **`/search.php` was hard-coded.** The current theme answers on `/?s=`; search
   now tries both routes.
3. **No challenge handling in WebView-land** (there was no WebView at all): the
   old HTTP path guessed based on page size and marker keywords; the new engine
   detects the challenge via JS (Indonesian/English markers) and the `403/429`
   HTTP callback, and never retries it.
4. **No structured scraping logs** — every stage is now emitted with request id,
   page kind, stage, elapsed time and URL.
5. **Concurrency:** screens could, in principle, race on one scraping channel.
   The engine serialises tasks with a mutex and hands each task a fresh WebView.

## 6. Architecture changes

- `data/scrape/WebViewScrapeEngine.kt` — centralised hidden WebView engine
  (JS bridge, timeouts, 1 controlled retry, redirect handling, navigation/SSL/
  renderer-failure handling, per-task WebView destroy, mutex serialising).
- `assets/scrape/{common,home,list,detail,stream}.js` — DOM extraction code
  (Chromium JS engine; no Rhino needed).
- `data/scrape/ScrapeContracts.kt` / `ScrapeValidator.kt` — `freepi.*.v1` JSON
  schemas + validation gate (UI-independent).
- `data/scrape/ScrapeMapper.kt` — JSON → Kotlin models (UI-independent).
- `data/scrape/WebViewCatalogDataSource.kt` — WebView-backed
  Home/List/Search/Detail source; redirects for series landing pages.
- `data/scrape/WebViewStreamResolver.kt` + `CatalogDataSource.kt` — embed
  resolution in the same hidden WebView with HTTP fallback.
- `MovieRepository` now consumes `CatalogDataSource` + `StreamProvider`; all
  UI layers are untouched and still go through the repository.
- The legacy `Lk21Client`/`Lk21Parser` remain as the WebView-less fallback and
  for HLS subtitle manifests.

## 7. Remaining limitations

- Live data is currently blocked by Cloudflare for every client; the fix can
  only be fully exercised against a mirror/snapshot or after the challenge
  clears. Selectors are verified against current-theme fixtures and public
  scraper docs, not the live DOM.
- Hidden WebView rendering depends on Android System WebView (checked at
  runtime; the HTTP fallback covers missing WebView).
- The stream resolver inspects DOM/scripts after render; providers that ask for
  interactive captchas or user interaction are not resolvable (and are not
  bypassed).
- Mobile vs desktop server variants may place cards in slightly different
  containers; the fallback card scans (`a[rel=bookmark]`, loose article scan)
  cover the known shapes.