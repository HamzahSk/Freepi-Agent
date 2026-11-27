package rofil.movie.app.data.scrape

import rofil.movie.app.data.remote.AccessBlockedException
import rofil.movie.app.data.remote.ParseException
import rofil.movie.app.data.remote.StreamResolutionException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ScrapeValidatorTest {

    private fun envelope(payload: JSONObject, kind: ScrapeKind = ScrapeKind.HOME): ScrapeEnvelope =
        ScrapeValidator.parseEnvelope(payload, kind)

    private fun okData(schema: String, data: JSONObject, source: JSONObject = JSONObject().put("finalUrl", "https://tv13.lk21official.cc/")) =
        JSONObject()
            .put("schema", schema)
            .put("ok", true)
            .put("source", source)
            .put("data", data)

    private fun card(slug: String = "venom-the-last-dance-2024") = JSONObject()
        .put("slug", slug)
        .put("title", "Venom: The Last Dance (2024)")
        .put("url", "https://tv13.lk21official.cc/$slug/")
        .put("rating", "6.1")
        .put("quality", "HD")

    // ------------------------------------------------------------ envelope

    @Test
    fun `accepts a well formed home payload`() {
        val sections = JSONObject().put("latest", org.json.JSONArray().put(card()))
        val payload = okData(ScrapeSchemas.HOME, JSONObject().put("sections", sections))
        val envelope = envelope(payload)
        assertEquals(ScrapeSchemas.HOME, envelope.schema)
        assertNotNull(ScrapeValidator.requireHome(envelope))
    }

    @Test
    fun `rejects a challenge payload with AccessBlockedException`() {
        val payload = JSONObject()
            .put("schema", ScrapeSchemas.ERROR)
            .put("ok", false)
            .put("source", JSONObject())
            .put(
                "error",
                JSONObject()
                    .put("code", ScrapeErrorCodes.ACCESS_BLOCKED)
                    .put("message", "challenge"),
            )
        try {
            envelope(payload)
            fail("expected AccessBlockedException")
        } catch (e: AccessBlockedException) {
            // expected
        }
    }

    @Test
    fun `rejects a payload with the wrong schema`() {
        val payload = okData(ScrapeSchemas.LIST, JSONObject().put("items", org.json.JSONArray()))
        try {
            envelope(payload, ScrapeKind.HOME)
            fail("expected ParseException")
        } catch (e: ParseException) {
            assertTrue(e.message!!.contains("schema"))
        }
    }

    @Test
    fun `rejects a payload without ok true`() {
        val payload = JSONObject().put("schema", ScrapeSchemas.HOME)
        try {
            envelope(payload)
            fail("expected ParseException")
        } catch (e: ParseException) {
            // expected
        }
    }

    // ----------------------------------------------------------------- home

    @Test
    fun `home with no cards is an error, never a silent empty list`() {
        val payload = okData(
            ScrapeSchemas.HOME,
            JSONObject().put("sections", JSONObject()
                .put("latest", org.json.JSONArray())
                .put("popular", org.json.JSONArray())),
        )
        val envelope = envelope(payload)
        try {
            ScrapeValidator.requireHome(envelope)
            fail("expected ParseException")
        } catch (e: ParseException) {
            assertTrue(e.message!!.contains("movie cards"))
        }
    }

    @Test
    fun `home skips malformed cards but accepts the valid ones`() {
        val sections = JSONObject()
            .put("latest", org.json.JSONArray().put(card()))
            .put("popular", org.json.JSONArray().put(JSONObject().put("slug", "x")))
        val payload = okData(ScrapeSchemas.HOME, JSONObject().put("sections", sections))
        assertEquals(1, ScrapeValidator.requireHome(envelope(payload)).optJSONObject("sections")
            ?.optJSONArray("latest")?.length())
    }

    @Test
    fun `list page one with no items and no hint is an error`() {
        val payload = okData(
            ScrapeSchemas.LIST,
            JSONObject().put("items", org.json.JSONArray()).put("page", 1).put("totalPages", 1),
        )
        try {
            ScrapeValidator.requireList(envelope(payload, ScrapeKind.LIST), isSearch = false)
            fail("expected ParseException")
        } catch (e: ParseException) {
            // expected
        }
    }

    @Test
    fun `empty search results are legitimate`() {
        val payload = okData(
            ScrapeSchemas.LIST,
            JSONObject()
                .put("items", org.json.JSONArray())
                .put("page", 1)
                .put("totalPages", 1)
                .put("emptyHint", true),
        )
        assertNotNull(ScrapeValidator.requireList(envelope(payload, ScrapeKind.LIST), isSearch = true))
    }

    @Test
    fun `list rejects an invalid next page url`() {
        val payload = okData(
            ScrapeSchemas.LIST,
            JSONObject()
                .put("items", org.json.JSONArray().put(card()))
                .put("page", 1)
                .put("totalPages", 2)
                .put("nextPageUrl", "javascript:alert(1)"),
        )
        try {
            ScrapeValidator.requireList(envelope(payload, ScrapeKind.LIST), isSearch = false)
            fail("expected ParseException")
        } catch (e: ParseException) {
            assertTrue(e.message!!.contains("next-page"))
        }
    }

    // ---------------------------------------------------------------- detail

    @Test
    fun `detail requires a title`() {
        val payload = okData(
            ScrapeSchemas.DETAIL,
            JSONObject()
                .put("jsonLd", JSONObject().put("name", "  "))
                .put("sources", org.json.JSONArray()),
        )
        try {
            ScrapeValidator.requireDetail(envelope(payload, ScrapeKind.DETAIL))
            fail("expected ParseException")
        } catch (e: ParseException) {
            assertTrue(e.message!!.contains("title"))
        }
    }

    @Test
    fun `stream payload without media, iframes or scripts is an error`() {
        val payload = okData(
            ScrapeSchemas.STREAM,
            JSONObject().put("media", org.json.JSONArray())
                .put("iframes", org.json.JSONArray())
                .put("scripts", org.json.JSONArray()),
        )
        try {
            ScrapeValidator.requireStream(envelope(payload, ScrapeKind.STREAM))
            fail("expected StreamResolutionException")
        } catch (e: StreamResolutionException) {
            // expected
        }
    }

    @Test
    fun `stream payload with one media url passes`() {
        val payload = okData(
            ScrapeSchemas.STREAM,
            JSONObject()
                .put("media", org.json.JSONArray().put(
                    JSONObject().put("url", "https://cdn.example.com/hls/master.m3u8")
                        .put("kind", "hls")
                ))
                .put("iframes", org.json.JSONArray())
                .put("scripts", org.json.JSONArray()),
        )
        assertNotNull(ScrapeValidator.requireStream(envelope(payload, ScrapeKind.STREAM)))
    }

    @Test
    fun `stream rejects HTML and initialization documents as playable media`() {
        val payload = okData(ScrapeSchemas.STREAM, JSONObject()
            .put("media", org.json.JSONArray().put(JSONObject().put("url", "https://player.example/init_player.js"))
                .put(JSONObject().put("url", "https://player.example/watch?datas=abc")))
            .put("scripts", org.json.JSONArray().put("https://player.example/init_player.js")))
        try {
            ScrapeValidator.requireStream(envelope(payload, ScrapeKind.STREAM))
            fail("expected StreamResolutionException")
        } catch (e: StreamResolutionException) {
            assertTrue(e.message!!.startsWith("PLAYER_METADATA:"))
        }
    }

    @Test
    fun `stream accepts extensionless typed manifest as an unvalidated candidate`() {
        val payload = okData(ScrapeSchemas.STREAM, JSONObject()
            .put("media", org.json.JSONArray().put(JSONObject()
                .put("url", "https://cdn.example/manifest?signature=redacted")
                .put("type", "application/vnd.apple.mpegurl"))))
        assertNotNull(ScrapeValidator.requireStream(envelope(payload, ScrapeKind.STREAM)))
    }

    @Test
    fun `null payloads for optional arrays are accepted`() {
        val data = JSONObject()
            .put("title", "Gladiator II (2024)")
            .put("rating", "6.9")
        val payload = okData(ScrapeSchemas.DETAIL, data)
        assertNotNull(ScrapeValidator.requireDetail(envelope(payload, ScrapeKind.DETAIL)))
    }

    @Test
    fun `url normalization used by mapper accepts root-relative paths`() {
        assertTrue(ScrapeValidator.isAcceptableUrl("/genre/action/"))
        assertTrue(ScrapeValidator.isAcceptableUrl("https://tv13.lk21official.cc/x/"))
        assertTrue(!ScrapeValidator.isAcceptableUrl("blob:https://x/abc"))
        assertTrue(!ScrapeValidator.isAcceptableUrl("javascript:void(0)"))
        assertNull(null)
    }
}
