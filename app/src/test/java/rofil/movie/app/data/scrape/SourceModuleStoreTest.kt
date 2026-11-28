package rofil.movie.app.data.scrape

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class SourceModuleStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(path: String) = javaClass.getResource("/extensions/$path")!!.readText()

    @Test fun `real registry downloads all modules and preserves verified snapshot on failed updates`() {
        var manifest = fixture("manifest.json")
        val directory = temporary.newFolder()
        val server = MockWebServer()
        var mode = "ok"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (mode == "offline") return MockResponse().setResponseCode(503)
                val path = request.path!!.removePrefix("/")
                if (mode == "corrupt" && path != "manifest.json") return MockResponse().setBody("corrupt module")
                return MockResponse().setBody(if (path == "manifest.json") manifest else fixture(path))
            }
        }
        server.start()
        manifest = JSONObject(manifest).put("baseUrl", server.url("/").toString()).toString()
        try {
            val http = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
            val store = SourceModuleStore(directory, http, server.url("/").toString())
            val snapshot = store.refresh(true)
            assertEquals(15, server.requestCount) // manifest + every real module (including unused extractors)
            val modules = snapshot.getJSONObject("modules")
            assertEquals(14, File(directory, "modules").listFiles()!!.size)
            for (id in listOf("lk21", "moviebox", "movy")) {
                val roots = store.sourceRoots(store.source(snapshot, id))
                val order = store.moduleOrder(snapshot, roots)
                assertEquals(order.distinct(), order)
                order.forEachIndexed { index, module ->
                    val deps = modules.getJSONObject(module).getJSONArray("dependencies")
                    for (i in 0 until deps.length()) assertTrue(order.indexOf(deps.getString(i)) < index)
                }
                assertTrue(store.scripts(snapshot, roots).contains("MovieSources"))
            }
            val movy = store.scripts(snapshot, "movy")
            assertEquals(1, Regex("global.FreepiExtractors =").findAll(movy).count())
            store.refresh(true)
            assertEquals(16, server.requestCount) // unchanged modules reused by hash
            mode = "offline"
            assertEquals(snapshot.toString(), store.refresh(true).toString())
            assertNotNull(store.lastRefreshError)
            val restarted = SourceModuleStore(directory, http, server.url("/").toString())
            assertEquals(snapshot.toString(), restarted.refresh().toString())
            // Reject corrupt content on a new install; never commit active.json.
            mode = "corrupt"
            val fresh = temporary.newFolder()
            assertThrows(java.io.IOException::class.java) { SourceModuleStore(fresh, http, server.url("/").toString()).refresh() }
            assertFalse(File(fresh, "active.json").exists())
            // Reject damaged offline snapshots too.
            mode = "offline"
            File(directory, "modules/${modules.getJSONObject("lk21").getString("sha256")}.js").writeText("damaged")
            assertThrows(IllegalArgumentException::class.java) { restarted.refresh(true) }
        } finally { server.shutdown() }
    }

    @Test fun `invalid update graph and hashes do not replace last valid manifest`() {
        var original = fixture("manifest.json")
        var manifest = original
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(
                if (request.path == "/manifest.json") manifest else fixture(request.path!!.removePrefix("/")))
        }
        server.start()
        original = JSONObject(original).put("baseUrl", server.url("/").toString()).toString()
        manifest = original
        try {
            val directory = temporary.newFolder()
            val store = SourceModuleStore(directory, OkHttpClient(), server.url("/").toString())
            store.refresh()
            for (bad in listOf("cycle", "missing", "hash", "entry", "schema", "path")) {
                val candidate = JSONObject(original)
                val module = candidate.getJSONObject("modules").getJSONObject("movy")
                when (bad) {
                    "cycle" -> module.getJSONArray("dependencies").put("movy")
                    "missing" -> module.getJSONArray("dependencies").put("absent")
                    "hash" -> module.put("sha256", "0".repeat(64))
                    "entry" -> candidate.getJSONArray("sources").getJSONObject(2).put("entry", "wrong.js")
                    "schema" -> candidate.put("runtimeVersion", 99)
                    "path" -> module.put("path", "../escape.js")
                }
                manifest = candidate.toString()
                assertEquals(JSONObject(original).toString(), store.refresh(true).toString())
                assertNotNull("must reject $bad", store.lastRefreshError)
                assertEquals(JSONObject(original).toString(), File(directory, "active.json").readText())
            }
        } finally { server.shutdown() }
    }
    @Test fun `valid version update replaces only changed content and survives restart`() {
        val directory = temporary.newFolder()
        val server = MockWebServer()
        var changedCode: String? = null
        var manifest = JSONObject(fixture("manifest.json"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.removePrefix("/")
                return MockResponse().setBody(when {
                    path == "manifest.json" -> manifest.toString()
                    path == "extensions/movy/extension.js" && changedCode != null -> changedCode!!
                    else -> fixture(path)
                })
            }
        }
        server.start()
        manifest.put("baseUrl", server.url("/").toString())
        try {
            val store = SourceModuleStore(directory, OkHttpClient(), server.url("/").toString())
            store.refresh()
            changedCode = fixture("extensions/movy/extension.js") + "\n// version update fixture\n"
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(changedCode!!.toByteArray())
                .joinToString("") { "%02x".format(it) }
            manifest.put("version", "2.0.1")
            manifest.getJSONObject("modules").getJSONObject("movy").put("version", "1.0.1").put("sha256", hash)
            manifest.getJSONArray("sources").getJSONObject(2).put("version", "1.0.1")
            val update = store.refresh(true)
            assertEquals(17, server.requestCount) // initial 15 + updated manifest + one changed module
            assertEquals("2.0.1", update.getString("version"))
            assertTrue(store.scripts(update, "movy").endsWith("// version update fixture\n"))
            assertEquals(15, File(directory, "modules").listFiles()!!.size) // old hash remains usable
            assertEquals("2.0.1", SourceModuleStore(directory, OkHttpClient(), server.url("/").toString()).refresh().getString("version"))
        } finally { server.shutdown() }
    }

}
