These two fixtures were fetched without authentication on 2026-11-27 UTC:

- `live-api.json`: POST https://videonode.de/api.php using the host/id dynamically
  found in the original `[84]` popup capture, with that popup as Referer.
- `live-provider.html`: GET of the actual returned embedUrl, with popup Referer.

They are actual response bodies, not invented API or media responses. Neither
contains a confirmed media manifest. Tests also consume the original popup from
`lk21-request/` directly. Synthetic downstream media data lives in test code and
is explicitly identified there; captured provider identifiers are never production
constants.
