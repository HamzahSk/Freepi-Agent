# Upstream integration snapshot

Unmodified runtime modules, extension bundles, manifest and JSON fixtures from
https://github.com/HamzahSk/movie-source at the commit recorded in `REVISION`.
SHA-256 is verified against this manifest by JVM tests and the Chromium harness.
These files are test resources only; the production app downloads remote modules.

The Chromium harness uses the existing captured LK21 detail HTML as DOM fixtures
and adds a synthetic search container. MovieBox detail/Nuxt/play responses and
Videasy seed/decryption/media responses are explicitly synthetic. Successful
fixture resolution does not establish live playback.
