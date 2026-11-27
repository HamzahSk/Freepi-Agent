# playcdn verification metadata investigation

Investigated November 27, 2026 using repository captures, the captured provider
initialization program, offline Chromium fixtures, and JVM tests. No live
verification response, manifest, or successful movie playback is claimed.

## Findings

The captured `playcdn-init-2026-11-27.js` makes an **XHR**, not a document
navigation, to `/verify/<slug>`. On `status: "success"`, it passes `fileUrl` to
`PlayerCore.init`. This creates `.custom-play-btn`; the button's click handler
subsequently calls `PlayerUI.setupPlayer` with the already received URL. There
is no JWPlayer playlist before that click.

`stream.js` already inspected completed XHR JSON, including `fileUrl`. However,
it required a JSON content type, and `gate()` blocked delivery while the ordinary
custom play control was visible. Consequently a valid observed URL could remain
in the observer's private array without any bridge payload reaching validation.
The original observer fails the new offline custom-play regression.

`WebViewScrapeEngine.shouldInterceptRequest` observes **requests**, not response
bodies. Its `observedMedia` map cannot discover `fileUrl` inside JSON. Previously
an extensionless `/verify/<id>` request could itself enter that map as a supposed
media candidate. The map is merged only when a successful JavaScript stream
payload is delivered; it cannot rescue a payload suppressed by `gate()`.

`PlayerMetadata.configuration` already understands `fileUrl`, resolves relative
URLs against the configuration response URL, and emits media candidates. Native
HTTP redirects ending at JSON use this path too. For observed XHR metadata,
`pageUrl` remains the player document, so manifest validation and playback use
that document as Referer rather than substituting the verification endpoint.

## Changes

- Ordinary `.custom-play-btn` controls no longer imply an advertising/auth gate
  in browser or static parsing. Visible `#overlay`, `#uyeouyeo.visible`, ancestor
  gates, challenges, pending dependencies, and player setup errors still block
  browser delivery. Chromium ignores hidden or zero-size overlays; static HTML
  parsing remains conservative because it cannot evaluate styles or scripts.
- Completed successful verification XHRs are parsed even with a non-JSON content
  type, with the existing 512 KiB text limit. Verification requires the provider's
  `status: "success"` contract. Failed responses and DRM-marked configuration
  nodes are not promoted to unprotected stream candidates. `fetch` stays native.
- `/verify/` request URLs are excluded from `observedMedia`; they are not replayed
  as extensionless streams. Bridge metadata must name a registered player page.
  Network candidates are merged only for that same page, avoiding unrelated
  frame candidates entering another frame's otherwise eligible payload.
- A body rejected by `validate` now retains a `STREAM_VALIDATION` failure with
  a reason such as `json_metadata_not_manifest`, `http_status`,
  `truncated_document`, or `not_media_document`.
- Restored the Gradle wrapper executable bit so the requested commands run.

## Diagnostic sequence

Production diagnostics redact URL paths, queries, cookies, response bodies and
provider text. URL hashes allow resource correlation without exposing URLs.

1. `PLAYER_INITIALIZATION`: `player_page registered`; actual advertising gates
   still report `state=interaction_required`.
2. `PLAYER_METADATA`: `source=verify_request` with trusted-context status;
   `source=verify_response` with HTTP status and observed candidate count.
3. `PLAYER_METADATA`: `metadata_pending` can occur before JWPlayer is ready;
   `metadata_unavailable` distinguishes a completed verification without usable
   metadata from a player that never initialized.
4. `PLAYER_METADATA`: `source=bridge`, candidate/network-merge counts, and
   `invoking onStreamMetadata` confirm entry into the native resolver.
5. `STREAM_VALIDATION`: request/response diagnostics, explicit rejection or
   `success mime=...`, followed by `onStreamMetadata accepted=true/false`.

An `.m3u8` filename or JSON `fileUrl` alone never proves playable media. HLS still
requires a complete successful response with `#EXTM3U`, a variant/media
declaration, and a URI. HTML, JSON, empty/truncated manifests, segments, and
protected manifests remain rejected. Existing DASH `ContentProtection` and HLS
`SAMPLE-AES` checks require an authorized DRM integration. Validation recognizes
manifest/container structure; it does not prove all future segments decode.

The observer does not click controls, alter advertising state or storage,
replay verification requests, invoke provider initialization functions, decode
opaque provider programs, solve challenges, or obtain DRM licenses. The captured
advertising page remains blocked even when its verification response exposes
`fileUrl`.

## Verification

- `./gradlew testDebugUnitTest`: PASS, 96 tests, zero failures/errors/skips.
- `./gradlew assembleDebug`: PASS.
- `./gradlew compileDebugAndroidTestKotlin`: PASS. The new real WebView/bridge
  regression is compiled but not executed: no Android device/emulator attached.
- `node tools/scrape-verify/recursive-player.mjs`: PASS, 16 cases. Covers verification before
  play, mislabeled JSON content type, hidden overlays, failed verification,
  HTTP denial, DRM metadata, advertising/ancestor gates, delayed dependencies,
  native fetch integrity, candidate retry, and nested cross-origin players.
- Running the custom-play regression against the original observer fails at
  the expected success assertion; the patched observer delivers the candidate
  with one verification request, the button untouched, and no JWPlayer setup.
