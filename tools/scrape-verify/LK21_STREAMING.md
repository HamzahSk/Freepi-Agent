# Recursive LK21 player extraction and loading investigation

Verified November 27, 2026 (UTC). This report distinguishes supplied captures,
accessible live responses, offline browser fixtures, and JVM transport tests.
**No live movie manifest or successful live playback is claimed.**

## 1. Exact streaming root cause

At the starting revision (`9d46336a7b20a77c93a0e6104d33a5c43e5276e3`),
`PlayerExtraction` already performed the popup POST and followed `embedUrl`.
The remaining failure was downstream:

- Capture `[150]` has `#vstr` and dependency scripts, but no media manifest.
- The accessible `js/init_player.js?t=1.5.5` derives the slug from the page's
  pathname and sends `GET /verify/<encoded-slug>` using XMLHttpRequest.
- A successful JSON response must have `status: "success"` and `fileUrl`.
  The script passes `fileUrl`, title and poster to `PlayerCore.init`.
- That initialization creates a custom play button. Its click handler calls
  `PlayerUI.setupPlayer`, which supplies `sources: [{ file: fileUrl, type: "hls" }]`
  to `jwplayer("vstr").setup(...)`.
- The previous observer only inspected DOM video elements, JWPlayer playlists,
  and literal/encoded inline scripts. It missed JSON metadata arriving before
  the custom button was clicked. No video or JWPlayer playlist existed yet.
- Browser observation was restricted to one provider origin, excluding deeper
  cross-origin player frames. A nested HTTP error also aborted sibling traversal.
  Each failing layer could restart the original browser tree. Finally, the
  production composite fallback repeated the same HTTP traversal after failure.

The library names `hls-0.14.17.js`, `p2p-jwplayer.hlsjs.min.js`,
`p2p-media-loader-core.min.js`, `p2p-media-loader-hlsjs.min.js`, and `jwplayer.js`
are dependencies, never media sources.

## 2. Verified loading sequence and evidence boundary

1. **Original catalog URL:** captured movie detail `[62]`, with `iframe#main-player`.
2. **Original popup player URL:** `/iframe3/<host>/<id>`. Capture `[84]` is the
   Hydrax popup, whose script documents the POST contract. Request `[148]`
   separately verifies the P2P popup's POST context and form values.
3. **API response:** POST to the popup origin's `/api.php` with URL-encoded
   `host`/`id` and the popup Referer. Response `[148]` contains a JSON `embedUrl`
   on `playcdn.de`, not media.
4. **Provider embed URL:** capture `[150]` is that playcdn HTML. It contains
   `#vstr`, CSS, the five libraries above, and `init_player.js`.
   Both popup and provider require real iframe ancestry; loading them as a top
   level page redirects elsewhere. CSP also constrains embedding origins.
5. **Player configuration:** the accessible initialization script requests
   `/verify/<slug>`. Its source proves the request and response contract.
6. **Media metadata:** `fileUrl` becomes available in the successful verification
   response, before JWPlayer setup. The offline browser replay confirms this
   ordering using the actual initialization script and synthetic response data.
7. **Actual media manifest:** the app validates `fileUrl` with a bounded HTTP
   request; only a recognized manifest/container can become `ResolvedStream.url`.
8. **Playback and segments:** Media3 subsequently loads the manifest/representations
   and individual segments. Segment URLs are never returned as standalone streams.

Steps 1–4 are captured traffic; steps 5–6 are accessible source plus offline runtime
verification. A fresh live Chromium catalog navigation encountered a Cloudflare
challenge. Investigation stopped without clicks or challenge bypass. Therefore
**the live `/verify/` body, live manifest URL, live subtitles and playback remain
unverified**. No live `/verify/` body is fabricated in production.

Chromium independently fetched the live initialization script: HTTP 200,
`application/javascript`, 13,913 bytes, response observed at 46 ms (104 ms total
probe). SHA-256:
`e1025f6796125ddc5bdf1bc6bcbfc6561b3cdce62624011ca0bb8dbec0533a6b`.
It matched the fixture byte for byte. That script does not declare subtitle tracks
in its setup configuration; the verification response could contain more data,
but no live subtitle declaration was established.

A custom play-button gesture is required for this script's JWPlayer setup.
Metadata retrieval happens earlier and does not require that gesture in the
replay. Neither the real advertising anchor nor the real play button was clicked.
Existing gesture tests click a button belonging solely to a synthetic test page.

## 3. Implementation and validated formats

`PlayerExtractionOptions` controls nesting depth (default 6, popup depth zero),
page budget (16), relevant initialization resources per page (4), per-exchange
layer deadline (15 seconds), and browser deadline (30 seconds).

The reusable pipeline now:

- Tracks visited normalized/fragment-free URLs, preserving signed query escaping.
- Follows player iframe/embed/object references and JSON embed references, not links.
- Isolates unavailable/timed-out siblings, propagating caller cancellation and
  access blocks immediately.
