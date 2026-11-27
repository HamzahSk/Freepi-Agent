These two fixtures were fetched without authentication on 2026-11-27 UTC:

- `live-api.json`: POST https://videonode.de/api.php using the host/id dynamically
  found in the original `[84]` popup capture, with that popup as Referer.
- `live-provider.html`: GET of the actual returned embedUrl, with popup Referer.

They are actual response bodies, not invented API or media responses. Neither
contains a confirmed media manifest. Tests also consume the original popup from
`lk21-request/` directly. Synthetic downstream media data lives in test code and
is explicitly identified there; captured provider identifiers are never production
constants.

`playcdn-init-2026-11-27.js` was fetched from the publicly accessible
`https://playcdn.de/js/init_player.js?t=1.5.5` on November 27, 2026. Chromium
independently returned HTTP 200 and the same 13,913 bytes. SHA-256:
`e1025f6796125ddc5bdf1bc6bcbfc6561b3cdce62624011ca0bb8dbec0533a6b`.
The recursive Chromium harness executes this unmodified script against synthetic
verification data, with dependency libraries stubbed. It does not click the
captured ad anchor or custom play button and does not prove live playback.
Original `[148]` API and `[150]` playcdn responses in `lk21-request/` are now also
consumed directly by tests; the production pipeline never imports fixtures.
