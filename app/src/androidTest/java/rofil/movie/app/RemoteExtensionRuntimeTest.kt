package rofil.movie.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import rofil.movie.app.data.scrape.RemoteSourceRuntime
import rofil.movie.app.data.scrape.SourceModuleStore
import java.io.File

/** Executes pinned upstream bundles in Android WebView, including the synchronous native HTTP bridge. */
@RunWith(AndroidJUnit4::class)
class RemoteExtensionRuntimeTest {
    @Test fun independentExtensionsAndSharedExtractorsSurviveSwitches() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun fixture(path: String) = assets.open("extensions/$path").bufferedReader().use { it.readText() }
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val body = when (request.url.host) {
                "modules.test" -> fixture(path.removePrefix("/")).let {
                    if (path == "/manifest.json") JSONObject(it).put("baseUrl", "https://modules.test/").toString() else it
                }
                "h5-api.aoneroom.com" -> fixture("fixtures/moviebox/home.json")
                "db.wecollege.net" -> fixture("fixtures/movy/" + when {
                    path.contains("trending") -> "trending.json"
                    path.contains("discover/movie") -> "discover_movie.json"
                    path.contains("discover/tv") -> "discover_tv.json"
                    path.contains("/movie/") -> "movie_27205.json"
                    else -> error("Unrouted fixture request")
                })
                else -> error("Unexpected network request")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val directory = File(context.cacheDir, "extension-test-${System.nanoTime()}")
        val runtime = RemoteSourceRuntime(context, SourceModuleStore(directory, http, "https://modules.test/"), http)
        try {
            for (source in listOf("moviebox", "movy", "moviebox", "movy")) {
                val home = runtime.execute(source, "home")
                assertTrue(home.toString(), home.getBoolean("ok"))
                assertEquals("freepi.home.v1", home.getString("schema"))
            }
            assertEquals("Inception", runtime.execute("movy", "detail", JSONObject().put("url", "movy:movie:27205"))
                .getJSONObject("data").getString("title"))
            // LK21 intentionally exposes browser playback as an access error, without breaking other sources.
            assertEquals("ACCESS_BLOCKED", runtime.execute("lk21", "stream").getJSONObject("error").getString("code"))
            val direct = runtime.extract("direct", JSONObject().put("url", "https://cdn.example/movie.mp4"))
            assertEquals(1, direct.getJSONObject("data").getJSONArray("media").length())
            assertFalse(runtime.extract("videasy", JSONObject().put("server", "invalid")).getBoolean("ok"))
            assertTrue(runtime.extract("direct", JSONObject().put("url", "https://cdn.example/after.mp4")).getBoolean("ok"))
            assertTrue(runtime.execute("movy", "home").getBoolean("ok"))
        } finally {
            withContext(Dispatchers.Main) { runtime.reset() }
            directory.deleteRecursively()
        }
    }
}