- Deduplicates source validation by URL and Referer and memoizes successful
  exchanges within one resolution. Signed stream results are not cached long term.
- Inspects only selected initialization/config scripts through HTTP. It does not
  recursively download every dependency or execute DOM programs in Rhino.
- Parses HTML, JSON object/array playlists, inline/encoded data, and subtitle data.
  Rhino remains responsible for suitable quoted/encoded JavaScript data.
- Runs at most one browser observation of the original catalog → popup → provider
  ancestry per initial-source attempt. The former redundant production HTTP
  fallback is removed because it is already part of the same pipeline.
- Activates observers along the selected player frame tree, including different
  origins. Passive XHR load listeners and bounded fetch-response clones inspect
  completed JSON without changing requests, return values or response bodies.
- Preserves relative bases, final redirect URLs, Referer, Origin, user agent and
  legitimately issued cookies. Cookies are scoped per request origin using the
  shared HTTP client, rather than a global playback Cookie header.

Validated by fixtures/transport tests: **HLS**, **DASH**, **MP4**, and **WebM**,
including extensionless metadata candidates. MP4-family/M4V/MOV signatures and
QuickTime MIME are supported. No format is claimed as a live LK21 extraction.

HLS requires `#EXTM3U`, a media/variant declaration and a URI. DASH requires an
MPD root and a representation. Progressive media requires a recognized container
signature and video/octet-stream content type. HTML, empty manifests, query-only
filenames, script filenames and `.ts`/`.m4s`/`.aac`/`.m4a` segments cannot qualify.
Validation inspects at most 512 KiB. This establishes recognizable metadata;
it cannot prove availability/decodability of every future segment.

Subtitles retain URL, label and language from DOM, JS, JSON and runtime playlists,
including implicit JWPlayer track kinds, HLS subtitle renditions, and external
DASH text files. HLS/DASH subtitle manifests remain metadata and are selected
through Media3's parent manifest, not mislabeled as standalone VTT files.
External SRT/VTT/TTML playback configurations remain supported.

## 4. Performance bottlenecks and changes

- `WebViewCatalogDataSource.detail` used `return@repeat` in `repeat(3)`. A successful
  details page was loaded/mapped three times. A bounded loop now exits on success.
- Home/list/details/search previously used the serialized hidden browser first,
  paying full document execution and subresource cost for static catalog HTML.
  The existing HTTP/Jsoup parser is now primary; WebView remains the fallback for
  unsupported dynamic layouts. Recognized no-results pages do not imply a failure.
- Catalog parsing and JS asset preparation now run off the UI thread. Existing
  native Compose screens, Media3, Rhino and Room remain intact.
- Repository model TTL caches remain (home 10 min, lists/search 5 min, details
  15 min). Search gains caching, and model/HTML requests share in-flight work.
- Reference-counted in-flight work lets one screen leave without cancelling
  another consumer; the last departing consumer cancels the HTTP body read.
  ViewModels cancel superseded page/search/refresh operations and rethrow
  coroutine cancellation instead of displaying stale error/results.
- One pooled WebView is reused under the existing mutex. Cleanup stops navigation,
  removes the document-start script and old bridge/handlers, and clears history;
  crashes/close destroy the instance. Per-request IDs reject stale callbacks.
- HTTP clients/connections/caches/cookies are shared. Catalog concurrency is
  bounded at four; OkHttp dispatch is bounded at 12 overall/four per host.
  Playback shares the pool but has no whole-call deadline, so long progressive
  playback is not cut off by a scraper deadline.
- Safe image/font resources and known telemetry hosts are skipped by the browser
  resource policy. CSS, API/verification calls, player JS, manifests and security
  resources are retained. Explicit refresh bypasses browser/HTTP caches.
- The working catalog host is tried first. Access challenges stop immediately,
  including search fallback; they are never retried through another mirror.

## 5–6. Measured request counts and timings

These are **single-run controlled fixture measurements**, not Android device or
live-network speed claims. The local response delay for the catalog transport
benchmark is 80 ms. The repeat-load baseline reproduces the previous three-call
loop at the HTTP/parser boundary; it is not a measurement of three Android
WebView instances. The concurrent test measures the HTTP in-flight sharing used
by the app. Browser timings include browser document initialization and polling.

| Scenario | Before/reference | After | Request evidence |
|---|---|---|---|
| Captured playcdn initialization in Chromium | No media after 20,497 ms; verify response at 662 ms | Candidate in 508 ms; verify response at 343 ms | 16 browser requests → 16; **0 eliminated** in this observation benchmark |
| Deeper cross-origin player + fetch JSON | Previously excluded by origin filter | Candidate + English subtitle in 702 ms | 8 browser requests in this separate fixture |
| Three successful detail loads at transport/parser boundary | 3 requests, 343 ms | Single shared request measured below | **2 repeated loads/requests eliminated in this controlled reproduction** |
| Eight simultaneous consumers of one captured detail | Unshared reference would require 8 requests | 1 request, 179 ms | **7 duplicate requests avoided**, distinct scenario from the previous row |
| Subsequent cached detail | A fresh request would be needed without cache | 0 additional requests, 21 ms | Fixture uses HTML cache and still parses; repository model cache also exists |

