# LK21 streaming extraction investigation

Verified on 2026-11-27 UTC. This report distinguishes observed live responses,
original captures, and synthetic downstream test fixtures. No live media extraction
success is claimed.

## Root cause

The captured popup is an asynchronous loader, not a video configuration page.
Its `iframe#video-iframe` initially has an empty `src`. On DOMContentLoaded it
POSTs `host` and `id` to `/api.php`, parses a JSON object, and assigns its
`embedUrl` property to the iframe. The previous HTTP resolver never performed
this exchange. The previous WebView resolver scanned once after page completion,
only inspected the current document, and subsequently opened iframe URLs as top
level pages. Both popup and provider redirect when opened at top level. A Referer
header alone does not supply the iframe ancestry required by those scripts or CSP.
The old resolvers also accepted filenames as playable media without validating
the returned content. The popup includes Cloudflare telemetry, which the old
HTTP helper's broad `challenge-platform` substring check could misclassify as a
challenge even in a successful popup response.

## Observed requests and responses

1. Original capture `[62]` is the movie detail page. Its active `#main-player` is
   a P2P source; Hydrax is a separate selectable source. Do not assume the default
   provider is the selected provider.
2. Original capture `[84]`: GET
   `https://videonode.de/iframe3/hydrax/a9rCpj6LGAx-CefoBJ7rkg`, with the movie
   detail as Referer. HTTP 200, HTML, title `LK21 - Popup Player Video`.
   A fresh HTTP fetch also returned this loader successfully.
3. The popup script documents POST `https://videonode.de/api.php`,
   `Content-Type: application/x-www-form-urlencoded`, body
   `host=hydrax&id=a9rCpj6LGAx-CefoBJ7rkg`. A live POST using the popup Referer
   returned HTTP 200, `application/json`, and this actual object:
   `{"embedUrl":"https:\/\/abyssplayer.com\/g34IONP9K"}`.
   The API response is absent from the original supplied captures; its newly
   fetched body is saved under `app/src/test/resources/lk21/player/live-api.json`.
4. GET `https://abyssplayer.com/g34IONP9K` with the popup Referer returned HTTP
   200 HTML. The actual response is `live-provider.html` beside the API fixture.
   Its title ends in `.mp4`, but the title is not a media URL. It loads JWPlayer
   assets from `iamcdn.net/player/` and `iamcdn.net/player-v2/core.bundle.js`.
   On window load it invokes `SoTrym(JSON.parse(atob(datas)))`; if that function
   is absent it loads `player-v2/lite.bundle.js` first. The encoded JSON contains
   `slug`, `md5_id`, `user_id`, an opaque `media` string, `config`, and `isRaw`.
   The core bundle itself was accessible by HTTP (217,090 bytes). These script
   references and conditional actions are established by the actual HTML;
   successful runtime execution and every later media request are unverified.
5. The popup also references `/script.min.js?v4`. Its full-screen `#uyeouyeo`
   anchor opens an advertising URL. The provider's `#overlay` click/touch handler
   opens two advertising URLs and then removes itself; desktop playback is
   invoked after those interactions. Neither overlay was clicked or removed.
6. Live Chromium navigation to the actual movie detail redirected to `/404.html`
   and displayed `Just a moment...` with a Cloudflare verification frame.
   Investigation stopped there. No challenge tokens were replayed, mirrors tried
   to avoid that restriction, browser checks patched, or advertising links clicked.

The evidence establishes INITIAL_PAGE → API_RESPONSE → EMBED_URL → PROVIDER_PAGE.
The provider HTML identifies the initialization function where runtime player
metadata must next be observed, but does not expose a clear media manifest.
Live post-interaction and initialized-player traffic could not legitimately be
compared. **No actual HLS/DASH/progressive URL or subtitle URL was confirmed.**

## Implementation

`PlayerExtraction` shares a bounded HTTP/API traversal between the HTTP and
WebView resolvers. It extracts each page's host/id and API endpoint, performs an
encoded form POST, parses the actual JSON object using Rhino, follows embed
pages, and parses source/track data. Every stage and failure is logged using:

`INITIAL_PAGE → API_RESPONSE → EMBED_URL → PROVIDER_PAGE → PLAYER_INITIALIZATION → STREAM_METADATA`