The stream comparison measures **candidate observation**, with an unsuccessful
baseline, not a speedup between two successful full extractions. Downstream
browser media/config data is synthetic. Actual HTTP manifest validation is tested
separately. Production WebView startup/reuse savings and the number of production
tracking/image requests skipped were **not measured**, so no numeric claim is
made. HTTP-to-browser handoff deliberately reloads the real ancestry when needed;
those remaining exchanges are not presented as eliminated requests.

## 7. Diagnostics and verification

Stage logs:

`INITIAL_PAGE → API_RESPONSE → EMBED_URL → PROVIDER_PAGE → PLAYER_CONFIG → MEDIA_METADATA → VALIDATION`

They include stage/total elapsed time, request duration, HTML/config parsing
measurements, logical HTTP exchange counts, cache hits/misses, nesting depth,
validation results and overall success/failure. Browser cleanup logs attempted
and skipped resource counts. Logical exchange counts do not count every redirect
hop as an independent call. Per-response logs distinguish disk-cache use. Catalog
model cache hits and parser duration are recorded. URLs in production/live
reports are reduced to origins; path slugs, queries, credentials, cookies,
provider console contents and response bodies are omitted.

Final verification:

- `./gradlew testDebugUnitTest`: **PASS**, 80 tests, 0 failures/errors/skips;
  final run 6 seconds (Gradle cache enabled).
- `./gradlew assembleDebug`: **PASS**, 1 second (Gradle cache enabled).
- Existing Chromium catalog fixtures: home/list/detail/embed, **4/4 PASS**.
- Existing delayed/gesture initialization fixtures: **2/2 PASS**.
- New Chromium replay: old failure reproduced; new captured-init and deeper
  cross-origin JSON scenarios **PASS**.
- `git diff --check`: **PASS**.

Coverage includes supplied popup/API/playcdn HTML, form encoding and ancestry,
relevant initialization resources, dynamic metadata, HLS/DASH/progressive
validation, subtitles, depth/page limits, cycles/fragments, duplicate requests,
failed siblings, timeout, cancellation, cache behavior, redirect bases, cookies,
resource policy and redaction. Reusing an Android WebView across sessions still
requires device/instrumentation validation; desktop Chromium cannot prove that
lifecycle behavior.

Reproduce from the repository root (installed Chromium at `/usr/bin/chromium`):

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
npm ci --prefix tools/scrape-verify
node tools/scrape-verify/run.mjs fixtures
node tools/scrape-verify/lk21-fixtures.mjs
# Optional baseline comparison against the exact starting observer:
git show 9d46336a7b20a77c93a0e6104d33a5c43e5276e3:app/src/main/assets/scrape/stream.js > /tmp/lk21-before.js
node tools/scrape-verify/recursive-player.mjs /tmp/lk21-before.js
# Passive live probes; stop if access is challenged:
node tools/scrape-verify/lk21-live.mjs 'https://playcdn.de/js/init_player.js?t=1.5.5'
node tools/scrape-verify/lk21-live.mjs 'https://tv13.lk21official.cc/slightest-touch-2026/'
```

New replay measurements are saved under ignored `tools/scrape-verify/out/`.
No capture is used as production response data.

## 8. Remaining limitations

- Live catalog access was challenged; live verification JSON, media formats,
  subtitle URLs, segment availability and actual playback are unconfirmed.
- The accessible init script and offline replay establish behavior, not a guarantee
  that every live provider variant uses the same contract. Unknown initialization
  formats and resources outside the targeted HTTP allowlist require browser runtime
  observation or future parser support.
- Hidden extraction does not synthesize gestures, remove ad overlays, follow ads,
  decrypt protected media, or bypass anti-bot/DRM/access restrictions. Metadata
  obtainable only after such actions fails with a stage error.
- Browser execution requires Android WebView document-start script support.
- Pool lifecycle, Compose navigation cancellation and actual device loading times
  need an Android device/emulator run. No instrumented run was available here.
- Browser fixtures use synthetic origins/metadata and stub dependency libraries;
  they test the real init script's pre-JWPlayer exchange without exercising real
  JWPlayer decoding, production CSP admission, peer-to-peer transfers or live media.
- Response inspection, traversal and timeouts are deliberately bounded. Very large
  configs/manifests or providers exceeding the budget can fail rather than produce
  an unvalidated stream. JS-created blob/MediaSource URLs are not portable media URLs.