Rhino handles JSON, quoted JS values, base64 data, and existing packer unpacking.
It never executes the DOM-dependent page initialization code. Static iframe
traversal is limited to player elements, not page links or location redirects.

When needed, the internal WebView loads the **real catalog parent**, selects the
requested source in its existing `iframe#main-player`, and preserves the browser's
actual iframe ancestry and access restrictions. Document-start observers run in
frames, polling rendered video sources and JWPlayer's runtime playlist/tracks on
the provider origin. They do not patch fetch, timers or provider functions.
Native request observation also collects media candidates from that provider.
The existing bridge transfers JSON to Kotlin. A wrapper cannot immediately
complete extraction with an empty iframe; the top observer allows 20 seconds for
initialization. No synthetic gesture is generated; playback gesture requirements
remain enabled. Challenges propagate without retrying the HTTP fallback.

Candidate validation uses a bounded HTTP range request (up to 512 KiB). HLS must
return `#EXTM3U`; DASH must return an MPD root; progressive sources require video
MIME plus an MP4/MOV or WebM container signature. HTML errors, iframe paths,
query-only filenames and `.ts`/`.m4s`/`.aac` segments do not qualify as media.
Results use the existing `ResolvedStream`/`SubtitleTrack` models, provider labels,
parent Referer and parent Origin. Expiring player results are no longer cached
indefinitely.

Supported metadata: HLS, DASH, MP4/M4V/MOV/WebM; DOM/JS/runtime subtitle tracks
with label/language, HLS `EXT-X-MEDIA` subtitles, and external DASH subtitle files.
Segmented or embedded DASH subtitle tracks remain Media3's responsibility.
Extensionless runtime sources are eligible when the player provides a recognized
media MIME and the HTTP response validates. Manifests and segment URLs remain
separate; manifest validation does not prove that all future segments are
accessible or decodable.

## Verification and reproduction

From the repository root:

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
npm install --prefix tools/scrape-verify --no-package-lock
node tools/scrape-verify/lk21-fixtures.mjs
node tools/scrape-verify/lk21-live.mjs 'https://tv13.lk21official.cc/slightest-touch-2026/'
```

The Chromium scripts use `/usr/bin/chromium` and the existing Playwright package.
The live script records passive network observations to the ignored
`tools/scrape-verify/out/lk21-live/` directory and deliberately performs no clicks.

`./gradlew testDebugUnitTest` passes (61 JVM tests), and
`./gradlew assembleDebug` passes. Both Chromium fixture scenarios pass. New coverage includes dynamic popup parameters, the real API
shape and provider HTML, stage errors, blocked access, encoded form transport,
HTML rejection, manifests, progressive signatures, and subtitle languages/bases.
The full accessible-response replay correctly fails at STREAM_METADATA rather
than manufacturing an HLS URL.

The offline Chromium test replays the original popup verbatim inside a synthetic
catalog parent. Synthetic API/provider responses test delayed iframe loading and
initialization after a legitimate fixture button gesture. It verifies the actual
shipped common/stream scripts in cross-origin frames and checks the bridge JSON.
All other fixture traffic is aborted, so no real advertising or protection
services are contacted. Its `.test` manifest is a candidate used to test browser
observation, not a live HLS extraction result. Actual manifest validation is
covered separately in Kotlin transport/pipeline tests.

## Remaining limits

Live runtime metadata is unverified because the catalog is challenged and the
observed overlays perform advertising actions. The internal hidden WebView
cannot supply a real human gesture; genuinely gesture-dependent or ad-gated
providers fail with a stage error. Opaque provider data is not decrypted outside
its legitimate browser initialization. DRM, authentication and access controls
are not bypassed. WebView document-start support is required for browser fallback;
older engines retain the HTTP stages and report an actionable update error.
Runtime observation targets the identified provider origin; an unknown further
cross-origin runtime player wrapper may require another supported adapter.
WebView-only session credentials are not copied into native media requests;
credential-dependent media may therefore fail validation. Android device execution
was not performed; Chromium verifies the shared JS, not Android WebView itself.
Compose/catalog modules and Media3 playback architecture remain intact.
